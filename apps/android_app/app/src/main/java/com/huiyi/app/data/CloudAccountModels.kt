package com.huiyi.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CloudUser(
    val userId: String,
    val username: String,
    val displayName: String,
    val phone: String = "",
    val accessToken: String = "",
    val expiresAtMillis: Long = 0L
) {
    val tokenValid: Boolean
        get() = accessToken.isNotBlank() && (expiresAtMillis <= 0L || expiresAtMillis > System.currentTimeMillis() + 60_000L)
}

data class CloudTaskItem(
    val task: MeetingTask,
    val result: MeetingProcessingResult?
)

data class CloudBootstrap(
    val userId: String,
    val tasks: List<CloudTaskItem>,
    val schedules: List<ScheduledMeeting>
)

data class MembershipPlan(
    val id: String,
    val name: String,
    val priceCents: Int,
    val price: Double,
    val transcriptionMinutes: Int,
    val hours: Double,
    val knowledgeQa: Int,
    val enabled: Boolean = true
)

data class MembershipAddon(
    val id: String,
    val name: String,
    val unit: String,
    val priceCents: Int,
    val price: Double,
    val enabled: Boolean = true
)

data class MembershipProfile(
    val active: Boolean = false,
    val accountStatus: String = "normal",
    val loading: Boolean = false,
    val planId: String = "none",
    val planName: String = "无套餐",
    val expiresAt: String? = null,
    val periodMonth: String = "",
    val transcriptionMinutesTotal: Int = 0,
    val transcriptionMinutesUsed: Int = 0,
    val knowledgeQaTotal: Int = 0,
    val knowledgeQaUsed: Int = 0,
    val paymentEnabled: Boolean = false,
    val plans: List<MembershipPlan> = emptyList(),
    val addons: List<MembershipAddon> = emptyList()
) {
    val frozen: Boolean
        get() = accountStatus == "frozen"

    val transcriptionMinutesRemaining: Int
        get() = (transcriptionMinutesTotal - transcriptionMinutesUsed).coerceAtLeast(0)

    val knowledgeQaRemaining: Int
        get() = (knowledgeQaTotal - knowledgeQaUsed).coerceAtLeast(0)
}

data class PaymentOrder(
    val id: String,
    val productType: String,
    val planId: String,
    val planName: String,
    val addonId: String,
    val addonName: String,
    val productName: String,
    val transcriptionMinutes: Int,
    val amount: Double,
    val status: String,
    val channel: String,
    val createdAt: String = "",
    val paidAt: String = "",
    val updatedAt: String = "",
    val channelNo: String = ""
)

data class AlipayPaymentOrder(
    val order: PaymentOrder,
    val orderString: String,
    val payUrl: String = "",
    val paymentMode: String = "app"
)

class SharedPrefsCloudUserStore(context: Context) {
    private val preferences = context.getSharedPreferences("huixiao_cloud_user", Context.MODE_PRIVATE)

    fun loadUser(): CloudUser? {
        val raw = preferences.getString(KEY_USER, null) ?: return null
        return runCatching {
            val item = JSONObject(raw)
            val user = CloudUser(
                userId = item.getString("userId"),
                username = item.getString("username"),
                displayName = item.optString("displayName", item.getString("username")),
                phone = item.optString("phone"),
                accessToken = item.optString("accessToken"),
                expiresAtMillis = item.optLong("expiresAtMillis", 0L)
            )
            user.takeIf { it.tokenValid }
        }.getOrNull()
    }

    fun saveUser(user: CloudUser) {
        preferences.edit().putString(
            KEY_USER,
            JSONObject()
                .put("userId", user.userId)
                .put("username", user.username)
                .put("displayName", user.displayName)
                .put("phone", user.phone)
                .put("accessToken", user.accessToken)
                .put("expiresAtMillis", user.expiresAtMillis)
                .toString()
        ).apply()
    }

    fun clear() {
        preferences.edit().remove(KEY_USER).apply()
    }

    fun loadCloudSyncEnabled(): Boolean {
        return preferences.getBoolean(KEY_CLOUD_SYNC_ENABLED, false)
    }

    fun saveCloudSyncEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_CLOUD_SYNC_ENABLED, enabled).apply()
    }

    fun loadProfileName(userId: String? = loadUser()?.userId): String {
        val accountKey = userId?.let { "$KEY_PROFILE_NAME:$it" }
        return accountKey?.let { preferences.getString(it, null) }?.takeIf { it.isNotBlank() }
            ?: preferences.getString(KEY_PROFILE_NAME, null)?.takeIf { it.isNotBlank() }
            ?: loadUser()?.displayName
            ?: ""
    }

    fun saveProfileName(name: String, userId: String? = loadUser()?.userId) {
        val editor = preferences.edit()
        val clean = name.trim()
        if (userId != null) {
            editor.putString("$KEY_PROFILE_NAME:$userId", clean)
        } else {
            editor.putString(KEY_PROFILE_NAME, clean)
        }
        editor.apply()
    }

    fun loadDeviceId(): String {
        val existing = preferences.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing
        val created = "android-${UUID.randomUUID()}"
        preferences.edit().putString(KEY_DEVICE_ID, created).apply()
        return created
    }

    private companion object {
        const val KEY_USER = "user"
        const val KEY_CLOUD_SYNC_ENABLED = "cloud_sync_enabled"
        const val KEY_DEVICE_ID = "device_id"
        const val KEY_PROFILE_NAME = "profile_name"
    }
}

