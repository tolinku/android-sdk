package com.tolinku.sdk

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles fetching in-app messages from the Tolinku API.
 */
class Messages internal constructor(private val client: TolinkuClient) {

    /**
     * Fetch messages, optionally filtered by trigger.
     *
     * @param trigger Optional trigger name to filter messages (e.g., "milestone", "welcome").
     *   If provided, must not be blank.
     * @return A list of [Message] objects.
     * @throws IllegalArgumentException if trigger is provided but blank.
     * @throws TolinkuException if the request fails.
     */
    suspend fun fetch(trigger: String? = null): List<Message> = fetch(trigger, null)

    /**
     * Fetch messages for a trigger, narrowed to one event or milestone name.
     *
     * The name is sent as `trigger_value`, and the result is also filtered by it here, so a
     * server older than that parameter gives the same answer: messages for that name, plus
     * messages of the trigger that name none.
     *
     * A separate function rather than a default argument on the one above, so apps built
     * against the old signature keep working without a recompile.
     *
     * @param trigger Optional trigger: "manual", "milestone" or "event". If null, the server
     *   returns manual messages. If provided, must not be blank.
     * @param triggerValue Optional event or milestone name, such as "purchase" or "installed".
     * @return A list of [Message] objects.
     * @throws IllegalArgumentException if trigger is provided but blank.
     * @throws TolinkuException if the request fails.
     */
    suspend fun fetch(trigger: String?, triggerValue: String?): List<Message> {
        if (trigger != null) {
            require(trigger.isNotBlank()) { "trigger must not be blank when provided" }
        }

        val params = mutableMapOf<String, String>()
        if (trigger != null) params["trigger"] = trigger
        if (!triggerValue.isNullOrEmpty()) params["trigger_value"] = triggerValue
        val userId = Tolinku.userId
        if (userId != null) params["user_id"] = userId
        val response = client.get("/v1/api/messages", params.ifEmpty { null })

        val messages = mutableListOf<Message>()
        val dataArray = response.optJSONArray("messages")
        if (dataArray != null) {
            for (i in 0 until dataArray.length()) {
                messages.add(Message.fromJson(dataArray.getJSONObject(i)))
            }
        }
        return messages.filter { messageMatches(it, triggerValue) }
    }

    /**
     * Request a short-lived render token for loading message HTML in a WebView.
     *
     * The token is scoped to the given message and expires after 5 minutes.
     * Use it to load the render URL without exposing the API key.
     *
     * @param messageId The ID of the message to render.
     * @return A render token string.
     * @throws TolinkuException if the request fails.
     */
    suspend fun renderToken(messageId: String): String {
        val encodedId = java.net.URLEncoder.encode(messageId, "UTF-8")
        val response = client.post("/v1/api/messages/$encodedId/render-token", org.json.JSONObject())
        return response.getString("token")
    }

    /**
     * Java-friendly callback wrapper for [fetch].
     *
     * @param trigger Optional trigger name to filter messages.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    @JvmOverloads
    fun fetchAsync(
        trigger: String? = null,
        callback: TolinkuCallback<List<Message>>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { fetch(trigger) }
            callback.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [fetch] with an event or milestone name.
     *
     * @param trigger Optional trigger name to filter messages.
     * @param triggerValue Optional event or milestone name.
     * @param callback Invoked with a [Result] when the operation completes.
     */
    fun fetchAsync(
        trigger: String?,
        triggerValue: String?,
        callback: TolinkuCallback<List<Message>>
    ) {
        Tolinku.scope.launch {
            val result = runCatching { fetch(trigger, triggerValue) }
            callback.onResult(result)
        }
    }

    /**
     * Fetch and display the highest-priority non-dismissed in-app message.
     *
     * This convenience method fetches messages (optionally filtered by trigger),
     * removes any that have been dismissed within their cooldown period, sorts by
     * priority (highest first), and shows the top message in a WebView dialog.
     *
     * @param context Android context (Activity context recommended for dialog display).
     * @param trigger Optional trigger name to filter messages.
     * @param onAction Called when the user taps an action in the message. Receives the URL string.
     * @param onDismiss Called when the message is dismissed.
     */
    suspend fun show(
        context: Context,
        trigger: String? = null,
        onAction: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) = showMatching(context, trigger, null, onAction, onDismiss)

