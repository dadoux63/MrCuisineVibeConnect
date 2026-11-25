package app.toque.core

import app.toque.core.json.Json
import app.toque.core.net.RecipeProgress
import app.toque.core.net.StatusServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicReference

class RecipeProgressTest {
    private val token = "0123456789abcdef0123456789abcdef"
    private val p = RecipeProgress(
        title = "Risotto", step = 3, stepCount = 12, stepText = "Ajoutez le riz.", waiting = true,
        waitingFor = "weigh", weighG = 300, weighLabel = "riz arborio",
        ingredients = listOf("300 g riz arborio"), stepSinceMs = 10_000,
    )

    @Test fun roundTripThroughMap() {
        assertEquals(p, RecipeProgress.fromMap(p.toMap()))
        assertNull(RecipeProgress.fromMap(emptyMap()))
    }

    @Test fun publicMapIsWhitelistedAndBounded() {
        val m = p.copy(stepText = "x".repeat(5_000), waitingFor = "rm -rf").publicMap(nowMs = 75_500)
        assertEquals(65L, m["step_elapsed_s"])
        assertEquals(RecipeProgress.MAX_TEXT, (m["step_text"] as String).length)
        assertNull("raison inconnue non publiée", m["waiting_for"])
        assertEquals(12, m["step_count"])
        assertEquals(
            setOf("title", "step", "step_count", "step_text", "waiting", "waiting_for", "weigh_g", "weigh_label", "ingredients", "step_elapsed_s"),
            m.keys,
        )
    }

    @Test fun statusServerPublishesRecipe() {
        val current = AtomicReference<RecipeProgress?>(p)
        val server = StatusServer(0, token, { null }, bindAddress = InetAddress.getLoopbackAddress(),
            recipe = { current.get()?.publicMap(20_000) })
        server.start()
        try {
            fun fetch(): Map<String, Any?> {
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                    s.soTimeout = 3_000
                    s.getOutputStream().write("GET ${StatusServer.PATH} HTTP/1.1\r\nAuthorization: Bearer $token\r\n\r\n".toByteArray())
                    val text = s.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                    @Suppress("UNCHECKED_CAST")
                    return Json.parse(text.substringAfter("\r\n\r\n")) as Map<String, Any?>
                }
            }
            @Suppress("UNCHECKED_CAST") val r = fetch()["recipe"] as Map<String, Any?>
            assertEquals("Risotto", r["title"])
            assertEquals(3L, r["step"])
            assertEquals(true, r["waiting"])
            assertEquals("weigh", r["waiting_for"])
            assertEquals(300L, r["weigh_g"])
            assertEquals(listOf("300 g riz arborio"), r["ingredients"])
            assertEquals(10L, r["step_elapsed_s"])
            current.set(null)
            assertTrue(fetch().containsKey("recipe"))
            assertNull(fetch()["recipe"])
        } finally {
            server.stop()
        }
    }
}
