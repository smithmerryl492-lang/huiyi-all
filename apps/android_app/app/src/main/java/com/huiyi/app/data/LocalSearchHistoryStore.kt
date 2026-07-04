package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray

interface LocalSearchHistoryStore {
    fun loadKeywords(): List<String>
    fun saveKeywords(keywords: List<String>)
}

class SharedPrefsLocalSearchHistoryStore(context: Context) : LocalSearchHistoryStore {
    private val prefs = context.getSharedPreferences("huixiao_local_search_history", Context.MODE_PRIVATE)

    override fun loadKeywords(): List<String> {
        val raw = prefs.getString(KEYWORDS, "[]").orEmpty()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length())
                .mapNotNull { index -> array.optString(index).trim().takeIf { it.isNotBlank() } }
                .distinctBy { it.lowercase() }
                .take(MAX_RECENT_SEARCHES)
        }.getOrDefault(emptyList())
    }

    override fun saveKeywords(keywords: List<String>) {
        val array = JSONArray()
        keywords
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .take(MAX_RECENT_SEARCHES)
            .forEach { array.put(it) }
        prefs.edit().putString(KEYWORDS, array.toString()).apply()
    }

    private companion object {
        const val KEYWORDS = "keywords"
        const val MAX_RECENT_SEARCHES = 10
    }
}