    /**
     * Fetch and display the highest-priority non-dismissed in-app message for one event or
     * milestone name, such as `show(context, "event", "purchase")`.
     *
     * Shows messages for that name, plus messages of the trigger that name none. The name is
     * sent as `trigger_value` and also filtered here, so older servers behave the same.
     *
     * An overload rather than a new default argument on the [show] above, so existing calls
     * (including a trailing `onDismiss` lambda) and apps built against the old signature keep
     * working. [triggerValue] is not nullable so that `show(context, trigger, null)` still means
     * `onAction = null`; to ask for no name, call the [show] above.
     *
     * @param context Android context (Activity context recommended for dialog display).
     * @param trigger Optional trigger: "manual", "milestone" or "event".
     * @param triggerValue The event or milestone name.
     * @param onAction Called when the user taps an action in the message. Receives the URL string.
     * @param onDismiss Called when the message is dismissed.
     */
    suspend fun show(
        context: Context,
        trigger: String?,
        triggerValue: String,
        onAction: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) = showMatching(context, trigger, triggerValue, onAction, onDismiss)

    private suspend fun showMatching(
        context: Context,
        trigger: String?,
        triggerValue: String?,
        onAction: ((String) -> Unit)?,
        onDismiss: (() -> Unit)?
    ) {
        val messages = fetch(trigger, triggerValue)

        val eligible = messages
            .filter { !TolinkuMessagePresenter.isMessageDismissed(context, it) }
            .filter { !TolinkuMessagePresenter.isMessageSuppressed(context, it) }
            .sortedByDescending { it.priority }

        val message = eligible.firstOrNull() ?: return

        TolinkuMessagePresenter.recordImpression(context, message.id)
        val token = renderToken(message.id)

        withContext(Dispatchers.Main) {
            TolinkuMessagePresenter.show(context, message, token, onAction, onDismiss)
        }
    }

    /**
     * Java-friendly callback wrapper for [show].
     *
     * @param context Android context (Activity context recommended for dialog display).
     * @param trigger Optional trigger name to filter messages.
     * @param onAction Called when the user taps an action in the message.
     * @param onDismiss Called when the message is dismissed.
     * @param callback Invoked with a [Result] when the operation completes (Unit on success).
     */
    @JvmOverloads
    fun showAsync(
        context: Context,
        trigger: String? = null,
        onAction: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        callback: TolinkuCallback<Unit>? = null
    ) {
        Tolinku.scope.launch {
            val result = runCatching { show(context, trigger, onAction, onDismiss) }
            callback?.onResult(result)
        }
    }

    /**
     * Java-friendly callback wrapper for [show] with an event or milestone name.
     *
     * @param context Android context (Activity context recommended for dialog display).
     * @param trigger Optional trigger: "manual", "milestone" or "event".
     * @param triggerValue The event or milestone name.
     * @param onAction Called when the user taps an action in the message.
     * @param onDismiss Called when the message is dismissed.
     * @param callback Invoked with a [Result] when the operation completes (Unit on success).
     */
    fun showAsync(
        context: Context,
        trigger: String?,
        triggerValue: String,
        onAction: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null,
        callback: TolinkuCallback<Unit>? = null
    ) {
        Tolinku.scope.launch {
            val result = runCatching { show(context, trigger, triggerValue, onAction, onDismiss) }
            callback?.onResult(result)
        }
    }
}

/**
 * Whether a message belongs to a request for [triggerValue]: always when no name was asked for;
 * otherwise when the message names that one, or names none (a message for any event or milestone
 * of its trigger). The rule the server applies to `trigger_value`, repeated here for servers
 * older than it.
 */
internal fun messageMatches(message: Message, triggerValue: String?): Boolean {
    if (triggerValue.isNullOrEmpty()) return true
    val own = message.triggerValue
    if (own.isNullOrEmpty()) return true
    return own == triggerValue
}
