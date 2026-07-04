# 鲲穹会纪 iOS 端开发方案

更新时间：2026-06-29

## 1. 结论

鲲穹会纪 iOS 端建议采用原生 Swift + SwiftUI 开发。当前不建议使用 uni-app x 作为 iOS 完整主 App 的底座，原因不是 uni-app x 不能做，而是本项目核心能力集中在 iOS 原生层：长录音、实时 PCM、后台音频、文件沙盒、Keychain、StoreKit 2、TestFlight 和权限合规。若用 uni-app x，核心能力仍需大量 Swift/UTS 插件，调试链路更长。

当前阶段原则：

- 不改后端数据库。
- 不改 Android 支付宝 WAP 支付链路。
- 不复用 Android `/payments/alipay/...` 作为 iOS 正式会员支付。
- iOS 先复用现有账号、会议、任务、会员展示、知识库、云同步接口。
- iOS 支付后续单独设计 StoreKit 2 和 `/payments/apple/...` 类接口。
- Windows 可以先写代码、模型、状态机和页面结构，但所有 iOS 编译、模拟器、真机录音、签名、TestFlight 均标记为“待 Mac 编译验证”。

## 2. 当前项目基线

当前真实客户端只有 Android：

```text
apps/android_app
```

Android 已提交评审，关键信息：

```text
应用名称：鲲穹会纪
Android 包名：com.huiyi.app
Android 固定签名 MD5：8531D2343D10604A463ECC0C5DC6A459
Android 支付主链路：支付宝 WAP 网页支付
```

后端当前服务：

```text
services/api_server      # App 统一业务接口
services/asr_service     # ASR 服务
services/ai_service      # 纪要、embedding、知识库问答
services/funasr_runtime  # 文件转写、声纹、离线说话人相关 runtime
```

客户端只直接访问 `api_server`。ASR、AI、知识库索引、会员额度、订单和云同步由服务端负责。

## 3. iOS 技术栈

推荐技术栈：

```text
语言：Swift
UI：SwiftUI
并发：async/await
网络：URLSession
WebSocket：URLSessionWebSocketTask
录音：AVAudioEngine + AVAudioSession
本地安全存储：Keychain
本地文件：FileManager + App Sandbox
轻量状态持久化：Codable JSON
必要时本地索引：SQLite/CoreData，首期不引入
文件导入：UIDocumentPicker
支付：StoreKit 2
分发测试：TestFlight
```

说明：这里的“本地存储”不是本地业务数据库，也不是替代远程数据库。远程数据库仍是业务主数据来源；iOS 本地只保存登录态、音频文件、临时任务状态、缓存和 UI 偏好。

## 4. 推荐目录结构

```text
apps/
  ios_app/
    HuiyiApp/
      App/
        HuiyiApp.swift
        AppRouter.swift
        AppSession.swift
        AppEnvironment.swift

      Core/
        Network/
          APIClient.swift
          APIEndpoint.swift
          APIError.swift
          APIModels.swift
        Auth/
          TokenStore.swift
          AuthSession.swift
        Storage/
          ClientTaskStateStore.swift
          ClientCacheStore.swift
          AudioFileStore.swift
          SettingsStore.swift
        UI/
          Theme.swift
          Components.swift
          ToastCenter.swift
          LoadingOverlay.swift

      Models/
        User.swift
        Meeting.swift
        MeetingTask.swift
        MeetingResult.swift
        TranscriptSegment.swift
        TodoItem.swift
        Membership.swift
        PaymentOrder.swift
        Knowledge.swift
        Schedule.swift

      Features/
        Login/
          LoginView.swift
          LoginViewModel.swift
        Meetings/
          MeetingHomeView.swift
          MeetingListView.swift
          MeetingDetailView.swift
          MeetingDetailViewModel.swift
        Recording/
          RecordingView.swift
          RecordingViewModel.swift
          RecordingLanguagePicker.swift
        Processing/
          ProcessingView.swift
          ProcessingViewModel.swift
          ProcessingQueue.swift
        FileImport/
          FileImportView.swift
          FileImportViewModel.swift
        Knowledge/
          KnowledgeView.swift
          KnowledgeViewModel.swift
        Membership/
          MembershipView.swift
          PaymentOrdersView.swift
          MembershipViewModel.swift
        Profile/
          ProfileView.swift
          ProfileViewModel.swift
        CloudSync/
          CloudSyncService.swift

      Services/
        Recording/
          RecordingEngine.swift
          AVAudioRecordingEngine.swift
          AudioSessionManager.swift
          WavFileWriter.swift
        RealtimeASR/
          RealtimeASRClient.swift
          RealtimeASRSession.swift
          RealtimeTranscriptBuffer.swift
          MissingAudioBackfillPlanner.swift
        FileImport/
          DocumentImportService.swift
        Payment/
          StoreKitPaymentService.swift
          PurchaseRestorationService.swift
        AudioPlayback/
          AudioSegmentPlayer.swift
```

