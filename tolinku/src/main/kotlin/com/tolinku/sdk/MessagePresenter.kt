package com.tolinku.sdk

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageButton
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Handles presenting in-app messages using a WebView-based dialog overlay.
 *
 * Messages are rendered server-side at the /v1/api/messages/:id/render endpoint,
 * which returns a self-contained HTML document. A JavaScript bridge allows the
 * rendered message to communicate actions (close, navigate) back to native code.
 */
object TolinkuMessagePresenter {

    private const val PREFS_NAME = "tolinku_messages"
    private const val DISMISSED_PREFIX = "tolinku_dismissed_"
    private const val IMPRESSIONS_PREFIX = "tolinku_impressions_"
    private const val LAST_SHOWN_PREFIX = "tolinku_last_shown_"

    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Show an in-app message in a full-screen transparent dialog with a centered WebView card.
     *
     * The message HTML is loaded from the server's render endpoint. A JavaScript interface
     * named "Android" is injected so the HTML can call Android.onAction(action) to
     * communicate user interactions.
     *
     * @param context The Android context (Activity context recommended for dialog display).
     * @param message The message to display.
     * @param onAction Called when the user taps an action button. Receives the action URL string.
     * @param onDismiss Called when the message is dismissed (close button or JS "close" action).
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun show(
        context: Context,
        message: Message,
        renderToken: String,
        onAction: ((String) -> Unit)? = null,
        onDismiss: (() -> Unit)? = null
    ) {
        val baseUrl = Tolinku.configuredBaseUrl
            ?: throw TolinkuException("Tolinku SDK not configured. Call Tolinku.configure() first.")

        val encodedId = java.net.URLEncoder.encode(message.id, "UTF-8")
        val encodedToken = java.net.URLEncoder.encode(renderToken, "UTF-8")
        val renderUrl = "${baseUrl.trimEnd('/')}/v1/api/messages/$encodedId/render?token=$encodedToken"

        runOnMainThread {
            val dialog = Dialog(context)
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
            dialog.setCancelable(true)
            dialog.setCanceledOnTouchOutside(true)

            val container = FrameLayout(context)
            container.setBackgroundColor(Color.parseColor("#80000000"))

            // WebView for rendering the message HTML
            val webView = WebView(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true

                webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        if (Tolinku.debug) {
                            Log.d(Tolinku.TAG, "Message WebView loaded: $url")
                        }
                    }
                }

                addJavascriptInterface(
                    MessageJsBridge(dialog, message, context, onAction, onDismiss),
                    "Android"
                )

                loadUrl(renderUrl)
            }

