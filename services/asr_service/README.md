# 会晓 AI ASR Service

当前 ASR 主线是 `阿里云百炼实时 ASR + FunASR runtime 文件/声纹处理`：

- 实时转写：App PCM -> API WebSocket 代理 -> `asr_service` -> 阿里云百炼 `qwen3-asr-flash-realtime`。
- 文件转写：`asr_service` 用 ffmpeg 预处理为 16kHz mono WAV/PCM，再通过 WebSocket 交给 FunASR runtime 的离线路径。
- 说话人：实时字幕阶段不做说话人分离；文件转写/声纹阶段由 runtime 内 CAMPPlus 和 `asr_service` 的说话人修复、声纹抽取逻辑处理。

不要恢复旧 Dolphin-CN-Dialect ASR 入口。`Dolphin`、`Dolphin_poc` 和 `Dolphin_poc_env` 只属于历史 POC/本机 Python 环境命名，不再是当前 ASR 服务的模型所有权来源。

## 本地运行

先启动 FunASR runtime：

```powershell
cd F:\会晓AI
services\funasr_runtime\start_runtime.ps1
```

再启动 ASR 服务：

```powershell
cd F:\会晓AI\services\asr_service
$env:HUIXIAO_FUNASR_RUNTIME_WS_URL='ws://127.0.0.1:10095'
$env:HUIXIAO_FFMPEG_PATH='ffmpeg'
$env:HUIXIAO_ASR_DEVICE='cpu'
F:\会晓AI\Dolphin_poc_env\Scripts\python.exe -m uvicorn app.main:app --host 127.0.0.1 --port 8081
```

接口：

```text
GET  /health
POST /api/v1/transcriptions
POST /api/v1/voiceprints/extract
POST /api/v1/voiceprints/enroll-audio
WS   /api/v1/live/ws
```

## 安全边界

- 实时 ASR 默认由 `HUIXIAO_LIVE_ASR_PROVIDER=aliyun` 启用，复用 `HUIXIAO_LLM_API_KEY`，也可用 `HUIXIAO_ALIYUN_DASHSCOPE_API_KEY` 单独覆盖。
- 离线 ASR 模型只允许在 `funasr_runtime` 进程中加载，不能在 `asr_service` 内恢复第二套离线 ASR 模型。
- `deploy/funasr_wss_server.py` 的离线模型当前固定为 `paraformer-zh`。模型替换必须走隔离实验，不允许上线前直接切换。
- 热词当前默认禁用。热词会改变转写原文，并影响后续纪要、知识库和 AI 问答，只能在基线 A/B 证明收益大于误触发风险后开启。
- 实时音频预处理当前不在主链路。历史上曾经接入后又被移除，不能在没有 CER/延迟/漏字对比的情况下恢复。
