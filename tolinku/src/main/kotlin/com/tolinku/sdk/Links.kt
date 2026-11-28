package com.tolinku.sdk

import java.net.URI
import org.json.JSONObject

/**
 * The route that answers a link.
 *
 * @property prefix the route's prefix, which may place its token with {token}
 * @property name the route's name, as set in the dashboard
 * @property template which landing page the route uses, or "none"
 * @property linkType "dynamic" or "static", or null from an older platform
 */
data class ResolvedRoute(
    val prefix: String,
    val name: String,
    val template: String,
    val linkType: String?,
)

/**
 * What a Tolinku link turned out to mean.
 *
 * The same shape in every SDK, so an app moving between them reads one thing.
 * The Appspace the link belongs to is deliberately not here: the app already
 * knows which Appspace it is, and nothing about routing a link needs it.
 *
 * @property route the route that answers this link
 * @property token the token the link carried, or "" where it carried none
 * @property deepLinkPath the canonical path, with the token wherever the
 *   route's prefix puts it
 */
data class ResolvedLink(
    val route: ResolvedRoute,
    val token: String,
    val deepLinkPath: String,
)

/**
 * Working out what a link the system handed the app actually means.
 *
 * An app receives the URL that was tapped, exactly as it was written. That is
 * fine while the URL is readable: `/order/4821` says "order" and the app can
 * route it. It is not fine for a short link, which is the same route written as
 * a code:
 *
 * ```
 * https://links.example.com/s7k2p9q/4821
 * ```
 *
 * Nothing in that URL says "order", and nothing about the code can be worked
 * out on the device. An app parsing the path itself sees a first segment it has
 * never heard of and does nothing, so the link opens the app and then appears
 * to fail: no error, no screen, no clue. Short links are what the dashboard
 * offers for sharing and what a QR code carries, so this is not a rare path.
 *
 * [resolve] asks the platform, which answers with the route, the token and the
 * canonical path, and the app can route that the way it routes anything else. A
 * readable URL comes back unchanged, so an app can simply resolve everything
 * rather than guessing which kind it has.
 */
class Links internal constructor(private val client: TolinkuClient) {

    /**
     * What this link means, or null if it means nothing here.
     *
     * The question goes to the link's own host, because that is how the
     * platform knows which Appspace is being asked about. When that host is not
     * Tolinku's, as the click tracking domain an email platform wraps every
     * link in is not, the question goes once more to the configured API, which
     * can see through the wrapping. A link that belongs to neither simply
     * answers nothing.
     *
     * Each question is asked once, with [TolinkuClient.ONE_SHOT_TIMEOUT_MS] to
     * answer. The server unwraps an email link once, which is how the email
     * platform counts the click (Tolinku records no click of its own), so asking
     * again could count it twice, and someone is waiting on the answer with the
     * app half open.
     *
     * Never throws. A link that cannot be resolved, for a bad network or any
     * other reason, is one the app should fall back to its own handling for,
     * and an exception in the middle of a cold start is no way to say so.
     */
    suspend fun resolve(url: String): ResolvedLink? {
        val link = normalise(url) ?: return null

        // Step 1: the link's own host, without the key. The origin comes from
        // the URL this was handed, and an app resolving a link from somewhere
        // it does not control would otherwise send the key to a stranger.
        val reply = try {
            client.postOnce(
                "/v1/api/path",
                JSONObject().put("path", link.path).put("url", link.whole),
                authenticated = false,
                origin = link.origin,
            )
        } catch (e: Exception) {
            null
        }
        if (reply != null) {
            val json = jsonOrNull(reply.body)
            if (reply.code == 200 && json != null) {
                parse(json)?.let { return it }
            }
            // Tolinku answers with X-Tolinku: 1, whatever the status, and the
            // host has then said its piece; asking the API as well would only
            // repeat the question. An email platform's host can answer in JSON
            // with an "error" too, so the body alone proves nothing. A server
            // from before the header sends none: its error body is trusted only
            // on Tolinku's own domain.
            if (reply.tolinku || (json?.opt("error") is String && isTolinkuDomain(link.host))) return null
        }

        // Step 2: no answer that was Tolinku's, which is what a tracking
        // domain belonging to an email platform gives. Tolinku's own API can
        // unwrap the link instead, asked with the key since it is the host this
        // SDK was configured to trust. Not when the link is already on that
        // host, where the first answer was the only one there is, and not
        // without a key to ask with.
        if (client.apiKey.isBlank()) return null
        val apiHost = try {
            URI(client.baseUrl.trim()).host
        } catch (e: Exception) {
            null
        } ?: return null
        if (link.host == apiHost.lowercase()) return null

        val second = try {
            client.postOnce(
                "/v1/api/links/resolve",
                JSONObject().put("url", link.whole),
                authenticated = true,
            )
        } catch (e: Exception) {
            return null
        }
        if (second.code != 200) return null
        return jsonOrNull(second.body)?.let { parse(it) }
    }

