package com.tolinku.sdk

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for model parsing and JSON extension helpers.
 */
class ModelsTest {

    // -----------------------------------------------------------------------
    // JSONObject.NULL handling
    // -----------------------------------------------------------------------

    @Test
    fun `toMap converts JSONObject NULL to actual null`() {
        val json = JSONObject().apply {
            put("name", "test")
            put("description", JSONObject.NULL)
        }

        val map = json.toMap()
        assertEquals("test", map["name"])
        assertNull("JSONObject.NULL should become null, not the string 'null'", map["description"])
    }

    @Test
    fun `toList converts JSONObject NULL to actual null`() {
        val array = JSONArray().apply {
            put("value")
            put(JSONObject.NULL)
            put(42)
        }

        val list = array.toList()
        assertEquals(3, list.size)
        assertEquals("value", list[0])
        assertNull("JSONObject.NULL should become null in lists", list[1])
        assertEquals(42, list[2])
    }

    @Test
    fun `toMap handles nested objects`() {
        val nested = JSONObject().apply {
            put("inner_key", "inner_value")
        }
        val json = JSONObject().apply {
            put("outer_key", nested)
        }

        val map = json.toMap()
        @Suppress("UNCHECKED_CAST")
        val innerMap = map["outer_key"] as Map<String, Any?>
        assertEquals("inner_value", innerMap["inner_key"])
    }

    @Test
    fun `toMap handles nested arrays`() {
        val array = JSONArray().apply {
            put("a")
            put("b")
        }
        val json = JSONObject().apply {
            put("items", array)
        }

        val map = json.toMap()
        @Suppress("UNCHECKED_CAST")
        val items = map["items"] as List<Any?>
        assertEquals(listOf("a", "b"), items)
    }

    // -----------------------------------------------------------------------
    // optStringOrNull
    // -----------------------------------------------------------------------

    @Test
    fun `optStringOrNull returns string when present`() {
        val json = JSONObject().apply { put("key", "value") }
        assertEquals("value", json.optStringOrNull("key"))
    }

    @Test
    fun `optStringOrNull returns null when key missing`() {
        val json = JSONObject()
        assertNull(json.optStringOrNull("missing"))
    }

    @Test
    fun `optStringOrNull returns null when value is JSONObject NULL`() {
        val json = JSONObject().apply { put("key", JSONObject.NULL) }
        assertNull(json.optStringOrNull("key"))
    }

    // -----------------------------------------------------------------------
    // CreateReferralResponse model parsing
    // -----------------------------------------------------------------------

    @Test
    fun `CreateReferralResponse fromJson parses all fields`() {
        val json = JSONObject().apply {
            put("referral_code", "REF123")
            put("referral_url", "https://example.com/ref/REF123")
            put("referral_id", "doc_456")
        }

        val response = CreateReferralResponse.fromJson(json)
        assertEquals("REF123", response.referralCode)
        assertEquals("https://example.com/ref/REF123", response.referralUrl)
        assertEquals("doc_456", response.referralId)
    }

    @Test
    fun `CreateReferralResponse fromJson handles null referral_url`() {
        val json = JSONObject().apply {
            put("referral_code", "REF789")
            put("referral_id", "doc_xyz")
        }

        val response = CreateReferralResponse.fromJson(json)
        assertEquals("REF789", response.referralCode)
        assertNull(response.referralUrl)
    }

    // -----------------------------------------------------------------------
    // DeferredLink model parsing
    // -----------------------------------------------------------------------

    @Test
    fun `DeferredLink fromJson parses all fields`() {
        val json = JSONObject().apply {
            put("deep_link_path", "/product/123")
            put("appspace_id", "app_456")
            put("referrer_id", "user_abc")
            put("referral_code", "REF_SUMMER")
        }

        val link = DeferredLink.fromJson(json)
        assertEquals("/product/123", link.deepLinkPath)
        assertEquals("app_456", link.appspaceId)
        assertEquals("user_abc", link.referrerId)
        assertEquals("REF_SUMMER", link.referralCode)
    }

    // -----------------------------------------------------------------------
    // LeaderboardEntry model parsing
    // -----------------------------------------------------------------------

    @Test
    fun `LeaderboardEntry fromJson parses correctly`() {
        val json = JSONObject().apply {
            put("referrer_id", "user_1")
            put("referrer_name", "Bob")
            put("total", 15)
            put("completed", 8)
            put("pending", 7)
            put("total_reward_value", "150")
        }

        val entry = LeaderboardEntry.fromJson(json)
        assertEquals("user_1", entry.referrerId)
        assertEquals("Bob", entry.referrerName)
        assertEquals(15, entry.total)
        assertEquals(8, entry.completed)
        assertEquals(7, entry.pending)
        assertEquals("150", entry.totalRewardValue)
    }

    // -----------------------------------------------------------------------
    // ReferralCompletion, MilestoneResult, RewardClaim model parsing
    // -----------------------------------------------------------------------

    @Test
    fun `ReferralCompletion fromJson parses correctly`() {
        val json = JSONObject().apply {
            put("referral", JSONObject().apply {
                put("id", "ref_001")
                put("referrer_id", "user_1")
                put("referred_user_id", "user_2")
                put("status", "completed")
                put("milestone", "signup")
                put("completed_at", "2025-01-01T00:00:00Z")
            })
        }

        val result = ReferralCompletion.fromJson(json)
        assertEquals("ref_001", result.referral.id)
        assertEquals("user_1", result.referral.referrerId)
        assertEquals("completed", result.referral.status)
    }

    @Test
    fun `MilestoneResult fromJson parses correctly`() {
        val json = JSONObject().apply {
            put("referral", JSONObject().apply {
                put("id", "ref_001")
                put("referral_code", "CODE1")
                put("milestone", "purchased")
                put("status", "active")
            })
        }

        val result = MilestoneResult.fromJson(json)
        assertEquals("ref_001", result.referral.id)
        assertEquals("CODE1", result.referral.referralCode)
        assertEquals("purchased", result.referral.milestone)
    }

    @Test
    fun `RewardClaim fromJson parses correctly`() {
        val json = JSONObject().apply {
            put("success", true)
            put("referral_code", "CODE1")
            put("reward_claimed", true)
        }

        val result = RewardClaim.fromJson(json)
        assertTrue(result.success)
        assertEquals("CODE1", result.referralCode)
        assertTrue(result.rewardClaimed)
    }

    // -----------------------------------------------------------------------
    // Message model parsing
    // -----------------------------------------------------------------------

    @Test
    fun `Message fromJson parses all fields`() {
        val json = JSONObject().apply {
            put("id", "msg_001")
            put("name", "welcome_msg")
            put("title", "Welcome!")
            put("body", "Thanks for joining.")
            put("trigger", "welcome")
            put("trigger_value", "first_open")
            put("background_color", "#ffffff")
            put("priority", 10)
            put("dismiss_days", 7)
        }

        val message = Message.fromJson(json)
        assertEquals("msg_001", message.id)
        assertEquals("welcome_msg", message.name)
        assertEquals("Welcome!", message.title)
        assertEquals("Thanks for joining.", message.body)
        assertEquals("welcome", message.trigger)
        assertEquals("first_open", message.triggerValue)
        assertEquals("#ffffff", message.backgroundColor)
        assertEquals(10, message.priority)
        assertEquals(7, message.dismissDays)
    }
}
