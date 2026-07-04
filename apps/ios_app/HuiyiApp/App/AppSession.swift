import Foundation

@MainActor
final class AppSession: ObservableObject {
    @Published private(set) var currentUser: CloudUser?
    @Published private(set) var membershipProfile: MembershipProfile = .empty
    @Published private(set) var taskQueue = ProcessingQueue()
    @Published private(set) var speakerProfiles: [SpeakerProfile] = []
    @Published private(set) var profileName = ""
    @Published private(set) var cloudSyncEnabled = false
    @Published private(set) var cloudSyncStatusText = "云端同步未开启"
    @Published private(set) var cloudSyncInProgress = false
    @Published private(set) var cloudSyncOperations: [CloudSyncOperation] = []
    @Published private(set) var profileCloudSyncFocusRequest = 0
    @Published private(set) var authStateMessage: String?
    @Published private(set) var reminderScheduleId: String?
    @Published private(set) var transcriptEditedTaskIds: Set<String> = []

    private let tokenStore: TokenStore
    private let apiClient: APIClient
    private let taskStateStore: ClientTaskStateStore
    private let audioFileStore: AudioFileStore
    private let cacheStore: ClientCacheStore
    private let settingsStore: SettingsStore
    private let localKnowledgeStore: LocalKnowledgeStore
    private let localResultStore: LocalResultStore
    private let cloudSyncOperationStore: CloudSyncOperationStore
    private let localScheduleStore: LocalScheduleStore
    private let profileCacheStore: ProfileCacheStore
    private let scheduleNotificationService: ScheduleNotificationService
    private var latestSchedules: [ScheduledMeeting] = []
    private var dismissedScheduleReminderIds: Set<String> = []
    private var snoozedScheduleReminderUntil: [String: Int64] = [:]

    init(
        tokenStore: TokenStore,
        apiClient: APIClient,
        taskStateStore: ClientTaskStateStore = ClientTaskStateStore(),
        audioFileStore: AudioFileStore = AudioFileStore(),
        cacheStore: ClientCacheStore = ClientCacheStore(),
        settingsStore: SettingsStore = SettingsStore(),
        localKnowledgeStore: LocalKnowledgeStore? = nil,
        localResultStore: LocalResultStore? = nil,
        cloudSyncOperationStore: CloudSyncOperationStore? = nil,
        localScheduleStore: LocalScheduleStore? = nil,
        profileCacheStore: ProfileCacheStore? = nil,
        scheduleNotificationService: ScheduleNotificationService? = nil
    ) {
        self.tokenStore = tokenStore
        self.apiClient = apiClient
        self.taskStateStore = taskStateStore
        self.audioFileStore = audioFileStore
        self.cacheStore = cacheStore
        self.settingsStore = settingsStore
        self.localKnowledgeStore = localKnowledgeStore ?? LocalKnowledgeStore(cacheStore: cacheStore)
        self.localResultStore = localResultStore ?? LocalResultStore(cacheStore: cacheStore)
        self.cloudSyncOperationStore = cloudSyncOperationStore ?? CloudSyncOperationStore(cacheStore: cacheStore)
        self.localScheduleStore = localScheduleStore ?? LocalScheduleStore(cacheStore: cacheStore)
        self.profileCacheStore = profileCacheStore ?? ProfileCacheStore(cacheStore: cacheStore)
        self.scheduleNotificationService = scheduleNotificationService ?? ScheduleNotificationService()
        currentUser = tokenStore.loadUser()
        taskQueue.restore(taskStateStore.load())
        restoreProfileSettings()
        apiClient.accessTokenProvider = { [weak tokenStore] in
            tokenStore?.loadUser()?.accessToken
        }
        apiClient.authFailureHandler = { [weak self] message in
            self?.clearExpiredSession(message: message)
        }
        recoverUnfinishedRecordings()
    }

    func restore() {
        currentUser = tokenStore.loadUser()
        taskQueue.restore(taskStateStore.load())
        restoreProfileSettings()
        recoverUnfinishedRecordings()
    }

    func setUser(_ user: CloudUser) {
        tokenStore.saveUser(user)
        currentUser = user
        restoreProfileSettings()
        recoverUnfinishedRecordings()
    }

    func clearUser() {
        if let currentUser {
            cloudSyncOperationStore.save(cloudSyncOperations, userId: currentUser.userId)
        }
        tokenStore.clear()
        currentUser = nil
        membershipProfile = .empty
        speakerProfiles = []
        profileName = ""
        cloudSyncEnabled = false
        cloudSyncStatusText = "云端同步未开启"
        cloudSyncInProgress = false
        cloudSyncOperations = []
        transcriptEditedTaskIds = []
        profileCloudSyncFocusRequest = 0
        authStateMessage = nil
    }

    func clearLocalMeetingData() {
        if let currentUser {
            cloudSyncOperationStore.clear(userId: currentUser.userId)
            localScheduleStore.clear(userId: currentUser.userId)
            profileCacheStore.clearMembership(userId: currentUser.userId)
            profileCacheStore.clearSpeakerProfiles(userId: currentUser.userId)
        }
        cloudSyncOperations = []
        transcriptEditedTaskIds = []
        taskQueue.restore([])
        taskStateStore.clear()
        audioFileStore.clearAllBuckets()
        scheduleNotificationService.cancelAllReminders()
        cacheStore.clearAll()
        localKnowledgeStore.clearAll()
        localResultStore.clearAll()
    }

    func logoutAndClearLocalData() {
        clearLocalMeetingData()
        clearUser()
    }

    func clearAllMeetingData() async throws {
        if let user = currentUser, user.tokenValid {
            _ = try await apiClient.clearCloudData(user: user)
        }
        clearLocalMeetingData()
    }

    func markTranscriptEdited(taskId: String) {
        guard !taskId.isEmpty else { return }
        transcriptEditedTaskIds.insert(taskId)
    }

    func clearTranscriptEdited(taskId: String) {
        guard !taskId.isEmpty else { return }
        transcriptEditedTaskIds.remove(taskId)
    }

    func transcriptNeedsRegeneration(taskId: String) -> Bool {
        guard !taskId.isEmpty else { return false }
        if transcriptEditedTaskIds.contains(taskId) { return true }
        guard let task = taskQueue.tasks.first(where: { $0.id == taskId || $0.remoteTaskId == taskId }) else {
            return false
        }
        return transcriptEditedTaskIds.contains(task.id) || task.remoteTaskId.map { transcriptEditedTaskIds.contains($0) } == true
    }

    func loginByPassword(phone: String, password: String) async throws {
        let response = try await apiClient.loginByPassword(phone: phone, password: password)
        setUser(response.toCloudUser())
    }

    func loginBySms(phone: String, code: String) async throws {
        let response = try await apiClient.loginBySms(phone: phone, code: code)
        setUser(response.toCloudUser())
    }

    func registerByPassword(phone: String, code: String, password: String) async throws {
        let response = try await apiClient.registerByPassword(phone: phone, code: code, password: password)
        setUser(response.toCloudUser())
    }

    func resetPassword(phone: String, code: String, password: String) async throws {
        let response = try await apiClient.resetPassword(phone: phone, code: code, password: password)
        setUser(response.toCloudUser())
    }

    func sendSmsCode(phone: String, scene: SmsCodeScene) async throws -> SmsCodeResponse {
        try await apiClient.sendSmsCode(phone: phone, scene: scene)
    }

    func setPassword(_ password: String) async throws {
        let user = try requireUser()
        _ = try await apiClient.setPassword(user: user, password: password)
    }

    func changePassword(oldPassword: String, newPassword: String) async throws {
        let user = try requireUser()
        _ = try await apiClient.changePassword(user: user, oldPassword: oldPassword, newPassword: newPassword)
    }

    func verifyCurrentPhoneForChange(oldPhone: String, oldCode: String) async throws -> String {
        let user = try requireUser()
        return try await apiClient.verifyCurrentPhoneForChange(user: user, oldPhone: oldPhone, oldCode: oldCode).verificationToken
    }

    func changePhone(oldPhone: String, verificationToken: String, newPhone: String, newCode: String) async throws {
        let user = try requireUser()
        let response = try await apiClient.changePhone(
            user: user,
            oldPhone: oldPhone,
            oldVerificationToken: verificationToken,
            newPhone: newPhone,
            newCode: newCode
        )
        setUser(response.toCloudUser())
    }

    func refreshMembership() async throws {
        let user = try requireUser()
        do {
            let profile = try await apiClient.getMembershipProfile(user: user)
            membershipProfile = profile
            profileCacheStore.saveMembership(profile, userId: user.userId)
        } catch {
            if let cached = profileCacheStore.loadMembership(userId: user.userId) {
                membershipProfile = cached
            }
            throw error
        }
    }

