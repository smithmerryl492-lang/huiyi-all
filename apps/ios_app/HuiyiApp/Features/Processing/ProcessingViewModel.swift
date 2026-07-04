import Foundation

@MainActor
final class ProcessingViewModel: ObservableObject {
    private static let transientRetryLimit = 2

    @Published var selectedTask: MeetingTask?
    @Published var isRunning = false
    @Published var isPolling = false
    @Published var isCanceling = false
    @Published var errorMessage: String?
    @Published var statusMessage: String?
    @Published var completedRemoteTaskId: String?
    @Published var lastCompletedRemoteTaskId: String?

    func load(taskId: String?, session: AppSession) {
        guard let taskId else {
            selectedTask = session.taskQueue.activeTask ?? session.taskQueue.waitingTasks.first ?? session.taskQueue.failedTasks.first
            return
        }
        selectedTask = session.taskQueue.tasks.first { $0.id == taskId }
    }

    func open(taskId: String?, session: AppSession) {
        guard let taskId else {
            load(taskId: nil, session: session)
            return
        }
        switch session.openTask(taskId) {
        case let .showActiveProcessing(activeTaskId):
            if activeTaskId != taskId {
                statusMessage = "已加入待处理，当前任务完成后继续"
            }
            load(taskId: activeTaskId, session: session)
        case let .showSelected(selectedTaskId, _):
            statusMessage = nil
            load(taskId: selectedTaskId, session: session)
        }
    }

    func startOrContinue(session: AppSession, router: AppRouter? = nil) async {
        guard let task = selectedTask else { return }
        isRunning = true
        errorMessage = nil
        completedRemoteTaskId = nil
        do {
            try await session.ensureTranscriptionAvailable()
        } catch {
            let message = userMessage(error)
            if isAuthFailure(error, message: message) {
                session.markTaskWaitingProcess(task.id, message: message, label: "等待登录", stage: "waiting_login")
            } else if isQuotaExhausted(error, message: message) {
                session.markTaskWaitingProcess(task.id, message: message, label: "等待充值", stage: "waiting_payment")
                router?.go(.membership)
            } else {
                session.markTaskFailed(task.id, message: message)
            }
            errorMessage = message
            isRunning = false
            load(taskId: task.id, session: session)
            return
        }
        let retryRemote = task.status == .failed || task.status == .waitingRetry
        session.markTaskProcessing(task.id, retryRemote: retryRemote)
        defer {
            isRunning = false
            if selectedTask?.id == task.id {
                load(taskId: task.id, session: session)
            }
        }
        do {
            var detail: RemoteTaskDetail
            if task.requiresExplicitRetry {
                if task.status == .canceled {
                    detail = try await session.processRemoteTask(task)
                } else {
                    detail = try await session.retryRemoteTask(task)
                }
            } else {
                detail = try await session.processRemoteTask(task)
            }
            detail = try await detailWithResultIfFinished(detail, session: session)
            let clientTask = updateLocalTask(from: detail, fallback: task, session: session)
            if clientTask.status == .processing {
                try await pollUntilSettled(task: clientTask, session: session)
            }
            if selectedTask?.status == .finished {
                lastCompletedRemoteTaskId = selectedTask?.remoteTaskId ?? selectedTask?.id
                try? await session.refreshMembership()
                let hadNextWaitingTask = session.taskQueue.waitingTasks.contains { $0.id != selectedTask?.id }
                await startNextWaitingTaskIfNeeded(session: session, router: router)
                if !hadNextWaitingTask && selectedTask?.status == .finished {
                    completedRemoteTaskId = lastCompletedRemoteTaskId
                }
            }
        } catch {
            let message = userMessage(error)
            if isAuthFailure(error, message: message) {
                session.markTaskWaitingProcess(task.id, message: message, label: "等待登录", stage: "waiting_login")
            } else if isQuotaExhausted(error, message: message) {
                session.markTaskWaitingProcess(task.id, message: message, label: "等待充值", stage: "waiting_payment")
            } else if message.isTransientProcessingFailure {
                session.markTaskWaitingRetry(task.id, message: "处理暂未完成，稍后可继续")
            } else {
                session.markTaskFailed(task.id, message: message)
            }
            errorMessage = message
        }
    }

