# 会晓 AI Services

服务端工程目录。

```text
services
├─ api_server   # App 统一业务接口，当前可运行
├─ asr_service    # FunASR/CAMPPlus 文件转写 + 实时转写网关
├─ ai_service     # LLM 纪要、embedding 与知识库问答服务
├─ funasr_runtime # FunASR 2pass + CAMPPlus WebSocket 运行时
└─ tools          # 本地链路检查脚本
```

App 只直接对接 `api_server`。`api_server` 负责调用 `asr_service`、`ai_service`，并把任务、纪要结果和知识库索引写入共享远程数据库。

## 文件上传转写

文件上传处理链路已统一到 `FunASR Offline + CAMPPlus`，不再使用旧的 Dolphin 18 秒文本切段，也不再复用实时 WebSocket 的增量片段聚类。

数据流向：

```text
Android App 上传音频/视频
  -> api_server 保存临时文件
  -> asr_service 预处理为 16kHz 单声道 PCM
  -> FunASR Offline + VAD + PUNC + CAMPPlus
  -> 句级转写片段 + 说话人标签
  -> LLM 纪要/议题/待办/风险
  -> 共享远程数据库 + App 详情页
```

## 实时转写与匿名说话人分离

前期正式链路采用 `FunASR 2pass + CAMPPlus`，声纹识别后期再做。

本地启动：

```powershell
.\start_local_services.bat
```

数据流向：

```text
Android App AudioRecord(PCM 16k mono)
  -> WebSocket ws://API/api/v1/live/ws
  -> api_server WebSocket 代理
  -> asr_service WebSocket 网关
  -> FunASR 2pass + CAMPPlus 运行时
  -> transcript.partial / transcript.final
  -> App 实时转写列表
```

关键配置：

```text
api_server:
HUIXIAO_DATABASE_URL=mysql+pymysql://...  # 必须指向共享远程数据库，不允许本地库兜底
HUIXIAO_ASR_LIVE_WS_URL=ws://127.0.0.1:8081/api/v1/live/ws

asr_service:
HUIXIAO_FUNASR_RUNTIME_WS_URL=ws://127.0.0.1:10095
HUIXIAO_LIVE_ASR_MODE=online_refine
HUIXIAO_LIVE_CHUNK_SIZE=0,8,4
HUIXIAO_LIVE_CHUNK_INTERVAL=8
HUIXIAO_LIVE_REQUIRE_SPEAKER=false
```

App 端只保存完整录音文件和展示服务端返回的实时结果；不在 App 端做 ASR、说话人分离或声纹识别。实时字幕链路优先低延迟展示，停止录音后的完整音频处理链路仍负责高准确率转写、说话人分离、声纹识别和会议纪要。

本地链路检查：

```powershell
Dolphin_poc_env\Scripts\python.exe services\tools\live_ws_check.py test_assets\speaker_split_check\multi_speaker_meeting.wav --realtime
```