    /**
     * Whether [host] is Tolinku's own: the configured API's host, or a host
     * under its parent domain (an Appspace's tolinku.com subdomain).
     */
    private fun isTolinkuDomain(host: String): Boolean {
        val apiHost = try {
            URI(client.baseUrl.trim()).host?.lowercase()
        } catch (e: Exception) {
            null
        } ?: return false
        if (host == apiHost) return true
        val labels = apiHost.split(".")
        return labels.size > 2 && host.endsWith("." + labels.takeLast(2).joinToString("."))
    }

    /**
     * A link in the one form both questions use.
     *
     * @property host the lowercased host, without a port
     * @property origin https, the host, and a port only if it is not a default
     * @property path the percent-encoded path, "/" when there is none
     * @property whole the origin, path and query, without a fragment
     */
    internal class NormalisedLink(
        val host: String,
        val origin: String,
        val path: String,
        val whole: String,
    )

    /**
     * The link trimmed, with its fragment dropped, its scheme and host
     * lowercased, http upgraded to https and a default port dropped. The path
     * and query are left exactly as written, encoding included. Null for
     * anything that is not an http or https link.
     */
    internal fun normalise(url: String): NormalisedLink? {
        // java.net.URI rather than android.net.Uri: this is ordinary parsing
        // with no need for the framework, and the framework class is a stub in
        // unit tests, so using it would put this behaviour beyond the reach of
        // anything but a device.
        val uri = try {
            URI(url.trim())
        } catch (e: Exception) {
            return null
        }

        // http and https only. A custom scheme link already carries the path the
        // app wants, and anything else is not a link this could answer for.
        val scheme = uri.scheme?.lowercase()
        if (scheme != "https" && scheme != "http") return null
        val host = uri.host?.lowercase() ?: return null

        // Always https: every Tolinku and click tracking domain serves it, and
        // Android's cleartext rules would block a plain http request anyway.
        // Which makes :80 as much a default as :443.
        val origin = buildString {
            append("https://").append(host)
            if (uri.port != -1 && uri.port != 443 && uri.port != 80) {
                append(":").append(uri.port)
            }
        }

        // The raw path, not the decoded one. A token may contain an encoded
        // slash, and decoding first turns "/promo/a%2Fb" into a path three deep
        // rather than a token of "a/b" on "promo", which resolves to a
        // different route or to nothing.
        val path = uri.rawPath?.takeUnless { it.isEmpty() } ?: "/"

        // The whole link as well as the path. A link in an email arrives
        // wrapped in the email platform's click tracking, and for some of them
        // (SendGrid, for one) everything that says where it leads is in the
        // query string, which the path alone would throw away. The fragment
        // stays behind: a browser never sends it, so no server reads it.
        val query = uri.rawQuery?.takeUnless { it.isEmpty() }
        val whole = origin + path + (if (query != null) "?$query" else "")

        return NormalisedLink(host = host, origin = origin, path = path, whole = whole)
    }

    private fun jsonOrNull(body: String): JSONObject? = try {
        JSONObject(body)
    } catch (e: Exception) {
        null
    }

    /**
     * The answer from either endpoint, or null if it is not one an app can
     * safely route. Both answer in the same shape, and both get the same
     * suspicion about what they say.
     */
    private fun parse(response: JSONObject): ResolvedLink? {
        val route = response.optJSONObject("route") ?: return null

        // The first answer went to a host taken from the URL this was given,
        // so an app resolving a link from somewhere it does not control is
        // talking to a stranger, and the second is checked the same way rather
        // than trusted for coming from home. The contract is a path: a full
        // URL, or a protocol relative "//host" that reads as one, is a redirect
        // waiting to happen in whatever the app does next.
        val path = response.optString("deep_link_path")
        if (!path.startsWith("/") || path.startsWith("//")) return null
        return ResolvedLink(
            route = ResolvedRoute(
                prefix = route.optString("prefix"),
                name = route.optString("name"),
                template = route.optString("template"),
                linkType = route.optString("link_type").takeUnless { it.isEmpty() },
            ),
            token = response.optString("token", ""),
            deepLinkPath = path,
        )
    }
}