    func removeFailedOrCanceled(session: AppSession) {
        guard let selectedTask, selectedTask.status == .failed || selectedTask.status == .canceled else { return }
        session.removeTask(selectedTask.id)
        self.selectedTask = nil
    }

    func cancelProcessing(session: AppSession) async {
        guard let selectedTask else { return }
        isCanceling = true
        errorMessage = nil
        defer {
            isCanceling = false
            load(taskId: selectedTask.id, session: session)
        }
        do {
            if let remote = try await session.cancelRemoteTask(selectedTask) {
                var canceled = remote.toClientTask(
                    localFilePath: selectedTask.localFilePath,
                    fallbackId: selectedTask.id,
                    fallbackCreatedAtMillis: selectedTask.createdAtMillis,
                    fallbackSizeLabel: selectedTask.sizeLabel,
                    fallbackScheduleId: selectedTask.scheduleId,
                    fallbackScheduleNote: selectedTask.scheduleNote,
                    fallbackRecognitionLanguage: selectedTask.recognitionLanguage,
                    fallbackMissingAudioRanges: selectedTask.missingAudioRanges
                )
                canceled.liveTranscripts = selectedTask.liveTranscripts
                canceled.remoteTaskId = nil
                canceled.fileId = nil
                session.enqueueTask(canceled)
            }
        } catch {
            errorMessage = userMessage(error)
            load(taskId: selectedTask.id, session: session)
        }
    }

    func viewCompletedDetail() {
        completedRemoteTaskId = lastCompletedRemoteTaskId
    }

    private func pollUntilSettled(task: MeetingTask, session: AppSession) async throws {
        isPolling = true
        defer { isPolling = false }
        var current = task
        var transientFailureRetries = 0
        while current.status == .processing {
            try await Task.sleep(nanoseconds: 1_000_000_000)
            if Task.isCancelled { return }
            var detail = try await session.loadTaskDetail(for: current)
            detail = try await detailWithResultIfFinished(detail, session: session)
            if detail.task.clientStatus == .failed {
                let message = detail.task.errorMessage ?? "处理失败"
                if transientFailureRetries < Self.transientRetryLimit, message.isTransientProcessingFailure {
                    transientFailureRetries += 1
                    let remoteTaskId = detail.task.id
                    session.markTaskAutoRetrying(current.id, remoteTaskId: remoteTaskId, progressPercent: detail.task.progressPercent)
                    var retryCandidate = current
                    retryCandidate.remoteTaskId = remoteTaskId
                    retryCandidate.progressPercent = max(detail.task.progressPercent, 8)
                    retryCandidate.progressLabel = "处理暂未完成，正在继续"
                    retryCandidate.progressStage = "auto_retrying"
                    do {
                        let retryDetail = try await session.retryRemoteTask(retryCandidate)
                        current = updateLocalTask(from: retryDetail, fallback: retryCandidate, session: session)
                        continue
                    } catch {
                        if userMessage(error).isTransientProcessingFailure {
                            session.markTaskWaitingRetry(current.id, message: "处理暂未完成，稍后可继续")
                        }
                        throw error
                    }
                }
            }
            current = updateLocalTask(from: detail, fallback: current, session: session)
            if current.status == .waitingRetry {
                throw APIError.httpStatus(503, current.errorMessage ?? current.progressLabel ?? "处理暂未完成，稍后可继续")
            }
        }
    }