命名说明：

- `HuiyiApp` 可作为工程内部模块名沿用，避免和既有 API/包名历史冲突。
- App 展示名称必须是“鲲穹会纪”。
- iOS Bundle ID 需要 Apple Developer 后台最终确认，建议不要简单照搬 Android 包名；可选如 `com.kunqiong.huiyi` 或公司主体名下的正式 ID。

## 5. 客户端本地存储边界

远程数据库仍保存主数据：

- 用户账号。
- 云端任务。
- 会议结果。
- 知识库索引。
- 会员权益。
- 订单。
- 说话人/声纹资料。
- 预约会议。

iOS 本地只保存客户端状态：

| 类型 | 建议实现 | 说明 |
|---|---|---|
| 登录 token | Keychain | 保存 `access_token`、过期时间、用户基础信息 |
| 录音文件 | FileManager | 保存到 App Sandbox，如 `Documents/Recordings` |
| 导入文件 | FileManager | UIDocumentPicker 选中文件后复制到 App 沙盒 |
| 云端音频缓存 | FileManager | 下载 `/tasks/{id}/audio` 后缓存，来源核验播放使用 |
| 任务临时状态 | Codable JSON | 待处理、处理中、失败、可继续、已终止等客户端队列状态 |
| 会员/订单缓存 | Codable JSON | 启动加速，刷新后以服务端为准 |
| UI 偏好 | UserDefaults/Codable JSON | 识别语言、最近搜索、云同步开关等 |

首期不建议引入 CoreData/SQLite，除非后续出现大量本地知识库索引、离线检索或复杂离线编辑需求。

## 6. 现有服务端接口复用

iOS 首期复用以下接口：

```text
POST /api/v1/auth/sms/send-code
POST /api/v1/auth/sms/login
POST /api/v1/auth/password/register
POST /api/v1/auth/password/login
POST /api/v1/auth/password/reset
POST /api/v1/auth/password/set
POST /api/v1/auth/password/change
POST /api/v1/auth/phone/change/verify-current
POST /api/v1/auth/phone/change

GET  /api/v1/sync/bootstrap
PUT  /api/v1/sync/schedules/{schedule_id}
DELETE /api/v1/sync/schedules/{schedule_id}

GET  /api/v1/membership/me

POST /api/v1/live/session

POST /api/v1/files/upload

GET  /api/v1/tasks
GET  /api/v1/tasks/{task_id}
PATCH /api/v1/tasks/{task_id}
POST /api/v1/tasks/{task_id}/process
POST /api/v1/tasks/{task_id}/retry
POST /api/v1/tasks/{task_id}/cancel
GET  /api/v1/tasks/{task_id}/result
PUT  /api/v1/tasks/{task_id}/result
GET  /api/v1/tasks/{task_id}/audio
GET  /api/v1/tasks/{task_id}/export
DELETE /api/v1/tasks/{task_id}

POST /api/v1/tasks/{task_id}/regenerate-minutes
POST /api/v1/tasks/regenerate-local-minutes

POST /api/v1/knowledge/ask

GET  /api/v1/voiceprints/profiles
POST /api/v1/voiceprints/profiles/from-task
POST /api/v1/voiceprints/profiles/from-audio
PATCH /api/v1/voiceprints/profiles/{profile_id}
DELETE /api/v1/voiceprints/profiles/{profile_id}

GET  /api/v1/payments/orders
GET  /api/v1/payments/orders/{order_id}
```

