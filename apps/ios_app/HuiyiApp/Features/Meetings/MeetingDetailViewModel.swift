import Foundation

enum MeetingDetailTab: String, CaseIterable, Identifiable {
    case info
    case summary
    case transcript
    case topics
    case decisions
    case todos
    case risks

    var id: String { rawValue }

    var title: String {
        switch self {
        case .info: return "详情"
        case .summary: return "摘要"
        case .transcript: return "内容"
        case .topics: return "议题"
        case .decisions: return "决策"
        case .todos: return "待办"
        case .risks: return "风险"
        }
    }
}

@MainActor
final class MeetingDetailViewModel: ObservableObject {
    @Published private(set) var detail: RemoteTaskDetail?
    @Published var selectedTab: MeetingDetailTab = .summary
    @Published var transcriptQuery = ""
    @Published var isLoading = false
    @Published var isDeleting = false
    @Published var isRegenerating = false
    @Published var isExporting = false
    @Published var exportedMarkdown: String?
    @Published var exportStatusMessage: String?
    @Published var savingTodoId: String?
    @Published var transcriptNeedsRegeneration = false
    @Published var activeAudioSegmentId: Int?
    @Published var audioStatusMessage: String?
    @Published var errorMessage: String?
    private let audioFileStore = AudioFileStore()
    private let audioSegmentPlayer = AudioSegmentPlayer()

    var taskTitle: String {
        detail?.task.title ?? "会议详情"
    }

    var result: MeetingProcessingResult? {
        detail?.result
    }

    var speakerIdentities: [SpeakerIdentity] {
        result?.transcripts.speakerIdentities ?? []
    }

    var filteredTranscriptContexts: [TranscriptSegmentContext] {
        guard let transcripts = result?.transcripts else { return [] }
        let query = transcriptQuery.trimmingCharacters(in: .whitespacesAndNewlines)
        return transcripts.enumerated().compactMap { index, segment in
            let matches = query.isEmpty ||
                segment.text.localizedCaseInsensitiveContains(query) ||
                segment.speaker.localizedCaseInsensitiveContains(query) ||
                segment.timestamp.localizedCaseInsensitiveContains(query)
            return matches ? TranscriptSegmentContext(index: index, segment: segment) : nil
        }
    }

    func transcriptContext(for todo: TodoItem) -> TranscriptSegmentContext? {
        guard let transcripts = result?.transcripts, !transcripts.isEmpty else { return nil }
        if let index = todo.sourceSegmentIndex, transcripts.indices.contains(index) {
            return TranscriptSegmentContext(index: index, segment: transcripts[index])
        }
        if let sourceTimestamp = todo.sourceTimestamp?.trimmingCharacters(in: .whitespacesAndNewlines), !sourceTimestamp.isEmpty,
           let match = transcripts.enumerated().first(where: { _, segment in
               segment.timestamp == sourceTimestamp || segment.timeRangeLabel == sourceTimestamp
           }) {
            return TranscriptSegmentContext(index: match.offset, segment: match.element)
        }
        let cleanSource = todo.source.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanSource.isEmpty,
           let match = transcripts.enumerated().first(where: { _, segment in
               cleanSource.localizedCaseInsensitiveContains(segment.text) ||
                   segment.text.localizedCaseInsensitiveContains(cleanSource)
           }) {
            return TranscriptSegmentContext(index: match.offset, segment: match.element)
        }
        return nil
    }

    func transcriptContext(for source: KnowledgeSource) -> TranscriptSegmentContext? {
        guard let transcripts = result?.transcripts, !transcripts.isEmpty else { return nil }
        if let startMs = source.startMs {
            let match = transcripts.enumerated().compactMap { index, segment -> (Int, Int64)? in
                guard let segmentStart = segment.startMs else { return nil }
                return (index, abs(segmentStart - startMs))
            }
            .min { $0.1 < $1.1 }
            if let match, transcripts.indices.contains(match.0) {
                return TranscriptSegmentContext(index: match.0, segment: transcripts[match.0])
            }
        }
        if let timestamp = source.timestamp?.trimmingCharacters(in: .whitespacesAndNewlines), !timestamp.isEmpty,
           let match = transcripts.enumerated().first(where: { _, segment in
               segment.timestamp == timestamp || segment.timeRangeLabel.contains(timestamp)
           }) {
            return TranscriptSegmentContext(index: match.offset, segment: match.element)
        }
        let sourceText = String(source.text.prefix(24))
        if !sourceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
           let match = transcripts.enumerated().first(where: { _, segment in
               segment.text.contains(sourceText) || source.text.contains(String(segment.text.prefix(24)))
           }) {
            return TranscriptSegmentContext(index: match.offset, segment: match.element)
        }
        return TranscriptSegmentContext(index: 0, segment: transcripts[0])
    }

