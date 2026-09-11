package com.tolinku.sdk

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

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
 * platform knows which Appspace is being asked about. The server here stands in
 * for that host, and the configured base URL is deliberately somewhere else, so
 * a request sent to the wrong one shows up as a failure rather than passing by
 * accident.
 */
class LinksTest {

    private lateinit var server: MockWebServer
    private lateinit var links: Links

    private val answer = """
        {
          "route": {"prefix": "order/{token}/receipt", "name": "Order Receipt", "template": "none", "link_type": "dynamic"},
          "token": "4821",
          "deep_link_path": "/order/4821/receipt",
          "appspace": {"name": "Example App", "slug": "example"}
        }
    """.trimIndent()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        val client = TolinkuClient(
            apiKey = "tolk_pub_test_key",
            baseUrl = "https://somewhere-else.example.com",
        )
        links = Links(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    /** The mock server's own origin, which stands in for the link's host. */
    private fun linkUrl(path: String): String =
        server.url("/").toString().trimEnd('/') + path

    @Test
    fun `asks the link its own host, with just the path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(answer))

        val link = links.resolve(linkUrl("/s7k2p9q/4821"))

        val request = server.takeRequest()
        assertEquals("/v1/api/path", request.path)
        assertEquals("/s7k2p9q/4821", JSONObject(request.body.readUtf8()).getString("path"))
        assertNotNull(link)
        assertEquals("4821", link!!.token)
        assertEquals("/order/4821/receipt", link.deepLinkPath)
        assertEquals("order/{token}/receipt", link.route.prefix)
        assertEquals("Order Receipt", link.route.name)
        assertEquals("dynamic", link.route.linkType)
    }

    @Test
    fun `leaves the query string out of the question`() = runTest {
        // A tapped link usually carries utm parameters, and they say nothing
        // about which route it is.
        server.enqueue(MockResponse().setResponseCode(200).setBody(answer))

        links.resolve(linkUrl("/s7k2p9q/4821?utm_source=qr"))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("/s7k2p9q/4821", body.getString("path"))
    }

    @Test
    fun `keeps an encoded slash in the token encoded`() = runTest {
        // Decoding first turns "/promo/a%2Fb" into a path three deep rather
        // than a token of "a/b" on "promo", which resolves to a different route
        // or to nothing.
        server.enqueue(MockResponse().setResponseCode(200).setBody(answer))

        links.resolve(linkUrl("/promo/a%2Fb"))

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("/promo/a%2Fb", body.getString("path"))
    }

    /**
     * resolve sends its question to a host taken from the URL it was given, so
     * an app resolving a link from somewhere it does not control is talking to
     * a stranger. Anything but a path is a redirect waiting to happen.
     */
    private fun assertRefusesPath(badPath: String) = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200)
                .setBody(answer.replace("/order/4821/receipt", badPath))
        )
        assertNull(links.resolve(linkUrl("/s7k2p9q/4821")))
    }

    @Test
    fun `refuses an answer that is a full URL`() = assertRefusesPath("https://evil.example.com/take-over")

    @Test
    fun `refuses an answer that is protocol relative`() = assertRefusesPath("//evil.example.com/take-over")

    @Test
    fun `refuses an answer that is a bare word`() = assertRefusesPath("order/4821")

    @Test
    fun `refuses an answer that is nothing`() = assertRefusesPath("")

    @Test
    fun `says nothing for a custom scheme link`() = runTest {
        // That one already carries the path the app wants.
        assertNull(links.resolve("example://order/4821/receipt"))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `says nothing for something that is not a link`() = runTest {
        assertNull(links.resolve("/order/4821"))
        assertNull(links.resolve(""))
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `returns null rather than throwing into a cold start`() = runTest {
        // This runs while the app is opening. An exception here is the
        // difference between a link that did not route and an app that did not
        // start. 404 is not retried, so one response is enough.
        server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))

        assertNull(links.resolve(linkUrl("/s7k2p9q/4821")))
    }

    @Test
    fun `returns null for a link this Appspace does not own`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))

        assertNull(links.resolve(linkUrl("/whatever/1")))
    }
}
