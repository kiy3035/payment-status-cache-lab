#Requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$script:Rps = 100
$root = Split-Path -Parent $PSScriptRoot
$runId = Get-Date -Format 'yyyyMMdd-HHmmss'
$project = 'payment-status-cache-lab-stage5-' + $runId
$resultRelative = "results/$runId"
$resultDir = Join-Path $root $resultRelative
$appImage = "payment-status-cache-lab:stage5-$runId"
$composeFiles = @('-f', 'docker-compose.yml', '-f', 'docker-compose.performance.yml')
$cpuRows = [System.Collections.Generic.List[object]]::new()
$allResults = [System.Collections.Generic.List[object]]::new()
$created = $false
$environmentNames = @(
    'MYSQL_PORT', 'REDIS_PORT', 'TOXIPROXY_API_PORT', 'TOXIPROXY_REDIS_PORT', 'APP_PORT',
    'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD', 'APP_IMAGE',
    'APP_JAVA_TOOL_OPTIONS', 'PAYMENT_STATUS_CACHE_ENABLED', 'PERFORMANCE_RESULTS_DIR',
    'K6_RPS', 'K6_DURATION', 'K6_HOT_SET_SIZE', 'K6_EXPECTED_CACHE_RESULTS',
    'K6_SCENARIO_NAME', 'K6_SUMMARY_PATH', 'K6_ALLOW_DROPPED_ITERATIONS', 'RECOVERY_ID'
)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-Stage5([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Invoke-Compose {
    & docker compose -p $project @composeFiles @args
    Assert-Stage5 ($LASTEXITCODE -eq 0) '5단계 Compose 명령 실패'
}

function Get-ResponseText($Response) {
    if ($Response.Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Response.Content)
    }
    return [string]$Response.Content
}

function Wait-App([bool]$RequireOverallHealth) {
    $deadline = (Get-Date).AddSeconds(90)
    $path = if ($RequireOverallHealth) { '/actuator/health' } else { '/actuator/health/liveness' }
    do {
        try {
            $response = Invoke-WebRequest "$baseUrl$path" -TimeoutSec 5 -SkipHttpErrorCheck
            if ($response.StatusCode -eq 200) { return }
        } catch [System.Net.Http.HttpRequestException] {
        } catch [System.Threading.Tasks.TaskCanceledException] {
        }
        Start-Sleep -Milliseconds 500
    } while ((Get-Date) -lt $deadline)
    throw '측정 애플리케이션 health 대기 시간 초과'
}

function Invoke-Proxy([string]$Method, [string]$Path, $Body) {
    $parameters = @{
        Uri = "$proxyUrl$Path"; Method = $Method; TimeoutSec = 5
        Headers = @{ 'User-Agent' = 'toxiproxy-cli/2.12.0' }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 6 -Compress
    }
    Invoke-RestMethod @parameters | Out-Null
}

function Invoke-Redis {
    $output = & docker compose -p $project @composeFiles exec -T redis redis-cli --raw @args
    Assert-Stage5 ($LASTEXITCODE -eq 0) 'Redis 측정 명령 실패'
    return ($output -join "`n").Trim()
}

function Get-RedisCommandCalls([string]$CommandName) {
    $lines = & docker compose -p $project @composeFiles exec -T redis redis-cli --raw INFO commandstats
    Assert-Stage5 ($LASTEXITCODE -eq 0) 'Redis commandstats 조회 실패'
    foreach ($line in $lines) {
        $match = [regex]::Match([string]$line, "^cmdstat_${CommandName}:calls=(\d+)")
        if ($match.Success) { return [int64]$match.Groups[1].Value }
    }
    return [int64]0
}

function Get-MySqlSelectCount {
    $output = & docker compose -p $project @composeFiles exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -Nse "SHOW GLOBAL STATUS LIKE ''Com_select'';"'
    Assert-Stage5 ($LASTEXITCODE -eq 0) 'MySQL Com_select 조회 실패'
    $parts = ($output -join '').Trim() -split "\s+"
    Assert-Stage5 ($parts.Count -ge 2) 'MySQL Com_select 결과 해석 실패'
    return [int64]$parts[-1]
}

function Get-MetricValue([string]$Text, [string]$MetricName, [string]$LabelName = '', [string]$LabelValue = '') {
    $sum = 0.0
    foreach ($line in ($Text -split "`n")) {
        if ($line.StartsWith('#')) { continue }
        if ($LabelName) {
            $pattern = '^' + [regex]::Escape($MetricName) + '\{[^}]*' + [regex]::Escape($LabelName) + '="' + [regex]::Escape($LabelValue) + '"[^}]*\}\s+([0-9.eE+-]+)$'
        } else {
            $pattern = '^' + [regex]::Escape($MetricName) + '(?:\{[^}]*\})?\s+([0-9.eE+-]+)$'
        }
        $match = [regex]::Match($line.Trim(), $pattern)
        if ($match.Success) {
            $sum += [double]::Parse($match.Groups[1].Value, [Globalization.CultureInfo]::InvariantCulture)
        }
    }
    return $sum
}

function Get-AppMetrics {
    $text = Get-ResponseText (Invoke-WebRequest "$baseUrl/actuator/prometheus" -TimeoutSec 10)
    return @{
        DbReads = Get-MetricValue $text 'payment_status_db_read_total'
        Hit = Get-MetricValue $text 'payment_status_cache_access_total' 'result' 'hit'
        Miss = Get-MetricValue $text 'payment_status_cache_access_total' 'result' 'miss'
        Timeout = Get-MetricValue $text 'payment_status_cache_access_total' 'result' 'timeout'
        Error = Get-MetricValue $text 'payment_status_cache_access_total' 'result' 'error'
        Disabled = Get-MetricValue $text 'payment_status_cache_access_total' 'result' 'disabled'
    }
}

function Add-CpuSamplesFromFile([string]$Path, [string]$Scenario, [int]$RunNumber, [DateTimeOffset]$StartedAt, [int]$DurationSeconds) {
    $containers = @{
        "$project-app-1" = 'app'
        "$project-mysql-1" = 'mysql'
        "$project-redis-1" = 'redis'
    }
    $samples = @{ app = [System.Collections.Generic.List[object]]::new(); mysql = [System.Collections.Generic.List[object]]::new(); redis = [System.Collections.Generic.List[object]]::new() }
    foreach ($line in (Get-Content -LiteralPath $Path)) {
        $cleanLine = $line -replace "`e\[[0-9;]*[A-Za-z]", ''
        $parts = $cleanLine -split '\|', 3
        if ($parts.Count -ne 3 -or -not $containers.ContainsKey($parts[0])) { continue }
        $cpuText = $parts[1].Trim().TrimEnd('%')
        $cpuValue = 0.0
        if (-not [double]::TryParse($cpuText, [Globalization.NumberStyles]::Float, [Globalization.CultureInfo]::InvariantCulture, [ref]$cpuValue)) { continue }
        $service = $containers[$parts[0]]
        $samples[$service].Add([pscustomobject]@{
            cpu_percent = $cpuValue
            memory = $parts[2].Trim()
        })
    }
    foreach ($service in @('app', 'mysql', 'redis')) {
        $source = $samples[$service]
        $desired = [Math]::Min($DurationSeconds, $source.Count)
        for ($index = 0; $index -lt $desired; $index++) {
            # Docker stream의 플랫폼별 갱신 빈도와 무관하게 초마다 하나를 균등 선택한다.
            $sourceIndex = [Math]::Min($source.Count - 1, [Math]::Floor($index * $source.Count / $desired))
            $sample = $source[$sourceIndex]
            $cpuRows.Add([pscustomobject]@{
                timestamp = $StartedAt.AddSeconds($index).ToString('o')
                scenario = $Scenario
                run = $RunNumber
                service = $service
                sample_index = $index
                cpu_percent = $sample.cpu_percent
                memory = $sample.memory
            })
        }
    }
}

function Get-Percentile([double[]]$Values, [double]$Quantile) {
    if ($Values.Count -eq 0) { return 0.0 }
    $sorted = @($Values | Sort-Object)
    $index = [Math]::Max(0, [Math]::Ceiling($Quantile * $sorted.Count) - 1)
    return [double]$sorted[$index]
}

function Get-CpuSummary([string]$Scenario, [int]$RunNumber, [string]$Service) {
    [double[]]$values = @($cpuRows | Where-Object {
        $_.scenario -eq $Scenario -and $_.run -eq $RunNumber -and $_.service -eq $Service
    } | ForEach-Object { $_.cpu_percent })
    return [pscustomobject]@{
        average = $(if ($values.Count) { [Math]::Round(($values | Measure-Object -Average).Average, 4) } else { 0 })
        p95 = [Math]::Round((Get-Percentile $values 0.95), 4)
        samples = $values.Count
    }
}

function Start-App([bool]$CacheEnabled) {
    $env:PAYMENT_STATUS_CACHE_ENABLED = $CacheEnabled.ToString().ToLowerInvariant()
    & docker compose -p $project @composeFiles rm -sf app | Out-Null
    Assert-Stage5 ($LASTEXITCODE -eq 0) '기존 앱 컨테이너 정리 실패'
    Invoke-Compose up -d app
    Wait-App $CacheEnabled
    $health = Invoke-WebRequest "$baseUrl/actuator/health/liveness" -TimeoutSec 5
    Assert-Stage5 ($health.StatusCode -eq 200) '앱 liveness 검증 실패'
}

function Invoke-K6([string]$Scenario, [int]$RunNumber, [int]$DurationSeconds, [string]$Expected, [string]$OutputName, [bool]$CollectCpu) {
    $env:K6_RPS = '100'
    $env:K6_DURATION = "${DurationSeconds}s"
    $env:K6_HOT_SET_SIZE = '1000'
    $env:K6_EXPECTED_CACHE_RESULTS = $Expected
    $env:K6_SCENARIO_NAME = $Scenario
    $env:K6_SUMMARY_PATH = "/results/$OutputName"
    $stdout = Join-Path $resultDir ($OutputName -replace '\.json$', '.stdout.log')
    $stderr = Join-Path $resultDir ($OutputName -replace '\.json$', '.stderr.log')
    $arguments = @('compose', '-p', $project) + $composeFiles + @('run', '--rm', 'k6', 'run', '/scripts/status-load.js')
    $process = $null
    $statsProcess = $null
    $statsPath = Join-Path $resultDir "docker-stats-$Scenario-run-$RunNumber.log"
    $statsStartedAt = [DateTimeOffset]::Now
    try {
        if ($CollectCpu) {
            $names = @("$project-app-1", "$project-mysql-1", "$project-redis-1")
            $statsArguments = @('stats', '--format', '{{.Name}}|{{.CPUPerc}}|{{.MemUsage}}') + $names
            $statsProcess = Start-Process docker -ArgumentList $statsArguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $statsPath
        }
        $process = Start-Process docker -ArgumentList $arguments -WindowStyle Hidden -PassThru -RedirectStandardOutput $stdout -RedirectStandardError $stderr
        $process.WaitForExit()
    } finally {
        if ($null -ne $statsProcess -and -not $statsProcess.HasExited) {
            Stop-Process -Id $statsProcess.Id
            $statsProcess.WaitForExit(5000) | Out-Null
        }
    }
    Assert-Stage5 ($null -ne $process) "k6 프로세스 시작 실패: $Scenario"
    if ($CollectCpu) { Add-CpuSamplesFromFile $statsPath $Scenario $RunNumber $statsStartedAt $DurationSeconds }
    $path = Join-Path $resultDir $OutputName
    Assert-Stage5 (Test-Path -LiteralPath $path) 'k6 summary JSON 생성 실패'
    $summary = Get-Content -LiteralPath $path -Raw | ConvertFrom-Json -Depth 100
    if ($process.ExitCode -ne 0) {
        $warmupOnly = $env:K6_ALLOW_DROPPED_ITERATIONS -eq 'true'
        $checksPassed = (Get-K6Value $summary 'checks' 'rate') -gt 0.999
        $requestsSucceeded = (Get-K6Value $summary 'http_req_failed' 'rate') -lt 0.001
        Assert-Stage5 ($warmupOnly -and $checksPassed -and $requestsSucceeded) "k6 실행 실패: $Scenario run $RunNumber"
    }
    return $summary
}

function Invoke-Warmup([string]$Scenario, [int]$RunNumber, [string]$Expected) {
    $env:K6_ALLOW_DROPPED_ITERATIONS = 'true'
    try {
        # 웜업은 캐시를 채우는 단계이므로 가능한 캐시 경로를 모두 관찰한다.
        $warmupExpected = 'DISABLED,HIT,MISS_FALLBACK,TIMEOUT_FALLBACK,ERROR_FALLBACK'
        Invoke-K6 "${Scenario}_warmup" $RunNumber 30 $warmupExpected "warmup-$Scenario-run-$RunNumber.json" $false | Out-Null
    } finally {
        $env:K6_ALLOW_DROPPED_ITERATIONS = 'false'
    }
}

function Invoke-CachePrefill {
    # 정상 비교 전에 동일 GET API로 hot-set 전체를 순차 적재해 cold miss 영향을 분리한다.
    for ($paymentId = 1; $paymentId -le 1000; $paymentId++) {
        $response = Invoke-WebRequest "$baseUrl/api/v1/payments/$paymentId/status" -TimeoutSec 5
        Assert-Stage5 ($response.StatusCode -eq 200) "hot-set 캐시 적재 실패: $paymentId"
    }
    $size = [int64](Invoke-Redis DBSIZE)
    Assert-Stage5 ($size -ge 1000) "hot-set 캐시 크기 부족: $size"
}

function Get-K6Value($Summary, [string]$Metric, [string]$Value, [double]$Default = 0) {
    $metricObject = $Summary.metrics.$Metric
    if ($null -eq $metricObject -or $null -eq $metricObject.values.$Value) { return $Default }
    return [double]$metricObject.values.$Value
}

function Measure-Scenario([string]$Scenario, [int]$RunNumber, [int]$DurationSeconds, [string]$Expected, [string]$OutputName) {
    $failureScenario = $Scenario -in @('redis-down', 'redis-timeout')
    $env:K6_ALLOW_DROPPED_ITERATIONS = 'true'
    $beforeApp = Get-AppMetrics
    $beforeSelect = Get-MySqlSelectCount
    $beforeRedisGet = if ($Scenario -eq 'redis-down') { [int64]0 } else { Get-RedisCommandCalls 'get' }
    $summary = Invoke-K6 $Scenario $RunNumber $DurationSeconds $Expected $OutputName $true
    $afterApp = Get-AppMetrics
    $afterSelect = Get-MySqlSelectCount
    $afterRedisGet = if ($Scenario -eq 'redis-down') { $beforeRedisGet } else { Get-RedisCommandCalls 'get' }
    $hit = $afterApp.Hit - $beforeApp.Hit
    $miss = $afterApp.Miss - $beforeApp.Miss
    $timeout = $afterApp.Timeout - $beforeApp.Timeout
    $error = $afterApp.Error - $beforeApp.Error
    $cacheTotal = $hit + $miss + $timeout + $error
    $result = [pscustomobject]@{
        scenario = $Scenario
        run = $RunNumber
        duration_seconds = $DurationSeconds
        requests = [int64](Get-K6Value $summary 'http_reqs' 'count')
        achieved_rps = [Math]::Round((Get-K6Value $summary 'http_reqs' 'count') / $DurationSeconds, 4)
        success_rate = [Math]::Round((1 - (Get-K6Value $summary 'http_req_failed' 'rate')) * 100, 6)
        dropped_iterations = [int64](Get-K6Value $summary 'dropped_iterations' 'count')
        avg_ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'avg'), 4)
        p50_ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(50)'), 4)
        p95_ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(95)'), 4)
        p99_ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'p(99)'), 4)
        max_ms = [Math]::Round((Get-K6Value $summary 'http_req_duration' 'max'), 4)
        app_db_reads = [int64]($afterApp.DbReads - $beforeApp.DbReads)
        app_db_qps = [Math]::Round(($afterApp.DbReads - $beforeApp.DbReads) / $DurationSeconds, 4)
        mysql_selects = [int64]($afterSelect - $beforeSelect)
        mysql_select_qps = [Math]::Round(($afterSelect - $beforeSelect) / $DurationSeconds, 4)
        cache_hit_ratio = $(if ($cacheTotal -gt 0) { [Math]::Round($hit / $cacheTotal, 6) } else { 0 })
        cache_hit = [int64]$hit
        cache_miss = [int64]$miss
        cache_timeout = [int64]$timeout
        cache_error = [int64]$error
        cache_disabled = [int64]($afterApp.Disabled - $beforeApp.Disabled)
        redis_get_commands = [int64]($afterRedisGet - $beforeRedisGet)
        app_cpu = Get-CpuSummary $Scenario $RunNumber 'app'
        mysql_cpu = Get-CpuSummary $Scenario $RunNumber 'mysql'
        redis_cpu = Get-CpuSummary $Scenario $RunNumber 'redis'
        raw_summary = $OutputName
    }
    $expectedRequests = [int64]($DurationSeconds * $script:Rps)
    if ($failureScenario) {
        # 장애 시나리오는 Redis 재연결 대기로 처리량이 낮아질 수 있으므로 누락을 결과에 보존한다.
        Assert-Stage5 ($result.requests -ge [int64]($expectedRequests * 0.8)) "장애 시나리오 처리 요청 수 부족: $Scenario (expected=$expectedRequests actual=$($result.requests))"
        Assert-Stage5 ($result.success_rate -eq 100) "장애 시나리오 fallback 성공률 검증 실패: $Scenario"
    } else {
        # 공유 호스트의 자원 경합은 숨기지 않고 최대 2%까지 결과에 보존한다.
        Assert-Stage5 ($result.requests -ge [int64]($expectedRequests * 0.98) -and $result.requests -le ($expectedRequests + 1)) "정상 시나리오 처리 요청 수 부족: $Scenario (expected=$expectedRequests actual=$($result.requests))"
        Assert-Stage5 ($result.dropped_iterations -le [int64]($expectedRequests * 0.02)) "정상 시나리오 dropped iteration 과다: $Scenario"
        Assert-Stage5 ($result.success_rate -eq 100) "정상 시나리오 성공률 검증 실패: $Scenario"
    }
    if ($Scenario -eq 'redis-normal') {
        Assert-Stage5 ($result.cache_hit_ratio -ge 0.99) "Redis 정상 hit ratio 부족: $($result.cache_hit_ratio)"
    }
    $minimumSamples = [Math]::Floor($DurationSeconds * 0.85)
    Assert-Stage5 ($result.app_cpu.samples -ge $minimumSamples -and $result.mysql_cpu.samples -ge $minimumSamples) "1초 CPU 표본 수 부족: $Scenario"
    if ($Scenario -ne 'redis-down') {
        Assert-Stage5 ($result.redis_cpu.samples -ge $minimumSamples) "Redis 1초 CPU 표본 수 부족: $Scenario"
    }
    $allResults.Add($result)
    $result | ConvertTo-Json -Depth 8 -Compress
}

