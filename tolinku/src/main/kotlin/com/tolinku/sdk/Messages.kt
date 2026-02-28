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
    suspend fun fetch(trigger: String? = null): List<Message> {
        if (trigger != null) {
            require(trigger.isNotBlank()) { "trigger must not be blank when provided" }
        }

        val params = mutableMapOf<String, String>()
        if (trigger != null) params["trigger"] = trigger
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
        return messages
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
    ) {
        val messages = fetch(trigger)

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
}
