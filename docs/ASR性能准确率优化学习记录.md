# ASR 性能与准确率优化学习记录

更新时间：2026-06-04

## 目标和边界

- 目标：在不破坏现有文件转写、实时转写、说话人分离、知识库问答链路的前提下，降低 ASR 文件转写链路的额外开销。
- 当前上线前边界：不替换模型，不恢复旧 Dolphin ASR，不调整 VAD/ASR/PUNC/声纹阈值，不启用热词，不新增实时音频预处理。
- 测试范围：只做本地服务测试，不启动、不修改测试服务器容器。

## 学习资料

- FunASR WebSocket 协议：配置和元信息使用 JSON，音频数据使用 bytes；PCM 音频可以直接发送 bytes。
  - https://github.com/modelscope/FunASR/blob/main/runtime/docs/websocket_protocol.md
  - 本地镜像：`services/funasr_runtime/FunASR/runtime/docs/websocket_protocol_zh.md`
- FunASR online/offline runtime 文档：2pass 会用实时模型输出并在句尾通过离线模型纠错；offline 文件转写包含 VAD、ASR、PUNC 等完整链路，支持热词但热词会改变识别输出。
  - https://github.com/modelscope/FunASR/blob/main/runtime/docs/SDK_advanced_guide_online.md
  - https://github.com/modelscope/FunASR/blob/main/runtime/docs/SDK_advanced_guide_offline.md
- Python websockets 文档：`str` 发送为 Text frame，`bytes`/`bytearray`/`memoryview` 发送为 Binary frame。
  - https://websockets.readthedocs.io/en/stable/reference/asyncio/client.html
- Base64 资料：Base64 每 24 bit 输入编码为 4 个字符，文件 PCM 放进 JSON 会带来体积膨胀和额外 encode/decode/copy。
  - https://datatracker.ietf.org/doc/html/rfc4648#section-4
  - https://docs.python.org/3/library/base64.html
- Android 音频效果资料：NoiseSuppressor/AGC 属于设备效果链，能否提高 ASR 取决于设备和场景，不能在无 A/B 数据时上线默认开启。
  - https://developer.android.com/reference/android/media/audiofx/NoiseSuppressor
- 外部模型方向只作为后续研究：faster-whisper、WhisperX、SenseVoice/上下文 Paraformer 等可能影响授权、部署资源、时延、声纹链路和上线稳定性，不纳入本轮上线前改动。

## 本轮判断

当前文件转写路径是：

1. ASR service 读取并规整音频为 16k mono s16le PCM。
2. ASR service 将整段 PCM `base64.b64encode` 后放进 JSON。
3. FunASR runtime 从 JSON 里取 `audio_b64` 并 `base64.b64decode`。
4. runtime 继续走同一套 VAD、ASR、PUNC、CAMPPlus 声纹和全局 speaker 聚类。

这个路径的问题不是模型准确率，而是传输层多做了一次 base64 编码、解码和大字符串拷贝。FunASR 官方协议本来就是 JSON 元信息加 bytes 音频帧，因此本轮采用低风险优化：

- ASR service 先发 `file_pcm_transcribe_binary` JSON 元信息。
- 下一帧直接发原始 PCM bytes。
- runtime 收到待处理文件请求后，把下一帧 bytes 交给同一个文件 PCM 处理函数。
- 原 `file_pcm_transcribe` + `audio_b64` 入口保留，方便回退和兼容旧客户端。

## 不做的高风险项

- 不启用热词：热词会改写 ASR 文本，可能影响知识点抽取、知识库召回和 AI 回答。除非后续有热词词表、权重、领域样本和知识库回归集，否则不能默认开启。
- 不恢复旧离线 ASR 模型或 Dolphin 入口：历史上存在同类模型重复加载导致服务器崩溃的风险，当前主线只保留 FunASR runtime 拥有离线模型。
- 不做实时预处理默认开启：降噪、AGC、AEC、音频源切换可能改善噪声场景，也可能拖慢实时链路或损伤语音特征，必须先做设备矩阵和人工转写准确率对照。
- 不替换模型：外部模型可能提升准确率，但会引入授权、内存峰值、CPU/GPU 资源、部署镜像、说话人分离适配和人工验收风险。

## 优化前本地基线

本地服务：API `127.0.0.1:8080`，ASR `127.0.0.1:8081`，AI `127.0.0.1:8082`，FunASR runtime `127.0.0.1:10095`。

健康检查：

- ASR `/health`：ok，`offline_model_loaded=true`，`file_engine=FunASR Offline + CAMPPlus via runtime`
- API `/api/v1/health`：ok，`database=mysql`，ASR 指向本地 `http://127.0.0.1:8081`
- AI `/health`：ok，`model_connected=true`

文件转写基线：

| 音频 | 时长 | 耗时 | RTF | 片段 | 字数 |
| --- | ---: | ---: | ---: | ---: | ---: |
| `test_assets/meeting_review_segments/12_王强.wav` | 8.640s | 2.420s | 0.280 | 1 | 38 |
| `test_assets/speaker_split_check/multi_speaker_meeting.wav` | 41.209s | 8.936s | 0.217 | 3 | 158 |

实时 WebSocket 基线：

- `partials=26`
- `final_events=1`
- `final_segments=1`
- `speakers=说话人 1`

## 优化后复测

本轮改动后，本地重启 API、ASR、AI、FunASR runtime，并复测同一批音频。

健康检查：

- ASR `/health`：ok，`offline_model_loaded=true`
- API `/api/v1/health`：ok，ASR 指向本地 `http://127.0.0.1:8081`
- AI `/health`：ok，`model_connected=true`

文件转写复测：

| 音频 | 优化前耗时 | 优化后耗时 | 优化前 RTF | 优化后 RTF | 文本 |
| --- | ---: | ---: | ---: | ---: | --- |
| `test_assets/meeting_review_segments/12_王强.wav` | 2.420s | 2.223s | 0.280 | 0.257 | 一致 |
| `test_assets/speaker_split_check/multi_speaker_meeting.wav` | 8.936s | 8.367s | 0.217 | 0.203 | 一致 |

实时 WebSocket 复测：

- `partials=26`
- `final_events=1`
- `final_segments=1`
- `speakers=说话人 1`

兼容性复测：

- 直连 runtime 旧 `file_pcm_transcribe` + `audio_b64` 入口，返回 `file_pcm_result`，文本与短音频基线一致。
- runtime 日志确认新路径收到 `file_pcm_transcribe_binary` 和对应 `audio_bytes`，文件转写默认路径已走 binary PCM。

结论：

- 本轮性能提升来自减少 base64 编码、解码和大 JSON 字符串拷贝；没有改变 PCM 内容、模型、VAD、PUNC、声纹、热词和文本后处理策略。
- 自动化复测未发现功能链路损坏；准确率不做硬性提升声明，但本地样本转写文本一致，未观察到退化。
