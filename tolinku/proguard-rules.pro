# Tolinku SDK ProGuard rules

# Keep OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep class okio.** { *; }

# Keep Tolinku SDK public API classes and their public/protected members
-keep class com.tolinku.sdk.Tolinku { public *; }
-keep class com.tolinku.sdk.Analytics { public *; }
-keep class com.tolinku.sdk.Referrals { public *; }
-keep class com.tolinku.sdk.DeferredDeepLink { public *; }
-keep class com.tolinku.sdk.Messages { public *; }
-keep class com.tolinku.sdk.TolinkuException { *; }
-keep class com.tolinku.sdk.TolinkuClient { public *; }
-keep interface com.tolinku.sdk.TolinkuCallback { *; }

# Keep data model classes (needed for JSON deserialization and consumer access)
-keep class com.tolinku.sdk.CreateReferralResponse { *; }
-keep class com.tolinku.sdk.ReferralDetails { *; }
-keep class com.tolinku.sdk.CompletedReferral { *; }
-keep class com.tolinku.sdk.MilestoneReferral { *; }
-keep class com.tolinku.sdk.LeaderboardEntry { *; }
-keep class com.tolinku.sdk.DeferredLink { *; }
-keep class com.tolinku.sdk.Message { *; }
-keep class com.tolinku.sdk.ReferralCompletion { *; }
-keep class com.tolinku.sdk.MilestoneResult { *; }
-keep class com.tolinku.sdk.RewardClaim { *; }
