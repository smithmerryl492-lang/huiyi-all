$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$servicesRoot = Join-Path $root "services"
$apiPython = Join-Path $servicesRoot ".venv\Scripts\python.exe"
$asrPython = Join-Path $root "Dolphin_poc_env\Scripts\python.exe"
$apiDir = Join-Path $servicesRoot "api_server"
$asrDir = Join-Path $servicesRoot "asr_service"
$aiDir = Join-Path $servicesRoot "ai_service"
$logDir = Join-Path $servicesRoot "logs"
$envFile = Join-Path $servicesRoot ".env"

function Stop-LocalServiceProcesses {
    Get-CimInstance Win32_Process |
        Where-Object {
            $_.Name -eq "python.exe" -and
            $_.CommandLine -match "uvicorn\s+app\.main:app" -and
            $_.CommandLine -match "--port\s+808[012]"
        } |
        ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
    Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -in 8080, 8081, 8082 } |
        ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }
}

function Wait-Port {
    param(
        [int]$Port,
        [int]$TimeoutSeconds = 120
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    while ((Get-Date) -lt $deadline) {
        $listener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
        if ($listener) {
            return $true
        }
        Start-Sleep -Seconds 1
    }
    return $false
}

function Wait-Http {
    param(
        [string]$Url,
        [int]$TimeoutSeconds = 60
    )
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $lastError = $null
    while ((Get-Date) -lt $deadline) {
        try {
            return (Invoke-WebRequest -UseBasicParsing -Uri $Url -TimeoutSec 5).Content
        } catch {
            $lastError = $_.Exception.Message
            Start-Sleep -Seconds 1
        }
    }
    throw "HTTP check failed: $Url. Last error: $lastError"
}

if (Test-Path $envFile) {
    Get-Content -Encoding UTF8 $envFile |
        Where-Object { $_ -match "^\s*[^#][^=]+=" } |
        ForEach-Object {
            $key, $value = $_ -split "=", 2
            [Environment]::SetEnvironmentVariable($key.Trim(), $value.Trim().Trim('"').Trim("'"), "Process")
        }
}

if (-not (Test-Path $apiPython)) {
    throw "API Python venv not found: $apiPython"
}
if (-not (Test-Path $asrPython)) {
    throw "ASR Python runtime not found: $asrPython"
}
if (-not $env:HUIXIAO_DATABASE_URL) {
    throw "HUIXIAO_DATABASE_URL is required. Set it in services\.env to the shared remote database; local PostgreSQL fallback is disabled."
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Write-Host "Stopping existing local uvicorn services on 8080/8081/8082..."
Stop-LocalServiceProcesses
Start-Sleep -Seconds 1

$env:HUIXIAO_ASR_MAX_SPEAKERS = if ($env:HUIXIAO_ASR_MAX_SPEAKERS) { $env:HUIXIAO_ASR_MAX_SPEAKERS } else { "15" }
$env:HUIXIAO_LIVE_ASR_PROVIDER = if ($env:HUIXIAO_LIVE_ASR_PROVIDER) { $env:HUIXIAO_LIVE_ASR_PROVIDER } else { "aliyun" }
$env:HUIXIAO_ALIYUN_REGION = if ($env:HUIXIAO_ALIYUN_REGION) { $env:HUIXIAO_ALIYUN_REGION } else { "cn-beijing" }
$env:HUIXIAO_ALIYUN_REALTIME_MODEL = if ($env:HUIXIAO_ALIYUN_REALTIME_MODEL) { $env:HUIXIAO_ALIYUN_REALTIME_MODEL } else { "qwen3-asr-flash-realtime" }
$env:HUIXIAO_ALIYUN_DASHSCOPE_API_KEY = if ($env:HUIXIAO_ALIYUN_DASHSCOPE_API_KEY) { $env:HUIXIAO_ALIYUN_DASHSCOPE_API_KEY } else { $env:HUIXIAO_LLM_API_KEY }
$env:HUIXIAO_ALIYUN_REALTIME_TOKEN_TTL_SECONDS = if ($env:HUIXIAO_ALIYUN_REALTIME_TOKEN_TTL_SECONDS) { $env:HUIXIAO_ALIYUN_REALTIME_TOKEN_TTL_SECONDS } else { "1800" }
$env:HUIXIAO_ALIYUN_REALTIME_PREWARM_ENABLED = if ($env:HUIXIAO_ALIYUN_REALTIME_PREWARM_ENABLED) { $env:HUIXIAO_ALIYUN_REALTIME_PREWARM_ENABLED } else { "true" }
$startFunasrRuntime = ($env:HUIXIAO_START_FUNASR_RUNTIME -and $env:HUIXIAO_START_FUNASR_RUNTIME.ToLowerInvariant() -in @("1", "true", "yes"))
if ($startFunasrRuntime) {
    Write-Host "Starting local FunASR runtime on 127.0.0.1:10095..."
    & (Join-Path $servicesRoot "funasr_runtime\start_runtime.ps1")
    Start-Sleep -Seconds 2
}

$env:HUIXIAO_ASR_SERVICE_URL = "http://127.0.0.1:8081"
$env:HUIXIAO_ASR_LIVE_WS_URL = "ws://127.0.0.1:8081/api/v1/live/ws"
$env:HUIXIAO_TASK_PROCESSING_CONCURRENCY = if ($env:HUIXIAO_TASK_PROCESSING_CONCURRENCY) { $env:HUIXIAO_TASK_PROCESSING_CONCURRENCY } else { "1" }
Start-Process -FilePath $apiPython `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8080") `
    -WorkingDirectory $apiDir `
    -RedirectStandardOutput (Join-Path $logDir "api.out.log") `
    -RedirectStandardError (Join-Path $logDir "api.err.log") `
    -WindowStyle Hidden

$env:HUIXIAO_AI_MINUTES_CONCURRENCY = if ($env:HUIXIAO_AI_MINUTES_CONCURRENCY) { $env:HUIXIAO_AI_MINUTES_CONCURRENCY } else { "2" }
$env:HUIXIAO_AI_KNOWLEDGE_ANSWER_CONCURRENCY = if ($env:HUIXIAO_AI_KNOWLEDGE_ANSWER_CONCURRENCY) { $env:HUIXIAO_AI_KNOWLEDGE_ANSWER_CONCURRENCY } else { "2" }
$env:HUIXIAO_AI_EMBEDDING_CONCURRENCY = if ($env:HUIXIAO_AI_EMBEDDING_CONCURRENCY) { $env:HUIXIAO_AI_EMBEDDING_CONCURRENCY } else { "2" }
Start-Process -FilePath $apiPython `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8082") `
    -WorkingDirectory $aiDir `
    -RedirectStandardOutput (Join-Path $logDir "ai.out.log") `
    -RedirectStandardError (Join-Path $logDir "ai.err.log") `
    -WindowStyle Hidden

$env:HUIXIAO_FUNASR_RUNTIME_WS_URL = "ws://127.0.0.1:10095"
$env:HUIXIAO_LIVE_ASR_MODE = "online_refine"
$env:HUIXIAO_LIVE_CHUNK_SIZE = "0,8,4"
$env:HUIXIAO_LIVE_CHUNK_INTERVAL = "8"
$env:HUIXIAO_LIVE_REQUIRE_SPEAKER = "false"
$env:HUIXIAO_ASR_MAX_LIVE_SESSIONS = if ($env:HUIXIAO_ASR_MAX_LIVE_SESSIONS) { $env:HUIXIAO_ASR_MAX_LIVE_SESSIONS } else { "24" }
$env:HUIXIAO_ASR_MAX_FILE_TRANSCRIPTIONS = if ($env:HUIXIAO_ASR_MAX_FILE_TRANSCRIPTIONS) { $env:HUIXIAO_ASR_MAX_FILE_TRANSCRIPTIONS } else { "2" }
$env:HUIXIAO_ASR_MAX_VOICEPRINT_JOBS = if ($env:HUIXIAO_ASR_MAX_VOICEPRINT_JOBS) { $env:HUIXIAO_ASR_MAX_VOICEPRINT_JOBS } else { "2" }
$ffmpegExe = "F:\Program Files\ffmpeg\bin\ffmpeg.exe"
if (Test-Path $ffmpegExe) {
    $env:HUIXIAO_FFMPEG_PATH = $ffmpegExe
    $env:PATH = "$(Split-Path $ffmpegExe -Parent);$env:PATH"
}
Start-Process -FilePath $asrPython `
    -ArgumentList @("-m", "uvicorn", "app.main:app", "--host", "127.0.0.1", "--port", "8081") `
    -WorkingDirectory $asrDir `
    -RedirectStandardOutput (Join-Path $logDir "asr.out.log") `
    -RedirectStandardError (Join-Path $logDir "asr.err.log") `
    -WindowStyle Hidden

Write-Host "Waiting for local services..."
$ports = @(8080, 8081, 8082)
if ($startFunasrRuntime) {
    $ports += 10095
}
foreach ($port in $ports) {
    $timeout = if ($port -eq 10095) { 300 } else { 90 }
    if (-not (Wait-Port -Port $port -TimeoutSeconds $timeout)) {
        throw "Port $port did not start within ${timeout}s. Check logs under $logDir."
    }
}
$health = Wait-Http -Url "http://127.0.0.1:8080/api/v1/health" -TimeoutSeconds 60

Write-Host "Local services ready:"
Write-Host "  API:   http://127.0.0.1:8080"
Write-Host "  ASR:   http://127.0.0.1:8081"
Write-Host "  AI:    http://127.0.0.1:8082"
Write-Host "  Live:  ws://127.0.0.1:8080/api/v1/live/ws"
if ($startFunasrRuntime) {
    Write-Host "  FunASR ws://127.0.0.1:10095"
}
Write-Host "Health:"
Write-Host "  $health"
Write-Host "Logs:"
Write-Host "  $logDir"