    func ensureTranscriptionAvailable() async throws {
        _ = try requireUser()
        try await ensureMembershipLoadedIfNeeded()
        guard !membershipProfile.frozen else {
            throw APIError.httpStatus(403, "该账户状态异常已经被冻结")
        }
        guard membershipProfile.transcriptionMinutesRemaining > 0 else {
            throw APIError.httpStatus(402, "额度已耗尽，请充值后继续享受权益")
        }
    }

    func ensureKnowledgeAvailable() async throws {
        _ = try requireUser()
        try await ensureMembershipLoadedIfNeeded()
        guard !membershipProfile.frozen else {
            throw APIError.httpStatus(403, "该账户状态异常已经被冻结")
        }
        guard membershipProfile.knowledgeQaRemaining > 0 else {
            throw APIError.httpStatus(402, "额度已耗尽，请充值后继续享受权益")
        }
    }

    func loadCloudBootstrap() async throws -> CloudBootstrapResponse {
        let user = try requireUser()
        cloudSyncInProgress = true
        cloudSyncStatusText = "正在拉取云端"
        defer { cloudSyncInProgress = false }
        do {
            try await syncPendingLocalResultsToCloud(forceUpload: false)
            cloudSyncStatusText = "正在拉取云端"
            let response = try await apiClient.bootstrapCloud(user: user)
            let merged = mergeCloudBootstrap(response, user: user)
            latestSchedules = merged.schedules
            localScheduleStore.save(merged.schedules, userId: user.userId)
            try? cacheStore.save(merged, key: cloudBootstrapCacheKey(userId: user.userId))
            indexKnowledge(from: merged)
            await cacheSyncedCloudAudio(from: merged)
            checkScheduleReminders(schedules: merged.schedules)
            await scheduleSystemReminders(merged.schedules)
            if cloudSyncStatusText != "云端数据和音频已同步" &&
                cloudSyncStatusText != "云端数据已同步，部分音频稍后再缓存" {
                cloudSyncStatusText = "云端数据已同步"
            }
            return merged
        } catch {
            if let cached = cacheStore.load(CloudBootstrapResponse.self, key: cloudBootstrapCacheKey(userId: user.userId)) {
                cloudSyncStatusText = "已显示本机缓存，云端稍后重试"
                latestSchedules = cached.schedules
                checkScheduleReminders(schedules: cached.schedules)
                await scheduleSystemReminders(cached.schedules)
                return cached
            }
            let localTasks = taskQueue.tasks.compactMap { localCloudTaskItem(for: $0) }
            let localSchedules = localScheduleStore.load(userId: user.userId)
            if !localTasks.isEmpty || !localSchedules.isEmpty {
                cloudSyncStatusText = "已显示本机数据，云端稍后重试"
                latestSchedules = localSchedules
                checkScheduleReminders(schedules: localSchedules)
                await scheduleSystemReminders(localSchedules)
                return CloudBootstrapResponse(userId: user.userId, tasks: localTasks, schedules: localSchedules)
            }
            cloudSyncStatusText = cloudSyncEnabled ? "同步失败，请稍后重试" : "云端同步未开启"
            throw error
        }
    }

    func updateProfileName(_ name: String) {
        guard let user = currentUser else { return }
        let clean = name.trimmingCharacters(in: .whitespacesAndNewlines)
        profileName = clean.isEmpty ? user.displayName : clean
        settingsStore.saveProfileName(profileName, userId: user.userId)
    }

    func setCloudSyncEnabled(_ enabled: Bool) {
        guard let user = currentUser else { return }
        cloudSyncEnabled = enabled
        settingsStore.saveCloudSyncEnabled(enabled, userId: user.userId)
        cloudSyncStatusText = enabled ? "云端同步已开启" : "云端同步已关闭"
        if enabled {
            Task { try? await syncPendingLocalResultsToCloud(forceUpload: false) }
        }
    }

    func openProfileCloudSync() {
        profileCloudSyncFocusRequest += 1
    }

    func consumeProfileCloudSyncFocusRequest() {
        profileCloudSyncFocusRequest = 0
    }

    func loadOrders() async throws -> [PaymentOrder] {
        let user = try requireUser()
        return try await apiClient.listOrders(user: user)
    }

    func loadOrder(id: String) async throws -> PaymentOrder? {
        let user = try requireUser()
        return try await apiClient.getOrder(id, user: user)
    }

    func syncPaymentOrder(id: String) async throws -> PaymentOrder? {
        let user = try requireUser()
        let order = try await apiClient.syncAlipayPaymentOrder(id, user: user)
        if order?.status.contains("支付成功") == true || order?.status.contains("已支付") == true {
            try? await refreshMembership()
        }
        return order
    }

    func confirmAppleTransaction(_ body: AppleTransactionConfirmRequest) async throws -> PaymentOrder? {
        let user = try requireUser()
        let order = try await apiClient.confirmAppleTransaction(body, user: user)
        if order?.status.contains("支付成功") == true || order?.status.contains("已支付") == true {
            try? await refreshMembership()
        }
        return order
    }

    func refreshSpeakerProfiles() async throws {
        let user = try requireUser()
        do {
            let profiles = try await apiClient.listSpeakerProfiles(user: user)
            replaceSpeakerProfiles(profiles, userId: user.userId)
        } catch {
            let cached = profileCacheStore.loadSpeakerProfiles(userId: user.userId)
            if !cached.isEmpty {
                speakerProfiles = cached
            }
            throw error
        }
    }

    func updateSpeakerProfile(_ profile: SpeakerProfile, displayName: String? = nil, active: Bool? = nil) async throws {
        let user = try requireUser()
        let updated = try await apiClient.updateSpeakerProfile(user: user, profileId: profile.id, displayName: displayName, active: active)
        replaceSpeakerProfiles((speakerProfiles.filter { $0.id != updated.id } + [updated]).sorted { $0.displayName < $1.displayName }, userId: user.userId)
    }

    func deleteSpeakerProfile(_ profile: SpeakerProfile) async throws {
        let user = try requireUser()
        _ = try await apiClient.deleteSpeakerProfile(user: user, profileId: profile.id)
        replaceSpeakerProfiles(speakerProfiles.filter { $0.id != profile.id }, userId: user.userId)
    }

    func enrollSpeakerProfileFromTask(taskId: String, speakerId: String, speakerName: String, displayName: String) async throws {
        let user = try requireUser()
        let profile = try await apiClient.enrollSpeakerProfileFromTask(user: user, taskId: taskId, speakerId: speakerId, speakerName: speakerName, displayName: displayName)
        replaceSpeakerProfiles((speakerProfiles.filter { $0.id != profile.id } + [profile]).sorted { $0.displayName < $1.displayName }, userId: user.userId)
    }

    func ensureRemoteTaskForVoiceprint(localTaskId: String) async throws -> String {
        if let task = taskQueue.tasks.first(where: { $0.id == localTaskId || $0.remoteTaskId == localTaskId }) {
            if let remoteTaskId = task.remoteTaskId, !remoteTaskId.isEmpty {
                return remoteTaskId
            }
            enqueueCloudOperation(type: .upload, localTaskId: task.id, remoteTaskId: nil)
            try await syncPendingLocalResultsToCloud(forceUpload: true)
            if let updatedTask = taskQueue.tasks.first(where: { $0.id == task.id }),
               let remoteTaskId = updatedTask.remoteTaskId,
               !remoteTaskId.isEmpty {
                return remoteTaskId
            }
            throw APIError.httpStatus(409, "会议同步到云端后才能保存声纹")
        }
        return localTaskId
    }

    func enrollSpeakerProfileFromAudio(displayName: String, localFileURL: URL, profileId: String? = nil) async throws {
        let user = try requireUser()
        let profile = try await apiClient.enrollSpeakerProfileFromAudio(user: user, displayName: displayName, localFileURL: localFileURL, profileId: profileId)
        replaceSpeakerProfiles((speakerProfiles.filter { $0.id != profile.id } + [profile]).sorted { $0.displayName < $1.displayName }, userId: user.userId)
    }

    func loadTaskDetail(taskId: String) async throws -> RemoteTaskDetail {
        let user = try requireUser()
        let detail = try await apiClient.getTask(taskId, user: user)
        if let result = detail.result {
            cacheResult(detail.task.toClientTask(), result: result)
        }
        return detail
    }

    func loadTaskDetail(for task: MeetingTask) async throws -> RemoteTaskDetail {
        let remoteId = task.remoteTaskId ?? task.id
        return try await loadTaskDetail(taskId: remoteId)
    }

