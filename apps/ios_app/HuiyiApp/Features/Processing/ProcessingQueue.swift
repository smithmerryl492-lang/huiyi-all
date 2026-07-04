import Foundation

struct ProcessingQueue: Codable, Equatable, Sendable {
    private(set) var tasks: [MeetingTask] = []
    private(set) var activeTaskId: String?
    private(set) var queuedRetryTaskIds: [String] = []

    private enum CodingKeys: String, CodingKey {
        case tasks
        case activeTaskId
        case queuedRetryTaskIds
    }

    init() {}

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        tasks = (try? container.decode([MeetingTask].self, forKey: .tasks)) ?? []
        activeTaskId = try? container.decodeIfPresent(String.self, forKey: .activeTaskId)
        queuedRetryTaskIds = (try? container.decode([String].self, forKey: .queuedRetryTaskIds)) ?? []
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encode(tasks, forKey: .tasks)
        try container.encodeIfPresent(activeTaskId, forKey: .activeTaskId)
        try container.encode(queuedRetryTaskIds, forKey: .queuedRetryTaskIds)
    }

    var activeTask: MeetingTask? {
        guard let activeTaskId else { return nil }
        return tasks.first { $0.id == activeTaskId }
    }

    var waitingTasks: [MeetingTask] {
        tasks.filter { $0.status == .waitingProcess && $0.progressStage != "waiting_retry" }
    }

    func visibleQueuedTasks(excluding selectedTaskId: String?) -> [MeetingTask] {
        let visible = (queuedRetryTasks + waitingTasks).reduce(into: [MeetingTask]()) { result, task in
            if !result.contains(where: { $0.id == task.id }) {
                result.append(task)
            }
        }
        return visible.filter { task in
            task.id != selectedTaskId && task.id != activeTaskId
        }
    }

    var failedTasks: [MeetingTask] {
        tasks.filter { $0.status == .failed }
    }

    var queuedRetryTasks: [MeetingTask] {
        queuedRetryTaskIds.compactMap { taskId in
            tasks.first { task in
                task.id == taskId && (task.status == .failed || task.status == .waitingRetry)
            }
        }
    }

    var nextAutoProcessingTask: MeetingTask? {
        queuedRetryTasks.first ?? waitingTasks.first
    }

    var hasActiveProcessing: Bool {
        activeTask?.status == .processing
    }

    mutating func restore(_ restoredTasks: [MeetingTask]) {
        tasks = restoredTasks.map { task in
            var copy = task
            if copy.status == .processing {
                copy.status = .waitingRetry
                copy.progressLabel = copy.progressLabel ?? "可继续处理"
            }
            return copy
        }
        queuedRetryTaskIds = queuedRetryTaskIds.filter { queuedTaskId in
            tasks.contains { task in
                task.id == queuedTaskId && (task.status == .failed || task.status == .waitingRetry)
            }
        }
        activeTaskId = tasks.first { $0.status == .processing }?.id
    }

    mutating func upsert(_ task: MeetingTask) {
        if let index = tasks.firstIndex(where: { $0.id == task.id }) {
            tasks[index] = task
        } else {
            tasks.append(task)
        }
        if task.status == .processing {
            activeTaskId = task.id
        } else if activeTaskId == task.id && task.status != .processing {
            activeTaskId = nil
        }
    }

    mutating func enqueue(_ task: MeetingTask) {
        var copy = task
        if copy.status == .localSaved {
            copy.status = .waitingProcess
        }
        upsert(copy)
    }

    mutating func open(_ taskId: String) -> QueueOpenDecision {
        if hasActiveProcessing, activeTaskId != taskId {
            queueRetryIfNeeded(taskId)
            moveToBackOfWaitingQueue(taskId)
            return .showActiveProcessing(activeTaskId: activeTaskId!)
        }
        return .showSelected(taskId: taskId, autoStart: false)
    }

    mutating func markProcessing(_ taskId: String, retryRemote: Bool = false) {
        update(taskId) { task in
            let wasCanceled = task.status == .canceled
            task.status = .processing
            task.errorMessage = nil
            if wasCanceled {
                task.remoteTaskId = nil
            }
            if retryRemote {
                task.progressPercent = 0
                task.progressLabel = task.progressStage == "waiting_retry" ? "准备继续处理" : "准备重新处理"
                task.progressStage = "retrying"
            } else if wasCanceled {
                task.progressPercent = 0
                task.progressLabel = "等待上传"
                task.progressStage = "uploading"
            } else if task.remoteTaskId != nil {
                task.progressLabel = task.progressLabel ?? "恢复处理状态"
                task.progressStage = task.progressStage ?? "resuming"
            } else {
                task.progressPercent = 0
                task.progressLabel = "等待上传"
                task.progressStage = "uploading"
            }
        }
        queuedRetryTaskIds.removeAll { $0 == taskId }
        activeTaskId = taskId
    }

    mutating func markFailed(_ taskId: String, message: String) {
        update(taskId) { task in
            task.status = .failed
            task.errorMessage = message
        }
        if activeTaskId == taskId {
            activeTaskId = nil
        }
    }

    mutating func markWaitingRetry(_ taskId: String, message: String?) {
        update(taskId) { task in
            task.status = .waitingRetry
            task.errorMessage = message
            task.progressLabel = "可继续处理"
            task.progressStage = "waiting_retry"
        }
        if activeTaskId == taskId {
            activeTaskId = nil
        }
    }

    mutating func markAutoRetrying(_ taskId: String, remoteTaskId: String?, progressPercent: Double) {
        update(taskId) { task in
            task.status = .processing
            if let remoteTaskId {
                task.remoteTaskId = remoteTaskId
            }
            task.progressPercent = max(progressPercent, 8)
            task.progressLabel = "处理暂未完成，正在继续"
            task.progressStage = "auto_retrying"
        }
        activeTaskId = taskId
    }

    mutating func markWaitingProcess(_ taskId: String, message: String?, label: String, stage: String) {
        update(taskId) { task in
            task.status = .waitingProcess
            task.errorMessage = message
            task.progressLabel = label
            task.progressStage = stage
        }
        if activeTaskId == taskId {
            activeTaskId = nil
        }
    }

    mutating func markCanceled(_ taskId: String) {
        update(taskId) { task in
            task.status = .canceled
            task.errorMessage = "任务已终止"
            task.remoteTaskId = nil
            task.progressLabel = "已终止"
            task.progressStage = "canceled"
        }
        if activeTaskId == taskId {
            activeTaskId = nil
        }
    }

    mutating func markFinished(_ taskId: String) {
        update(taskId) { task in
            task.status = .finished
            task.progressPercent = 100
        }
        queuedRetryTaskIds.removeAll { $0 == taskId }
        if activeTaskId == taskId {
            activeTaskId = nil
        }
    }

    mutating func remove(_ taskId: String) {
        tasks.removeAll { $0.id == taskId }
        queuedRetryTaskIds.removeAll { $0 == taskId }
        if activeTaskId == taskId {
            activeTaskId = nil
        }
    }

    private mutating func update(_ taskId: String, change: (inout MeetingTask) -> Void) {
        guard let index = tasks.firstIndex(where: { $0.id == taskId }) else { return }
        change(&tasks[index])
    }

    private mutating func moveToBackOfWaitingQueue(_ taskId: String) {
        guard let index = tasks.firstIndex(where: { $0.id == taskId }) else { return }
        let task = tasks.remove(at: index)
        tasks.append(task)
    }

    private mutating func queueRetryIfNeeded(_ taskId: String) {
        guard let task = tasks.first(where: { $0.id == taskId }),
              task.status == .failed || task.status == .waitingRetry,
              !queuedRetryTaskIds.contains(taskId) else {
            return
        }
        queuedRetryTaskIds.append(taskId)
    }
}

enum QueueOpenDecision: Equatable, Sendable {
    case showSelected(taskId: String, autoStart: Bool)
    case showActiveProcessing(activeTaskId: String)
}
