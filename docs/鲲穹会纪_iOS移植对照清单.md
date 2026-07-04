# 鲲穹会纪 iOS 移植对照清单

检查时间：2026-06-30

原则：iOS 不是从 0 做简化版，而是以 Android 现有源码为功能基准做等价移植。Android 已有的页面、弹窗、状态规则、接口调用和边界处理，除非属于 iOS 平台限制或必须等 Mac/Apple 后台验证，否则都应迁移到 iOS。

## 1. Android 页面基线

来源：`apps/android_app/app/src/main/java/com/huiyi/app/model/UiModels.kt`、`Screens.kt`

| Android 页面 | iOS 文件 | 当前状态 | 下一步 |
| --- | --- | --- | --- |
| Home | `MeetingHomeView.swift` | 已按 Android 首页主链路迁移：会议、录音、导入、预约、云端同步、全部会议、搜索、概览统计、会员/云同步状态、处理中/待处理卡、今日预约记录/编辑/删除、最近会议删除 | 待 Mac/Xcode 编译验证；视觉为 SwiftUI 原生等价，不做 Android 像素复刻 |
| Tasks | `TasksView.swift`、`MeetingDetailView.swift` | 已迁移待办聚合、统计面板、优先待办、筛选、只看我的、搜索、勾选、详情、编辑、删除、开始待办、本地处理任务入口和非处理中任务删除；会议详情和全局待办页都已接同一套待办详情/编辑/删除/来源跳转；全局待办页可带来源上下文跳会议详情；待办完成/开始/编辑/取消已改为本地会议优先保存，再按远程任务状态入云同步队列 | 待 Mac/Xcode 编译验证跨页来源弹窗 |
| Knowledge | `KnowledgeView.swift`、`KnowledgeViewModel.swift`、`LocalKnowledgeStore.swift` | 已迁移推荐问题、问答、停止回答、重新编辑、最近上下文追问、本地知识索引、来源展开、来源点击定位到会议原文片段；已补空态最近会议主题入口 | 待 Mac/Xcode 编译验证；范围切换底层支持但 Android UI 当前未暴露 |
| Profile | `ProfileView.swift`、`AccountSecurityView.swift`、`LegalDocumentView.swift` | 已迁移账号展示、昵称编辑、云同步开关/状态/未上传会议数/待同步删除数/上传本机/拉取云端、会员/订单/账号安全/声纹入口、系统权限状态、用户协议、隐私政策、删除会议数据、退出确认；账号安全已补 8 位密码、11 位手机号、6 位验证码和验证码倒计时；登录协议同意状态已按 Android 改为版本化存储 | 待 Mac/真机验证权限读取、Keychain 和系统短信键盘体验 |
| Import | `FileImportView.swift` | 半移植：选语言、选文件、复制到沙盒、入队、正在处理展示、待处理/失败任务可见、等待/失败任务删除确认、开始处理入口；已有任务处理中时点击待处理/失败任务会回到当前处理页并保留队列；本地任务默认本地知识库作用域 | UIDocumentPicker 真机权限待验证 |
| Search | `SearchView.swift` | 已按 Android 搜索范围迁移：可搜标题/摘要/参会人/标签/议题/决策/风险/待办/原文，已补最近搜索和搜索提交记录；云端会议与本机完成会议均参与搜索；原文和待办命中额外支持直接打开来源核验片段 | 待 Mac/Xcode 编译验证；Android 搜索本身只打开会议详情，不要求议题/风险搜索结果直接定位来源 |
| Schedules | `SchedulesView.swift`、`ScheduleNotificationService.swift`、`LocalScheduleStore.swift` | 已迁移列表、新建、编辑、DatePicker 生成标准预约时间、删除确认、开始记录入口、筛选、冲突检测、预约录音绑定 scheduleId/scheduleNote、应用内到点提醒、系统本地通知调度、稍后提醒、忽略、开始记录；已补本机预约保存和云端同步队列 | Android 自然语言时间输入未迁移，iOS 当前用原生 DatePicker；系统状态栏通知待 Xcode/真机验证 |
| Meetings | `AllMeetingsView.swift` | 半移植：全部会议、搜索、日期筛选、多选/全选、单条和批量删除确认、删除后清理本机任务/本机结果/知识索引、处理中任务混排；云端 bootstrap 失败时可回退缓存和本机完成会议 | 补自定义日期范围和后台任务卡视觉细节 |
| Record | `RecordingView.swift`、`RecordingViewModel.swift`、`RealtimeASRClient.swift`、`RealtimeTranscriptBuffer.swift`、`AudioFileStore.swift` | 半移植：ASR ready 后录音、快速取消不建任务、实时字幕、暂停/继续、入处理队列、预约录音上下文入任务、未完成录音恢复入待处理、启动前主动请求麦克风权限；已按 Android 补登录/冻结/转写额度前置校验；录音任务默认本地知识库作用域并使用 Android 对齐的准备处理状态；已补实时字幕去重/修正/排序、直连 session.updated 后才发送音频、断线后指数退避重连、转写 item 时间戳、语气词过滤，录音中不向用户暴露底层“转写恢复”提示 | 真机麦克风/后台录音待 Mac 验证；Android 源码保留 `/live/ws` 代理分支但当前未见启用路径，iOS 暂不凭空启用；结束前 WAV 缺口补传仍需继续迁移 |
| Voiceprints | `VoiceprintsView.swift` | 半移植：未登录提示、授权、姓名、录音样本建档、导入音频建档、列表、改名、启停、删除确认；会议详情内“同时保存为声纹档案”已按 Android 补齐本地会议先同步云端、再用远程任务保存声纹 | 录音样本和上传真机验证待 Mac |
| Membership | `MembershipView.swift` | 半移植：权益、套餐、加量包、额度、订单展示、套餐/加量包选择、已有会员复购提示/确认、恢复购买入口、购买不可用业务态；iOS 当前不创建支付宝订单 | 补 StoreKit 商品映射和 Apple 交易校验；Apple 后端待设计 |
| PaymentOrders | `PaymentOrdersView.swift` | 半移植：独立订单页、列表刷新、复制订单号、单笔订单状态刷新、支付状态色标、普通业务提示；现阶段仅用于核对服务端已有订单，不作为 iOS 正式购买入口 | 后续接 Apple 订单语义；若 App Store 审核策略要求，需隐藏或隔离支付宝历史订单状态刷新 |
| Generating | `ProcessingView.swift` | 已迁移开始/继续/终止确认/失败删除/已终止删除/已终止重新尝试/队列、后台处理、完成查看详情、后续队列自动串行处理；已有任务处理中时优先显示当前处理任务；已按 Android 补登录/冻结/转写额度前置校验；处理上传已按 Android 使用 `persist_to_cloud=false` 临时任务，本地完成后保存本机结果和本地知识索引，并接入云同步队列；已补瞬态失败自动重试、`waiting_retry` 不自动重跑、完成详情缺 result 时兜底拉取 `/result`、完成后清理临时远端任务 | 补 Android 同级步骤状态文案；待 Mac/Xcode 编译验证 |
| Detail | `MeetingDetailView.swift` | 半移植：详情/纪要/议题/决策/待办/风险/原文、待确认/已确认状态与确认动作、标题/参会人/标签/隐私/知识库范围/纪要编辑、重新生成、删除、本地导出范围选择、说话人重命名、单段说话人修改、来源核验、原文纠错、音频片段播放、从会议说话人保存声纹；议题/决策/风险已按 Android 补来源定位按钮；会议详情待办已补同全局页一致的详情/编辑/删除；本地完成会议的标题/确认/纪要/待办/说话人/原文编辑已补本地优先保存和知识库重建，再按是否已有 remoteTaskId 入云同步队列 | 待 Mac/Xcode 编译验证；议题/决策/风险细粒度编辑属于增强项，Android 当前未提供同级编辑入口 |

