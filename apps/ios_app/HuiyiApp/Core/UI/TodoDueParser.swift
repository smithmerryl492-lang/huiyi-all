import Foundation

enum TodoDueParser {
    static func parseMillis(_ value: String) -> Int64? {
        let clean = value.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !clean.isEmpty else { return nil }
        if let relative = parseRelativeMillis(clean) {
            return relative
        }
        let patterns = ["yyyy-MM-dd HH:mm", "yyyy-MM-dd", "MM-dd HH:mm", "MM-dd"]
        for pattern in patterns {
            let formatter = DateFormatter()
            formatter.locale = Locale(identifier: "zh_CN")
            formatter.dateFormat = pattern
            if let date = formatter.date(from: clean) {
                var components = Calendar.current.dateComponents([.year, .month, .day, .hour, .minute], from: date)
                if pattern.hasPrefix("MM") {
                    components.year = Calendar.current.component(.year, from: Date())
                }
                if !pattern.contains("HH") {
                    components.hour = 23
                    components.minute = 59
                }
                components.second = 0
                guard let normalizedDate = Calendar.current.date(from: components) else { continue }
                return Int64(normalizedDate.timeIntervalSince1970 * 1000)
            }
        }
        return nil
    }

    private static func parseRelativeMillis(_ value: String) -> Int64? {
        var calendar = Calendar.current
        calendar.locale = Locale(identifier: "zh_CN")
        let now = Date()

        func endOfDay(_ date: Date) -> Int64? {
            var components = calendar.dateComponents([.year, .month, .day], from: date)
            components.hour = 23
            components.minute = 59
            components.second = 0
            guard let dueDate = calendar.date(from: components) else { return nil }
            return Int64(dueDate.timeIntervalSince1970 * 1000)
        }

        if value.contains("今天") {
            return endOfDay(now)
        }
        if value.contains("明天"), let date = calendar.date(byAdding: .day, value: 1, to: now) {
            return endOfDay(date)
        }
        if value.contains("后天"), let date = calendar.date(byAdding: .day, value: 2, to: now) {
            return endOfDay(date)
        }
        if value.contains("月底") || value.contains("本月底") {
            guard let range = calendar.range(of: .day, in: .month, for: now) else { return nil }
            var components = calendar.dateComponents([.year, .month], from: now)
            components.day = range.upperBound - 1
            guard let date = calendar.date(from: components) else { return nil }
            return endOfDay(date)
        }

        guard let weekdayText = value.nextWeekdayText else { return nil }
        let weekdayMap = ["一": 2, "二": 3, "三": 4, "四": 5, "五": 6, "六": 7, "日": 1, "天": 1]
        guard let targetWeekday = weekdayMap[weekdayText] else { return nil }
        let currentWeekday = calendar.component(.weekday, from: now)
        let mondayOffset = (2 - currentWeekday + 7) % 7
        let daysToNextMonday = mondayOffset == 0 ? 7 : mondayOffset
        let targetOffset = targetWeekday == 1 ? 6 : targetWeekday - 2
        guard let date = calendar.date(byAdding: .day, value: daysToNextMonday + targetOffset, to: now) else { return nil }
        return endOfDay(date)
    }
}

private extension String {
    var nextWeekdayText: String? {
        let pattern = "下周([一二三四五六日天])"
        guard let regex = try? NSRegularExpression(pattern: pattern),
              let match = regex.firstMatch(in: self, range: NSRange(startIndex..., in: self)),
              match.numberOfRanges > 1,
              let range = Range(match.range(at: 1), in: self) else {
            return nil
        }
        return String(self[range])
    }
}
