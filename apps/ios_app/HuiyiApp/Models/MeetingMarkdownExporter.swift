import Foundation

enum MeetingMarkdownExporter {
    static func markdown(title: String, result: MeetingProcessingResult, includeTranscript: Bool) -> String {
        var lines: [String] = ["# \(title)", ""]
        if let participants = result.participants, !participants.isEmpty {
            lines.append("- 参会人：\(participants)")
        }
        if !result.tags.isEmpty {
            lines.append("- 标签：\(result.tags.joined(separator: "、"))")
        }
        if !result.generatedAt.isEmpty {
            lines.append("- 生成时间：\(result.generatedAt)")
        }
        lines.append("")
        lines += ["## 摘要", result.summary, ""]
        if !result.topics.isEmpty {
            lines += ["## 议题"]
            lines += result.topics.map { topic in
                let body = topic.summary.isEmpty ? "" : "：\(topic.summary)"
                let source = [topic.sourceTimestamp ?? "无时间", topic.source.isEmpty ? "无来源" : topic.source].joined(separator: " ")
                return "- \(topic.title)\(body)（\(source)）"
            }
            lines.append("")
        }
        if !result.decisions.isEmpty {
            lines += ["## 决策"]
            lines += result.decisions.map { "- \($0)" }
            lines.append("")
        }
        if !result.todos.isEmpty {
            lines += ["## 待办"]
            lines += result.todos.map { todo in
                let assignee = todo.assignee?.isEmpty == false ? todo.assignee! : "待补充"
                let due = todo.dueAt?.isEmpty == false ? todo.dueAt! : "待补充"
                let source = [todo.sourceTimestamp ?? "无时间", todo.source.isEmpty ? "无来源" : todo.source].joined(separator: " ")
                return "- \(todo.title)（负责人：\(assignee)；截止：\(due)；来源：\(source)）"
            }
            lines.append("")
        }
        if !result.risks.isEmpty {
            lines += ["## 风险"]
            lines += result.risks.map { risk in
                let level = risk.level.isEmpty ? "" : "（\(risk.level)）"
                let body = risk.description.isEmpty ? risk.recommendation : risk.description
                let source = [risk.sourceTimestamp ?? "无时间", risk.source.isEmpty ? "无来源" : risk.source].joined(separator: " ")
                return "- \(risk.title)\(level)：\(body)（\(source)）"
            }
            lines.append("")
        }
        if includeTranscript, !result.transcripts.isEmpty {
            lines += ["## 转写原文"]
            lines += result.transcripts.map { "- \($0.timeRangeLabel) \($0.speaker)：\($0.text)" }
        }
        return lines.joined(separator: "\n")
    }
}