## 2. Android 弹窗/底表基线

来源：`SheetType`

| Android Sheet | iOS 当前状态 | 下一步 |
| --- | --- | --- |
| RecordConsent | 已迁移到 `RecordConsentView` | 已在录音启动前请求系统麦克风权限；待 Mac/Xcode 编译验证和真机弹窗验证 |
| ImportFile | 已由 `UIDocumentPicker` 部分覆盖 | 已复制到沙盒并按语言创建待处理任务，支持队列展示、开始处理和删除确认 |
| Notifications | 半迁移到 `ScheduleNotificationService` | 已支持预约保存后调度本地通知、删除预约后取消通知；权限弹窗和系统送达待 Mac/真机验证 |
| CreateMeeting | 已迁移到 `ScheduleEditorView` | 已支持标题、参会人、备注、开始/结束时间、结束时间校验、冲突提示、保存失败不关闭弹窗；待 Mac/Xcode 编译验证 |
| Speakers | 已迁移到会议详情说话人编辑 | 已支持说话人列表重命名、本段说话人改派/新建、从会议内容保存声纹；待 Mac/Xcode 编译验证 |
| Source | 已迁移到 `SourceDetailView` | 已支持原文来源定位、修正入口、音频片段播放；待 Mac/真机验证音频格式兼容 |
| Export | 已迁移到会议详情导出弹窗 | 已支持仅会议纪要/纪要和原文选择；导出文本优先由当前本地详情结果生成，不依赖远程任务 ID，避免本地未同步会议导出失败；待 Mac/Xcode 编译验证分享流程 |
| Correction | 已迁移到 `SourceDetailView` 内联修正 | 待 Mac/Xcode 编译验证 |
| EditMinutes | 已迁移 | Android 当前仅编辑摘要纪要，iOS 已支持摘要编辑 |
| EditMeetingInfo | 已迁移并扩展 | Android 当前仅编辑会议名称，iOS 已支持标题、参会人、标签、私密会议、知识库范围；待 Mac/Xcode 编译验证 |
| TodoDetail | 已迁移 | 全局待办页和会议详情页均可打开待办详情，支持编辑、删除/取消、开始、来源片段定位；全局待办页可跳转来源会议并携带待办来源上下文 | 待 Mac/Xcode 编译验证 |
| CreateTodo | 已迁移到会议详情 `TodoCreateView` | 待 Mac/Xcode 编译验证 |
| DeleteMeeting | 已迁移到会议详情/全部会议确认弹窗 | 已支持单条删除确认和批量删除确认；待 Mac/Xcode 编译验证 |
| DeleteSchedule | 已迁移到预约页和首页确认弹窗 | 已支持本机先删除、云端失败入队同步；待 Mac/Xcode 编译验证 |
| DeleteTask | 已迁移到导入页/处理页确认弹窗 | 已补删除确认、本地文件物理清理、本地知识索引清理；待 Mac/Xcode 编译验证 |
| ScheduleReminder | 半迁移 | 已支持应用内提醒弹窗、开始记录、稍后提醒、忽略、本地通知调度；系统状态栏通知待 Xcode/真机验证 |
| DeleteData | 已迁移到个人中心确认弹窗 | 待 Mac/Xcode 编译验证 |
| LogoutConfirm | 已迁移到个人中心确认弹窗 | 已展示云同步状态和未同步数量；待 Mac/Xcode 编译验证 |
| UserAgreement | 已迁移到 `LegalDocumentView` | 待 Mac/Xcode 编译验证 |
| PrivacyPolicy | 已迁移到 `LegalDocumentView` | 待 Mac/Xcode 编译验证 |