    func transcriptContext(timestamp: String? = nil, text: String = "") -> TranscriptSegmentContext? {
        guard let transcripts = result?.transcripts, !transcripts.isEmpty else { return nil }
        if let timestamp = timestamp?.trimmingCharacters(in: .whitespacesAndNewlines), !timestamp.isEmpty,
           let match = transcripts.enumerated().first(where: { _, segment in
               segment.timestamp == timestamp || segment.timeRangeLabel == timestamp
           }) {
            return TranscriptSegmentContext(index: match.offset, segment: match.element)
        }
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        if !cleanText.isEmpty {
            if let match = transcripts.enumerated().first(where: { _, segment in
                cleanText.contains(segment.timestamp) || cleanText.contains(segment.timeRangeLabel)
            }) {
                return TranscriptSegmentContext(index: match.offset, segment: match.element)
            }
            let sourceText = String(cleanText.prefix(24))
            if !sourceText.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               let match = transcripts.enumerated().first(where: { _, segment in
                   segment.text.contains(sourceText) || cleanText.contains(String(segment.text.prefix(24)))
               }) {
                return TranscriptSegmentContext(index: match.offset, segment: match.element)
            }
        }
        return nil
    }

    func todoContext(for todo: TodoItem) -> TodoContextItem? {
        guard let detail else { return nil }
        return TodoContextItem(
            todo: todo,
            taskId: detail.task.clientTaskId ?? detail.task.id,
            remoteTaskId: detail.task.syncScope == .localProcessing ? nil : detail.task.id,
            meetingTitle: detail.task.title,
            result: detail.result
        )
    }

    func load(taskId: String?, session: AppSession) async {
        guard let taskId, !taskId.isEmpty else {
            errorMessage = "缺少会议 ID。"
            return
        }
        isLoading = true
        errorMessage = nil
        defer { isLoading = false }
        do {
            detail = try await session.loadTaskDetail(taskId: taskId)
            transcriptNeedsRegeneration = session.transcriptNeedsRegeneration(taskId: taskId)
        } catch {
            if let local = session.localTaskDetail(taskId: taskId) {
                detail = local
                transcriptNeedsRegeneration = session.transcriptNeedsRegeneration(taskId: taskId)
                errorMessage = nil
            } else {
                errorMessage = userMessage(error)
            }
        }
    }

    func deleteCurrentTask(session: AppSession) async -> Bool {
        guard let detail else { return false }
        isDeleting = true
        errorMessage = nil
        defer { isDeleting = false }
        do {
            try await session.deleteMeetingTask(detail.task)
            return true
        } catch {
            errorMessage = userMessage(error)
            return false
        }
    }