    func loadTaskResult(taskId: String) async throws -> MeetingProcessingResult {
        let user = try requireUser()
        return try await apiClient.getTaskResult(taskId, user: user)
    }

    func makeRealtimeASRClient(
        onEvent: @escaping RealtimeASRClient.EventHandler,
        onState: @escaping RealtimeASRClient.StateHandler
    ) -> RealtimeASRClient {
        RealtimeASRClient(apiClient: apiClient, onEvent: onEvent, onState: onState)
    }

    func askKnowledge(
        question: String,
        scope: KnowledgeQueryScope = .local,
        contextTaskIds: [String] = [],
        contextMessages: [KnowledgeContextItem] = []
    ) async throws -> KnowledgeAskResponse {
        try await ensureKnowledgeAvailable()
        let user = try requireUser()
        let localSources = localKnowledgeStore.list(scope: scope).map { $0.toRequestSource() }
        return try await apiClient.askKnowledge(
            KnowledgeAskRequest(
                question: question,
                userId: user.userId,
                userName: user.displayName,
                limit: 6,
                taskIds: [],
                contextTaskIds: contextTaskIds,
                contextMessages: contextMessages,
                scope: scope,
                localSources: localSources
            ),
            user: user
        )
    }

    func knowledgeTopics(limit: Int = 5) -> [KnowledgeTopic] {
        localKnowledgeStore.recentTopics(limit: limit)
    }

    func processRemoteTask(_ task: MeetingTask) async throws -> RemoteTaskDetail {
        try await ensureTranscriptionAvailable()
        let user = try requireUser()
        let preparedTask = try await prepareRemoteTask(task, user: user)
        let remoteId = preparedTask.remoteTaskId ?? preparedTask.id
        return try await apiClient.processTask(
            remoteId,
            user: user,
            context: TaskProcessingContextRequest(
                meetingNote: preparedTask.scheduleNote,
                scheduleId: preparedTask.scheduleId,
                recognitionLanguage: preparedTask.recognitionLanguage.remoteValue,
                transcripts: preparedTask.liveTranscripts.isEmpty ? nil : preparedTask.liveTranscripts
            )
        )
    }

    func retryRemoteTask(_ task: MeetingTask) async throws -> RemoteTaskDetail {
        guard task.remoteTaskId != nil else {
            return try await processRemoteTask(task)
        }
        try await ensureTranscriptionAvailable()
        let user = try requireUser()
        let remoteId = task.remoteTaskId ?? task.id
        return try await apiClient.retryTask(
            remoteId,
            user: user,
            context: TaskProcessingContextRequest(
                meetingNote: task.scheduleNote,
                scheduleId: task.scheduleId,
                recognitionLanguage: task.recognitionLanguage.remoteValue,
                transcripts: task.liveTranscripts.isEmpty ? nil : task.liveTranscripts
            )
        )
    }

    func deleteRemoteTask(_ taskId: String) async throws -> MessageResponse {
        let user = try requireUser()
        let localTaskId: String?
        if let detail = try? await apiClient.getTask(taskId, user: user) {
            localTaskId = detail.task.toClientTask().id
        } else {
            localTaskId = nil
        }
        let response = try await apiClient.deleteTask(taskId, user: user)
        completeCloudOperations(localTaskId: localTaskId ?? taskId, types: [.delete])
        localKnowledgeStore.deleteMeetingIndex(taskId: taskId)
        if let localTaskId, localTaskId != taskId {
            localKnowledgeStore.deleteMeetingIndex(taskId: localTaskId)
        }
        return response
    }

    func cancelRemoteTask(_ task: MeetingTask) async throws -> RemoteMeetingTask? {
        let remoteId = task.remoteTaskId
        markTaskCanceled(task.id)
        guard let remoteId else {
            return nil
        }
        let user = try requireUser()
        do {
            return try await apiClient.cancelTask(remoteId, user: user)
        } catch {
            _ = try? await apiClient.deleteTask(remoteId, user: user)
            localKnowledgeStore.deleteMeetingIndex(taskId: remoteId)
            throw error
        }
    }

    func updateTaskResult(taskId: String, request: ResultUpdateRequest) async throws -> MeetingProcessingResult {
        let user = try requireUser()
        let result = try await apiClient.updateTaskResult(taskId, user: user, body: request)
        await refreshLocalResultCache(taskId: taskId, result: result, user: user)
        if let task = taskQueue.tasks.first(where: { $0.remoteTaskId == taskId || $0.id == taskId }) {
            enqueueCloudOperation(type: .updateResult, localTaskId: task.id, remoteTaskId: task.remoteTaskId ?? taskId)
        }
        return result
    }

    func updateMeetingResult(taskId: String, request: ResultUpdateRequest) async throws -> MeetingProcessingResult {
        if let task = taskQueue.tasks.first(where: { $0.id == taskId || $0.remoteTaskId == taskId }),
           let current = localResultStore.load(taskId: task.id) {
            let merged = current.merged(with: request).normalized(
                taskId: task.id,
                remoteTaskId: task.remoteTaskId ?? current.remoteTaskId ?? task.id,
                sourceFilePath: task.localFilePath
            )
            localResultStore.save(merged, taskId: task.id)
            localKnowledgeStore.replaceMeetingIndex(task: task, result: merged)
            enqueueCloudOperation(
                type: task.remoteTaskId == nil ? .upload : .updateResult,
                localTaskId: task.id,
                remoteTaskId: task.remoteTaskId
            )
            return merged
        }
        return try await updateTaskResult(taskId: taskId, request: request)
    }

    func updateRemoteTask(taskId: String, request: TaskUpdateRequest) async throws -> RemoteMeetingTask {
        let user = try requireUser()
        let task = try await apiClient.updateTask(taskId, user: user, body: request)
        if let detail = try? await apiClient.getTask(taskId, user: user), let result = detail.result {
            cacheResult(detail.task.toClientTask(), result: result)
        }
        return task
    }

    func updateMeetingTask(taskId: String, request: TaskUpdateRequest) async throws -> RemoteMeetingTask {
        if let task = taskQueue.tasks.first(where: { $0.id == taskId || $0.remoteTaskId == taskId }) {
            var nextTask = task
            if let title = request.title?.trimmingCharacters(in: .whitespacesAndNewlines), !title.isEmpty {
                nextTask.title = title
            }
            if let confirmed = request.confirmed {
                nextTask.confirmed = confirmed
            }
            if let isPrivate = request.isPrivate {
                nextTask.isPrivate = isPrivate
            }
            if let knowledgeScope = request.knowledgeScope {
                nextTask.knowledgeScope = knowledgeScope
            }
            nextTask.errorMessage = nil
            taskQueue.upsert(nextTask)
            persistTaskQueue()
            if let result = localResultStore.load(taskId: nextTask.id) {
                localKnowledgeStore.replaceMeetingIndex(task: nextTask, result: result)
            }
            enqueueCloudOperation(
                type: nextTask.remoteTaskId == nil ? .upload : .updateResult,
                localTaskId: nextTask.id,
                remoteTaskId: nextTask.remoteTaskId
            )
            if let remoteTaskId = nextTask.remoteTaskId {
                do {
                    return try await updateRemoteTask(taskId: remoteTaskId, request: request)
                } catch {
                    cloudSyncStatusText = "会议修改已保存，云端同步稍后重试"
                }
            }
            return nextTask.toRemoteTaskFallback()
        }
        return try await updateRemoteTask(taskId: taskId, request: request)
    }

    func confirmMeeting(taskId: String) async throws -> RemoteMeetingTask {
        if let localTask = taskQueue.tasks.first(where: { $0.remoteTaskId == taskId || $0.id == taskId }) {
            var nextTask = localTask
            nextTask.confirmed = true
            nextTask.errorMessage = nil
            taskQueue.upsert(nextTask)
            persistTaskQueue()
            enqueueCloudOperation(
                type: nextTask.remoteTaskId == nil ? .upload : .updateResult,
                localTaskId: nextTask.id,
                remoteTaskId: nextTask.remoteTaskId
            )
            if let remoteTaskId = nextTask.remoteTaskId {
                do {
                    let updated = try await apiClient.updateTask(remoteTaskId, user: try requireUser(), body: TaskUpdateRequest(confirmed: true))
                    completeCloudOperations(localTaskId: nextTask.id, types: [.updateResult])
                    return updated
                } catch {
                    cloudSyncStatusText = "会议确认已保存，云端同步稍后重试"
                    return nextTask.toRemoteTaskFallback()
                }
            }
            return nextTask.toRemoteTaskFallback()
        }
        return try await updateRemoteTask(taskId: taskId, request: TaskUpdateRequest(confirmed: true))
    }

