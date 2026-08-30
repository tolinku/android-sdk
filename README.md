# Tolinku Android SDK

[![Maven Central](https://img.shields.io/maven-central/v/com.tolinku/sdk.svg)](https://central.sonatype.com/artifact/com.tolinku/sdk)
[![API 24+](https://img.shields.io/badge/API-24%2B-brightgreen.svg)](https://developer.android.com/about/versions/nougat)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)

The official [Tolinku](https://tolinku.com) SDK for Android. Add deep linking, analytics, referral tracking, deferred deep links, and in-app messages to your Android app. Supports App Links out of the box.

## What is Tolinku?

[Tolinku](https://tolinku.com) is a deep linking platform for mobile and web apps. It handles Universal Links (iOS), App Links (Android), deferred deep linking, referral programs, analytics, and smart banners. Tolinku provides a complete toolkit for user acquisition, attribution, and engagement across platforms.

Get your API key at [tolinku.com](https://tolinku.com) and check out the [documentation](https://tolinku.com/docs) to get started.

## Installation

Add the dependency to your module-level `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.tolinku:sdk:0.3.0")
}
```

**Requirements:** Min SDK 24 (Android 7.0+)

## Quick Start

```kotlin
import com.tolinku.sdk.Tolinku

// Configure the SDK (typically in Application.onCreate)
Tolinku.configure(apiKey = "tolk_pub_your_api_key", context = this)

// Identify a user
Tolinku.setUserId("user_123")

// Track a custom event
Tolinku.track("purchase", properties = mapOf("plan" to "growth"))
```

## Features

### Analytics

Track custom events with automatic batching. Events are queued and sent in batches of 10, or every 5 seconds. Events are also flushed automatically when the app enters the background.

```kotlin
Tolinku.track("signup_completed", properties = mapOf(
    "source" to "landing_page",
    "trial" to true,
))

// Flush queued events immediately
Tolinku.analytics.flush()
```

All analytics methods are suspend functions. Java-friendly callback wrappers are also available:

```kotlin
Tolinku.analytics.trackAsync("signup_completed") { result ->
    result.onSuccess { println("Tracked") }
    result.onFailure { println("Error: ${it.message}") }
}
```

### Referrals

Create and manage referral programs with leaderboards and reward tracking.

```kotlin
val referrals = Tolinku.referrals

// Create a referral
val result = referrals.create(userId = "user_123", userName = "Alice")
val code = result.referralCode

// Look up a referral
val details = referrals.get(code)

// Complete a referral
val completion = referrals.complete(
    code = code,
    referredUserId = "user_456",
    referredUserName = "Bob",
)

// Update milestone
val milestone = referrals.milestone(code = code, milestone = "first_purchase")

// Claim reward
val reward = referrals.claimReward(code)

// Fetch leaderboard
val entries = referrals.leaderboard(limit = 10)
```

### Ecommerce

Track purchases, cart activity, and product events with built-in revenue analytics. Available on paid plans.

```kotlin
Tolinku.setUserId("user_123")

// Track a product view
Tolinku.ecommerce.viewItem(
    items = listOf(TolinkuItem(itemId = "sku_1", itemName = "T-Shirt", price = BigDecimal("24.99")))
)

// Track a purchase
Tolinku.ecommerce.purchase(
    transactionId = "order_456",
    revenue = BigDecimal("49.99"),
    currency = "USD",
    items = listOf(TolinkuItem(itemId = "sku_1", itemName = "T-Shirt", price = BigDecimal("24.99"), quantity = 2))
)

// Flush ecommerce events
Tolinku.ecommerce.flush()
```

The SDK supports 13 event types covering the full shopping journey. Money values use `BigDecimal` for precision. Cart IDs are managed automatically via `SharedPreferences` and cleared after purchase. Events auto-flush when the app enters the background via `ActivityLifecycleCallbacks`.

### Deferred Deep Links

Recover deep link context for users who installed your app after clicking a link. Deferred deep linking lets you route users to specific content even when the app was not installed at the time of the click.

```kotlin
val deferred = Tolinku.deferred

// Claim by referrer token
val link = deferred.claimByToken("abc123")
if (link != null) {
    println(link.deepLinkPath) // e.g. "/merchant/xyz"
}

// Claim by device signal matching. Timezone, language, screen size, pixel ratio and
// OS version are collected for you.
val link = deferred.claimBySignals(
    appspaceId = "64f0a1b2c3d4e5f60718",
    context = this,
)
```

On Android the Play Install Referrer is the deterministic mechanism: a Tolinku
link attaches a token to the store URL, Play keeps it through the install, and
this SDK reads it back on first launch. It names the exact click, survives for
days, and does not depend on the network the device was on. Device signals are
the fallback, and the only option on iOS, where no equivalent exists.

Prefer `claimDeferredLink()` over choosing a mechanism yourself:

```kotlin
// Referrer first, device signals as the fallback.
val link = Tolinku.deferred.claimDeferredLink(
    appspaceId = "64f0a1b2c3d4e5f60718",
    context = this,
)
link?.let { routeTo(it.deepLinkPath) }
```

Call it once on first launch. Calling again is safe, but a claim is consumed the
first time it succeeds, so a second call returns nothing.

`appspaceId` is your Appspace ID, not your subdomain or slug. Copy it from the dashboard
under **Integrate** or **Settings**. It looks like `64f0a1b2c3d4e5f60718`.


### In-App Messages

Display server-configured messages as modal overlays. Create and manage messages from the Tolinku dashboard without shipping app updates.

```kotlin
// Show the highest-priority message matching a trigger
Tolinku.messages.show(context = this, trigger = "milestone")

// With action and dismiss callbacks
Tolinku.messages.show(
    context = this,
    trigger = "milestone",
    onAction = { action -> println("Button tapped: $action") },
    onDismiss = { println("Message dismissed") },
)
```

You can also fetch and present messages manually:

```kotlin
val messages = Tolinku.messages.fetch(trigger = "milestone")
if (messages.isNotEmpty()) {
    val message = messages.first()
    val token = Tolinku.messages.renderToken(message.id)
    TolinkuMessagePresenter.show(this, message, token)
}
```

## Configuration Options

```kotlin
// Full configuration
Tolinku.configure(
    apiKey = "tolk_pub_your_api_key",     // Required. Your Tolinku publishable API key.
    baseUrl = "https://api.tolinku.com", // Optional. API base URL.
    context = this,                      // Optional. Application context for lifecycle events.
    debug = false,                       // Optional. Enable debug logging.
)

// Set user identity at any time
Tolinku.setUserId("user_123")

// Shut down the SDK when done
Tolinku.shutdown()
```

## API Reference

### `Tolinku`

| Method | Description |
|--------|-------------|
| `configure(apiKey, baseUrl?, context?, debug?)` | Initialize the SDK |
| `setUserId(userId)` | Set or clear the current user ID |
| `track(eventType, properties?)` | Track a custom event |
| `shutdown()` | Release all resources |

### `Tolinku.analytics`

| Method | Description |
|--------|-------------|
| `track(eventType, properties?)` | Queue a custom event |
| `flush()` | Send all queued events |
| `trackAsync(eventType, properties?, callback)` | Queue event (callback) |
| `flushAsync(callback)` | Send events (callback) |

### `Tolinku.referrals`

| Method | Description |
|--------|-------------|
| `create(userId, metadata?, userName?)` | Create a new referral |
| `get(code)` | Get referral details by code |
| `complete(code, referredUserId, milestone?, referredUserName?)` | Mark a referral as converted |
| `milestone(code, milestone)` | Update a referral milestone |
| `claimReward(code)` | Claim a referral reward |
| `leaderboard(limit?)` | Fetch the referral leaderboard |

### `Tolinku.ecommerce`

| Method | Description |
|--------|-------------|
| `viewItem(items)` | Track a product view |
| `addToCart(items)` | Track item added to cart |
| `removeFromCart(items)` | Track item removed from cart |
| `addToWishlist(items)` | Track item added to wishlist |
| `viewCart()` | Track cart view |
| `addPaymentInfo()` | Track payment info entered |
| `beginCheckout()` | Track checkout started |
| `purchase(transactionId, revenue, currency, items)` | Track a purchase |
| `refund(transactionId, revenue)` | Track a refund |
| `search(searchTerm)` | Track a product search |
| `share(itemId)` | Track a product share |
| `rate(itemId, rating, maxRating)` | Track a product rating |
| `spendCredits(revenue, currency)` | Track loyalty credits spent |
| `flush()` | Send all queued ecommerce events |

### `Tolinku.deferred`

| Method | Description |
|--------|-------------|
| `claimByToken(token)` | Claim a deferred link by token |
| `claimBySignals(appspaceId, context)` | Claim a deferred link by device signals |

### `Tolinku.messages`

| Method | Description |
|--------|-------------|
| `fetch(trigger?)` | Fetch messages with optional trigger filter |
| `renderToken(messageId)` | Get a render token for a message |
| `show(context, trigger?, onAction?, onDismiss?)` | Show the highest-priority message |

## Documentation

Full documentation is available at [tolinku.com/docs](https://tolinku.com/docs).

## Community

- [GitHub](https://github.com/tolinku)
- [X (Twitter)](https://x.com/trytolinku)
- [Facebook](https://facebook.com/trytolinku)
- [Instagram](https://www.instagram.com/trytolinku/)

## License

MIT
