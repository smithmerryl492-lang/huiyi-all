package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface SpeakerProfileStore {
    fun loadProfiles(userId: String?): List<SpeakerProfile>
    fun saveProfiles(userId: String?, profiles: List<SpeakerProfile>)
    fun clearProfiles(userId: String?)
}

class SharedPrefsSpeakerProfileStore(context: Context) : SpeakerProfileStore {
    private val preferences = context.getSharedPreferences("huixiao_speaker_profiles", Context.MODE_PRIVATE)

    override fun loadProfiles(userId: String?): List<SpeakerProfile> {
        val key = profilesKey(userId) ?: return emptyList()
        val raw = preferences.getString(key, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) {
                    val item = array.getJSONObject(index)
                    val id = item.optString("id").trim()
                    if (id.isBlank()) continue
                    add(
                        SpeakerProfile(
                            id = id,
                            displayName = item.optString("displayName").ifBlank { "未命名声纹" },
                            sampleCount = item.optInt("sampleCount", 0),
                            active = item.optBoolean("active", true),
                            updatedAt = item.optString("updatedAt")
                        )
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun saveProfiles(userId: String?, profiles: List<SpeakerProfile>) {
        val key = profilesKey(userId) ?: return
        val array = JSONArray()
        profiles.forEach { profile ->
            array.put(
                JSONObject()
                    .put("id", profile.id)
                    .put("displayName", profile.displayName)
                    .put("sampleCount", profile.sampleCount)
                    .put("active", profile.active)
                    .put("updatedAt", profile.updatedAt)
            )
        }
        preferences.edit().putString(key, array.toString()).apply()
    }

    override fun clearProfiles(userId: String?) {
        val key = profilesKey(userId) ?: return
        preferences.edit().remove(key).apply()
    }

    private fun profilesKey(userId: String?): String? {
        val clean = userId?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "$KEY_PROFILES_PREFIX$clean"
    }

    private companion object {
        const val KEY_PROFILES_PREFIX = "profiles:"
    }
}