    func regenerateMinutes(taskId: String, transcripts: [TranscriptSegment]) async throws -> MeetingProcessingResult {
        let user = try requireUser()
        if let task = taskQueue.tasks.first(where: { $0.id == taskId || $0.remoteTaskId == taskId }),
           let current = localResultStore.load(taskId: task.id) {
            let result: MeetingProcessingResult
            if let remoteTaskId = task.remoteTaskId {
                result = try await apiClient.regenerateMinutes(remoteTaskId, user: user, transcripts: transcripts)
            } else {
                result = try await apiClient.regenerateLocalMinutes(
                    RegenerateLocalMinutesRequest(
                        taskId: task.id,
                        title: task.title.isEmpty ? current.taskId : task.title,
                        sourceFilePath: current.sourceFilePath,
                        participants: current.participants,
                        meetingNote: task.scheduleNote,
                        tags: current.tags,
                        transcripts: transcripts
                    ),
                    user: user
                )
            }
            let normalized = result.normalized(
                taskId: task.id,
                remoteTaskId: result.remoteTaskId ?? task.remoteTaskId ?? current.remoteTaskId ?? task.id,
                sourceFilePath: current.sourceFilePath
            )
            localResultStore.save(normalized, taskId: task.id)
            localKnowledgeStore.replaceMeetingIndex(task: task, result: normalized)
            enqueueCloudOperation(
                type: task.remoteTaskId == nil ? .upload : .updateResult,
                localTaskId: task.id,
                remoteTaskId: task.remoteTaskId
            )
            return normalized
        }
        let result = try await apiClient.regenerateMinutes(taskId, user: user, transcripts: transcripts)
        await refreshLocalResultCache(taskId: taskId, result: result, user: user)
        return result
    }

    func exportTaskText(taskId: String, includeTranscript: Bool) async throws -> String {
        let user = try requireUser()
        return try await apiClient.exportTaskText(taskId, user: user, includeTranscript: includeTranscript)
    }

    func downloadTaskAudio(taskId: String) async throws -> Data {
        let user = try requireUser()
        return try await apiClient.downloadTaskAudio(taskId, user: user)
    }

    func saveSchedule(_ schedule: ScheduledMeeting) async throws -> ScheduledMeeting {
        let user = try requireUser()
        localScheduleStore.upsert(schedule, userId: user.userId)
        updateCachedSchedule(schedule)
        await scheduleNotificationService.scheduleReminder(for: schedule, requestingPermission: true)
        do {
            let saved = try await apiClient.upsertSchedule(schedule, user: user)
            localScheduleStore.upsert(saved, userId: user.userId)
            updateCachedSchedule(saved)
            await scheduleNotificationService.scheduleReminder(for: saved)
            completeCloudOperations(localTaskId: saved.id, types: [.upsertSchedule])
            return saved
        } catch {
            enqueueCloudOperation(type: .upsertSchedule, localTaskId: schedule.id, remoteTaskId: schedule.id, lastError: userMessage(error))
            cloudSyncStatusText = "云端预约同步失败，已保存在本机"
            return schedule
        }
    }

    func deleteSchedule(_ scheduleId: String) async throws {
        let user = try requireUser()
        localScheduleStore.delete(id: scheduleId, userId: user.userId)
        removeCachedSchedule(scheduleId)
        scheduleNotificationService.cancelReminder(scheduleId: scheduleId)
        do {
            _ = try await apiClient.deleteSchedule(scheduleId, user: user)
            completeCloudOperations(localTaskId: scheduleId, types: [.deleteSchedule])
        } catch {
            enqueueCloudOperation(type: .deleteSchedule, localTaskId: scheduleId, remoteTaskId: scheduleId, lastError: userMessage(error))
            cloudSyncStatusText = "云端预约删除失败，已排队同步"
        }
    }

    var reminderSchedule: ScheduledMeeting? {
        guard let reminderScheduleId else { return nil }
        return latestSchedules.first { $0.id == reminderScheduleId }
    }

    func checkScheduleReminders(schedules: [ScheduledMeeting]? = nil, nowMillis: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) {
        if let schedules {
            latestSchedules = schedules
        }
        if let reminderScheduleId,
           latestSchedules.contains(where: { $0.id == reminderScheduleId }) {
            return
        }
        reminderScheduleId = nil
        let overdueIds = Set(latestSchedules.filter { $0.isOverdue(nowMillis: nowMillis) || $0.isFinished(nowMillis: nowMillis) }.map(\.id))
        dismissedScheduleReminderIds.subtract(overdueIds)
        snoozedScheduleReminderUntil = snoozedScheduleReminderUntil.filter { !overdueIds.contains($0.key) }
        let leadMillis: Int64 = 5 * 60_000
        reminderScheduleId = latestSchedules
            .sorted { ($0.startAtMillis ?? Int64.max) < ($1.startAtMillis ?? Int64.max) }
            .first { schedule in
                guard let start = schedule.startAtMillis else { return false }
                let end = schedule.endAtMillis ?? (start + 60 * 60_000)
                return !dismissedScheduleReminderIds.contains(schedule.id) &&
                    (snoozedScheduleReminderUntil[schedule.id] ?? 0) <= nowMillis &&
                    !schedule.isFinished(nowMillis: nowMillis) &&
                    !schedule.isOverdue(nowMillis: nowMillis) &&
                    (start - leadMillis)...end ~= nowMillis
            }?.id
    }

    func dismissScheduleReminder() {
        if let reminderScheduleId {
            dismissedScheduleReminderIds.insert(reminderScheduleId)
            snoozedScheduleReminderUntil.removeValue(forKey: reminderScheduleId)
        }
        reminderScheduleId = nil
    }

    func snoozeScheduleReminder(minutes: Int64 = 5) {
        if let reminderScheduleId {
            dismissedScheduleReminderIds.remove(reminderScheduleId)
            snoozedScheduleReminderUntil[reminderScheduleId] = Int64(Date().timeIntervalSince1970 * 1000) + minutes * 60_000
        }
        reminderScheduleId = nil
    }

    func consumeScheduleReminderForRecording() -> ScheduledMeeting? {
        let meeting = reminderSchedule
        reminderScheduleId = nil
        if let meeting {
            dismissedScheduleReminderIds.insert(meeting.id)
            snoozedScheduleReminderUntil.removeValue(forKey: meeting.id)
        }
        return meeting
    }

    func enqueueTask(_ task: MeetingTask) {
        taskQueue.enqueue(task)
        persistTaskQueue()
    }

    @discardableResult
    func recoverUnfinishedRecordings() -> Int {
        let existingPaths = Set(taskQueue.tasks.map(\.localFilePath))
        let recovered = audioFileStore.recoverableRecordings().compactMap { url -> MeetingTask? in
            guard !existingPaths.contains(url.path) else { return nil }
            guard !url.deletingPathExtension().lastPathComponent.hasPrefix("voiceprint-") else { return nil }
            let now = Int64(Date().timeIntervalSince1970 * 1000)
            let createdAtMillis = (try? url.resourceValues(forKeys: [.creationDateKey]).creationDate)
                .map { Int64($0.timeIntervalSince1970 * 1000) } ?? now
            let taskId = "recording-\(stableIdentifierSuffix(for: url.path))"
            return MeetingTask(
                id: taskId,
                remoteTaskId: nil,
                fileId: nil,
                title: recoveredRecordingTitle(for: url),
                source: .recording,
                status: .waitingProcess,
                localFilePath: url.path,
                createdAtMillis: createdAtMillis,
                sizeLabel: readableFileSize(url),
                errorMessage: nil,
                progressPercent: 0,
                progressLabel: "待恢复处理",
                progressStage: "waiting",
                confirmed: false,
                knowledgeScope: .local,
                isPrivate: false,
                deviceId: settingsStore.deviceId,
                scheduleId: nil,
                scheduleNote: nil,
                recognitionLanguage: settingsStore.preferredRecognitionLanguage,
                liveTranscripts: []
            )
        }
        for task in recovered {
            taskQueue.enqueue(task)
        }
        if !recovered.isEmpty {
            persistTaskQueue()
        }
        return recovered.count
    }

    func openTask(_ taskId: String) -> QueueOpenDecision {
        let decision = taskQueue.open(taskId)
        persistTaskQueue()
        return decision
    }

    func markTaskProcessing(_ taskId: String, retryRemote: Bool = false) {
        taskQueue.markProcessing(taskId, retryRemote: retryRemote)
        persistTaskQueue()
    }

    func markTaskFailed(_ taskId: String, message: String) {
        taskQueue.markFailed(taskId, message: message)
        persistTaskQueue()
    }

