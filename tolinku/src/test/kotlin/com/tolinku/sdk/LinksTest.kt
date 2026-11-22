package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.QueueDispatcher
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.util.Collections
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * Turning a link the system handed the app into something routable.
 *
 * The URL an app receives is the one that was tapped, exactly as written. A
 * short link is an opaque code, "/s7k2p9q/4821", and nothing on the device
 * can say what the code stands for. An app parsing the path itself sees a first
 * segment it has never heard of and does nothing, so the link opens the app and
 * appears to fail with no error and no screen.
 *
 * The question has to go to the link's own host, because that is how the
 * platform knows which Appspace is being asked about, and always over https.
 * The mock servers speak plain http, so an interceptor in front of the network
 * notes every URL the SDK asked for and then sends each https one to [server],
 * which stands in for whatever host the link is on. The configured API is
 * [api], reached as http://localhost, which the interceptor leaves alone. A
 * request the SDK should never have made shows up in [asked] even when nothing
 * is listening for it.
 */
class LinksTest {

    private lateinit var server: MockWebServer
    private lateinit var api: MockWebServer
    private lateinit var links: Links

    /** Every URL the SDK asked for, in order, before any rewriting. */
    private val asked: MutableList<String> = Collections.synchronizedList(mutableListOf())

    private val loopback = InetAddress.getByName("127.0.0.1")

    private val answer = """
        {
          "route": {"prefix": "order/{token}/receipt", "name": "Order Receipt", "template": "none", "link_type": "dynamic"},
          "token": "4821",
          "deep_link_path": "/order/4821/receipt",
          "appspace": {"name": "Example App", "slug": "example"}
        }
    """.trimIndent()