    private func detailWithResultIfFinished(_ detail: RemoteTaskDetail, session: AppSession) async throws -> RemoteTaskDetail {
        guard detail.task.clientStatus == .finished, detail.result == nil else { return detail }
        let result = try await session.loadTaskResult(taskId: detail.task.id)
        return RemoteTaskDetail(task: detail.task, file: detail.file, result: result)
    }

    @discardableResult
    private func updateLocalTask(from detail: RemoteTaskDetail, fallback: MeetingTask, session: AppSession) -> MeetingTask {
        var clientTask = detail.task.toClientTask(
            localFilePath: fallback.localFilePath,
            fallbackId: fallback.id,
            fallbackCreatedAtMillis: fallback.createdAtMillis,
            fallbackSizeLabel: fallback.sizeLabel,
            fallbackScheduleId: fallback.scheduleId,
            fallbackScheduleNote: fallback.scheduleNote,
            fallbackRecognitionLanguage: fallback.recognitionLanguage,
            fallbackMissingAudioRanges: fallback.missingAudioRanges
        )
        clientTask.liveTranscripts = fallback.liveTranscripts
        if clientTask.status == .finished {
            let temporaryRemoteTaskId = detail.result?.remoteTaskId ?? clientTask.remoteTaskId ?? fallback.remoteTaskId
            clientTask.remoteTaskId = nil
            clientTask.fileId = nil
            clientTask.knowledgeScope = .local
            clientTask.progressPercent = 100
            clientTask.progressLabel = "处理完成"
            clientTask.progressStage = "finished"
            if let temporaryRemoteTaskId {
                Task { await session.deleteTemporaryRemoteProcessingTask(temporaryRemoteTaskId) }
            }
        }
        session.enqueueTask(clientTask)
        selectedTask = clientTask
        switch clientTask.status {
        case .finished:
            session.markTaskFinished(clientTask.id)
            lastCompletedRemoteTaskId = clientTask.id
            if var result = detail.result {
                result.remoteTaskId = nil
                session.indexLocalResult(task: clientTask, result: result)
            }
        case .failed:
            session.markTaskFailed(clientTask.id, message: clientTask.errorMessage ?? "处理失败")
        case .waitingRetry:
            session.markTaskWaitingRetry(clientTask.id, message: clientTask.errorMessage)
        default:
            break
        }
        return clientTask
    }

    private func startNextWaitingTaskIfNeeded(session: AppSession, router: AppRouter?) async {
        guard let next = session.taskQueue.nextAutoProcessingTask else { return }
        selectedTask = next
        await startOrContinue(session: session, router: router)
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    private func isAuthFailure(_ error: Error, message: String) -> Bool {
        if case let APIError.httpStatus(status, _) = error, status == 401 {
            return true
        }
        return ["请先登录", "登录已失效", "登录已过期", "登录凭证"].contains { message.contains($0) }
    }

    private func isQuotaExhausted(_ error: Error, message: String) -> Bool {
        if case let APIError.httpStatus(status, _) = error, status == 402 {
            return true
        }
        return ["额度已耗尽", "转写时长不足", "知识库问答次数不足"].contains { message.contains($0) }
    }
}

private extension String {
    var isTransientProcessingFailure: Bool {
        let clean = trimmingCharacters(in: .whitespacesAndNewlines)
        if clean.isEmpty { return true }
        let lower = clean.lowercased()
        let fatalHints = ["额度", "登录", "冻结", "文件不存在", "文件暂不可用", "本地文件", "任务已终止"]
        if fatalHints.contains(where: { clean.contains($0) }) {
            return false
        }
        let transientHints = ["语音识别暂时失败", "智能处理暂时失败", "服务器维护", "请求超时", "稍后重试"]
        return transientHints.contains { clean.contains($0) } ||
            lower.contains("timeout") ||
            lower.contains("timed out") ||
            lower.contains("connection") ||
            lower.contains("unavailable") ||
            lower.contains("bad gateway") ||
            lower.contains("gateway timeout") ||
            lower.contains("internal server error")
    }
}