    func markTaskWaitingRetry(_ taskId: String, message: String?) {
        taskQueue.markWaitingRetry(taskId, message: message)
        persistTaskQueue()
    }

    func markTaskAutoRetrying(_ taskId: String, remoteTaskId: String?, progressPercent: Double) {
        taskQueue.markAutoRetrying(taskId, remoteTaskId: remoteTaskId, progressPercent: progressPercent)
        persistTaskQueue()
    }

    func markTaskWaitingProcess(_ taskId: String, message: String?, label: String, stage: String) {
        taskQueue.markWaitingProcess(taskId, message: message, label: label, stage: stage)
        persistTaskQueue()
    }

    func markTaskCanceled(_ taskId: String) {
        taskQueue.markCanceled(taskId)
        persistTaskQueue()
    }

    func markTaskFinished(_ taskId: String) {
        taskQueue.markFinished(taskId)
        persistTaskQueue()
    }

    func indexLocalResult(task: MeetingTask, result: MeetingProcessingResult) {
        localResultStore.save(result, taskId: task.id)
        localKnowledgeStore.replaceMeetingIndex(task: task, result: result)
        if task.status == .finished {
            enqueueCloudOperation(
                type: task.remoteTaskId == nil ? .upload : .updateResult,
                localTaskId: task.id,
                remoteTaskId: task.remoteTaskId
            )
            if cloudSyncEnabled {
                Task { try? await syncPendingLocalResultsToCloud(forceUpload: false) }
            }
        }
    }

    func deleteTemporaryRemoteProcessingTask(_ remoteTaskId: String) async {
        guard let user = try? requireUser() else { return }
        _ = try? await apiClient.deleteTask(remoteTaskId, user: user)
        localKnowledgeStore.deleteMeetingIndex(taskId: remoteTaskId)
    }

    func localMeetingDetails() -> [MeetingDetail] {
        taskQueue.tasks
            .filter { $0.status == .finished }
            .compactMap { task in
                guard let result = localResultStore.load(taskId: task.id) else { return nil }
                return MeetingDetail(task: task, file: nil, result: result)
            }
    }

    func localTaskDetail(taskId: String) -> RemoteTaskDetail? {
        guard let local = localMeetingDetails().first(where: { detail in
            detail.task.id == taskId || detail.task.remoteTaskId == taskId
        }) else {
            return nil
        }
        return RemoteTaskDetail(task: local.task.toRemoteTaskFallback(syncScope: .localProcessing), file: local.file, result: local.result)
    }

    func removeTask(_ taskId: String, enqueueRemoteDelete: Bool = true) {
        guard taskQueue.tasks.first(where: { $0.id == taskId })?.status != .processing else {
            cloudSyncStatusText = "正在处理的文件不能删除，请先终止任务"
            return
        }
        let remoteTaskIdForDelete = taskQueue.tasks.first(where: { $0.id == taskId })?.remoteTaskId
        if let task = taskQueue.tasks.first(where: { $0.id == taskId }) {
            if !task.localFilePath.isEmpty {
                audioFileStore.removeFile(path: task.localFilePath)
            }
            if let remoteTaskId = task.remoteTaskId {
                localResultStore.remove(taskId: remoteTaskId)
                localKnowledgeStore.deleteMeetingIndex(taskId: remoteTaskId)
            }
        }
        localResultStore.remove(taskId: taskId)
        localKnowledgeStore.deleteMeetingIndex(taskId: taskId)
        taskQueue.remove(taskId)
        persistTaskQueue()
        if enqueueRemoteDelete, let remoteTaskIdForDelete {
            enqueueCloudOperation(type: .delete, localTaskId: taskId, remoteTaskId: remoteTaskIdForDelete)
        }
    }

    func deleteMeetingTask(_ task: RemoteMeetingTask) async throws {
        let localId = task.clientTaskId ?? task.id
        if let localTask = taskQueue.tasks.first(where: { $0.id == localId || $0.remoteTaskId == task.id }) {
            guard localTask.status != .processing else {
                throw APIError.httpStatus(409, "正在处理的文件不能删除，请先终止任务")
            }
            if let remoteTaskId = localTask.remoteTaskId {
                do {
                    _ = try await deleteRemoteTask(remoteTaskId)
                    removeTask(localTask.id, enqueueRemoteDelete: false)
                } catch {
                    removeTask(localTask.id, enqueueRemoteDelete: true)
                    throw error
                }
            } else {
                removeTask(localTask.id, enqueueRemoteDelete: false)
            }
            return
        }
        _ = try await deleteRemoteTask(task.id)
    }

    func deleteMeetingTask(_ task: MeetingTask) async throws {
        if task.status == .processing {
            throw APIError.httpStatus(409, "正在处理的文件不能删除，请先终止任务")
        }
        if let remoteTaskId = task.remoteTaskId {
            do {
                _ = try await deleteRemoteTask(remoteTaskId)
                removeTask(task.id, enqueueRemoteDelete: false)
            } catch {
                removeTask(task.id, enqueueRemoteDelete: true)
                throw error
            }
        } else {
            removeTask(task.id, enqueueRemoteDelete: false)
        }
    }

    var unsyncedFinishedMeetingCount: Int {
        taskQueue.tasks.filter { task in
            task.status == .finished &&
                task.syncStatus != .synced &&
                localResultStore.load(taskId: task.id) != nil
        }.count
    }

    var pendingDeleteCount: Int {
        cloudSyncOperations.filter { $0.type == .delete || $0.type == .deleteSchedule }.count
    }

    func uploadAllUnsyncedMeetings() async throws {
        let pendingCount = unsyncedFinishedMeetingCount
        guard pendingCount > 0 else {
            cloudSyncStatusText = "没有待上传会议"
            return
        }
        let pendingTaskIds = taskQueue.tasks
            .filter { $0.status == .finished && $0.syncStatus != .synced }
            .map(\.id)
        for taskId in pendingTaskIds {
            updateTaskSyncStatus(taskId, status: .pendingUpload)
        }
        try await syncPendingLocalResultsToCloud(forceUpload: true)
    }

    func syncPendingLocalResultsToCloud(forceUpload: Bool = false) async throws {
        let user = try requireUser()
        guard cloudSyncEnabled || forceUpload || cloudSyncOperations.contains(where: { $0.type == .delete || $0.type == .deleteSchedule }) else {
            cloudSyncStatusText = "云端同步未开启"
            return
        }
        enqueueSyncForPendingLocalTasks(userId: user.userId)
        guard !cloudSyncOperations.isEmpty else {
            cloudSyncStatusText = "没有待同步内容"
            return
        }
        cloudSyncInProgress = true
        cloudSyncStatusText = forceUpload ? "正在上传本机会议" : "正在同步云端"
        defer { cloudSyncInProgress = false }

        var firstError: Error?
        for operation in cloudSyncOperations.sorted(by: { $0.createdAtMillis < $1.createdAtMillis }) {
            do {
                switch operation.type {
                case .upload:
                    if cloudSyncEnabled || forceUpload {
                        try await syncUploadOperation(operation, user: user)
                    }
                case .updateResult:
                    if cloudSyncEnabled || forceUpload {
                        try await syncUpdateOperation(operation, user: user)
                    }
                case .delete:
                    try await syncDeleteOperation(operation, user: user)
                case .upsertSchedule:
                    if cloudSyncEnabled {
                        try await syncUpsertScheduleOperation(operation, user: user)
                    }
                case .deleteSchedule:
                    try await syncDeleteScheduleOperation(operation, user: user)
                }
            } catch {
                failCloudOperation(operation, error: error)
                if firstError == nil {
                    firstError = error
                }
            }
        }

        if let firstError {
            cloudSyncStatusText = "部分同步失败，\(cloudSyncOperations.count) 项待重试"
            throw firstError
        }
        cloudSyncStatusText = cloudSyncOperations.isEmpty ? "云端同步完成" : "\(cloudSyncOperations.count) 项等待同步"
    }

    func requireUser() throws -> CloudUser {
        if let currentUser, currentUser.tokenValid {
            return currentUser
        }
        if currentUser != nil {
            clearExpiredSession(message: "登录已过期，请重新登录")
        }
        throw APIError.authRequired
    }

    func consumeAuthStateMessage() -> String? {
        defer { authStateMessage = nil }
        return authStateMessage
    }

    private func persistTaskQueue() {
        try? taskStateStore.save(taskQueue.tasks)
    }

