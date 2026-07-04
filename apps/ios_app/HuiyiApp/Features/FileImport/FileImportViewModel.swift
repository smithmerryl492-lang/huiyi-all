import Foundation

@MainActor
final class FileImportViewModel: ObservableObject {
    @Published var selectedLanguage: RecognitionLanguage = .chinese
    @Published var importedFile: ImportedLocalFile?
    @Published var createdTask: MeetingTask?
    @Published var isImporting = false
    @Published var errorMessage: String?

    private let importService: DocumentImportService
    private let settingsStore: SettingsStore

    init(importService: DocumentImportService = DocumentImportService(), settingsStore: SettingsStore = SettingsStore()) {
        self.importService = importService
        self.settingsStore = settingsStore
        selectedLanguage = settingsStore.preferredRecognitionLanguage
    }

    func handlePickedFile(_ url: URL, session: AppSession) {
        isImporting = true
        errorMessage = nil
        defer { isImporting = false }
        do {
            settingsStore.preferredRecognitionLanguage = selectedLanguage
            let imported = try importService.copyIntoSandbox(sourceURL: url)
            importedFile = imported
            let task = makeTask(from: imported)
            createdTask = task
            session.enqueueTask(task)
        } catch {
            errorMessage = userMessage(error)
        }
    }

    private func makeTask(from imported: ImportedLocalFile) -> MeetingTask {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        return MeetingTask(
            id: UUID().uuidString,
            remoteTaskId: nil,
            fileId: nil,
            title: imported.displayName,
            source: .importFile,
            status: .waitingProcess,
            localFilePath: imported.localFileURL.path,
            createdAtMillis: now,
            sizeLabel: imported.sizeLabel,
            errorMessage: nil,
            progressPercent: 0,
            progressLabel: "待处理",
            progressStage: "waiting",
            confirmed: false,
            knowledgeScope: .local,
            isPrivate: false,
            deviceId: settingsStore.deviceId,
            scheduleId: nil,
            scheduleNote: nil,
            recognitionLanguage: selectedLanguage,
            liveTranscripts: []
        )
    }

    private func userMessage(_ error: Error) -> String {
        if let localized = (error as? LocalizedError)?.errorDescription, !localized.isEmpty {
            return localized
        }
        return error.localizedDescription
    }
}