不作为 iOS 正式支付使用：

```text
POST /api/v1/payments/alipay/orders
POST /api/v1/payments/alipay/addon-orders
POST /api/v1/payments/alipay/orders/{order_id}/sync
POST /api/v1/payments/alipay/notify
```

这些接口继续服务 Android 支付宝 WAP，不应因 iOS 改造改变语义。

## 7. iOS 录音与实时 ASR 设计

### 7.1 总体原则

iOS 实时转写必须遵守 Android 已验证的业务经验：

- 开始录音前先请求 `/live/session`。
- 阿里云实时 ASR WebSocket 连接成功并完成 session update 后，才真正开始计时和采集音频。
- 录音采集 16kHz mono PCM。
- 录音过程中实时字幕只作为用户展示。
- 结束录音后仍进入处理页。
- 处理页隐藏尾段回传、缺口补转、说话人分离、声纹识别等底层细节。
- 不能因为已有实时字幕就跳过服务端说话人分离和声纹阶段。
- 快速点击结束但 ASR/录音尚未真正开始时，不进入处理页。

### 7.2 识别语言

首期支持三种：

```text
中文：zh-CN / 发送给实时 ASR 时使用 zh
英文：en-US / 发送给实时 ASR 时使用 en
中英自由说：auto / 实时 ASR 不显式指定 language
```

语言选择在开始录音或导入文件时固定到任务上。任务创建后，后续 UI 选择器变化不影响已排队任务。

### 7.3 录音状态机

推荐状态：

```text
idle
preparingASR
readyToRecord
recording
paused
stopping
finished
failedBeforeStart
```

关键规则：

- `preparingASR` 阶段只显示准备状态，不计时。
- 收到 ASR ready 后进入 `recording` 并开始计时。
- `failedBeforeStart` 不创建会议任务，不进入处理页。
- `stopping` 阶段等待音频文件封口、ASR commit/finish、尾段缓冲完成。

### 7.4 录音文件

iOS 录音应保存完整 WAV 文件：

```text
Documents/Recordings/{taskId}.wav
sampleRate: 16000
channels: 1
format: PCM signed 16-bit little-endian
```

如果 AVAudioEngine 输出格式与目标格式不同，由 `AVAudioConverter` 转换后写入 WAV，同时把同一 PCM 帧推给 ASR。

## 8. 文件导入设计

使用 `UIDocumentPickerViewController`。导入后必须复制到 App 沙盒，不能依赖临时 security-scoped URL。

支持格式与 Android 保持一致：

```text
mp3, m4a, wav, aac, mp4, mov
最大 500MB
```

流程：

```text
选择语言
选择文件
复制到 App 沙盒 Documents/Imports
创建本地 WaitingProcess 任务
用户手动开始处理
若已有任务处理中则排队
失败任务保留可见并可重试
```

## 9. 任务状态机

iOS 必须按 Android 最新经验设计，不允许失败任务静默消失。

客户端任务状态：

```text
localSaved        # 本地文件已保存，尚未进入处理队列
waitingProcess    # 待处理
processing        # 正在处理
waitingRetry      # 处理暂未完成，可继续
failed            # 处理失败
canceled          # 已终止
finished          # 已完成
```

服务端状态映射：

```text
waiting_process -> waitingProcess 或 waitingRetry
processing      -> processing
finished        -> finished
failed          -> failed
canceled        -> canceled
```

核心规则：

- 失败任务在待办/导入/处理页可见。
- 点击失败任务进入处理页，但不自动重跑。
- 失败页点击“重新尝试”才调用重试。
- `waitingRetry` 点击进入处理页，点击“继续处理”才重试。
- 如果已有任务处理中，点击失败任务或待处理任务，应进入当前处理页，并把目标任务加入后续队列。
- 用户退出失败页后，可以按产品规则删除失败任务。
- App 重启后，`processing` 本地任务应恢复为可继续/待处理，不应假装仍在本机处理。