    private func restoreProfileSettings() {
        guard let user = currentUser else {
            profileName = ""
            cloudSyncEnabled = false
            cloudSyncStatusText = "云端同步未开启"
            return
        }
        profileName = settingsStore.profileName(userId: user.userId).trimmingCharacters(in: .whitespacesAndNewlines)
        if profileName.isEmpty {
            profileName = user.displayName
        }
        membershipProfile = profileCacheStore.loadMembership(userId: user.userId) ?? .empty
        speakerProfiles = profileCacheStore.loadSpeakerProfiles(userId: user.userId)
        cloudSyncEnabled = settingsStore.cloudSyncEnabled(userId: user.userId)
        cloudSyncOperations = cloudSyncOperationStore.load(userId: user.userId)
        cloudSyncStatusText = cloudSyncEnabled ? "云端同步已开启" : "云端同步未开启"
    }

    private func replaceSpeakerProfiles(_ profiles: [SpeakerProfile], userId: String) {
        speakerProfiles = profiles
        profileCacheStore.saveSpeakerProfiles(profiles, userId: userId)
    }

    private func ensureMembershipLoadedIfNeeded() async throws {
        if membershipProfile.planId == "none",
           membershipProfile.transcriptionMinutesTotal == 0,
           membershipProfile.knowledgeQaTotal == 0,
           membershipProfile.accountStatus == "normal" {
            try await refreshMembership()
        }
    }

    private func clearExpiredSession(message: String) {
        if let currentUser {
            cloudSyncOperationStore.save(cloudSyncOperations, userId: currentUser.userId)
        }
        tokenStore.clear()
        currentUser = nil
        membershipProfile = .empty
        speakerProfiles = []
        profileName = ""
        cloudSyncEnabled = false
        cloudSyncStatusText = "登录已失效，云端同步已关闭"
        cloudSyncInProgress = false
        cloudSyncOperations = []
        profileCloudSyncFocusRequest = 0
        authStateMessage = message.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "登录已过期，请重新登录" : message
    }

    private func indexKnowledge(from response: CloudBootstrapResponse) {
        for item in response.tasks {
            guard let result = item.result else { continue }
            localKnowledgeStore.replaceMeetingIndex(task: item.task.toClientTask(), result: result)
        }
    }

    private func mergeCloudBootstrap(_ response: CloudBootstrapResponse, user: CloudUser) -> CloudBootstrapResponse {
        let pendingDeletes = Set(cloudSyncOperations.compactMap { operation -> String? in
            operation.type == .delete ? operation.remoteTaskId : nil
        })
        let activeCloudItems = response.tasks.filter { item in
            item.task.syncScope == .cloud &&
                item.result != nil &&
                !pendingDeletes.contains(item.task.id) &&
                !pendingDeletes.contains(item.task.clientTaskId ?? "")
        }
        let remoteKeys = Set(activeCloudItems.flatMap { item in
            [item.task.id, item.task.clientTaskId].compactMap { $0 }
        })
        var byLocalId: [String: CloudTaskItem] = [:]

        purgeStaleSyncedCloudTasks(remoteKeys: remoteKeys)

        for item in activeCloudItems {
            let task = item.task.toClientTask()
            let localTask = taskQueue.tasks.first { candidate in
                let taskRemoteId = task.remoteTaskId
                let candidateRemoteId = candidate.remoteTaskId
                return candidate.id == task.id ||
                    candidate.id == taskRemoteId ||
                    candidateRemoteId == task.id ||
                    (taskRemoteId != nil && candidateRemoteId == taskRemoteId)
            }
            if let localTask, hasPendingCloudChange(localTask) {
                if let localItem = localCloudTaskItem(for: localTask) {
                    byLocalId[localTask.id] = localItem
                }
                continue
            }
            var target = localTask.map { mergeLocalTask($0, with: item.task) } ?? task
            if item.result != nil, target.status == .waitingProcess {
                target.status = .finished
            }
            if let result = item.result {
                cacheResult(target, result: result)
            }
            target.syncStatus = .synced
            taskQueue.upsert(target)
            byLocalId[target.id] = CloudTaskItem(task: target.toRemoteTaskFallback(syncScope: .cloud), file: item.file, result: item.result ?? localResultStore.load(taskId: target.id))
        }

        for localTask in taskQueue.tasks where localTask.status == .finished {
            let keys = [localTask.id, localTask.remoteTaskId].compactMap { $0 }
            guard keys.allSatisfy({ !remoteKeys.contains($0) }) else { continue }
            if let localItem = localCloudTaskItem(for: localTask) {
                byLocalId[localTask.id] = localItem
            }
        }

        persistTaskQueue()
        let mergedTasks = byLocalId.values.sorted { left, right in
            (left.task.createdAtMillis ?? 0) > (right.task.createdAtMillis ?? 0)
        }
        let pendingDeletedScheduleIds = Set(cloudSyncOperations.compactMap { operation -> String? in
            operation.type == .deleteSchedule ? operation.localTaskId : nil
        })
        let pendingLocalSchedules = localScheduleStore.load(userId: user.userId)
            .filter { !pendingDeletedScheduleIds.contains($0.id) }
        let mergedSchedules = response
            .mergingLocalSchedules(pendingLocalSchedules)
            .schedules
            .filter { !pendingDeletedScheduleIds.contains($0.id) }
        return CloudBootstrapResponse(userId: response.userId, tasks: mergedTasks, schedules: mergedSchedules)
    }

    private func purgeStaleSyncedCloudTasks(remoteKeys: Set<String>) {
        let staleTasks = taskQueue.tasks.filter { task in
            guard task.status == .finished, task.remoteTaskId != nil, !hasPendingCloudChange(task) else {
                return false
            }
            let keys = [task.id, task.remoteTaskId].compactMap { $0 }
            return keys.allSatisfy { !remoteKeys.contains($0) }
        }
        for task in staleTasks {
            localResultStore.remove(taskId: task.id)
            localKnowledgeStore.deleteMeetingIndex(taskId: task.id)
            if let remoteTaskId = task.remoteTaskId {
                localResultStore.remove(taskId: remoteTaskId)
                localKnowledgeStore.deleteMeetingIndex(taskId: remoteTaskId)
            }
            taskQueue.remove(task.id)
        }
    }

    private func localCloudTaskItem(for task: MeetingTask) -> CloudTaskItem? {
        guard let result = localResultStore.load(taskId: task.id) else { return nil }
        return CloudTaskItem(task: task.toRemoteTaskFallback(), file: nil, result: result)
    }

    private func hasPendingCloudChange(_ task: MeetingTask) -> Bool {
        cloudSyncOperations.contains { operation in
            guard operation.type == .upload || operation.type == .updateResult else { return false }
            let matchesRemote = task.remoteTaskId.map { remoteId in
                operation.remoteTaskId == remoteId
            } ?? false
            return operation.localTaskId == task.id ||
                matchesRemote ||
                operation.remoteTaskId == task.id
        }
    }

    private func mergeLocalTask(_ local: MeetingTask, with remote: RemoteMeetingTask) -> MeetingTask {
        var merged = local
        merged.remoteTaskId = remote.id
        merged.fileId = remote.fileId
        merged.title = remote.title
        merged.source = remote.source
        merged.status = remote.clientStatus
        merged.errorMessage = remote.errorMessage
        merged.progressPercent = remote.progressPercent
        merged.progressLabel = remote.progressLabel
        merged.progressStage = remote.progressStage
        merged.knowledgeScope = remote.knowledgeScope
        merged.syncStatus = remote.syncScope == .cloud ? .synced : .localProcessing
        merged.isPrivate = remote.isPrivate
        merged.confirmed = remote.confirmed
        merged.recognitionLanguage = remote.recognitionLanguage
        merged.deviceId = remote.deviceId ?? merged.deviceId
        return merged
    }

    private func refreshLocalResultCache(taskId: String, result: MeetingProcessingResult, user: CloudUser) async {
        if let detail = try? await apiClient.getTask(taskId, user: user) {
            cacheResult(detail.task.toClientTask(), result: result)
        } else if let task = taskQueue.tasks.first(where: { $0.remoteTaskId == taskId || $0.id == taskId }) {
            cacheResult(task, result: result)
        }
    }

    private func cacheResult(_ task: MeetingTask, result: MeetingProcessingResult) {
        let normalized = result.normalized(
            taskId: task.id,
            remoteTaskId: task.remoteTaskId ?? result.remoteTaskId ?? task.id,
            sourceFilePath: task.localFilePath
        )
        localResultStore.save(normalized, taskId: task.id)
        localKnowledgeStore.replaceMeetingIndex(task: task, result: normalized)
    }

