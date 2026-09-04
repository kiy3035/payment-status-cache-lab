#Requires -Version 7.0
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$repositoryRoot = Split-Path -Parent $PSScriptRoot
$composeProject = 'payment-status-cache-lab-stage4-' + [guid]::NewGuid().ToString('N').Substring(0, 8)
$appProcess = $null
$created = $false
$environmentNames = @(
    'MYSQL_HOST', 'MYSQL_PORT', 'MYSQL_DATABASE', 'MYSQL_USER', 'MYSQL_PASSWORD', 'MYSQL_ROOT_PASSWORD',
    'REDIS_HOST', 'REDIS_PORT', 'TOXIPROXY_API_PORT', 'TOXIPROXY_REDIS_PORT', 'SERVER_PORT',
    'PAYMENT_STATUS_CACHE_ENABLED', 'PAYMENT_STATUS_CACHE_KEY_PREFIX', 'PAYMENT_STATUS_CACHE_TTL',
    'PAYMENT_STATUS_CACHE_COMMAND_TIMEOUT', 'PAYMENT_STATUS_CACHE_CONNECT_TIMEOUT', 'SPRING_DATASOURCE_URL'
)
$savedEnvironment = @{}
foreach ($name in $environmentNames) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, 'Process')
}

function Assert-Check([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-FreePort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    $listener.Start()
    try { return $listener.LocalEndpoint.Port } finally { $listener.Stop() }
}

function Invoke-Compose {
    & docker compose -p $composeProject @args
    Assert-Check ($LASTEXITCODE -eq 0) '검증 전용 Compose 명령 실패'
}

function Invoke-Redis {
    $result = & docker compose -p $composeProject exec -T redis redis-cli --raw @args
    Assert-Check ($LASTEXITCODE -eq 0) '검증 전용 Redis 명령 실패'
    return ($result -join "`n").Trim()
}

function Invoke-Proxy([string]$Method, [string]$Path, $Body) {
    $parameters = @{
        Uri = "$proxyUrl$Path"; Method = $Method; TimeoutSec = 5
        Headers = @{ 'User-Agent' = 'toxiproxy-cli/2.12.0' }
    }
    if ($null -ne $Body) {
        $parameters.ContentType = 'application/json'
        $parameters.Body = $Body | ConvertTo-Json -Depth 8 -Compress
    }
    Invoke-RestMethod @parameters | Out-Null
}

function Invoke-App([string]$Path, [string]$Method = 'GET', [string]$Body = '') {
    $parameters = @{ Uri = "$baseUrl$Path"; Method = $Method; TimeoutSec = 5; SkipHttpErrorCheck = $true }
    if ($Body) { $parameters.ContentType = 'application/json'; $parameters.Body = $Body }
    return Invoke-WebRequest @parameters
}

function Get-ResponseJson($Response) {
    # Actuator 전용 JSON 미디어 타입은 PowerShell에서 바이트 배열로 반환될 수 있다.
    $content = $Response.Content
    if ($content -is [byte[]]) { $content = [System.Text.Encoding]::UTF8.GetString($content) }
    return ConvertFrom-Json -InputObject $content
}

function Assert-Response($Response, [int]$Status, [string]$CacheResult, [string]$PaymentStatus) {
    Assert-Check ($Response.StatusCode -eq $Status) "HTTP 응답 검증 실패: $($Response.StatusCode)"
    if ($CacheResult) {
        Assert-Check (($Response.Headers['X-Cache-Result'] -join ',') -eq $CacheResult) '캐시 경로 검증 실패'
    }
    if ($PaymentStatus) {
        $actualStatus = (Get-ResponseJson $Response).status
        Assert-Check ($actualStatus -eq $PaymentStatus) "상태 검증 실패: 경로=$($Response.BaseResponse.RequestMessage.RequestUri.AbsolutePath), 기대=$PaymentStatus, 실제=$actualStatus"
    }
}

function Wait-Healthy {
    $deadline = (Get-Date).AddSeconds(60)
    do {
        try {
            $response = Invoke-App '/actuator/health'
            if ($response.StatusCode -eq 200) { return }
        } catch [System.Net.Http.HttpRequestException] {
        } catch [System.Threading.Tasks.TaskCanceledException] {
        }
        Start-Sleep -Milliseconds 250
    } while ((Get-Date) -lt $deadline)
    throw '애플리케이션 정상 복구 대기 시간 초과'
}

Push-Location $repositoryRoot
try {
    $jar = Join-Path $repositoryRoot 'build/libs/payment-status-cache-lab-0.0.1-SNAPSHOT.jar'
    Assert-Check (Test-Path -LiteralPath $jar) '먼저 Gradle Wrapper bootJar를 실행하세요.'
    $env:MYSQL_HOST = '127.0.0.1'
    $env:MYSQL_PORT = [string](Get-FreePort)
    $env:MYSQL_DATABASE = 'payment_lab'
    $env:MYSQL_USER = 'payment_app'
    $env:MYSQL_PASSWORD = 'validation-' + [guid]::NewGuid().ToString('N')
    $env:MYSQL_ROOT_PASSWORD = 'validation-' + [guid]::NewGuid().ToString('N')
    $env:REDIS_HOST = '127.0.0.1'
    $directRedisPort = [string](Get-FreePort)
    $env:REDIS_PORT = $directRedisPort
    $env:TOXIPROXY_API_PORT = [string](Get-FreePort)
    $env:TOXIPROXY_REDIS_PORT = [string](Get-FreePort)
    $env:SERVER_PORT = [string](Get-FreePort)
    $env:PAYMENT_STATUS_CACHE_ENABLED = 'true'
    $env:PAYMENT_STATUS_CACHE_KEY_PREFIX = 'stage4:manual:payment:status:'
    $env:PAYMENT_STATUS_CACHE_TTL = '2m'
    $env:PAYMENT_STATUS_CACHE_COMMAND_TIMEOUT = '100ms'
    $env:PAYMENT_STATUS_CACHE_CONNECT_TIMEOUT = '100ms'
    $env:SPRING_DATASOURCE_URL = "jdbc:mysql://127.0.0.1:$env:MYSQL_PORT/$env:MYSQL_DATABASE" + '?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=1000&socketTimeout=10000'
    $baseUrl = "http://127.0.0.1:$env:SERVER_PORT"
    $proxyUrl = "http://127.0.0.1:$env:TOXIPROXY_API_PORT"

    # 임의의 고유 프로젝트에서 생성한 리소스만 장애 주입과 정리 대상으로 삼는다.
    $existing = & docker ps -a --filter "label=com.docker.compose.project=$composeProject" --format '{{.ID}}'
    Assert-Check ($LASTEXITCODE -eq 0 -and -not $existing) '검증 프로젝트 이름 충돌 또는 Docker 접근 실패'
    Invoke-Compose config --quiet
    $created = $true
    Invoke-Compose up -d --wait mysql redis toxiproxy
    Invoke-Proxy POST '/proxies' @{ name = 'redis'; listen = '0.0.0.0:26379'; upstream = 'redis:6379' }
    $env:REDIS_PORT = $env:TOXIPROXY_REDIS_PORT
    $appProcess = Start-Process -FilePath 'java' -ArgumentList @(
        '-jar', ('"' + $jar + '"'), '--spring.datasource.hikari.connection-timeout=700',
        '--spring.datasource.hikari.validation-timeout=500'
    ) -WindowStyle Hidden -PassThru -RedirectStandardOutput "build/$composeProject.stdout.log" -RedirectStandardError "build/$composeProject.stderr.log"
    # Compose의 Redis 공개 포트와 앱의 프록시 접속 포트는 서로 독립적이다.
    $env:REDIS_PORT = $directRedisPort
    Wait-Healthy
    Write-Output "PROJECT=$composeProject APP_PID=$($appProcess.Id)"
    Invoke-Compose ps
    Assert-Check ((Invoke-Redis PING) -eq 'PONG') 'Redis 연결 검증 실패'
    Assert-Response (Invoke-App '/api/v1/payments/100/status') 200 'MISS_FALLBACK' 'READY'
    Assert-Response (Invoke-App '/api/v1/payments/100/status') 200 'HIT' 'READY'

    Invoke-Proxy POST '/proxies/redis/toxics' @{
        name = 'latency'; type = 'latency'; stream = 'downstream'; attributes = @{ latency = 300; jitter = 0 }
    }
    $timer = [System.Diagnostics.Stopwatch]::StartNew()
    $delayed = Invoke-App '/api/v1/payments/103/status'
    $timer.Stop()
    Assert-Response $delayed 200 'TIMEOUT_FALLBACK' 'READY'
    Assert-Check ($timer.ElapsedMilliseconds -ge 80 -and $timer.ElapsedMilliseconds -lt 900) '100ms 명령 제한의 HTTP 소요 시간 검증 실패'
    Write-Output "TIMEOUT_FALLBACK_MS=$($timer.ElapsedMilliseconds)"
    Invoke-Proxy DELETE '/proxies/redis/toxics/latency' $null
    Wait-Healthy

    Assert-Check ((Invoke-Redis ACL SETUSER default -get) -eq 'OK') '읽기 장애 주입 실패'
    Assert-Response (Invoke-App '/api/v1/payments/106/status') 200 'ERROR_FALLBACK' 'READY'
    Invoke-Redis ACL SETUSER default +get | Out-Null
    Assert-Response (Invoke-App '/api/v1/payments/106/status') 200 'HIT' 'READY'

    Assert-Response (Invoke-App '/api/v1/payments/109/status') 200 'MISS_FALLBACK' 'READY'
    Invoke-Redis ACL SETUSER default -set | Out-Null
    Assert-Response (Invoke-App '/api/v1/payments/112/status') 200 'MISS_FALLBACK' 'READY'
    Assert-Check ((Invoke-Redis EXISTS ($env:PAYMENT_STATUS_CACHE_KEY_PREFIX + '112')) -eq '0') '쓰기 실패 키 검증 실패'
    Assert-Response (Invoke-App '/api/v1/payments/109/status' PATCH '{"targetStatus":"AUTH"}') 200 '' 'AUTH'
    Assert-Response (Invoke-App '/api/v1/payments/109/status') 200 'HIT' 'READY'
    Write-Output 'READ_ERROR_WRITE_ERROR_AND_STALE=PASS'
    Invoke-Redis ACL SETUSER default +set | Out-Null

    Invoke-Compose stop redis
    $disconnectDeadline = (Get-Date).AddSeconds(5)
    do {
        $duringStop = Invoke-App '/api/v1/payments/115/status'
        Assert-Response $duringStop 200 '' 'READY'
        $stopHeader = $duringStop.Headers['X-Cache-Result'] -join ','
        if ($stopHeader -eq 'ERROR_FALLBACK') { break }
        Start-Sleep -Milliseconds 100
    } while ((Get-Date) -lt $disconnectDeadline)
    Assert-Check ($stopHeader -eq 'ERROR_FALLBACK') 'Redis 중단 fallback 검증 실패'
    Assert-Response (Invoke-App '/api/v1/payments/115/status' PATCH '{"targetStatus":"AUTH"}') 200 '' 'AUTH'
    Assert-Response (Invoke-App '/actuator/health/liveness') 200 '' 'UP'
    Assert-Response (Invoke-App '/actuator/health/readiness') 200 '' 'UP'
    Assert-Response (Invoke-App '/actuator/health') 503 '' 'DOWN'
    Assert-Check (-not $appProcess.HasExited) 'Redis 중단 중 애플리케이션 종료'
    Write-Output "REDIS_STOP_FALLBACK=$stopHeader LIVENESS=UP PATCH=AUTH"

    Invoke-Compose start redis
    Wait-Healthy
    Assert-Response (Invoke-App '/api/v1/payments/115/status') 200 'MISS_FALLBACK' 'AUTH'
    Assert-Response (Invoke-App '/api/v1/payments/115/status') 200 'HIT' 'AUTH'
    Write-Output 'RECOVERY_MISS_THEN_HIT=PASS'
    $db = & docker compose -p $composeProject exec -T mysql sh -c 'MYSQL_PWD="$MYSQL_PASSWORD" mysql -u"$MYSQL_USER" -D"$MYSQL_DATABASE" -Nse "SELECT status,version FROM payments WHERE id IN (109,115) ORDER BY id; SELECT COUNT(*) FROM payments;"'
    Assert-Check ($LASTEXITCODE -eq 0 -and $db[0] -eq "AUTH`t1" -and $db[1] -eq "AUTH`t1" -and $db[2] -eq '100000') 'DB commit 및 seed 검증 실패'
    Write-Output ("MYSQL={0}" -f ($db -join '|'))

    Invoke-Proxy POST '/proxies/redis' @{ enabled = $false }
    Invoke-Compose stop mysql
    $unavailable = Invoke-App '/api/v1/payments/118/status'
    Assert-Response $unavailable 503 '' ''
    Assert-Check ((Get-ResponseJson $unavailable).code -eq 'PAYMENT_STATUS_UNAVAILABLE') '이중 장애 오류 코드 검증 실패'
    Assert-Response (Invoke-App '/actuator/health/liveness') 200 '' 'UP'
    Assert-Response (Invoke-App '/actuator/health/readiness') 503 '' 'DOWN'
    Write-Output 'DUAL_FAILURE=503 LIVENESS=UP READINESS=DOWN'
    (Invoke-App '/actuator/prometheus').Content -split "`n" | Where-Object {
        $_ -match '^payment_status_(cache_access_total|db_read_total|cache_write_total|transition_total)'
    }
    Assert-Check (-not $appProcess.HasExited) '최종 애플리케이션 생존 검증 실패'
    Write-Output 'STAGE4_RUNTIME_VERIFICATION=PASS'
} finally {
    try {
        if ($null -ne $appProcess -and -not $appProcess.HasExited) {
            Stop-Process -Id $appProcess.Id
            Assert-Check ($appProcess.WaitForExit(10000)) '검증용 앱 종료 실패'
        }
        if ($created) {
            $env:REDIS_PORT = $directRedisPort
            Invoke-Compose down --volumes
            $remaining = & docker ps -a --filter "label=com.docker.compose.project=$composeProject" --format '{{.ID}}'
            Assert-Check ($LASTEXITCODE -eq 0 -and -not $remaining) '검증 컨테이너 정리 실패'
            $volumes = & docker volume ls --filter "label=com.docker.compose.project=$composeProject" --format '{{.Name}}'
            Assert-Check ($LASTEXITCODE -eq 0 -and -not $volumes) '검증 볼륨 정리 실패'
            $networks = & docker network ls --filter "label=com.docker.compose.project=$composeProject" --format '{{.Name}}'
            Assert-Check ($LASTEXITCODE -eq 0 -and -not $networks) '검증 네트워크 정리 실패'
            Write-Output 'STAGE4_CLEANUP=PASS'
        }
    } finally {
        foreach ($name in $environmentNames) {
            [Environment]::SetEnvironmentVariable($name, $savedEnvironment[$name], 'Process')
        }
        Pop-Location
    }
}
