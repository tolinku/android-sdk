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
     * platform knows which Appspace is being asked about, which also means a
     * link on a domain that is not yours simply answers nothing.
     *
     * Never throws. A link that cannot be resolved, for a bad network or any
     * other reason, is one the app should fall back to its own handling for,
     * and an exception in the middle of a cold start is no way to say so.
     */
    suspend fun resolve(url: String): ResolvedLink? {
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
        val host = uri.host ?: return null

        val origin = buildString {
            append(scheme).append("://").append(host)
            if (uri.port != -1) append(":").append(uri.port)
        }

        // The raw path, not the decoded one. A token may contain an encoded
        // slash, and decoding first turns "/promo/a%2Fb" into a path three deep
        // rather than a token of "a/b" on "promo", which resolves to a
        // different route or to nothing.
        val path = uri.rawPath?.takeUnless { it.isEmpty() } ?: "/"

        return try {
            val response = client.postPublicToOrigin(
                origin,
                "/v1/api/path",
                JSONObject().put("path", path),
            )
            val route = response.optJSONObject("route") ?: return null
            ResolvedLink(
                route = ResolvedRoute(
                    prefix = route.optString("prefix"),
                    name = route.optString("name"),
                    template = route.optString("template"),
                    linkType = route.optString("link_type").takeUnless { it.isEmpty() },
                ),
                token = response.optString("token", ""),
                deepLinkPath = response.optString("deep_link_path", ""),
            )
        } catch (e: Exception) {
            null
        }
    }
}