## 10. 会员与支付设计

### 10.1 首期

iOS 首期先做：

- 会员信息展示。
- 套餐展示。
- 额度展示。
- 订单记录页面。
- 额度不足拦截录音/处理/知识库问答。

首期复用：

```text
GET /api/v1/membership/me
GET /api/v1/payments/orders
GET /api/v1/payments/orders/{order_id}
```

### 10.2 StoreKit 2

正式 iOS 会员支付走 StoreKit 2。必须支持：

- 购买会员套餐。
- 购买加量包或等价产品。
- 恢复购买。
- 交易校验。
- 订阅状态刷新。
- 退款/撤销/过期处理。

不建议复用 Android `orders.channel = alipay` 的语义。后续新增接口建议：

```text
GET  /api/v1/payments/apple/products
POST /api/v1/payments/apple/transactions/verify
POST /api/v1/payments/apple/transactions/restore
GET  /api/v1/payments/apple/entitlements
POST /api/v1/payments/apple/notifications
```

## 11. 服务端与数据库分析

### 11.1 当前数据库主表

账号与认证：

```text
users
sms_verification_codes
```

会议与处理：

```text
files
tasks
results
scheduled_meetings
knowledge_chunks
```

声纹与说话人：

```text
speaker_profiles
speaker_profile_samples
meeting_speaker_embeddings
```

会员与订单：

```text
membership_plans
membership_addons
user_memberships
user_monthly_entitlements
user_trial_entitlements
orders
```

后台与运营：

```text
admin_users
user_admin_states
grant_batches
grant_items
```

### 11.2 iOS 首期是否需要改库

不需要。

原因：

- 登录复用 `users` 和 auth 接口。
- 会议复用 `files/tasks/results/knowledge_chunks`。
- 云同步复用 `sync/bootstrap`。
- 会员展示复用 `membership_plans/membership_addons/user_memberships/user_monthly_entitlements/user_trial_entitlements`。
- 订单记录首期只读现有 `orders`。
- iOS 客户端本地状态不进入服务端数据库。

### 11.3 后续 Apple IAP 是否需要改库

需要，但应单独设计，不和 Android 支付宝订单语义混在一起。

推荐新增表：

```text
apple_transactions
```

建议字段：

```text
id
user_id
app_account_token
product_id
transaction_id
original_transaction_id
web_order_line_item_id
purchase_date
expires_date
revocation_date
environment
status
signed_transaction_info
signed_renewal_info
created_at
updated_at
```

是否复用 `orders`：

- 可以在 Apple 交易校验成功后写入一条 `orders.channel = apple` 的展示订单。
- 但 Apple 原始交易状态应由 `apple_transactions` 保存。
- 不建议把 Apple 的订阅续期、恢复购买、退款完全塞进现有支付宝订单模型。

### 11.4 服务端后续改造边界

保持 Android 不受影响：

- 不修改 `/payments/alipay/...` 行为。
- 不修改 `HUIXIAO_ALIPAY_PAYMENT_MODE=wap` 默认逻辑。
- 不修改现有 `orders.channel = alipay` 的支付确认语义。
- Apple 相关接口必须新增路由，建议 `/payments/apple/...`。
- Apple 交易发放权益时复用现有会员权益发放函数或抽公共服务层。

## 12. 安全与合规

iOS 必须准备：

- 麦克风权限文案。
- 文件访问说明。
- 网络访问说明。
- 隐私政策和用户协议入口。
- 声纹录入单独同意文案。
- 会议说话人保存声纹单独同意文案。
- StoreKit 商品说明和恢复购买入口。

不得在 iOS App 中保存：

- 阿里云长期密钥。
- OpenAI/百炼模型密钥。
- 支付宝密钥。
- Apple 私钥。
- 数据库连接串。

