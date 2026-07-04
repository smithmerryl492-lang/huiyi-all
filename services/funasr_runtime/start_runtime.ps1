$ErrorActionPreference = "Stop"

$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$python = Join-Path $root "Dolphin_poc_env\Scripts\python.exe"
$runtimeRoot = Join-Path $PSScriptRoot "FunASR"
$deployServer = Join-Path $root "deploy\funasr_wss_server.py"
$server = if (Test-Path $deployServer) { $deployServer } else { Join-Path $runtimeRoot "runtime\python\websocket\funasr_wss_server.py" }
$workdir = Join-Path $runtimeRoot "runtime\python\websocket"
$logDir = Join-Path $PSScriptRoot "logs"

if (-not (Test-Path $python)) {
    throw "Python runtime not found: $python"
}
if (-not (Test-Path $server)) {
    throw "FunASR websocket server not found: $server"
}

New-Item -ItemType Directory -Force -Path $logDir | Out-Null

Get-CimInstance Win32_Process |
    Where-Object { $_.Name -eq "python.exe" -and $_.CommandLine -like "*funasr_wss_server.py*" } |
    ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
Start-Sleep -Seconds 1

$env:PYTHONPATH = $runtimeRoot
$ffmpegDir = "F:\Program Files\ffmpeg\bin"
if (Test-Path $ffmpegDir) {
    $env:PATH = "$ffmpegDir;$env:PATH"
}

$vadMaxEndSilenceMs = if ($env:HUIXIAO_FUNASR_VAD_MAX_END_SILENCE_MS) { $env:HUIXIAO_FUNASR_VAD_MAX_END_SILENCE_MS } else { "800" }
$endpointDelayMs = if ($env:HUIXIAO_FUNASR_OFFLINE_ENDPOINT_DELAY_MS) { $env:HUIXIAO_FUNASR_OFFLINE_ENDPOINT_DELAY_MS } else { "300" }
$mergeSilenceMs = if ($env:HUIXIAO_FUNASR_OFFLINE_MERGE_SILENCE_MS) { $env:HUIXIAO_FUNASR_OFFLINE_MERGE_SILENCE_MS } else { "260" }
$preRollMs = if ($env:HUIXIAO_FUNASR_OFFLINE_PRE_ROLL_MS) { $env:HUIXIAO_FUNASR_OFFLINE_PRE_ROLL_MS } else { "240" }
$workerThreads = if ($env:HUIXIAO_FUNASR_WORKER_THREADS) { $env:HUIXIAO_FUNASR_WORKER_THREADS } else { "4" }
$concurrentVad = if ($env:HUIXIAO_FUNASR_CONCURRENT_VAD) { $env:HUIXIAO_FUNASR_CONCURRENT_VAD } else { "4" }
$concurrentAsrOnline = if ($env:HUIXIAO_FUNASR_CONCURRENT_ASR_ONLINE) { $env:HUIXIAO_FUNASR_CONCURRENT_ASR_ONLINE } else { "4" }
$concurrentAsrOffline = if ($env:HUIXIAO_FUNASR_CONCURRENT_ASR_OFFLINE) { $env:HUIXIAO_FUNASR_CONCURRENT_ASR_OFFLINE } else { "2" }
$concurrentPunc = if ($env:HUIXIAO_FUNASR_CONCURRENT_PUNC) { $env:HUIXIAO_FUNASR_CONCURRENT_PUNC } else { "1" }
$concurrentSv = if ($env:HUIXIAO_FUNASR_CONCURRENT_SV) { $env:HUIXIAO_FUNASR_CONCURRENT_SV } else { "1" }
$maxSpeakers = if ($env:HUIXIAO_ASR_MAX_SPEAKERS) { $env:HUIXIAO_ASR_MAX_SPEAKERS } else { "15" }
$speakerClusterThreshold = if ($env:HUIXIAO_SPEAKER_CLUSTER_THRESHOLD) { $env:HUIXIAO_SPEAKER_CLUSTER_THRESHOLD } else { "0.62" }

Start-Process -FilePath $python `
    -ArgumentList @(
        "-u",
        $server,
        "--host", "127.0.0.1",
        "--port", "10095",
        "--device", "cpu",
        "--ngpu", "0",
        "--ncpu", "2",
        "--worker_threads", $workerThreads,
        "--concurrent_vad", $concurrentVad,
        "--concurrent_asr_online", $concurrentAsrOnline,
        "--concurrent_asr_offline", $concurrentAsrOffline,
        "--concurrent_punc", $concurrentPunc,
        "--concurrent_sv", $concurrentSv,
        "--max_speakers", $maxSpeakers,
        "--speaker_cluster_threshold", $speakerClusterThreshold,
        "--vad_max_end_silence_time", $vadMaxEndSilenceMs,
        "--offline_endpoint_delay_ms", $endpointDelayMs,
        "--offline_merge_silence_ms", $mergeSilenceMs,
        "--offline_pre_roll_ms", $preRollMs,
        "--certfile=",
        "--keyfile="
    ) `
    -WorkingDirectory $workdir `
    -RedirectStandardOutput (Join-Path $logDir "runtime.out.log") `
    -RedirectStandardError (Join-Path $logDir "runtime.err.log") `
    -WindowStyle Hidden

Write-Host "FunASR runtime starting on ws://127.0.0.1:10095"