    private func cacheSyncedCloudAudio(from response: CloudBootstrapResponse) async {
        let items = response.tasks.filter { $0.task.syncScope == .cloud && $0.result != nil && $0.file != nil }
        guard !items.isEmpty else { return }
        var cachedCount = 0
        for item in items {
            let remoteTaskId = item.task.id
            let localTaskId = item.task.clientTaskId ?? remoteTaskId
            guard var task = taskQueue.tasks.first(where: { $0.id == localTaskId || $0.remoteTaskId == remoteTaskId }),
                  task.status == .finished,
                  task.syncStatus == .synced,
                  let file = item.file
            else {
                continue
            }
            if !task.localFilePath.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               FileManager.default.fileExists(atPath: task.localFilePath) {
                continue
            }
            do {
                let cachedURL = try audioFileStore.cachedAudioURL(taskId: remoteTaskId, originalName: file.originalName)
                if !FileManager.default.fileExists(atPath: cachedURL.path) {
                    cloudSyncStatusText = "正在同步云端音频"
                    let data = try await downloadTaskAudio(taskId: remoteTaskId)
                    try data.write(to: cachedURL, options: [.atomic])
                }
                task.localFilePath = cachedURL.path
                taskQueue.upsert(task)
                persistTaskQueue()
                if let result = localResultStore.load(taskId: task.id) ?? item.result {
                    let normalized = result.normalized(
                        taskId: task.id,
                        remoteTaskId: remoteTaskId,
                        sourceFilePath: cachedURL.path
                    )
                    localResultStore.save(normalized, taskId: task.id)
                    localKnowledgeStore.replaceMeetingIndex(task: task, result: normalized)
                }
                cachedCount += 1
            } catch {
                cloudSyncStatusText = "云端数据已同步，部分音频稍后再缓存"
            }
        }
        if cachedCount > 0 {
            cloudSyncStatusText = "云端数据和音频已同步"
        }
    }

    private func cloudBootstrapCacheKey(userId: String) -> String {
        "cloud_bootstrap_\(userId)"
    }

    private func prepareRemoteTask(_ task: MeetingTask, user: CloudUser) async throws -> MeetingTask {
        if task.remoteTaskId != nil {
            return task
        }
        let upload = try await apiClient.uploadFile(
            fileURL: URL(fileURLWithPath: task.localFilePath),
            user: user,
            source: task.source,
            clientTaskId: task.id,
            confirmed: task.confirmed,
            isPrivate: task.isPrivate,
            deviceId: task.deviceId,
            createdAtMillis: task.createdAtMillis,
            persistToCloud: false
        )
        var updated = task
        updated.remoteTaskId = upload.task.id
        updated.fileId = upload.file.id
        updated.progressPercent = upload.task.progressPercent
        updated.progressLabel = upload.task.progressLabel
        updated.progressStage = upload.task.progressStage
        updated.status = upload.task.clientStatus
        enqueueTask(updated)
        return updated
    }

    private func enqueueSyncForPendingLocalTasks(userId: String) {
        for task in taskQueue.tasks where task.status == .finished {
            guard localResultStore.load(taskId: task.id) != nil else { continue }
            guard task.syncStatus != .synced else { continue }
            if task.remoteTaskId == nil {
                enqueueCloudOperation(type: .upload, localTaskId: task.id, remoteTaskId: nil)
            } else if task.syncStatus == .pendingUpload || task.syncStatus == .syncFailed || task.syncStatus == .localOnly {
                enqueueCloudOperation(type: .updateResult, localTaskId: task.id, remoteTaskId: task.remoteTaskId)
            }
        }
        persistCloudOperations(userId: userId)
    }

    private func syncUploadOperation(_ operation: CloudSyncOperation, user: CloudUser) async throws {
        guard let task = taskQueue.tasks.first(where: { $0.id == operation.localTaskId }) else {
            completeCloudOperation(operation)
            return
        }
        updateTaskSyncStatus(task.id, status: .pendingUpload)
        if let remoteTaskId = task.remoteTaskId {
            enqueueCloudOperation(type: .updateResult, localTaskId: task.id, remoteTaskId: remoteTaskId)
            completeCloudOperation(operation)
            return
        }
        guard let result = localResultStore.load(taskId: task.id) else {
            completeCloudOperation(operation)
            return
        }
        let upload = try await apiClient.uploadFile(
            fileURL: URL(fileURLWithPath: task.localFilePath),
            user: user,
            source: task.source,
            clientTaskId: task.id,
            confirmed: task.confirmed,
            isPrivate: task.isPrivate,
            deviceId: task.deviceId,
            createdAtMillis: task.createdAtMillis,
            persistToCloud: true
        )
        guard let latestTask = taskQueue.tasks.first(where: { $0.id == task.id }) else {
            _ = try? await apiClient.deleteTask(upload.task.id, user: user)
            completeCloudOperation(operation)
            return
        }
        _ = try await apiClient.updateTask(
            upload.task.id,
            user: user,
            body: TaskUpdateRequest(
                title: latestTask.title,
                confirmed: latestTask.confirmed,
                isPrivate: latestTask.isPrivate,
                knowledgeScope: .cloud,
                createdAtMillis: latestTask.createdAtMillis
            )
        )
        let resultToUpload = localResultStore.load(taskId: latestTask.id) ?? result
        let updatedResult = try await apiClient.updateTaskResult(upload.task.id, user: user, body: resultToUpload.asUpdateRequest())
        finishResultSyncWithoutLosingLocalEdits(
            operation: operation,
            taskAtSend: latestTask,
            remoteTaskId: upload.task.id,
            remoteFileId: upload.file.id,
            sentResult: resultToUpload,
            serverResult: updatedResult
        )
    }

    private func syncUpdateOperation(_ operation: CloudSyncOperation, user: CloudUser) async throws {
        guard let task = taskQueue.tasks.first(where: { $0.id == operation.localTaskId }) else {
            completeCloudOperation(operation)
            return
        }
        updateTaskSyncStatus(task.id, status: .pendingUpload)
        guard let remoteTaskId = operation.remoteTaskId ?? task.remoteTaskId else {
            completeCloudOperation(operation)
            return
        }
        guard let result = localResultStore.load(taskId: task.id) else {
            completeCloudOperation(operation)
            return
        }
        let updatedResult = try await apiClient.updateTaskResult(remoteTaskId, user: user, body: result.asUpdateRequest())
        _ = try await apiClient.updateTask(
            remoteTaskId,
            user: user,
            body: TaskUpdateRequest(
                title: task.title,
                confirmed: task.confirmed,
                isPrivate: task.isPrivate,
                knowledgeScope: .cloud,
                createdAtMillis: task.createdAtMillis
            )
        )
        finishResultSyncWithoutLosingLocalEdits(
            operation: operation,
            taskAtSend: task,
            remoteTaskId: remoteTaskId,
            remoteFileId: nil,
            sentResult: result,
            serverResult: updatedResult
        )
    }

    private func finishResultSyncWithoutLosingLocalEdits(
        operation: CloudSyncOperation,
        taskAtSend: MeetingTask,
        remoteTaskId: String,
        remoteFileId: String?,
        sentResult: MeetingProcessingResult,
        serverResult: MeetingProcessingResult
    ) {
        guard let latestTask = taskQueue.tasks.first(where: { $0.id == taskAtSend.id }) else {
            completeCloudOperation(operation)
            return
        }
        let latestResult = localResultStore.load(taskId: latestTask.id)
        let changedAfterSend = latestTask.hasSyncContentChanged(since: taskAtSend) ||
            (latestResult != nil && latestResult != sentResult)
        if changedAfterSend, let latestResult {
            var pendingTask = latestTask
            pendingTask.remoteTaskId = remoteTaskId
            pendingTask.fileId = remoteFileId ?? pendingTask.fileId
            pendingTask.knowledgeScope = .cloud
            pendingTask.syncStatus = .pendingUpload
            pendingTask.errorMessage = nil
            let preserved = latestResult.normalized(
                taskId: pendingTask.id,
                remoteTaskId: remoteTaskId,
                sourceFilePath: pendingTask.localFilePath
            )
            localResultStore.save(preserved, taskId: pendingTask.id)
            localKnowledgeStore.replaceMeetingIndex(task: pendingTask, result: preserved)
            taskQueue.upsert(pendingTask)
            persistTaskQueue()
            completeCloudOperation(operation)
            enqueueCloudOperation(type: .updateResult, localTaskId: pendingTask.id, remoteTaskId: remoteTaskId)
            cloudSyncStatusText = "本机有新修改，已排队继续同步"
            return
        }

        var syncedTask = latestTask
        syncedTask.remoteTaskId = remoteTaskId
        syncedTask.fileId = remoteFileId ?? syncedTask.fileId
        syncedTask.knowledgeScope = .cloud
        syncedTask.syncStatus = .synced
        syncedTask.errorMessage = nil
        if syncedTask.status == .finished {
            syncedTask.progressPercent = 100
            syncedTask.progressLabel = "处理完成"
            syncedTask.progressStage = "finished"
        }
        let normalized = serverResult.normalized(
            taskId: syncedTask.id,
            remoteTaskId: remoteTaskId,
            sourceFilePath: syncedTask.localFilePath
        )
        localResultStore.save(normalized, taskId: syncedTask.id)
        localKnowledgeStore.replaceMeetingIndex(task: syncedTask, result: normalized)
        taskQueue.upsert(syncedTask)
        persistTaskQueue()
        completeCloudOperation(operation)
    }