function Get-Median([object[]]$Items, [string]$Property) {
    [double[]]$values = @($Items | ForEach-Object { [double]($_.$Property) })
    $sorted = @($values | Sort-Object)
    return $sorted[[Math]::Floor($sorted.Count / 2)]
}

function Get-MedianResult([string]$Scenario) {
    $items = @($allResults | Where-Object { $_.scenario -eq $Scenario })
    Assert-Stage5 ($items.Count -eq 3) "정상 시나리오 3회 결과 부족: $Scenario"
    $appCpu = @($items | ForEach-Object { $_.app_cpu })
    $mysqlCpu = @($items | ForEach-Object { $_.mysql_cpu })
    $redisCpu = @($items | ForEach-Object { $_.redis_cpu })
    return [pscustomobject]@{
        scenario = "$Scenario-median"
        run = 'median'
        requests = Get-Median $items 'requests'
        achieved_rps = Get-Median $items 'achieved_rps'
        dropped_iterations = Get-Median $items 'dropped_iterations'
        success_rate = Get-Median $items 'success_rate'
        avg_ms = Get-Median $items 'avg_ms'
        p95_ms = Get-Median $items 'p95_ms'
        p99_ms = Get-Median $items 'p99_ms'
        app_db_qps = Get-Median $items 'app_db_qps'
        mysql_select_qps = Get-Median $items 'mysql_select_qps'
        cache_hit_ratio = Get-Median $items 'cache_hit_ratio'
        redis_get_commands = Get-Median $items 'redis_get_commands'
        cache_error = Get-Median $items 'cache_error'
        cache_timeout = Get-Median $items 'cache_timeout'
        app_cpu = [pscustomobject]@{ average = Get-Median $appCpu 'average'; p95 = Get-Median $appCpu 'p95' }
        mysql_cpu = [pscustomobject]@{ average = Get-Median $mysqlCpu 'average'; p95 = Get-Median $mysqlCpu 'p95' }
        redis_cpu = [pscustomobject]@{ average = Get-Median $redisCpu 'average'; p95 = Get-Median $redisCpu 'p95' }
    }
}

