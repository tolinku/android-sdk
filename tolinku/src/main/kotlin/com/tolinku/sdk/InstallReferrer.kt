package com.tolinku.sdk

import android.content.Context
import com.android.installreferrer.api.InstallReferrerClient
import com.android.installreferrer.api.InstallReferrerStateListener
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.URLDecoder

/**
 * The Play Install Referrer, which is how a deferred link survives an install
 * on Android deterministically.
 *
 * A Tolinku link sends an Android visitor to the store with
 * `referrer=tolk_token=<token>` attached. Play retains that string through the
 * install and hands it back on first launch, so the exact click can be claimed
 * rather than guessed at from device signals. Signal matching remains the
 * fallback for installs the referrer cannot reach, such as a sideload or a
 * store other than Play.
 */
internal object InstallReferrer {

    private const val TOKEN_KEY = "tolk_token"

    /**
     * Pull our token out of a Play referrer string.
     *
     * The referrer is shared: a developer's own `utm_source` and anything else
     * they attached live in the same string, so the token has to be found among
     * the pairs rather than assumed to be the whole value. Play normally hands
     * back a decoded string, but a percent-encoded `%3D` is tolerated because
     * that assumption is not worth a lost install if it is ever wrong.
     */
    @JvmStatic
    fun parseToken(referrer: String?): String? {
        if (referrer.isNullOrBlank()) return null

        val decoded = try {
            URLDecoder.decode(referrer, "UTF-8")
        } catch (e: Exception) {
            referrer
        }

        return decoded
            .split('&')
            .asSequence()
            .map { it.trim() }
            .firstOrNull { it.startsWith("$TOKEN_KEY=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotBlank() }
    }

    /**
     * Ask Play for the referrer this install came from.
     *
     * Returns null whenever there is nothing to report, which is the ordinary
     * case for an organic install, and also when Play Services is absent or the
     * API is unavailable on the device. None of those are errors worth
     * surfacing: the caller falls back to signal matching.
     */
    suspend fun fetchToken(context: Context): String? = suspendCancellableCoroutine { cont ->
        val client = try {
            InstallReferrerClient.newBuilder(context.applicationContext).build()
        } catch (e: Throwable) {
            cont.resume(null)
            return@suspendCancellableCoroutine
        }

        // The listener can fire more than once on some devices, and resuming a
        // continuation twice crashes. This makes the first answer the only one.
        var settled = false
        fun settle(value: String?) {
            if (settled) return
            settled = true
            try { client.endConnection() } catch (e: Throwable) { /* already gone */ }
            if (cont.isActive) cont.resume(value)
        }

        cont.invokeOnCancellation { settle(null) }

        try {
            client.startConnection(object : InstallReferrerStateListener {
                override fun onInstallReferrerSetupFinished(responseCode: Int) {
                    if (responseCode != InstallReferrerClient.InstallReferrerResponse.OK) {
                        settle(null)
                        return
                    }
                    val token = try {
                        parseToken(client.installReferrer.installReferrer)
                    } catch (e: Throwable) {
                        null
                    }
                    settle(token)
                }

                override fun onInstallReferrerServiceDisconnected() {
                    settle(null)
                }
            })
        } catch (e: Throwable) {
            settle(null)
        }
    }
}