class SharedPrefsMembershipProfileStore(context: Context) {
    private val preferences = context.getSharedPreferences("huixiao_membership_profile", Context.MODE_PRIVATE)

    fun load(userId: String?): MembershipProfile? {
        if (userId.isNullOrBlank()) return null
        val raw = preferences.getString(key(userId), null) ?: return null
        return runCatching { JSONObject(raw).toCachedMembershipProfile() }.getOrNull()
    }

    fun save(userId: String?, profile: MembershipProfile) {
        if (userId.isNullOrBlank()) return
        preferences.edit().putString(key(userId), profile.copy(loading = false).toJson().toString()).apply()
    }

    fun clear(userId: String?) {
        if (!userId.isNullOrBlank()) {
            preferences.edit().remove(key(userId)).apply()
        }
    }

    private fun key(userId: String): String = "membership_$userId"

    private fun MembershipProfile.toJson(): JSONObject {
        return JSONObject()
            .put("active", active)
            .put("account_status", accountStatus)
            .put("plan_id", planId)
            .put("plan_name", planName)
            .put("expires_at", expiresAt ?: "")
            .put("period_month", periodMonth)
            .put("transcription_minutes_total", transcriptionMinutesTotal)
            .put("transcription_minutes_used", transcriptionMinutesUsed)
            .put("knowledge_qa_total", knowledgeQaTotal)
            .put("knowledge_qa_used", knowledgeQaUsed)
            .put("payment_enabled", paymentEnabled)
            .put("plans", JSONArray().also { array ->
                plans.forEach { plan ->
                    array.put(
                        JSONObject()
                            .put("id", plan.id)
                            .put("name", plan.name)
                            .put("price_cents", plan.priceCents)
                            .put("price", plan.price)
                            .put("transcription_minutes", plan.transcriptionMinutes)
                            .put("hours", plan.hours)
                            .put("knowledge_qa", plan.knowledgeQa)
                            .put("enabled", plan.enabled)
                    )
                }
            })
            .put("addons", JSONArray().also { array ->
                addons.forEach { addon ->
                    array.put(
                        JSONObject()
                            .put("id", addon.id)
                            .put("name", addon.name)
                            .put("unit", addon.unit)
                            .put("price_cents", addon.priceCents)
                            .put("price", addon.price)
                            .put("enabled", addon.enabled)
                    )
                }
            })
    }

    private fun JSONObject.toCachedMembershipProfile(): MembershipProfile {
        return MembershipProfile(
            active = optBoolean("active", false),
            accountStatus = optString("account_status", "normal").ifBlank { "normal" },
            planId = optString("plan_id", "none").ifBlank { "none" },
            planName = optString("plan_name", "无套餐").ifBlank { "无套餐" },
            expiresAt = optString("expires_at").takeIf { it.isNotBlank() },
            periodMonth = optString("period_month"),
            transcriptionMinutesTotal = optInt("transcription_minutes_total", 0),
            transcriptionMinutesUsed = optInt("transcription_minutes_used", 0),
            knowledgeQaTotal = optInt("knowledge_qa_total", 0),
            knowledgeQaUsed = optInt("knowledge_qa_used", 0),
            paymentEnabled = optBoolean("payment_enabled", false),
            plans = optJSONArray("plans").orEmpty().mapObjects { item ->
                MembershipPlan(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    priceCents = item.optInt("price_cents", 0),
                    price = item.optDouble("price", 0.0),
                    transcriptionMinutes = item.optInt("transcription_minutes", 0),
                    hours = item.optDouble("hours", item.optInt("transcription_minutes", 0) / 60.0),
                    knowledgeQa = item.optInt("knowledge_qa", 0),
                    enabled = item.optBoolean("enabled", true)
                )
            },
            addons = optJSONArray("addons").orEmpty().mapObjects { item ->
                MembershipAddon(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    unit = item.optString("unit", "hour"),
                    priceCents = item.optInt("price_cents", 0),
                    price = item.optDouble("price", 0.0),
                    enabled = item.optBoolean("enabled", true)
                )
            }
        )
    }
}

private fun JSONArray?.orEmpty(): JSONArray = this ?: JSONArray()

private fun <T> JSONArray.mapObjects(block: (JSONObject) -> T): List<T> {
    val items = mutableListOf<T>()
    for (index in 0 until length()) {
        optJSONObject(index)?.let { items.add(block(it)) }
    }
    return items
}