实时 ASR 必须从服务端获取临时凭证。

## 13. 开发顺序

### P0：工程骨架

- 创建 `apps/ios_app` 目录。
- 建立 Swift 源码结构。
- 创建模型、APIClient、TokenStore。
- 标注待 Mac 编译验证。

### P1：账号与基础业务

- 登录/注册/密码登录。
- Token Keychain 持久化。
- `sync/bootstrap` 拉取会议和预约。
- 会议首页、列表、详情。

### P2：会员与额度

- `membership/me`。
- 会员中心、套餐展示、额度展示。
- 订单记录。
- 额度不足拦截。

### P3：任务队列与处理页

- 本地任务状态持久化。
- 待处理、处理中、失败、可继续、已终止、已完成。
- 处理页轮询 `/tasks/{id}`。
- 失败重试和队列规则。

### P4：录音与实时 ASR

- AVAudioSession 配置。
- AVAudioEngine PCM 采集。
- WAV 写入。
- `/live/session` 获取临时凭证。
- URLSessionWebSocketTask 直连阿里云。
- ASR ready 后开录。
- 结束后进入处理页。

### P5：文件导入

- UIDocumentPicker。
- 复制到沙盒。
- 语言固定到任务。
- 上传和处理。

### P6：StoreKit 2

- 产品列表映射。
- 购买。
- 恢复购买。
- 服务端校验接口。
- Apple Server Notifications。

### P7：TestFlight 验收

- Mac 编译。
- 模拟器检查。
- 真机录音。
- 后台/锁屏录音策略验证。
- StoreKit 沙盒测试。
- TestFlight 上传。

## 14. 需要 Mac 验证的项目

以下项目不能在 Windows 上闭环：

- Xcode 工程编译。
- SwiftUI 预览。
- iOS 模拟器运行。
- 真机麦克风权限。
- AVAudioEngine 音频格式转换。
- 后台音频策略。
- URLSessionWebSocketTask 与阿里云实时 ASR 长连接稳定性。
- UIDocumentPicker security-scoped URL。
- Keychain 行为。
- StoreKit 2 沙盒。
- ipa 签名。
- TestFlight 上传。

## 15. 主要风险

| 风险 | 说明 | 应对 |
|---|---|---|
| Windows 无法编译 iOS | 当前主机不能跑 Xcode | 先写源码结构和业务逻辑，待 Mac 编译验证 |
| 实时 ASR 长连接 | iOS 网络切后台、锁屏策略不同 | 真机专项测试，必要时前台录音优先 |
| 后台录音审核 | iOS 对后台音频用途敏感 | 只在真实录音场景开启后台音频，文案合规 |
| StoreKit 服务端校验 | 需要新增 Apple 交易语义 | 单独设计 `/payments/apple/...` 和交易表 |
| 任务失败可见性 | Android 已踩坑 | iOS 状态机首期即固化规则 |
| 文件临时权限 | UIDocumentPicker URL 可能失效 | 导入后立即复制到沙盒 |

## 16. 不做事项

首期不做：

- 不做 iOS 跨平台低质量替代方案。
- 不做 uni-app x 完整主 App。
- 不改 Android 包名、签名和支付。
- 不改现有支付宝订单语义。
- 不新增本地业务数据库替代远程数据库。
- 不把长期 ASR/AI/支付密钥放进 App。
- 不跳过服务端说话人分离、声纹和纪要生成阶段。

## 17. 汇报摘要

推荐路线：

```text
iOS 完整版：Swift + SwiftUI 原生
服务端：首期复用现有 API 和远程数据库
数据库：首期不改
支付：首期展示会员与订单，后续 StoreKit 2 独立扩展
Windows：可先开发代码和结构
Mac：必须用于编译、真机调试、签名和 TestFlight
```

一句话结论：

```text
鲲穹会纪 iOS 端应原生开发；首期复用现有后端和远程数据库，不改 Android 支付链路；客户端本地只保存 token、音频文件、临时任务状态和缓存；Apple IAP 后续单独新增接口和交易表。
```
