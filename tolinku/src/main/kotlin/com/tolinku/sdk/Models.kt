package com.tolinku.sdk

import org.json.JSONArray
import org.json.JSONObject

/**
 * Response from creating a referral.
 */
data class CreateReferralResponse(
    val referralCode: String,
    val referralUrl: String?,
    val referralId: String
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): CreateReferralResponse {
            return CreateReferralResponse(
                referralCode = json.optString("referral_code", ""),
                referralUrl = json.optStringOrNull("referral_url"),
                referralId = json.optString("referral_id", "")
            )
        }
    }
}

/**
 * Details of an existing referral, returned by the GET endpoint.
 */
data class ReferralDetails(
    val referrerId: String,
    val status: String,
    val milestone: String?,
    val milestoneHistory: List<Any?>,
    val rewardType: String?,
    val rewardValue: String?,
    val rewardClaimed: Boolean,
    val createdAt: String?
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ReferralDetails {
            val history = json.optJSONArray("milestone_history")?.toList() ?: emptyList()
            return ReferralDetails(
                referrerId = json.optString("referrer_id", ""),
                status = json.optString("status", ""),
                milestone = json.optStringOrNull("milestone"),
                milestoneHistory = history,
                rewardType = json.optStringOrNull("reward_type"),
                rewardValue = json.optStringOrNull("reward_value"),
                rewardClaimed = json.optBoolean("reward_claimed", false),
                createdAt = json.optStringOrNull("created_at")
            )
        }
    }
}

/**
 * Represents a single entry on the referral leaderboard.
 */
data class LeaderboardEntry(
    val referrerId: String,
    val referrerName: String?,
    val total: Int,
    val completed: Int,
    val pending: Int,
    val totalRewardValue: String?
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): LeaderboardEntry {
            return LeaderboardEntry(
                referrerId = json.optString("referrer_id", ""),
                referrerName = json.optStringOrNull("referrer_name"),
                total = json.optInt("total", 0),
                completed = json.optInt("completed", 0),
                pending = json.optInt("pending", 0),
                totalRewardValue = json.optStringOrNull("total_reward_value")
            )
        }
    }
}

/**
 * Represents a deferred deep link that has been claimed.
 */
data class DeferredLink(
    val deepLinkPath: String,
    val appspaceId: String,
    val referrerId: String?,
    val referralCode: String?
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): DeferredLink {
            return DeferredLink(
                deepLinkPath = json.optString("deep_link_path", ""),
                appspaceId = json.optString("appspace_id", ""),
                referrerId = json.optStringOrNull("referrer_id"),
                referralCode = json.optStringOrNull("referral_code")
            )
        }
    }
}

/**
 * Represents an in-app message fetched from the Tolinku platform.
 */
data class Message(
    val id: String,
    val name: String,
    val title: String?,
    val body: String?,
    val trigger: String?,
    val triggerValue: String?,
    val backgroundColor: String?,
    val priority: Int,
    val dismissDays: Int?,
    val maxImpressions: Int?,
    val minIntervalHours: Int?
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): Message {
            return Message(
                id = json.optString("id", ""),
                name = json.optString("name", ""),
                title = json.optStringOrNull("title"),
                body = json.optStringOrNull("body"),
                trigger = json.optStringOrNull("trigger"),
                triggerValue = json.optStringOrNull("trigger_value"),
                backgroundColor = json.optStringOrNull("background_color"),
                priority = json.optInt("priority", 0),
                dismissDays = if (json.has("dismiss_days") && !json.isNull("dismiss_days")) json.getInt("dismiss_days") else null,
                maxImpressions = if (json.has("max_impressions") && !json.isNull("max_impressions")) json.getInt("max_impressions") else null,
                minIntervalHours = if (json.has("min_interval_hours") && !json.isNull("min_interval_hours")) json.getInt("min_interval_hours") else null
            )
        }
    }
}

/**
 * The nested referral object returned from the complete endpoint.
 */
data class CompletedReferral(
    val id: String,
    val referrerId: String,
    val referredUserId: String,
    val status: String,
    val milestone: String?,
    val completedAt: String?,
    val rewardType: String?,
    val rewardValue: String?
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): CompletedReferral {
            return CompletedReferral(
                id = json.optString("id", ""),
                referrerId = json.optString("referrer_id", ""),
                referredUserId = json.optString("referred_user_id", ""),
                status = json.optString("status", ""),
                milestone = json.optStringOrNull("milestone"),
                completedAt = json.optStringOrNull("completed_at"),
                rewardType = json.optStringOrNull("reward_type"),
                rewardValue = json.optStringOrNull("reward_value")
            )
        }
    }
}

/**
 * Response from completing a referral; wraps the nested referral object.
 */
data class ReferralCompletion(
    val referral: CompletedReferral
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): ReferralCompletion {
            return ReferralCompletion(
                referral = CompletedReferral.fromJson(json.getJSONObject("referral"))
            )
        }
    }
}

/**
 * The nested referral object returned from the milestone endpoint.
 */
data class MilestoneReferral(
    val id: String,
    val referralCode: String,
    val milestone: String,
    val status: String,
    val rewardType: String?,
    val rewardValue: String?
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): MilestoneReferral {
            return MilestoneReferral(
                id = json.optString("id", ""),
                referralCode = json.optString("referral_code", ""),
                milestone = json.optString("milestone", ""),
                status = json.optString("status", ""),
                rewardType = json.optStringOrNull("reward_type"),
                rewardValue = json.optStringOrNull("reward_value")
            )
        }
    }
}

/**
 * Response from updating a milestone; wraps the nested referral object.
 */
data class MilestoneResult(
    val referral: MilestoneReferral
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): MilestoneResult {
            return MilestoneResult(
                referral = MilestoneReferral.fromJson(json.getJSONObject("referral"))
            )
        }
    }
}

/**
 * Result of a reward claim request.
 */
data class RewardClaim(
    val success: Boolean,
    val referralCode: String,
    val rewardClaimed: Boolean
) {
    companion object {
        @JvmStatic
        fun fromJson(json: JSONObject): RewardClaim {
            return RewardClaim(
                success = json.optBoolean("success", false),
                referralCode = json.optString("referral_code", ""),
                rewardClaimed = json.optBoolean("reward_claimed", false)
            )
        }
    }
}

// Extension helpers for JSON parsing

internal fun JSONObject.optStringOrNull(key: String): String? {
    return if (has(key) && !isNull(key)) getString(key) else null
}

internal fun JSONObject.toMap(): Map<String, Any?> {
    val map = mutableMapOf<String, Any?>()
    keys().forEach { key ->
        val value = get(key)
        map[key] = when (value) {
            is JSONObject -> value.toMap()
            is JSONArray -> value.toList()
            JSONObject.NULL -> null
            else -> value
        }
    }
    return map
}

internal fun JSONArray.toList(): List<Any?> {
    val list = mutableListOf<Any?>()
    for (i in 0 until length()) {
        val value = get(i)
        list.add(
            when (value) {
                is JSONObject -> value.toMap()
                is JSONArray -> value.toList()
                JSONObject.NULL -> null
                else -> value
            }
        )
    }
    return list
}