function Write-Summary {
    $db = Get-MedianResult 'db-only'
    $redisResult = Get-MedianResult 'redis-normal'
    $down = @($allResults | Where-Object scenario -eq 'redis-down')[0]
    $timeout = @($allResults | Where-Object scenario -eq 'redis-timeout')[0]
    $lines = @(
        '# 5단계 측정 요약', '',
        "실행 ID: ``$runId``", '',
        '| 시나리오 | 실행 | 요청 | 달성 RPS | dropped | 성공률 | 평균 ms | p95 ms | p99 ms | 앱 DB QPS | MySQL SELECT QPS | hit ratio | 앱 CPU 평균/p95 | MySQL CPU 평균/p95 | Redis CPU 평균/p95 |',
        '| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |'
    )
    foreach ($item in @($db, $redisResult, $down, $timeout)) {
        $lines += "| $($item.scenario) | $($item.run) | $($item.requests) | $($item.achieved_rps) | $($item.dropped_iterations) | $($item.success_rate)% | $($item.avg_ms) | $($item.p95_ms) | $($item.p99_ms) | $($item.app_db_qps) | $($item.mysql_select_qps) | $($item.cache_hit_ratio) | $($item.app_cpu.average)/$($item.app_cpu.p95) | $($item.mysql_cpu.average)/$($item.mysql_cpu.p95) | $($item.redis_cpu.average)/$($item.redis_cpu.p95) |"
    }
    $lines += @('', '정상 시나리오는 각 지표별 3회 중앙값이다. 서로 다른 실행에서 나온 중앙값일 수 있다. 차이가 작거나 음수인 지표도 그대로 해석한다.', '')
    $dbReduction = $(if ($db.app_db_qps -gt 0) { [Math]::Round((1 - $redisResult.app_db_qps / $db.app_db_qps) * 100, 4) } else { 0 })
    $lines += "- DB-only 대비 Redis 정상 앱 DB QPS 변화: $dbReduction% 감소"
    $lines += "- DB-only Redis GET 명령: $($db.redis_get_commands)회"
    $lines += "- Redis 정상 hit ratio: $($redisResult.cache_hit_ratio)"
    $lines += "- Redis 중단 fallback: error $($down.cache_error)회, timeout $($down.cache_timeout)회"
    $lines += "- Redis 지연 fallback: timeout $($timeout.cache_timeout)회"
    $lines += ''
    $lines += '이 문서는 같은 디렉터리의 원시 k6 JSON, scenario-results.json, cpu.csv에서 생성했다.'
    Set-Content -LiteralPath (Join-Path $resultDir 'summary.md') -Value $lines -Encoding utf8
}

