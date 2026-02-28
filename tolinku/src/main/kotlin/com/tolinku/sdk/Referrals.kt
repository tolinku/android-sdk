package com.tolinku.sdk

import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Handles referral operations through the Tolinku API.
 */
class Referrals internal constructor(private val client: TolinkuClient) {

    /**
     * Create a new referral for a user.
     *
     * @param userId The unique identifier of the referring user.
     * @param metadata Optional metadata to attach to the referral.
     * @param userName Optional display name for the referring user.
     * @return The created [CreateReferralResponse].
     * @throws IllegalArgumentException if userId is blank.
     * @throws TolinkuException if the request fails.
     */
    suspend fun create(
        userId: String,
        metadata: Map<String, Any>? = null,
        userName: String? = null
    ): CreateReferralResponse {
        require(userId.isNotBlank()) { "userId must not be blank" }

        val body = JSONObject().apply {
            put("user_id", userId)
            if (metadata != null) {
                put("metadata", JSONObject(metadata))
            }
            if (userName != null) {
                put("user_name", userName)
            }
        }

        val response = client.post("/v1/api/referral/create", body)
        return CreateReferralResponse.fromJson(response)
    }

    /**
     * Get information about an existing referral.
     *
     * @param code The referral code to look up.
     * @return The [ReferralDetails].
     * @throws IllegalArgumentException if code is blank.
     * @throws TolinkuException if the request fails or the referral is not found.
     */
    suspend fun get(code: String): ReferralDetails {
        require(code.isNotBlank()) { "code must not be blank" }

        val encoded = URLEncoder.encode(code, "UTF-8")
        val response = client.get("/v1/api/referral/$encoded")
        return ReferralDetails.fromJson(response)
    }

    /**
     * Complete a referral when a referred user takes the qualifying action.
     *
     * @param code The referral code being completed.
     * @param referredUserId The unique identifier of the referred user.
     * @param milestone Optional milestone name for the completion.
     * @param referredUserName Optional display name for the referred user.
     * @return The [ReferralCompletion] result.
     * @throws IllegalArgumentException if code or referredUserId is blank.
     * @throws TolinkuException if the request fails.
     */
    suspend fun complete(
        code: String,
        referredUserId: String,
        milestone: String? = null,
        referredUserName: String? = null
    ): ReferralCompletion {
        require(code.isNotBlank()) { "code must not be blank" }
        require(referredUserId.isNotBlank()) { "referredUserId must not be blank" }

        val body = JSONObject().apply {
            put("referral_code", code)
            put("referred_user_id", referredUserId)
            if (milestone != null) {
                put("milestone", milestone)
            }
            if (referredUserName != null) {
                put("referred_user_name", referredUserName)
            }
        }

        val response = client.post("/v1/api/referral/complete", body)
        return ReferralCompletion.fromJson(response)
    }

    /**
     * Update the milestone for a referral.
     *
     * @param code The referral code.
     * @param milestone The milestone name to set.
     * @return The [MilestoneResult].
     * @throws IllegalArgumentException if code or milestone is blank.
     * @throws TolinkuException if the request fails.
     */
    suspend fun milestone(code: String, milestone: String): MilestoneResult {
        require(code.isNotBlank()) { "code must not be blank" }
        require(milestone.isNotBlank()) { "milestone must not be blank" }

        val body = JSONObject().apply {
            put("referral_code", code)
            put("milestone", milestone)
        }

        val response = client.post("/v1/api/referral/milestone", body)
        return MilestoneResult.fromJson(response)
    }

    /**
     * Fetch the referral leaderboard.
     *
     * @param limit Maximum number of entries to return. Defaults to server-side default if null.
     * @return A list of [LeaderboardEntry] items.
     * @throws TolinkuException if the request fails.
     */
    suspend fun leaderboard(limit: Int? = null): List<LeaderboardEntry> {
        val params = if (limit != null) mapOf("limit" to limit.toString()) else null
        val response = client.get("/v1/api/referral/leaderboard", params)

        val entries = mutableListOf<LeaderboardEntry>()
        val dataArray = response.optJSONArray("leaderboard")
        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                entries.add(LeaderboardEntry.fromJson(dataArray.getJSONObject(i)))
            }
        }
        return entries
    }

    /**
     * Claim a reward for a referral.
     *
     * @param code The referral code to claim a reward for.
     * @return The [RewardClaim] result.
     * @throws IllegalArgumentException if code is blank.
     * @throws TolinkuException if the request fails.
     */
    suspend fun claimReward(code: String): RewardClaim {
        require(code.isNotBlank()) { "code must not be blank" }

        val body = JSONObject().apply {
            put("referral_code", code)
        }

        val response = client.post("/v1/api/referral/claim-reward", body)
        return RewardClaim.fromJson(response)
    }

    // -----------------------------------------------------------------------
    // Java-friendly callback wrappers
    // -----------------------------------------------------------------------

    /**
     * Java-friendly callback wrapper for [create].
     */
    @JvmOverloads
    fun createAsync(
        userId: String,
        metadata: Map<String, Any>? = null,
        userName: String? = null,
        callback: TolinkuCallback<CreateReferralResponse>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { create(userId, metadata, userName) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [get].
     */
    fun getAsync(code: String, callback: TolinkuCallback<ReferralDetails>) {
        Tolinku.scope.launch {
            val result = runCatching { get(code) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [complete].
     */
    @JvmOverloads
    fun completeAsync(
        code: String,
        referredUserId: String,
        milestone: String? = null,
        referredUserName: String? = null,
        callback: TolinkuCallback<ReferralCompletion>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { complete(code, referredUserId, milestone, referredUserName) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [milestone].
     */
    fun milestoneAsync(
        code: String,
        milestone: String,
        callback: TolinkuCallback<MilestoneResult>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { milestone(code, milestone) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [leaderboard].
     */
    @JvmOverloads
    fun leaderboardAsync(
        limit: Int? = null,
        callback: TolinkuCallback<List<LeaderboardEntry>>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { leaderboard(limit) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [claimReward].
     */
    fun claimRewardAsync(code: String, callback: TolinkuCallback<RewardClaim>) {
        Tolinku.scope.launch {
            val result = runCatching { claimReward(code) }
            callback.onResult(result)
        }
    }
}