            val webViewParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                val margin = dpToPx(context, 24)
                setMargins(margin, margin, margin, margin)
                gravity = Gravity.CENTER
            }

            container.addView(webView, webViewParams)

            // Native close button (X) overlaid on top-right
            val closeButton = ImageButton(context).apply {
                setBackgroundColor(Color.TRANSPARENT)
                setColorFilter(Color.WHITE)
                contentDescription = "Close"
                setPadding(
                    dpToPx(context, 10),
                    dpToPx(context, 10),
                    dpToPx(context, 10),
                    dpToPx(context, 10)
                )

                // Draw a simple X using a text-based approach
                setImageDrawable(CloseIconDrawable(Color.WHITE))

                setOnClickListener {
                    markDismissed(context, message.id)
                    dialog.dismiss()
                    onDismiss?.invoke()
                }
            }

            val closeParams = FrameLayout.LayoutParams(
                dpToPx(context, 44),
                dpToPx(context, 44)
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                val margin = dpToPx(context, 8)
                setMargins(0, margin, margin, 0)
            }

            container.addView(closeButton, closeParams)

            dialog.setContentView(container)

            dialog.setOnCancelListener {
                markDismissed(context, message.id)
                onDismiss?.invoke()
            }

            // Make the dialog full-screen with transparent background
            dialog.window?.apply {
                setLayout(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT
                )
                setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
                clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            }

            dialog.show()

            if (Tolinku.debug) {
                Log.d(Tolinku.TAG, "Showing message: ${message.id} (${message.name})")
            }
        }
    }

    /**
     * Check whether a message has been dismissed and is still within its dismiss cooldown period.
     *
     * @param context Android context for SharedPreferences access.
     * @param message The message to check.
     * @return true if the message was dismissed and the cooldown has not expired yet.
     */
    fun isMessageDismissed(context: Context, message: Message): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val dismissedDate = prefs.getString("$DISMISSED_PREFIX${message.id}", null)
            ?: return false

        // If dismissDays is null, the message stays dismissed forever once dismissed.
        val dismissDays = message.dismissDays ?: return true

        return try {
            val dismissed = LocalDate.parse(dismissedDate, DateTimeFormatter.ISO_LOCAL_DATE)
            val now = LocalDate.now()
            val daysSinceDismissal = ChronoUnit.DAYS.between(dismissed, now)
            daysSinceDismissal < dismissDays
        } catch (e: Exception) {
            if (Tolinku.debug) {
                Log.w(Tolinku.TAG, "Failed to parse dismissed date: $dismissedDate", e)
            }
            false
        }
    }

    /**
     * Mark a message as dismissed by storing the current date in SharedPreferences.
     *
     * @param context Android context for SharedPreferences access.
     * @param messageId The ID of the message to mark as dismissed.
     */
    fun markDismissed(context: Context, messageId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val today = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
        prefs.edit().putString("$DISMISSED_PREFIX$messageId", today).apply()

        if (Tolinku.debug) {
            Log.d(Tolinku.TAG, "Message dismissed: $messageId")
        }
    }

    /**
     * Check whether a message should be suppressed based on max impressions
     * or minimum interval between displays.
     *
     * @param context Android context for SharedPreferences access.
     * @param message The message to check.
     * @return true if the message should be suppressed (not shown).
     */
    fun isMessageSuppressed(context: Context, message: Message): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        // Check max impressions
        val maxImpressions = message.maxImpressions
        if (maxImpressions != null && maxImpressions > 0) {
            val count = prefs.getInt("$IMPRESSIONS_PREFIX${message.id}", 0)
            if (count >= maxImpressions) return true
        }

        // Check min interval
        val minIntervalHours = message.minIntervalHours
        if (minIntervalHours != null && minIntervalHours > 0) {
            val lastShown = prefs.getString("$LAST_SHOWN_PREFIX${message.id}", null)
            if (lastShown != null) {
                return try {
                    val lastShownDate = LocalDate.parse(lastShown, DateTimeFormatter.ISO_LOCAL_DATE)
                    val hoursSince = ChronoUnit.HOURS.between(lastShownDate.atStartOfDay(), LocalDate.now().atStartOfDay())
                    hoursSince < minIntervalHours
                } catch (e: Exception) {
                    false
                }
            }
        }

        return false
    }

    /**
     * Record that a message was shown (increment impression count and update last-shown time).
     *
     * @param context Android context for SharedPreferences access.
     * @param messageId The ID of the message that was shown.
     */
    fun recordImpression(context: Context, messageId: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val count = prefs.getInt("$IMPRESSIONS_PREFIX$messageId", 0)
        prefs.edit()
            .putInt("$IMPRESSIONS_PREFIX$messageId", count + 1)
            .putString("$LAST_SHOWN_PREFIX$messageId", LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE))
            .apply()

        if (Tolinku.debug) {
            Log.d(Tolinku.TAG, "Message impression recorded: $messageId (count: ${count + 1})")
        }
    }

    private fun runOnMainThread(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post(block)
        }
    }

    private fun dpToPx(context: Context, dp: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp.toFloat(),
            context.resources.displayMetrics
        ).toInt()
    }

    /**
     * JavaScript interface bridge injected into the WebView.
     *
     * The rendered message HTML can call:
     *   Android.onAction('close')         - dismiss the dialog
     *   Android.onAction('navigate:URL')  - dismiss and navigate to the URL
     */
    private class MessageJsBridge(
        private val dialog: Dialog,
        private val message: Message,
        private val context: Context,
        private val onAction: ((String) -> Unit)?,
        private val onDismiss: (() -> Unit)?
    ) {
        @JavascriptInterface
        fun onAction(action: String) {
            if (Tolinku.debug) {
                Log.d(Tolinku.TAG, "JS bridge action: $action (message: ${message.id})")
            }

            Handler(Looper.getMainLooper()).post {
                when {
                    action == "close" -> {
                        markDismissed(context, message.id)
                        dialog.dismiss()
                        onDismiss?.invoke()
                    }
                    action.startsWith("navigate:") -> {
                        val url = action.removePrefix("navigate:")

                        // Validate URL scheme to prevent dangerous URIs (file://, content://, javascript:, etc.)
                        val isValidUrl = url.startsWith("http://") || url.startsWith("https://")
                        if (!isValidUrl) {
                            if (Tolinku.debug) {
                                Log.w(Tolinku.TAG, "Blocked invalid URL scheme in navigate action: $url")
                            }
                            markDismissed(context, message.id)
                            dialog.dismiss()
                            onDismiss?.invoke()
                            return@post
                        }

                        markDismissed(context, message.id)
                        dialog.dismiss()

                        if (onAction != null) {
                            onAction.invoke(url)
                        } else {
                            // Default behavior: open URL via Intent
                            try {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                if (Tolinku.debug) {
                                    Log.w(Tolinku.TAG, "Failed to open URL: $url", e)
                                }
                            }
                        }
                    }
                    else -> {
                        if (Tolinku.debug) {
                            Log.w(Tolinku.TAG, "Unknown JS bridge action: $action")
                        }
                    }
                }
            }
        }
    }
}

/**
 * Simple drawable that renders an X (close) icon using Canvas paths.
 */
internal class CloseIconDrawable(private val color: Int) : android.graphics.drawable.Drawable() {

    private val paint = android.graphics.Paint().apply {
        this.color = this@CloseIconDrawable.color
        strokeWidth = 6f
        style = android.graphics.Paint.Style.STROKE
        strokeCap = android.graphics.Paint.Cap.ROUND
        isAntiAlias = true
    }

    override fun draw(canvas: android.graphics.Canvas) {
        val bounds = bounds
        val padding = (bounds.width() * 0.25f)
        val left = bounds.left + padding
        val top = bounds.top + padding
        val right = bounds.right - padding
        val bottom = bounds.bottom - padding

        canvas.drawLine(left, top, right, bottom, paint)
        canvas.drawLine(right, top, left, bottom, paint)
    }

    override fun setAlpha(alpha: Int) {
        paint.alpha = alpha
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT

    override fun setColorFilter(colorFilter: android.graphics.ColorFilter?) {
        paint.colorFilter = colorFilter
    }
}