function Write-And-VerifyManifest {
    Assert-Stage5 ($allResults.Count -eq 8) '시나리오 결과 개수 검증 실패'
    foreach ($result in $allResults) {
        $rawPath = Join-Path $resultDir $result.raw_summary
        $raw = Get-Content -LiteralPath $rawPath -Raw | ConvertFrom-Json -Depth 100
        $rawRequests = [int64](Get-K6Value $raw 'http_reqs' 'count')
        Assert-Stage5 ($rawRequests -eq $result.requests) "원시 결과와 집계 요청 수 불일치: $($result.scenario)"
    }
    $recovery = Get-Content -LiteralPath (Join-Path $resultDir 'redis-recovery.json') -Raw | ConvertFrom-Json -Depth 100
    Assert-Stage5 ((Get-K6Value $recovery 'recovery_miss' 'count') -eq 1) '복구 miss 원시 결과 검증 실패'
    Assert-Stage5 ((Get-K6Value $recovery 'recovery_hit' 'count') -eq 1) '복구 hit 원시 결과 검증 실패'
    Assert-Stage5 ($cpuRows.Count -gt 0) 'CPU 원시 데이터가 없습니다.'
    $utf8 = [System.Text.UTF8Encoding]::new($false)
    Get-ChildItem -LiteralPath $resultDir -File | Where-Object { $_.Extension -in @('.json', '.csv', '.md') -and $_.Name -ne 'manifest.json' } | ForEach-Object {
        $content = [System.IO.File]::ReadAllText($_.FullName)
        [System.IO.File]::WriteAllText($_.FullName, $content.Replace("`r`n", "`n"), $utf8)
    }
    $manifest = Get-ChildItem -LiteralPath $resultDir -File | Where-Object { $_.Name -ne 'manifest.json' -and $_.Extension -ne '.log' } | Sort-Object Name | ForEach-Object {
        [pscustomobject]@{
            file = $_.Name
            bytes = $_.Length
            sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
        }
    }
    $manifestJson = ($manifest | ConvertTo-Json -Depth 4).Replace("`r`n", "`n") + "`n"
    [System.IO.File]::WriteAllText((Join-Path $resultDir 'manifest.json'), $manifestJson, $utf8)
}

