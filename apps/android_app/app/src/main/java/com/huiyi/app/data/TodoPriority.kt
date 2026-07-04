package com.huiyi.app.data

import java.util.Locale

const val TodoPriorityHigh = "high"
const val TodoPriorityMedium = "medium"
const val TodoPriorityLow = "low"

fun String?.normalizedTodoPriority(): String {
    val clean = this.orEmpty()
        .trim()
        .lowercase(Locale.ROOT)
        .replace(Regex("[\\s_\\-]+"), "")
    return when {
        clean in setOf(
            "urgent",
            "high",
            "p0",
            "p1",
            "top",
            "critical",
            "important",
            "紧急",
            "急",
            "重要",
            "高",
            "高优先",
            "高优先级"
        ) -> TodoPriorityHigh
        clean in setOf(
            "low",
            "p3",
            "p4",
            "minor",
            "optional",
            "deferred",
            "低",
            "低优先",
            "低优先级",
            "可延后",
            "不急"
        ) -> TodoPriorityLow
        else -> TodoPriorityMedium
    }
}

fun String?.todoPriorityWeight(): Int {
    return when (normalizedTodoPriority()) {
        TodoPriorityHigh -> 3
        TodoPriorityLow -> 1
        else -> 2
    }
}

fun String?.todoPriorityLabel(): String {
    return when (normalizedTodoPriority()) {
        TodoPriorityHigh -> "高优先级"
        TodoPriorityLow -> "低优先级"
        else -> "普通"
    }
}
