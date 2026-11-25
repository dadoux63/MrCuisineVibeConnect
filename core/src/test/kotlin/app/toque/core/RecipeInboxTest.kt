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
        assertTrue(body.contains("Importer une recette Cookomix ou Jow"))
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

    @Test fun sharedTextGetsAReadableAnswer() {
        val (code, body) = post("Partagé https://www.cookomix.com/recettes/a/", type = "text/plain")
        assertEquals(200, code)
        assertEquals("reçue", body)
        assertEquals("Erreur : jeton absent ou faux", post("https://www.cookomix.com/recettes/a/", auth = null, type = "text/plain").second)
        assertEquals("« X » ajoutée.\n\nÀ vérifier :\n• étape 9 : a\n• étape 30 : b",
            RecipeInbox.asText(mapOf("ok" to true, "message" to "« X » ajoutée.", "warnings" to listOf("étape 9 : a", "étape 30 : b"))))
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

    @Test fun severalAddressesAreImportedOneByOne() {
        inbox.stop()
        val pauses = ArrayList<Long>()
        inbox = RecipeInbox(0, { token }, { r ->
            received += r
            if (r.url.endsWith("/b/")) RecipeInbox.Reply.error(502, "injoignable")
            else RecipeInbox.Reply.ok(mapOf("message" to "reçue", "title" to "Titre " + r.url.takeLast(3), "warnings" to listOf("w")))
        }, bindAddress = InetAddress.getLoopbackAddress(), clock = { now }, pause = { pauses += it })
        inbox.start()
        val text = "https://www.cookomix.com/recettes/a/\nhttps://app.jow.com/x?action=recipe&recipeId=5be0510ef8d9630004f48b9f\n" +
            "https://www.cookomix.com/recettes/a/ https://www.cookomix.com/recettes/b/"
        val (code, body) = post(Json.write(mapOf("text" to text, "semaine" to true)))
        assertEquals(200, code)
        assertEquals(listOf("https://www.cookomix.com/recettes/a/", "https://jow.fr/recipes/5be0510ef8d9630004f48b9f",
            "https://www.cookomix.com/recettes/b/"), received.map { it.url })
        assertTrue(received.all { it.week })
        // Une pause entre deux téléchargements, pas avant le premier.
        assertEquals(listOf(RecipeInbox.MIN_INTERVAL_MS, RecipeInbox.MIN_INTERVAL_MS), pauses)
        val reply = Json.parse(body).obj()
        assertEquals(2L, (reply["imported"] as Number).toLong())
        assertTrue(reply["message"].toString().startsWith("2 recettes sur 3"))
        assertTrue(reply["message"].toString().contains("✗ https://www.cookomix.com/recettes/b/ : injoignable"))
    }

    @Test fun tooManyAddressesAreRefused() {
        val text = (1..RecipeInbox.MAX_BATCH + 1).joinToString("\n") { "https://www.cookomix.com/recettes/r$it/" }
        assertEquals(413, post(Json.write(mapOf("text" to text))).first)
        assertTrue(received.isEmpty())
        now += RecipeInbox.MIN_INTERVAL_MS
        val ten = (1..RecipeInbox.MAX_BATCH).joinToString("\n") { "https://www.cookomix.com/recettes/r$it/" }
        assertEquals(RecipeInbox.MAX_BATCH, RecipeInbox.parseAll(ten, "text/plain").size)
    }

    @Test fun thisWeekByDefaultForJowOnly() {
        val jow = RecipeInbox.parse("https://jow.fr/recipes/abc", "text/plain")!!
        val ckx = RecipeInbox.parse("https://www.cookomix.com/recettes/a/", "text/plain")!!
        assertTrue(jow.week)
        assertTrue(!ckx.week)
        assertTrue(!RecipeInbox.parse("""{"url":"https://jow.fr/recipes/abc","semaine":false}""", "application/json")!!.week)
        assertTrue(RecipeInbox.parse("""{"url":"https://www.cookomix.com/recettes/a/","semaine":true}""", "application/json")!!.week)
        // Une page déjà téléchargée ne vaut que pour la première adresse.
        val withPage = RecipeInbox.parseAll(Json.write(mapOf("text" to "https://jow.fr/recipes/a https://jow.fr/recipes/b", "html" to "<p>")), "application/json")
        assertEquals(listOf("https://jow.fr/recipes/a"), withPage.map { it.url })
    }

    @Test fun clearTheWeekOnceForTheWholeBatch() {
        val text = "https://jow.fr/recipes/a\nhttps://jow.fr/recipes/b\nhttps://jow.fr/recipes/c"
        val batch = RecipeInbox.parseAll(Json.write(mapOf("text" to text, "semaine" to true, "vider" to true)), "application/json")
        assertEquals(3, batch.size)
        val reset = batch[0].reset!!
        // Une seule demande de vidage, partagée par tout l'envoi.
        assertTrue(batch.all { it.reset === reset })
        assertTrue(!reset.cleared)
        assertTrue(reset.claim())
        assertTrue(!reset.claim())
        assertTrue(!batch[2].reset!!.claim())
        assertTrue(reset.cleared)
        // Sans « vider » (ou à false) : rien à vider.
        assertNull(RecipeInbox.parseAll(Json.write(mapOf("text" to text, "semaine" to true)), "application/json")[0].reset)
        assertNull(RecipeInbox.parseAll(Json.write(mapOf("text" to text, "vider" to false)), "application/json")[0].reset)
        assertNull(RecipeInbox.parseAll(text, "text/plain")[0].reset)
    }
}