Push-Location $root
try {
    New-Item -ItemType Directory -Path $resultDir | Out-Null
    $env:MYSQL_PORT = [string](Get-FreePort)
    $env:REDIS_PORT = [string](Get-FreePort)
    $env:TOXIPROXY_API_PORT = [string](Get-FreePort)
    $env:TOXIPROXY_REDIS_PORT = [string](Get-FreePort)
    $env:APP_PORT = [string](Get-FreePort)
    $env:MYSQL_DATABASE = 'payment_lab'
    $env:MYSQL_USER = 'payment_app'
    $env:MYSQL_PASSWORD = 'measurement-' + [guid]::NewGuid().ToString('N')
    $env:MYSQL_ROOT_PASSWORD = 'measurement-' + [guid]::NewGuid().ToString('N')
    $env:APP_IMAGE = $appImage
    $env:APP_JAVA_TOOL_OPTIONS = '-XX:MaxRAMPercentage=75.0 -XX:ActiveProcessorCount=1'
    $env:PERFORMANCE_RESULTS_DIR = $resultDir
    $baseUrl = "http://127.0.0.1:$env:APP_PORT"
    $proxyUrl = "http://127.0.0.1:$env:TOXIPROXY_API_PORT"

    $existing = & docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.ID}}'
    Assert-Stage5 ($LASTEXITCODE -eq 0 -and -not $existing) '측정 project 이름 충돌 또는 Docker 접근 실패'
    Invoke-Compose config --quiet
    $created = $true
    Invoke-Compose up -d --wait mysql redis toxiproxy
    Invoke-Proxy POST '/proxies' @{ name = 'redis'; listen = '0.0.0.0:26379'; upstream = 'redis:6379' }

    & docker build --pull=false -t $appImage .
    Assert-Stage5 ($LASTEXITCODE -eq 0) '앱 측정 이미지 빌드 실패'
    $imageId = (& docker image inspect $appImage --format '{{.Id}}').Trim()
    Assert-Stage5 ($LASTEXITCODE -eq 0) '앱 이미지 ID 조회 실패'
    $os = Get-CimInstance Win32_OperatingSystem
    $cpu = Get-CimInstance Win32_Processor | Select-Object -First 1
    $environment = [ordered]@{
        run_id = $runId
        started_at = [DateTimeOffset]::Now.ToString('o')
        os = "$($os.Caption) $($os.OSArchitecture) build $($os.BuildNumber)"
        cpu = $cpu.Name.Trim()
        memory_bytes = [int64]$os.TotalVisibleMemorySize * 1KB
        docker = (& docker version --format '{{.Server.Version}}').Trim()
        docker_compose = (& docker compose version --short).Trim()
        java_image = 'eclipse-temurin:21.0.12_8-jre-jammy'
        application_image = $appImage
        application_image_id = $imageId
        mysql_image = 'mysql:8.4.6'
        redis_image = 'redis:7.4.5-alpine'
        toxiproxy_image = 'ghcr.io/shopify/toxiproxy:2.12.0'
        k6_image = 'grafana/k6:1.2.3'
        resources = [ordered]@{
            app = '1 CPU, 512 MiB'; mysql = '1 CPU, 1 GiB'; redis = '0.5 CPU, 256 MiB'; k6 = '1 CPU, 512 MiB'
        }
        jvm_options = $env:APP_JAVA_TOOL_OPTIONS
        rps = 100
        warmup_seconds = 30
        normal_measurement_seconds = 120
        failure_measurement_seconds = 30
        hot_set_size = 1000
        command_timeout_ms = 100
        injected_latency_ms = 300
        external_running_container_count = @(& docker ps --format '{{.Names}}' | Where-Object { $_ -notlike "$project-*" }).Count
    }
    $environment | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $resultDir 'environment.json') -Encoding utf8

    for ($run = 1; $run -le 3; $run++) {
        Start-App $false
        Invoke-Warmup 'db-only' $run 'DISABLED'
        Measure-Scenario 'db-only' $run 120 'DISABLED' "db-only-run-$run.json"
    }
    for ($run = 1; $run -le 3; $run++) {
        Invoke-Redis FLUSHDB | Out-Null
        Start-App $true
        Invoke-Warmup 'redis-normal' $run 'MISS_FALLBACK,HIT'
        Invoke-CachePrefill
        Measure-Scenario 'redis-normal' $run 120 'HIT,TIMEOUT_FALLBACK,MISS_FALLBACK,ERROR_FALLBACK' "redis-run-$run.json"
    }

    Invoke-Redis FLUSHDB | Out-Null
    Start-App $true
    Invoke-Warmup 'redis-down' 1 'MISS_FALLBACK,HIT'
    Invoke-Compose stop redis
    Start-Sleep -Seconds 2
    Measure-Scenario 'redis-down' 1 30 'ERROR_FALLBACK,TIMEOUT_FALLBACK' 'redis-down.json'
    $liveness = Invoke-WebRequest "$baseUrl/actuator/health/liveness" -TimeoutSec 5
    Assert-Stage5 ($liveness.StatusCode -eq 200) 'Redis 중단 측정 후 liveness 실패'
    Invoke-Compose start redis
    Wait-App $true

    Invoke-Redis FLUSHDB | Out-Null
    Start-App $true
    Invoke-Warmup 'redis-timeout' 1 'MISS_FALLBACK,HIT'
    Invoke-Proxy POST '/proxies/redis/toxics' @{
        name = 'latency'; type = 'latency'; stream = 'downstream'; attributes = @{ latency = 300; jitter = 0 }
    }
    Measure-Scenario 'redis-timeout' 1 30 'TIMEOUT_FALLBACK' 'redis-timeout.json'
    Invoke-Proxy DELETE '/proxies/redis/toxics/latency' $null
    Wait-App $true

    Invoke-Redis FLUSHDB | Out-Null
    $env:RECOVERY_ID = '50000'
    $env:K6_SUMMARY_PATH = '/results/redis-recovery.json'
    Invoke-Compose run --rm -e RECOVERY_ID=50000 -e K6_SUMMARY_PATH=/results/redis-recovery.json k6 run /scripts/recovery.js
    Assert-Stage5 (Test-Path -LiteralPath (Join-Path $resultDir 'redis-recovery.json')) '복구 결과 JSON 생성 실패'

    $allResults | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath (Join-Path $resultDir 'scenario-results.json') -Encoding utf8
    $cpuRows | Export-Csv -LiteralPath (Join-Path $resultDir 'cpu.csv') -NoTypeInformation -Encoding utf8
    $allResults | Select-Object scenario, run, duration_seconds, achieved_rps, dropped_iterations, app_db_qps, mysql_select_qps | Export-Csv -LiteralPath (Join-Path $resultDir 'db-qps.csv') -NoTypeInformation -Encoding utf8
    Write-Summary
    Write-And-VerifyManifest
    Write-Output "STAGE5_RESULTS=$resultRelative"
    Write-Output 'STAGE5_MEASUREMENT=PASS'
} finally {
    try {
        if ($created) {
            Invoke-Compose down --volumes
            $containers = & docker ps -a --filter "label=com.docker.compose.project=$project" --format '{{.ID}}'
            $volumes = & docker volume ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}'
            $networks = & docker network ls --filter "label=com.docker.compose.project=$project" --format '{{.Name}}'
            Assert-Stage5 (-not $containers -and -not $volumes -and -not $networks) '측정 리소스 정리 실패'
            Write-Output 'STAGE5_CLEANUP=PASS'
        }
    } finally {
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
        Pop-Location
    }
}