    func updateTitle(_ title: String, session: AppSession) async {
        guard let detail else { return }
        let clean = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...80).contains(clean.count) else {
            errorMessage = "会议标题需为 2-80 个字符"
            return
        }
        errorMessage = nil
        do {
            let updated = try await session.updateMeetingTask(taskId: detail.task.id, request: TaskUpdateRequest(title: clean))
            var nextResult = detail.result
            if var result = detail.result {
                result.todos = result.todos.map { todo in
                    var copy = todo
                    copy.meetingTitle = clean
                    return copy
                }
                nextResult = try await session.updateMeetingResult(taskId: detail.task.id, request: ResultUpdateRequest(todos: result.todos))
            }
            self.detail = RemoteTaskDetail(task: updated, file: detail.file, result: nextResult)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func confirmMeeting(session: AppSession) async {
        guard let detail else { return }
        errorMessage = nil
        do {
            let updated = try await session.confirmMeeting(taskId: detail.task.id)
            self.detail = RemoteTaskDetail(task: updated, file: detail.file, result: detail.result)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func updateMeetingInfo(
        title: String,
        participants: String,
        tags: [String],
        isPrivate: Bool,
        knowledgeScope: KnowledgeIndexScope,
        session: AppSession
    ) async {
        guard let detail, let result = detail.result else { return }
        let cleanTitle = title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard (2...80).contains(cleanTitle.count) else {
            errorMessage = "会议标题需为 2-80 个字符"
            return
        }
        errorMessage = nil
        do {
            let updatedTask = try await session.updateMeetingTask(
                taskId: detail.task.id,
                request: TaskUpdateRequest(
                    title: cleanTitle,
                    isPrivate: isPrivate,
                    knowledgeScope: knowledgeScope
                )
            )
            let updatedResult = try await session.updateMeetingResult(
                taskId: detail.task.id,
                request: ResultUpdateRequest(participants: participants, tags: tags)
            )
            self.detail = RemoteTaskDetail(task: updatedTask, file: detail.file, result: updatedResult)
        } catch {
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: result)
            errorMessage = userMessage(error)
        }
    }

    func updateSummary(_ summary: String, session: AppSession) async {
        guard let detail else { return }
        let clean = summary.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else {
            errorMessage = "纪要不能为空"
            return
        }
        errorMessage = nil
        do {
            let updated = try await session.updateMeetingResult(taskId: detail.task.id, request: ResultUpdateRequest(summary: clean))
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func regenerateMinutes(session: AppSession) async {
        guard let detail, let result = detail.result else { return }
        guard result.transcripts.contains(where: { !$0.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty }) else {
            errorMessage = "会议内容为空，无法更新纪要"
            return
        }
        isRegenerating = true
        errorMessage = nil
        defer { isRegenerating = false }
        do {
            let regenerated = try await session.regenerateMinutes(taskId: detail.task.id, transcripts: result.transcripts)
            var merged = regenerated
            merged.todos = Self.mergeRegeneratedTodos(previousTodos: result.todos, regeneratedTodos: regenerated.todos)
            if merged.todos != regenerated.todos {
                let saved = try await session.updateMeetingResult(
                    taskId: detail.task.id,
                    request: ResultUpdateRequest(
                        summary: merged.summary,
                        topics: merged.topics,
                        decisions: merged.decisions,
                        todos: merged.todos,
                        risks: merged.risks,
                        transcripts: merged.transcripts
                    )
                )
                self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: saved)
            } else {
                self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: merged)
            }
            transcriptNeedsRegeneration = false
            session.clearTranscriptEdited(taskId: detail.task.clientTaskId ?? detail.task.id)
            session.clearTranscriptEdited(taskId: detail.task.id)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func exportMarkdown(includeTranscript: Bool, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else { return false }
        isExporting = true
        exportStatusMessage = "正在准备 TXT 文件"
        errorMessage = nil
        defer {
            isExporting = false
            exportStatusMessage = nil
        }
        exportedMarkdown = MeetingMarkdownExporter.markdown(
            title: detail.task.title,
            result: result,
            includeTranscript: includeTranscript
        )
        return true
    }

    func toggleTodo(_ todo: TodoItem, session: AppSession) async {
        guard let detail, let result = detail.result else { return }
        savingTodoId = todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        var nextTodos = result.todos
        guard let index = nextTodos.firstIndex(where: { $0.id == todo.id }) else { return }
        nextTodos[index].done.toggle()
        if nextTodos[index].done {
            nextTodos[index].status = "done"
            nextTodos[index].completedAtMillis = Int64(Date().timeIntervalSince1970 * 1000)
        } else {
            let hasAssignee = nextTodos[index].assignee?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false
            nextTodos[index].status = hasAssignee ? "todo" : "pending_confirm"
            nextTodos[index].completedAt = nil
            nextTodos[index].completedAtMillis = nil
        }
        nextTodos[index].lockField("status")
        do {
            let updated = try await session.updateMeetingResult(
                taskId: detail.task.id,
                request: ResultUpdateRequest(todos: nextTodos)
            )
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func startTodo(_ todo: TodoItem, session: AppSession) async {
        guard let detail, let result = detail.result else { return }
        guard todo.assignee?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false else {
            errorMessage = "请先补全负责人"
            return
        }
        savingTodoId = todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        var nextTodos = result.todos
        guard let index = nextTodos.firstIndex(where: { $0.id == todo.id }) else { return }
        nextTodos[index].status = "in_progress"
        nextTodos[index].done = false
        nextTodos[index].lockField("status")
        do {
            let updated = try await session.updateMeetingResult(
                taskId: detail.task.id,
                request: ResultUpdateRequest(todos: nextTodos)
            )
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    func updateTodo(_ todo: TodoItem, draft: TodoEditDraft, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else { return false }
        savingTodoId = todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        var nextTodos = result.todos
        guard let index = nextTodos.firstIndex(where: { $0.id == todo.id }) else { return false }
        let cleanTitle = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty else {
            errorMessage = "任务标题不能为空"
            return false
        }
        guard cleanTitle.count <= 100 else {
            errorMessage = "任务标题不能超过 100 个字符"
            return false
        }
        let cleanAssignee = draft.assignee.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDueAt = draft.dueAt.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDescription = draft.description.trimmingCharacters(in: .whitespacesAndNewlines)
        let previousTodo = nextTodos[index]
        nextTodos[index].appendLockedFields(
            from: previousTodo,
            title: cleanTitle,
            assignee: cleanAssignee,
            dueAt: cleanDueAt,
            priority: draft.priority,
            description: cleanDescription,
            status: draft.status
        )
        nextTodos[index].title = cleanTitle
        nextTodos[index].assignee = cleanAssignee.isEmpty ? nil : cleanAssignee
        nextTodos[index].dueAt = cleanDueAt.isEmpty ? nil : cleanDueAt
        nextTodos[index].dueAtMillis = TodoDueParser.parseMillis(cleanDueAt)
        nextTodos[index].priority = draft.priority.normalizedTodoPriority
        nextTodos[index].description = cleanDescription
        nextTodos[index].status = draft.status
        nextTodos[index].done = draft.status == "done"
        if nextTodos[index].done && nextTodos[index].completedAtMillis == nil {
            nextTodos[index].completedAtMillis = Int64(Date().timeIntervalSince1970 * 1000)
            nextTodos[index].completedAt = Self.dateTimeLabel(Date())
        } else if !nextTodos[index].done {
            nextTodos[index].completedAt = nil
            nextTodos[index].completedAtMillis = nil
        }
        do {
            let updated = try await session.updateMeetingResult(
                taskId: detail.task.id,
                request: ResultUpdateRequest(todos: nextTodos)
            )
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
            return true
        } catch {
            errorMessage = userMessage(error)
            return false
        }
    }

    func deleteTodo(_ todo: TodoItem, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else { return false }
        savingTodoId = todo.id
        errorMessage = nil
        defer { savingTodoId = nil }
        var nextTodos = result.todos
        guard let index = nextTodos.firstIndex(where: { $0.id == todo.id }) else { return false }
        nextTodos[index].status = "canceled"
        nextTodos[index].done = false
        nextTodos[index].lockField("status")
        do {
            let updated = try await session.updateMeetingResult(
                taskId: detail.task.id,
                request: ResultUpdateRequest(todos: nextTodos)
            )
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
            return true
        } catch {
            errorMessage = userMessage(error)
            return false
        }
    }

    func createManualTodo(_ draft: TodoEditDraft, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else {
            errorMessage = "当前会议无法补充待办"
            return false
        }
        let cleanTitle = draft.title.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTitle.isEmpty else {
            errorMessage = "任务标题不能为空"
            return false
        }
        guard cleanTitle.count <= 100 else {
            errorMessage = "任务标题不能超过 100 个字符"
            return false
        }
        let cleanAssignee = draft.assignee.trimmingCharacters(in: .whitespacesAndNewlines)
        let cleanDueAt = draft.dueAt.trimmingCharacters(in: .whitespacesAndNewlines)
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        let nextStatus = cleanAssignee.isEmpty && draft.status != "done" && draft.status != "canceled" ? "pending_confirm" : draft.status
        let manualTodo = TodoItem(
            id: "todo-manual-\(now)-\(UUID().uuidString.prefix(8))",
            title: cleanTitle,
            source: "手动补充",
            done: nextStatus == "done",
            sourceTimestamp: nil,
            meetingId: detail.task.id,
            meetingTitle: detail.task.title,
            description: draft.description.trimmingCharacters(in: .whitespacesAndNewlines),
            assignee: cleanAssignee.isEmpty ? nil : cleanAssignee,
            assigneeId: nil,
            dueAt: cleanDueAt.isEmpty ? nil : cleanDueAt,
            dueAtMillis: TodoDueParser.parseMillis(cleanDueAt),
            priority: draft.priority.normalizedTodoPriority,
            status: nextStatus,
            completedAt: nextStatus == "done" ? Self.dateTimeLabel(Date()) : nil,
            completedAtMillis: nextStatus == "done" ? now : nil,
            sourceSegmentIndex: nil,
            lockedFields: ["manual", "title", "description", "assignee", "due", "priority", "status"]
        )
        errorMessage = nil
        do {
            let updated = try await session.updateMeetingResult(
                taskId: detail.task.id,
                request: ResultUpdateRequest(todos: result.todos + [manualTodo])
            )
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
            return true
        } catch {
            errorMessage = userMessage(error)
            return false
        }
    }

    func renameSpeaker(_ speaker: SpeakerIdentity, target: String, saveVoiceprint: Bool, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else {
            errorMessage = "当前会议无法保存说话人名称"
            return false
        }
        let cleanTarget = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !speaker.id.isEmpty, !cleanTarget.isEmpty else {
            errorMessage = "说话人不能为空"
            return false
        }
        guard speaker.displayName != cleanTarget else {
            errorMessage = "说话人名称未变化"
            return false
        }
        let targetSpeakerId = result.transcripts.speakerIdentities.first { $0.id != speaker.id && $0.displayName == cleanTarget }?.id ?? speaker.id
        let updatedTranscripts = result.transcripts.map { segment -> TranscriptSegment in
            guard segment.stableSpeakerId == speaker.id else { return segment }
            var copy = segment
            copy.speaker = cleanTarget
            copy.speakerId = targetSpeakerId
            return copy
        }
        let updatedTodos = result.todos.map { todo -> TodoItem in
            let matchesAssignee = todo.assigneeId == speaker.id || todo.assignee == speaker.displayName
            guard matchesAssignee else { return todo }
            var copy = todo
            copy.assignee = cleanTarget
            copy.assigneeId = targetSpeakerId
            return copy
        }
        let updated = await updateResult(
            ResultUpdateRequest(todos: updatedTodos, transcripts: updatedTranscripts),
            session: session
        )
        guard updated else { return false }
        if saveVoiceprint {
            do {
                let remoteTaskId = try await session.ensureRemoteTaskForVoiceprint(localTaskId: detail.task.clientTaskId ?? detail.task.id)
                try await session.enrollSpeakerProfileFromTask(
                    taskId: remoteTaskId,
                    speakerId: targetSpeakerId,
                    speakerName: cleanTarget,
                    displayName: cleanTarget
                )
            } catch {
                errorMessage = userMessage(error)
                return false
            }
        }
        return true
    }

    func updateSegmentSpeaker(index: Int, target: String, selectedSpeakerId: String?, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else {
            errorMessage = "当前会议无法修正"
            return false
        }
        let cleanTarget = target.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanTarget.isEmpty else {
            errorMessage = "说话人不能为空"
            return false
        }
        guard result.transcripts.indices.contains(index) else {
            errorMessage = "来源片段不存在"
            return false
        }
        let identities = result.transcripts.speakerIdentities
        let targetId = selectedSpeakerId
            .flatMap { selectedId in identities.first { $0.id == selectedId && $0.displayName == cleanTarget }?.id }
            ?? identities.first { $0.displayName == cleanTarget }?.id
            ?? speakerIdentityId(for: cleanTarget)
        var transcripts = result.transcripts
        if transcripts[index].stableSpeakerId == targetId && transcripts[index].speaker == cleanTarget {
            errorMessage = "说话人未变化"
            return false
        }
        transcripts[index].speaker = cleanTarget
        transcripts[index].speakerId = targetId
        let updated = await updateResult(ResultUpdateRequest(transcripts: transcripts), session: session)
        if updated {
            transcriptNeedsRegeneration = true
            session.markTranscriptEdited(taskId: detail.task.clientTaskId ?? detail.task.id)
        }
        return updated
    }

    func updateTranscriptSegmentText(index: Int, text: String, session: AppSession) async -> Bool {
        guard let detail, let result = detail.result else {
            errorMessage = "当前会议无法修正"
            return false
        }
        let cleanText = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !cleanText.isEmpty else {
            errorMessage = "原文不能为空"
            return false
        }
        guard result.transcripts.indices.contains(index) else {
            errorMessage = "来源片段不存在"
            return false
        }
        var transcripts = result.transcripts
        transcripts[index].text = cleanText
        let updated = await updateResult(ResultUpdateRequest(transcripts: transcripts), session: session)
        if updated {
            transcriptNeedsRegeneration = true
            session.markTranscriptEdited(taskId: detail.task.clientTaskId ?? detail.task.id)
        }
        return updated
    }

    func playAudioSegment(_ context: TranscriptSegmentContext, session: AppSession) async {
        guard let detail else { return }
        if activeAudioSegmentId == context.id {
            audioSegmentPlayer.stop()
            activeAudioSegmentId = nil
            audioStatusMessage = nil
            return
        }
        audioStatusMessage = "正在准备音频片段"
        do {
            let localSourcePath = detail.result?.sourceFilePath.trimmingCharacters(in: .whitespacesAndNewlines) ?? ""
            let url: URL
            if !localSourcePath.isEmpty, FileManager.default.fileExists(atPath: localSourcePath) {
                url = URL(fileURLWithPath: localSourcePath)
            } else {
                let audioTaskId = detail.task.id
                let cachedURL = try audioFileStore.cachedAudioURL(taskId: audioTaskId, originalName: detail.file?.originalName)
                if !FileManager.default.fileExists(atPath: cachedURL.path) {
                    let data = try await session.downloadTaskAudio(taskId: audioTaskId)
                    try data.write(to: cachedURL, options: [.atomic])
                }
                url = cachedURL
            }
            try audioSegmentPlayer.play(url: url, startMs: context.segment.startMs, endMs: context.segment.endMs) { [weak self] in
                Task { @MainActor in
                    self?.activeAudioSegmentId = nil
                    self?.audioStatusMessage = nil
                }
            }
            activeAudioSegmentId = context.id
            audioStatusMessage = "正在播放 \(context.segment.timeRangeLabel)"
        } catch {
            activeAudioSegmentId = nil
            audioStatusMessage = nil
            errorMessage = userMessage(error)
        }
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }

    private func updateResult(_ request: ResultUpdateRequest, session: AppSession) async -> Bool {
        guard let detail else { return false }
        errorMessage = nil
        do {
            let updated = try await session.updateMeetingResult(taskId: detail.task.id, request: request)
            self.detail = RemoteTaskDetail(task: detail.task, file: detail.file, result: updated)
            return true
        } catch {
            errorMessage = userMessage(error)
            return false
        }
    }

    private static func dateTimeLabel(_ date: Date) -> String {
        let formatter = DateFormatter()
        formatter.dateFormat = "yyyy-MM-dd HH:mm"
        return formatter.string(from: date)
    }

    private static func mergeRegeneratedTodos(previousTodos: [TodoItem], regeneratedTodos: [TodoItem]) -> [TodoItem] {
        let completedTodos = previousTodos.filter { $0.effectiveStatus == "done" }
        let canceledTodos = previousTodos.filter { $0.effectiveStatus == "canceled" }
        let manualTodos = previousTodos.filter { $0.lockedFields.contains("manual") }
        let preservedTodos = uniqueTodos(manualTodos + completedTodos + canceledTodos)
        guard !preservedTodos.isEmpty else { return regeneratedTodos }
        let activeGenerated = regeneratedTodos.filter { generated in
            !completedTodos.contains { $0.matchesCompletedRegeneratedTodo(generated) } &&
            !canceledTodos.contains { $0.matchesCompletedRegeneratedTodo(generated) }
        }
        return activeGenerated + preservedTodos
    }

    private static func uniqueTodos(_ todos: [TodoItem]) -> [TodoItem] {
        var seen: Set<String> = []
        return todos.filter { todo in
            guard !seen.contains(todo.id) else { return false }
            seen.insert(todo.id)
            return true
        }
    }
}

struct TranscriptSegmentContext: Identifiable, Equatable {
    let index: Int
    var segment: TranscriptSegment

    var id: Int { index }
}
