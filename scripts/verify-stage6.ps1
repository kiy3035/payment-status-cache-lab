#Requires -Version 7.0
[CmdletBinding()]
param(
    [string]$ResultDirectory = 'results/20260905-222015'
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$resultPath = if ([System.IO.Path]::IsPathRooted($ResultDirectory)) {
    $ResultDirectory
} else {
    Join-Path $root $ResultDirectory
}

function Assert-Stage6([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
}

function Get-K6Value($Summary, [string]$Metric, [string]$Value) {
    return [double]$Summary.metrics.$Metric.values.$Value
}

function Get-Median([object[]]$Items, [string]$Property) {
    [double[]]$values = @($Items | ForEach-Object { [double]($_.$Property) })
    $sorted = @($values | Sort-Object)
    return $sorted[[Math]::Floor($sorted.Count / 2)]
}

function Assert-Close([double]$Actual, [double]$Expected, [string]$Message) {
    Assert-Stage6 ([Math]::Abs($Actual - $Expected) -lt 0.000001) "$Message (expected=$Expected actual=$Actual)"
}

function Assert-Contains([string]$Text, [string]$Expected, [string]$Message) {
    Assert-Stage6 ($Text.Contains($Expected, [StringComparison]::Ordinal)) "$Message ($Expected)"
}

Assert-Stage6 (Test-Path -LiteralPath $resultPath -PathType Container) '측정 결과 디렉터리가 없습니다.'
$requiredResultFiles = @(
    'environment.json', 'scenario-results.json', 'cpu.csv', 'db-qps.csv', 'summary.md', 'manifest.json',
    'db-only-run-1.json', 'db-only-run-2.json', 'db-only-run-3.json',
    'redis-run-1.json', 'redis-run-2.json', 'redis-run-3.json',
    'redis-down.json', 'redis-timeout.json', 'redis-recovery.json'
)
foreach ($name in $requiredResultFiles) {
    Assert-Stage6 (Test-Path -LiteralPath (Join-Path $resultPath $name) -PathType Leaf) "필수 결과 파일이 없습니다: $name"
}

$manifest = @(Get-Content -LiteralPath (Join-Path $resultPath 'manifest.json') -Raw | ConvertFrom-Json)
$manifestNames = @($manifest | ForEach-Object { $_.file })
$actualNames = @(Get-ChildItem -LiteralPath $resultPath -File | Where-Object { $_.Name -ne 'manifest.json' -and $_.Extension -ne '.log' } | ForEach-Object { $_.Name })
Assert-Stage6 ($manifest.Count -eq $actualNames.Count) 'manifest 파일 개수와 실제 결과 파일 개수가 다릅니다.'
Assert-Stage6 ((@($manifestNames | Sort-Object) -join '|') -eq (@($actualNames | Sort-Object) -join '|')) 'manifest 파일 목록과 실제 결과 파일 목록이 다릅니다.'
foreach ($entry in $manifest) {
    $path = Join-Path $resultPath $entry.file
    Assert-Stage6 (Test-Path -LiteralPath $path -PathType Leaf) "manifest 대상 파일이 없습니다: $($entry.file)"
    $file = Get-Item -LiteralPath $path
    Assert-Stage6 ($file.Length -eq [int64]$entry.bytes) "manifest 크기가 다릅니다: $($entry.file)"
    $hash = (Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash.ToLowerInvariant()
    Assert-Stage6 ($hash -eq $entry.sha256) "manifest 해시가 다릅니다: $($entry.file)"
}

$results = @(Get-Content -LiteralPath (Join-Path $resultPath 'scenario-results.json') -Raw | ConvertFrom-Json)
$dbOnly = @($results | Where-Object { $_.scenario -eq 'db-only' })
$redisNormal = @($results | Where-Object { $_.scenario -eq 'redis-normal' })
$redisDown = @($results | Where-Object { $_.scenario -eq 'redis-down' })
$redisTimeout = @($results | Where-Object { $_.scenario -eq 'redis-timeout' })
Assert-Stage6 ($results.Count -eq 8) '집계 시나리오 결과는 8개여야 합니다.'
Assert-Stage6 ($dbOnly.Count -eq 3 -and $redisNormal.Count -eq 3) '정상 시나리오는 각각 3회여야 합니다.'
Assert-Stage6 ($redisDown.Count -eq 1 -and $redisTimeout.Count -eq 1) '장애 시나리오는 각각 1회여야 합니다.'

foreach ($result in $results) {
    $raw = Get-Content -LiteralPath (Join-Path $resultPath $result.raw_summary) -Raw | ConvertFrom-Json -Depth 100
    Assert-Stage6 ((Get-K6Value $raw 'http_reqs' 'count') -eq [double]$result.requests) "원시 요청 수와 집계가 다릅니다: $($result.scenario) run $($result.run)"
    Assert-Stage6 ([double]$result.success_rate -eq 100) "HTTP 성공률이 100%가 아닙니다: $($result.scenario) run $($result.run)"
}

Assert-Close (Get-Median $dbOnly 'avg_ms') 6.3199 'DB-only 평균 중앙값이 다릅니다.'
Assert-Close (Get-Median $redisNormal 'avg_ms') 3.2311 'Redis 정상 평균 중앙값이 다릅니다.'
Assert-Close (Get-Median $dbOnly 'p95_ms') 19.2359 'DB-only p95 중앙값이 다릅니다.'
Assert-Close (Get-Median $redisNormal 'p95_ms') 4.5394 'Redis 정상 p95 중앙값이 다릅니다.'
Assert-Close (Get-Median $dbOnly 'app_db_qps') 100.0083 'DB-only 앱 DB QPS 중앙값이 다릅니다.'
Assert-Close (Get-Median $redisNormal 'app_db_qps') 0.0083 'Redis 정상 앱 DB QPS 중앙값이 다릅니다.'
Assert-Close (Get-Median $redisNormal 'cache_hit_ratio') 0.999917 'Redis 정상 hit ratio 중앙값이 다릅니다.'
Assert-Stage6 ($redisDown[0].requests -eq 2991 -and $redisDown[0].cache_error -eq 2991) 'Redis 중단 집계가 다릅니다.'
Assert-Stage6 ($redisTimeout[0].requests -eq 2854 -and $redisTimeout[0].cache_timeout -eq 2853) 'Redis timeout 집계가 다릅니다.'

$recovery = Get-Content -LiteralPath (Join-Path $resultPath 'redis-recovery.json') -Raw | ConvertFrom-Json -Depth 100
Assert-Stage6 ((Get-K6Value $recovery 'recovery_miss' 'count') -eq 1) 'Redis 복구 miss 횟수가 다릅니다.'
Assert-Stage6 ((Get-K6Value $recovery 'recovery_hit' 'count') -eq 1) 'Redis 복구 hit 횟수가 다릅니다.'
$cpuRows = @(Import-Csv -LiteralPath (Join-Path $resultPath 'cpu.csv'))
$dbQpsRows = @(Import-Csv -LiteralPath (Join-Path $resultPath 'db-qps.csv'))
Assert-Stage6 ($cpuRows.Count -eq 2340) 'CPU 1초 원시 표본 수가 다릅니다.'
Assert-Stage6 ($dbQpsRows.Count -eq 8) 'DB QPS 집계 행 수가 다릅니다.'

$environment = Get-Content -LiteralPath (Join-Path $resultPath 'environment.json') -Raw | ConvertFrom-Json
Assert-Stage6 ($environment.run_id -eq '20260905-222015') '문서화 대상 실행 ID가 다릅니다.'
Assert-Stage6 ($environment.rps -eq 100 -and $environment.hot_set_size -eq 1000) '부하 측정 조건이 다릅니다.'
Assert-Stage6 ($environment.command_timeout_ms -eq 100 -and $environment.injected_latency_ms -eq 300) '장애 측정 조건이 다릅니다.'

$summary = Get-Content -LiteralPath (Join-Path $resultPath 'summary.md') -Raw
$resultsDocument = Get-Content -LiteralPath (Join-Path $root 'RESULTS.md') -Raw
$blogDocument = Get-Content -LiteralPath (Join-Path $root 'BLOG_DRAFT.md') -Raw
$readme = Get-Content -LiteralPath (Join-Path $root 'README.md') -Raw
$progress = Get-Content -LiteralPath (Join-Path $root 'PROGRESS.md') -Raw
Assert-Contains $summary '100.0083' '측정 요약에서 DB-only RPS를 찾지 못했습니다.'
Assert-Contains $summary '0.999917' '측정 요약에서 hit ratio를 찾지 못했습니다.'
Assert-Contains $resultsDocument '99.9917%' 'RESULTS에서 DB QPS 감소율을 찾지 못했습니다.'
Assert-Contains $resultsDocument '2704.1618ms' 'RESULTS에서 timeout 평균을 찾지 못했습니다.'
Assert-Contains $blogDocument '2,854건' 'BLOG_DRAFT에서 timeout 완료 요청을 찾지 못했습니다.'
Assert-Contains $blogDocument '52 tests' 'BLOG_DRAFT에서 전체 테스트 결과를 찾지 못했습니다.'
Assert-Contains $readme '.\scripts\verify-stage6.ps1' 'README에서 최종 문서 검증 명령을 찾지 못했습니다.'
Assert-Contains $progress '6단계 — 최종 결과 문서화: 완료' 'PROGRESS에서 6단계 완료 상태를 찾지 못했습니다.'
foreach ($document in @($resultsDocument, $blogDocument, $readme)) {
    Assert-Stage6 ($document -notmatch '(?im)\bTODO\b|\bTBD\b|작성 예정') '최종 문서에 미완성 표시가 남아 있습니다.'
}

Write-Output "STAGE6_RESULT_FILES=$($manifest.Count)"
Write-Output "STAGE6_SCENARIOS=$($results.Count)"
Write-Output "STAGE6_CPU_SAMPLES=$($cpuRows.Count)"
Write-Output 'STAGE6_DOCUMENT_VERIFICATION=PASS'
