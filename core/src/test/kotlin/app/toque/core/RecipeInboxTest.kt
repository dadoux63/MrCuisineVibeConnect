package app.toque.core

import app.toque.core.json.Json
import app.toque.core.json.obj
import app.toque.core.net.RecipeInbox
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets

class RecipeInboxTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private var now = 1_000_000L
    private val received = ArrayList<RecipeInbox.Request>()
    private lateinit var inbox: RecipeInbox

    @Before fun open() {
        inbox = RecipeInbox(0, { token }, { r ->
            received += r
            RecipeInbox.Reply.ok(mapOf("message" to "reçue", "url" to r.url))
        }, bindAddress = InetAddress.getLoopbackAddress(), clock = { now })
        inbox.start()
    }

    @After fun close() = inbox.stop()

    private fun call(raw: ByteArray): Pair<Int, String> {
        Socket(InetAddress.getLoopbackAddress(), inbox.localPort).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write(raw)
            s.getOutputStream().flush()
            val text = s.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
            val code = text.substringAfter(' ').substringBefore(' ').toInt()
            return code to text.substringAfter("\r\n\r\n")
        }
    }

    private fun post(body: String, auth: String? = "Bearer $token", type: String = "application/json"): Pair<Int, String> {
        val b = body.toByteArray(StandardCharsets.UTF_8)
        val head = "POST ${RecipeInbox.API} HTTP/1.1\r\nHost: robot\r\nContent-Type: $type\r\n" +
            (auth?.let { "Authorization: $it\r\n" } ?: "") + "Content-Length: ${b.size}\r\n\r\n"
        return call(head.toByteArray(StandardCharsets.US_ASCII) + b)
    }

    @Test fun servesThePageWithoutSecret() {
        val (code, body) = call("GET / HTTP/1.1\r\nHost: robot\r\n\r\n".toByteArray())
        assertEquals(200, code)
        assertTrue(body.contains("Importer une recette Cookomix"))
        assertTrue(!body.contains(token))
    }

    @Test fun importsAnAddress() {
        val (code, body) = post("""{"url":"https://www.cookomix.com/recettes/bechamel-thermomix/"}""")
        assertEquals(200, code)
        assertEquals(true, Json.parse(body).obj()["ok"])
        assertEquals("https://www.cookomix.com/recettes/bechamel-thermomix/", received.single().url)
        assertNull(received.single().html)
        assertTrue(received.single().photo)
        now += RecipeInbox.MIN_INTERVAL_MS
        post("""{"url":"https://www.cookomix.com/recettes/bechamel-thermomix/","photo":false}""")
        assertTrue(!received[1].photo)
    }

    @Test fun acceptsSharedTextAndAPageWithAccents() {
        val (code, _) = post("Béchamel au Thermomix – Cookomix https://www.cookomix.com/recettes/bechamel-thermomix/", type = "text/plain")
        assertEquals(200, code)
        now += RecipeInbox.MIN_INTERVAL_MS
        val html = "<html>" + "é".repeat(50_000) + "</html>"
        val (code2, _) = post(Json.write(mapOf("url" to "https://cookomix.com/recettes/a/", "html" to html)))
        assertEquals(200, code2)
        assertEquals(html, received[1].html)
        assertEquals("https://www.cookomix.com/recettes/a/", received[1].url)
    }

    @Test fun refusesWithoutTokenOrWithAnotherSite() {
        assertEquals(401, post("""{"url":"https://www.cookomix.com/recettes/a/"}""", auth = null).first)
        assertEquals(401, post("""{"url":"https://www.cookomix.com/recettes/a/"}""", auth = "Bearer faux").first)
        assertEquals(400, post("""{"url":"http://192.168.1.1/admin"}""").first)
        assertEquals(400, post("""{"url":"https://www.cookomix.com.evil.example/recettes/a/"}""").first)
        assertTrue(received.isEmpty())
    }

    @Test fun oneRecipeAtATime() {
        assertEquals(200, post("""{"url":"https://www.cookomix.com/recettes/a/"}""").first)
        now += 1_000
        assertEquals(429, post("""{"url":"https://www.cookomix.com/recettes/b/"}""").first)
        now += RecipeInbox.MIN_INTERVAL_MS
        assertEquals(200, post("""{"url":"https://www.cookomix.com/recettes/b/"}""").first)
    }

    @Test fun otherRoutesAndMethods() {
        assertEquals(405, call("GET ${RecipeInbox.API} HTTP/1.1\r\n\r\n".toByteArray()).first)
        assertEquals(405, call("POST / HTTP/1.1\r\nContent-Length: 0\r\n\r\n".toByteArray()).first)
        assertEquals(404, call("GET /api/v1/status HTTP/1.1\r\n\r\n".toByteArray()).first)
        assertEquals(400, call("n'importe quoi\r\n\r\n".toByteArray()).first)
        val big = "POST ${RecipeInbox.API} HTTP/1.1\r\nAuthorization: Bearer $token\r\nContent-Length: ${RecipeInbox.MAX_BODY + 1}\r\n\r\n"
        assertEquals(413, call(big.toByteArray()).first)
        assertEquals(411, call("POST ${RecipeInbox.API} HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray()).first)
    }

    @Test fun anImporterFailureIsAnAnswerNotACrash() {
        inbox.stop()
        inbox = RecipeInbox(0, { token }, { throw IllegalStateException("boum") }, bindAddress = InetAddress.getLoopbackAddress(), clock = { now })
        inbox.start()
        val (code, body) = post("""{"url":"https://www.cookomix.com/recettes/a/"}""")
        assertEquals(500, code)
        assertTrue(body.contains("boum"))
        now += RecipeInbox.MIN_INTERVAL_MS
        assertEquals(500, post("""{"url":"https://www.cookomix.com/recettes/a/"}""").first)
    }
}