    private val apiKey = "tolk_pub_test_key"

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start(loopback, 0)
        api = MockWebServer()
        // An API with nothing queued answers 404 straight away rather than
        // holding the request open, so a test that never expected the second
        // question still finishes, and its requestCount says it was asked.
        api.dispatcher = QueueDispatcher().apply { setFailFast(true) }
        api.start(loopback, 0)
        links = linksWith(baseUrl = "http://localhost:${api.port}")
    }

    @After
    fun tearDown() {
        server.shutdown()
        api.shutdown()
    }

    private fun httpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val request = chain.request()
            asked.add(request.url.toString())
            if (request.url.isHttps) {
                val local = request.url.newBuilder()
                    .scheme("http").host("127.0.0.1").port(server.port).build()
                chain.proceed(request.newBuilder().url(local).build())
            } else {
                chain.proceed(request)
            }
        })
        .build()

    private fun linksWith(baseUrl: String, key: String = apiKey) =
        Links(TolinkuClient(apiKey = key, baseUrl = baseUrl, httpClient = httpClient()))

    private val step1 = "https://links.example.com/v1/api/path"
    private val step2 get() = "http://localhost:${api.port}/v1/api/links/resolve"

    private fun ok(body: String = answer) = MockResponse().setResponseCode(200).setBody(body)

    // Step 1: the link's own host

    @Test
    fun `asks the link its own host, with the path and the whole link`() = runTest {
        server.enqueue(ok())

        val link = links.resolve("https://links.example.com/s7k2p9q/4821")

        assertEquals(listOf(step1), asked)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("/s7k2p9q/4821", body.getString("path"))
        assertEquals("https://links.example.com/s7k2p9q/4821", body.getString("url"))
        assertNotNull(link)
        assertEquals("4821", link!!.token)
        assertEquals("/order/4821/receipt", link.deepLinkPath)
        assertEquals("order/{token}/receipt", link.route.prefix)
        assertEquals("Order Receipt", link.route.name)
        assertEquals("dynamic", link.route.linkType)
    }

    @Test
    fun `asks without the API key, since the host is not necessarily ours`() = runTest {
        // The origin comes from the URL this was handed. An app resolving a
        // link from somewhere it does not control would otherwise send the
        // Appspace's key to a stranger.
        server.enqueue(ok())

        links.resolve("https://links.example.com/s7k2p9q/4821")

        val request = server.takeRequest()
        assertNull(request.getHeader("X-API-Key"))
        assertNull(request.getHeader("Authorization"))
    }

    @Test
    fun `keeps the query in the whole link but out of the path`() = runTest {
        // A link in an email arrives wrapped in the email platform's click
        // tracking, and SendGrid's keeps everything that says where it leads in
        // the query string, which the path alone throws away.
        server.enqueue(ok())

        links.resolve("https://links.example.com/uni/ls/click?upn=XYZ&utm_source=qr")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("/uni/ls/click", body.getString("path"))
        assertEquals("https://links.example.com/uni/ls/click?upn=XYZ&utm_source=qr", body.getString("url"))
    }

    @Test
    fun `keeps an encoded slash in the token encoded`() = runTest {
        // Decoding first turns "/promo/a%2Fb" into a path three deep rather
        // than a token of "a/b" on "promo", which resolves to a different route
        // or to nothing.
        server.enqueue(ok())

        links.resolve("https://links.example.com/promo/a%2Fb?next=%2Fhome")

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("/promo/a%2Fb", body.getString("path"))
        assertEquals("https://links.example.com/promo/a%2Fb?next=%2Fhome", body.getString("url"))
    }

    @Test
    fun `normalises the link before asking`() = runTest {
        // Spaces trimmed, fragment dropped, scheme and host lowercased, http
        // upgraded to https, the default port dropped. The path and query keep
        // their case and their encoding.
        server.enqueue(ok())

        links.resolve("  HTTP://Links.Example.COM:80/Uni/LS/click?Upn=A%2Fb&X=Y#Frag  ")

        assertEquals(listOf(step1), asked)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("/Uni/LS/click", body.getString("path"))
        assertEquals("https://links.example.com/Uni/LS/click?Upn=A%2Fb&X=Y", body.getString("url"))
    }

    @Test
    fun `drops 443 and keeps any other port`() = runTest {
        server.enqueue(ok())
        server.enqueue(ok())

        links.resolve("https://links.example.com:443/s7k2p9q/4821")
        links.resolve("http://links.example.com:8443/s7k2p9q/4821")

        assertEquals(
            listOf(step1, "https://links.example.com:8443/v1/api/path"),
            asked,
        )
        assertEquals(
            "https://links.example.com/s7k2p9q/4821",
            JSONObject(server.takeRequest().body.readUtf8()).getString("url"),
        )
        assertEquals(
            "https://links.example.com:8443/s7k2p9q/4821",
            JSONObject(server.takeRequest().body.readUtf8()).getString("url"),
        )
    }

    @Test
    fun `normalise gives no path as a slash and leaves an empty query off`() {
        val link = links.normalise("https://LINKS.example.com?")!!
        assertEquals("links.example.com", link.host)
        assertEquals("https://links.example.com", link.origin)
        assertEquals("/", link.path)
        assertEquals("https://links.example.com/", link.whole)
    }

    @Test
    fun `says nothing for a custom scheme link`() = runTest {
        // That one already carries the path the app wants.
        assertNull(links.resolve("example://order/4821/receipt"))
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `says nothing for something that is not a link`() = runTest {
        assertNull(links.resolve("/order/4821"))
        assertNull(links.resolve(""))
        assertNull(links.resolve("ftp://links.example.com/order/4821"))
        assertTrue(asked.isEmpty())
    }

    /**
     * resolve sends its first question to a host taken from the URL it was
     * given, so an app resolving a link from somewhere it does not control is
     * talking to a stranger. Anything but a path is a redirect waiting to happen.
     */
    private fun assertRefusesPath(badPath: String) = runTest {
        server.enqueue(ok(answer.replace("/order/4821/receipt", badPath)))
        assertNull(links.resolve("https://links.example.com/s7k2p9q/4821"))
    }

    @Test
    fun `refuses an answer that is a full URL`() = assertRefusesPath("https://evil.example.com/take-over")

    @Test
    fun `refuses an answer that is protocol relative`() = assertRefusesPath("//evil.example.com/take-over")

    @Test
    fun `refuses an answer that is a bare word`() = assertRefusesPath("order/4821")

    @Test
    fun `refuses an answer that is nothing`() = assertRefusesPath("")

    // Tolinku's own reply ends it

    private fun assertNoFallbackAfter(reply: MockResponse) = runTest {
        // The host is Tolinku's and has answered. Asking the API as well would
        // count the click twice.
        server.enqueue(reply)

        assertNull(links.resolve("https://links.example.com/s7k2p9q/4821"))
        assertEquals(listOf(step1), asked)
        assertEquals(0, api.requestCount)
    }

    @Test
    fun `stops at a Tolinku 404`() = assertNoFallbackAfter(
        MockResponse().setResponseCode(404).setBody("""{"error":"Route not found"}""")
    )

    @Test
    fun `stops at a Tolinku 404 with a web_url`() = assertNoFallbackAfter(
        MockResponse().setResponseCode(404)
            .setBody("""{"error":"Route not found","web_url":"https://example.com/fallback"}""")
    )

    @Test
    fun `stops at a Tolinku error with any other status`() = assertNoFallbackAfter(
        MockResponse().setResponseCode(500).setBody("""{"error":"Internal error"}""")
    )

    // Anything else asks the API

    private fun assertFallsBackAfter(reply: MockResponse) = runTest {
        server.enqueue(reply)
        api.enqueue(ok())
        val link = "https://click.mail.example.net/uni/ls/click?upn=XYZ"

        val resolved = links.resolve(link)

        assertEquals(listOf("https://click.mail.example.net/v1/api/path", step2), asked)
        val request = api.takeRequest()
        assertEquals("/v1/api/links/resolve", request.path)
        assertEquals("POST", request.method)
        assertEquals(apiKey, request.getHeader("X-API-Key"))
        assertEquals(link, JSONObject(request.body.readUtf8()).getString("url"))
        assertNotNull(resolved)
        assertEquals("4821", resolved!!.token)
        assertEquals("/order/4821/receipt", resolved.deepLinkPath)
        assertEquals("Order Receipt", resolved.route.name)
    }

    @Test
    fun `asks the API after a 404 that is not JSON`() = assertFallsBackAfter(
        MockResponse().setResponseCode(404).setBody("<html><body>Not Found</body></html>")
    )

    @Test
    fun `asks the API after a 404 with JSON but no error`() = assertFallsBackAfter(
        MockResponse().setResponseCode(404).setBody("{}")
    )

    @Test
    fun `asks the API after a 404 whose error is not a string`() = assertFallsBackAfter(
        MockResponse().setResponseCode(404).setBody("""{"error":{"message":"nope"}}""")
    )

    @Test
    fun `asks the API after a 200 that is not an answer`() = assertFallsBackAfter(
        MockResponse().setResponseCode(200).setBody("""{"ok":true}""")
    )

    @Test
    fun `asks the API after a network error, without retrying the first`() {
        assertFallsBackAfter(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `asks the API without the scheme being upgraded`() = runTest {
        // The configured base URL is taken as configured; only the link is
        // normalised.
        server.enqueue(MockResponse().setResponseCode(404).setBody(""))
        api.enqueue(ok())

        assertNotNull(links.resolve("https://click.mail.example.net/x"))
        assertTrue(asked.last().startsWith("http://localhost:"))
    }

    // One attempt each, five seconds each

    @Test
    fun `asks each host once, even when the answer is a 503`() = runTest {
        // A 503 is retried on every other request. Here a retry could count
        // the click twice, so neither question is asked again.
        server.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        server.enqueue(ok())
        api.dispatcher = QueueDispatcher()
        api.enqueue(MockResponse().setResponseCode(503).setBody("Service Unavailable"))
        api.enqueue(ok())

        assertNull(links.resolve("https://click.mail.example.net/x"))
        assertEquals(1, server.requestCount)
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `asks the API once, even after a network error`() = runTest {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        api.dispatcher = QueueDispatcher()
        api.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
        api.enqueue(ok())

        assertNull(links.resolve("https://click.mail.example.net/x"))
        assertEquals(1, server.requestCount)
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `gives each question five seconds in all and no silent retry`() {
        val client = TolinkuClient(apiKey = apiKey, baseUrl = "https://api.tolinku.com")
        val oneShot = client.oneShotClient()

        assertEquals(5_000, oneShot.callTimeoutMillis)
        assertFalse(oneShot.retryOnConnectionFailure)
    }

    @Test
    fun `moves on to the API when the link's host is too slow`() = runTest(timeout = 30.seconds) {
        // Real time, not test time: OkHttp's clock is the wall's.
        server.enqueue(ok().setHeadersDelay(20, TimeUnit.SECONDS))
        api.enqueue(ok())

        val started = System.nanoTime()
        val resolved = links.resolve("https://click.mail.example.net/x")
        val elapsedMs = (System.nanoTime() - started) / 1_000_000

        assertNotNull(resolved)
        assertEquals(1, api.requestCount)
        assertTrue("took ${elapsedMs}ms", elapsedMs in 4_500L..12_000L)
    }

    // When not to ask the API

    @Test
    fun `does not ask the API when the link is on the API's host`() = runTest {
        // Case and port do not make it a different host: the first answer was
        // the only one there is.
        val sameHost = linksWith(baseUrl = "http://LINKS.Example.com:9999")
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        assertNull(sameHost.resolve("https://links.example.com:8443/s7k2p9q/4821"))
        assertEquals(listOf("https://links.example.com:8443/v1/api/path"), asked)
    }

    @Test
    fun `does not ask the API when the link is on the API's host in capitals`() = runTest {
        val sameHost = linksWith(baseUrl = "https://links.example.com")
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

        assertNull(sameHost.resolve("https://LINKS.EXAMPLE.COM/s7k2p9q/4821"))
        assertEquals(listOf(step1), asked)
    }

    @Test
    fun `does not ask the API without a key to ask with`() = runTest {
        val keyless = linksWith(baseUrl = "http://localhost:${api.port}", key = "")
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        assertNull(keyless.resolve("https://click.mail.example.net/x"))
        assertEquals(listOf("https://click.mail.example.net/v1/api/path"), asked)
        assertEquals(0, api.requestCount)
    }

    // What the API says

    @Test
    fun `refuses an unsafe path from the API too`() = runTest {
        // Coming from the configured host is no reason to trust the answer
        // more: a full URL is still a redirect waiting to happen.
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        api.enqueue(ok(answer.replace("/order/4821/receipt", "//evil.example.com/take-over")))

        assertNull(links.resolve("https://click.mail.example.net/uni/ls/click?upn=XYZ"))
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `takes only a 200 from the API`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))
        api.enqueue(MockResponse().setResponseCode(201).setBody(answer))

        assertNull(links.resolve("https://click.mail.example.net/x"))
        assertEquals(1, api.requestCount)
    }

    @Test
    fun `returns null rather than throwing into a cold start`() = runTest {
        // This runs while the app is opening. An exception here is the
        // difference between a link that did not route and an app that did not
        // start.
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
        api.enqueue(MockResponse().setResponseCode(500).setBody("not json"))

        assertNull(links.resolve("https://links.example.com/s7k2p9q/4821"))
    }
}