    private func syncDeleteOperation(_ operation: CloudSyncOperation, user: CloudUser) async throws {
        guard let remoteTaskId = operation.remoteTaskId else {
            completeCloudOperation(operation)
            return
        }
        _ = try await apiClient.deleteTask(remoteTaskId, user: user)
        completeCloudOperation(operation)
    }

    private func syncUpsertScheduleOperation(_ operation: CloudSyncOperation, user: CloudUser) async throws {
        guard let schedule = localScheduleStore.load(userId: user.userId).first(where: { $0.id == operation.localTaskId }) else {
            completeCloudOperation(operation)
            return
        }
        let saved = try await apiClient.upsertSchedule(schedule, user: user)
        localScheduleStore.upsert(saved, userId: user.userId)
        updateCachedSchedule(saved)
        completeCloudOperation(operation)
    }

    private func syncDeleteScheduleOperation(_ operation: CloudSyncOperation, user: CloudUser) async throws {
        _ = try await apiClient.deleteSchedule(operation.localTaskId, user: user)
        removeCachedSchedule(operation.localTaskId)
        completeCloudOperation(operation)
    }

    private func enqueueCloudOperation(
        type: CloudSyncOperationType,
        localTaskId: String,
        remoteTaskId: String?,
        lastError: String? = nil
    ) {
        guard let user = currentUser else { return }
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let next: [CloudSyncOperation]
        switch type {
        case .upload:
            guard remoteTaskId == nil else { return }
            next = cloudSyncOperations
                .filter { !($0.localTaskId == localTaskId && ($0.type == .upload || $0.type == .updateResult)) } +
                [CloudSyncOperation(id: "upload-\(user.userId)-\(localTaskId)", type: type, localTaskId: localTaskId, remoteTaskId: nil, userId: user.userId, createdAtMillis: now, lastError: lastError)]
        case .updateResult:
            guard let remoteTaskId else { return }
            if cloudSyncOperations.contains(where: { $0.localTaskId == localTaskId && $0.type == .upload }) { return }
            next = cloudSyncOperations
                .filter { !($0.localTaskId == localTaskId && $0.type == .updateResult) } +
                [CloudSyncOperation(id: "update-\(user.userId)-\(localTaskId)-\(remoteTaskId)", type: type, localTaskId: localTaskId, remoteTaskId: remoteTaskId, userId: user.userId, createdAtMillis: now, lastError: lastError)]
        case .delete:
            guard let remoteTaskId else { return }
            next = cloudSyncOperations
                .filter { $0.localTaskId != localTaskId && $0.remoteTaskId != remoteTaskId } +
                [CloudSyncOperation(id: "delete-\(user.userId)-\(remoteTaskId)", type: type, localTaskId: localTaskId, remoteTaskId: remoteTaskId, userId: user.userId, createdAtMillis: now, lastError: lastError)]
        case .upsertSchedule, .deleteSchedule:
            next = cloudSyncOperations
                .filter { !($0.localTaskId == localTaskId && ($0.type == .upsertSchedule || $0.type == .deleteSchedule)) } +
                [CloudSyncOperation(id: "\(type.rawValue)-\(user.userId)-\(localTaskId)", type: type, localTaskId: localTaskId, remoteTaskId: remoteTaskId, userId: user.userId, createdAtMillis: now, lastError: lastError)]
        }
        cloudSyncOperations = next.sorted { $0.createdAtMillis < $1.createdAtMillis }
        persistCloudOperations(userId: user.userId)
        if type == .upload || type == .updateResult {
            updateTaskSyncStatus(localTaskId, status: .pendingUpload, errorMessage: lastError)
        }
    }

    private func completeCloudOperation(_ operation: CloudSyncOperation) {
        cloudSyncOperations.removeAll { $0.id == operation.id }
        persistCloudOperations(userId: operation.userId)
    }

    private func completeCloudOperations(localTaskId: String, types: Set<CloudSyncOperationType>) {
        guard let user = currentUser else { return }
        cloudSyncOperations.removeAll { $0.localTaskId == localTaskId && types.contains($0.type) }
        persistCloudOperations(userId: user.userId)
    }

    private func failCloudOperation(_ operation: CloudSyncOperation, error: Error) {
        let message = userMessage(error)
        cloudSyncOperations = cloudSyncOperations.map {
            guard $0.id == operation.id else { return $0 }
            var copy = $0
            copy.lastError = message
            return copy
        }
        persistCloudOperations(userId: operation.userId)
        if operation.type == .upload || operation.type == .updateResult {
            updateTaskSyncStatus(operation.localTaskId, status: .syncFailed, errorMessage: message)
        }
    }

    private func updateTaskSyncStatus(_ taskId: String, status: CloudSyncStatus, errorMessage: String? = nil) {
        guard var task = taskQueue.tasks.first(where: { $0.id == taskId || $0.remoteTaskId == taskId }) else { return }
        task.syncStatus = status
        if let errorMessage {
            task.errorMessage = errorMessage
        } else if status == .pendingUpload || status == .synced {
            task.errorMessage = nil
        }
        taskQueue.upsert(task)
        persistTaskQueue()
    }

    private func persistCloudOperations(userId: String) {
        cloudSyncOperationStore.save(cloudSyncOperations, userId: userId)
    }

    private func updateCachedSchedule(_ schedule: ScheduledMeeting) {
        latestSchedules = (latestSchedules.filter { $0.id != schedule.id } + [schedule])
            .sorted { ($0.startAtMillis ?? $0.createdAtMillis) < ($1.startAtMillis ?? $1.createdAtMillis) }
        dismissedScheduleReminderIds.remove(schedule.id)
        snoozedScheduleReminderUntil.removeValue(forKey: schedule.id)
        checkScheduleReminders()
    }

    private func removeCachedSchedule(_ scheduleId: String) {
        latestSchedules.removeAll { $0.id == scheduleId }
        dismissedScheduleReminderIds.remove(scheduleId)
        snoozedScheduleReminderUntil.removeValue(forKey: scheduleId)
        if reminderScheduleId == scheduleId {
            reminderScheduleId = nil
        }
    }

    private func scheduleSystemReminders(_ schedules: [ScheduledMeeting]) async {
        for schedule in schedules where !schedule.isFinished() && !schedule.isOverdue() {
            await scheduleNotificationService.scheduleReminder(for: schedule)
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    private func recoveredRecordingTitle(for url: URL) -> String {
        let base = url.deletingPathExtension().lastPathComponent.trimmingCharacters(in: .whitespacesAndNewlines)
        if base.isEmpty {
            return "未完成录音"
        }
        return base
    }

    private func stableIdentifierSuffix(for text: String) -> String {
        var hash: UInt64 = 5381
        for scalar in text.unicodeScalars {
            hash = ((hash << 5) &+ hash) &+ UInt64(scalar.value)
        }
        return String(hash, radix: 16)
    }

    private func readableFileSize(_ url: URL) -> String? {
        guard let size = try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize, size > 0 else {
            return nil
        }
        return ByteCountFormatter.string(fromByteCount: Int64(size), countStyle: .file)
    }
}

private extension MeetingProcessingResult {
    func merged(with request: ResultUpdateRequest) -> MeetingProcessingResult {
        var copy = self
        if let participants = request.participants {
            copy.participants = participants
        }
        if let tags = request.tags {
            copy.tags = tags
        }
        if let summary = request.summary {
            copy.summary = summary
        }
        if let topics = request.topics {
            copy.topics = topics
        }
        if let decisions = request.decisions {
            copy.decisions = decisions
        }
        if let todos = request.todos {
            copy.todos = todos
        }
        if let risks = request.risks {
            copy.risks = risks
        }
        if let transcripts = request.transcripts {
            copy.transcripts = transcripts
        }
        return copy
    }

    func asUpdateRequest() -> ResultUpdateRequest {
        ResultUpdateRequest(
            participants: participants,
            tags: tags,
            summary: summary,
            topics: topics,
            decisions: decisions,
            todos: todos,
            risks: risks,
            transcripts: transcripts
        )
    }

}