## 3. 本轮源码级检查补齐项

以下属于 Android 已有业务或实现保护在 iOS 端的遗漏，已补齐：

- 重新生成纪要后合并待办：保留已完成、已取消、手动补充的待办，并按标题/来源/负责人相似度过滤重复生成项，避免用户已处理事项被 AI 结果覆盖。
- 待办状态锁定：会议详情和全局待办页的完成、取消、开始、编辑操作都会补 `locked_fields`，与 Android 的后续重生成保护一致。
- 本地结果缓存闭环：远端详情加载、结果更新、重新生成后同步写入本地结果缓存和本地知识索引，避免离线列表、最近主题、云同步队列读到旧结果。
- 未完成录音恢复 ID：从 Swift 进程随机 `hashValue` 改为稳定哈希，避免跨启动恢复任务 ID 不可靠。
- 登录失效统一处理：所有 JSON、文件下载、multipart 上传请求遇到 401 会清理登录态、关闭云同步并提示重新登录，避免页面继续显示“已登录”。
- 删除和编辑保护：底层删除任务时阻止删除处理中任务；会议标题保存补齐 Android 的 2-80 字符校验。
- Android 支付真实链路已源码核对：服务端默认 `HUIXIAO_ALIPAY_PAYMENT_MODE=wap`，Android 创建订单后优先打开 `pay_url` 浏览器 WAP 支付页；`PayTask.payV2` 只是服务端切 `app` 模式时的备用分支。iOS 正式会员支付不应复用支付宝 WAP，应走 StoreKit 2 和 Apple 后端语义。
- 处理状态机对齐：iOS 已补 Android 的瞬态失败自动重试 2 次、失败后转 `waiting_retry` 可继续、登录/额度异常转等待登录/等待充值、完成后本地化结果并删除 `persist_to_cloud=false` 临时远端任务。
- 队列语义对齐：普通待处理任务可自动串行；失败和 `waiting_retry` 任务保持可见但不会被自动重跑，只有用户手动继续或在已有处理任务中明确加入后续队列才会继续。
- 处理结果兜底：服务端任务状态已完成但详情未带 result 时，iOS 会像 Android 一样再请求 `/tasks/{id}/result`，避免只有完成状态没有本地会议详情。
- 实时字幕合并对齐：iOS 已补 Android 的空文本过滤、说话人兜底、重复片段合并、近似修正覆盖和按时间排序；底层转写恢复提示不直接暴露给录音页用户。
- 实时 ASR 直连状态机：iOS 已补 Android 的 `session.updated` 后才 ready/opened、音频帧发送失败记录缺口、断线指数退避重连、`speech_started/speech_stopped` 维护片段起止时间、临时凭证复用和实时口头填充过滤。
- 本地会议编辑闭环：会议详情和全局待办页不再假设所有完成会议都有远程任务；标题、确认、摘要、待办、说话人、原文纠错会先写本地结果缓存和本地知识索引，再按 remoteTaskId 状态入上传或更新队列。
- 从会议保存声纹：iOS 已补 Android 的前置同步逻辑；本地完成会议如未上传，会先强制上传会议结果，再调用 `/voiceprints/profiles/from-task` 使用远程任务 ID 保存声纹。
- 删除会议闭环：会议列表、首页和详情删除已改为统一入口；本地未同步会议直接删除本机文件/结果/知识索引，已有远程任务才调服务端删除，避免把本地 ID 当远程 ID 报错。
- 登录协议版本：iOS 已补 Android 的 `2026-06-08` 协议版本化同意记录，后续协议版本更新时可以重新要求用户确认，而不是只存一个永久 bool。
- 云端拉取合并：iOS bootstrap 已补本机完成会议合并和待上传改动保护；拉取云端时不会用云端旧数据覆盖本机待上传的会议编辑结果。
- 本地/远程任务 ID 语义：`sync_scope=local_processing` 的本地 fallback 会议不会再被转换成带 remoteTaskId 的云端会议，避免本地未上传会议在列表、删除、同步时被误判为已同步。
- 会员和订单细节：已按 Android 会员中心补已有会员复购提示/确认；订单页已补复制反馈、单笔订单状态刷新和状态色标。iOS 仍不创建支付宝订单，正式购买必须等 StoreKit 2。
- 会议详情待办入口：已补 Android 详情页待办可进入详情弹窗的行为，会议详情和全局待办页共用编辑/删除/来源跳转表单，避免只在全局待办页能改。
- 导出本地化：已从依赖远端 `/export` 改为优先使用当前详情结果本地生成导出文本，本地未同步会议也能导出，和 Android `selectedMeeting.toPlainText/toMarkdownText` 语义一致。
- 会议信息扩展编辑：iOS 已补私密会议和知识库范围编辑入口，底层走本地优先保存和云同步队列；这是对已有字段能力的完整化，不要求 Android 像素级已有入口。
- 议题/决策/风险来源定位：会议详情页已按 Android `Meeting.sourceIndexForOrNull(...)` 语义补来源推断和“查看来源”入口，可进入同一套来源核验/播放/纠错弹窗。

## 4. Android 接口基线

来源：`HuixiaoApiClient.kt`

已在 iOS 封装：短信登录/注册/重置、手机号换绑、设置/修改密码、bootstrap、会员、live session、订单读取/单笔订单刷新、日程增删改、文件上传、任务处理/重试/取消/删除、任务结果拉取、任务结果更新、任务标题/元数据更新、导出、音频下载、重新生成纪要、知识库问答、声纹列表/从任务/从音频/改名/启停/删除。处理上传已对齐 Android 的临时远端任务语义：本地处理用 `persist_to_cloud=false`；已补本机会议结果、预约、删除操作的云同步操作队列；本地会议详情编辑和全局待办编辑已补本地优先保存后同步的 Android 语义。

不应照搬到 iOS 正式支付：`/payments/alipay/...` 创建订单。Android 当前真实支付主链路是浏览器打开支付宝 WAP `pay_url`，不是默认直拉支付宝 App；iOS 当前只把已有订单作为服务端记录核对，正式数字会员购买仍必须用 StoreKit 2 和后续 `/payments/apple/...`。

## 5. 当前最高优先级缺口

1. Mac/Xcode 编译验证：当前 Windows 无 Swift 编译器，所有 SwiftUI、StoreKit、AVAudio、UIDocumentPicker、通知权限仍需 Mac 编译和真机验证。
2. 录音和实时 ASR 深度补齐及真机验证：后台录音、中断恢复、耳机切换、实时 WebSocket 音频帧、快速结束边界必须上真机；Android 的结束前本地 WAV 缺口补传仍需继续迁移。`/live/ws` 服务端代理协议存在，但当前 Android 源码未见实际启用路径，iOS 暂不新增行为差异。
3. 云同步实测：已补本机结果/预约/删除操作队列和拉取合并保护，但仍需用测试服务器验证上传、本机编辑后再同步、删除失败重试、拉取云端保留本机改动。
4. 支付平台差异：iOS 不能复用 Android 支付宝 WAP 正式会员支付，仍需 StoreKit 2 商品、Apple 交易校验、恢复购买和后端 `/payments/apple/...`。
5. 增强项而非 Android 已有迁移漏项：议题/决策/风险细粒度编辑。

## 6. 平台限制清单

以下不是“可以省略”，而是当前 Windows 环境不能验证或 Apple 后台未准备：

- Xcode 编译、SwiftUI 预览、iOS 模拟器。
- 真机麦克风、后台录音、锁屏、中断、耳机切换。
- UIDocumentPicker 安全作用域实测。
- Keychain 实测。
- StoreKit 2 沙盒购买、恢复购买。
- TestFlight、签名、IPA。
