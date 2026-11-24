package app.toque.core

import app.toque.core.json.Json
import app.toque.core.machine.Snapshot
import app.toque.core.net.ConsumedName
import app.toque.core.net.ConsumptionLog
import app.toque.core.net.StatusServer
import app.toque.core.net.WeighTally
import app.toque.core.recipes.Ingredient
import app.toque.core.recipes.IngredientGroup
import app.toque.core.recipes.Recipe
import app.toque.core.recipes.Step
import app.toque.core.recipes.Weigh
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.Files

class ConsumptionTest {
    private fun tmpFile(): File = File(Files.createTempDirectory("conso").toFile(), "consommation.json")

    @Test fun logKeepsEntriesAcrossInstancesAndCapsSize() {
        val f = tmpFile()
        var now = 1_000L
        val log = ConsumptionLog(f, max = 3) { now }
        for (i in 1..5) { log.add("Farine $i", 100 + i, "Crêpes"); now += 10 }
        val again = ConsumptionLog(f).entries()
        assertEquals(listOf("Farine 3", "Farine 4", "Farine 5"), again.map { it.ingredient })
        assertEquals(listOf(103, 104, 105), again.map { it.grams })
        assertEquals("Crêpes", again.first().recipe)
    }

    @Test fun idsStayIncreasingWhenTheClockGoesBack() {
        var now = 5_000L
        val log = ConsumptionLog(tmpFile()) { now }
        val a = log.add("Lait", 500, "Béchamel")!!
        now = 1_000L
        val b = log.add("Beurre", 50, "Béchamel")!!
        assertTrue(b.id > a.id)
        assertEquals(listOf("Beurre"), log.since(a.id).map { it.ingredient })
        assertEquals(2, log.since(0).size)
    }

    @Test fun noiseAndNonsenseAreNotLogged() {
        val log = ConsumptionLog(tmpFile())
        assertNull(log.add("Sel", 3, "x"))
        assertNull(log.add("   ", 100, "x"))
        assertNull(log.add("Bol", 9_000, "x"))
        assertTrue(log.entries().isEmpty())
    }

    @Test fun damagedFileReadsAsEmpty() {
        val f = tmpFile()
        f.parentFile.mkdirs()
        f.writeText("{pas du json")
        val log = ConsumptionLog(f)
        assertTrue(log.entries().isEmpty())
        assertEquals("Sucre", log.add("Sucre", 80, "Gâteau")!!.ingredient)
    }

    @Test fun tallyCountsOnlyAfterATare() {
        val t = WeighTally()
        // Pas de tare : le poids comprend le bol, rien n'est noté.
        t.arrive(tared = false)
        assertNull(t.leave(850, hasSample = true))
        // Tare automatique puis 320 g versés.
        t.arrive(tared = true)
        assertEquals(320, t.leave(320, hasSample = true))
        // Deux ingrédients à la suite avec « Tare » entre eux.
        t.arrive(tared = true)
        t.tare(200)
        assertEquals(350, t.leave(150, hasSample = true))
        // « Tare » sans tare automatique : seul ce qui suit compte.
        t.arrive(tared = false)
        t.tare(900)
        assertEquals(60, t.leave(60, hasSample = true))
        // Rien versé, ou pas de mesure.
        t.arrive(tared = true)
        assertNull(t.leave(2, hasSample = true))
        t.arrive(tared = true)
        assertNull(t.leave(300, hasSample = false))
    }

    @Test fun nameComesFromTheIngredientList() {
        val r = Recipe(
            id = "r", title = "Velouté",
            ingredients = listOf(IngredientGroup(items = listOf(
                Ingredient("20", "g", "de beurre"), Ingredient("600", "g", "de potimarron (épluché)"),
            ))),
            steps = listOf(
                Step("Mettre 20 g de beurre dans le bol.", weigh = Weigh("Mettre 20 g de beurre dans le bol.", 20)),
                Step("Ajouter 600 g de potimarron.", weigh = Weigh("Ajouter 600 g de potimarron.", 600)),
                Step("Ajouter 50 g de lardons.", weigh = Weigh("", 50)),
                Step("Mixer 30 s."),
            ),
        )
        assertEquals("Beurre", ConsumedName.of(r, 0))
        assertEquals("Potimarron", ConsumedName.of(r, 1))
        assertEquals("Lardons", ConsumedName.of(r, 2))
        assertNull(ConsumedName.of(r, 3))
        assertEquals("Échalotes", ConsumedName.tidy("échalotes (60 g)."))
    }

    @Test fun statusServerPublishesTheJournalWithTheToken() {
        val token = "0123456789abcdef0123456789abcdef"
        var now = 10_000L
        val log = ConsumptionLog(tmpFile()) { now }
        val first = log.add("Farine", 250, "Crêpes")!!
        now += 1
        log.add("Lait", 500, "Crêpes")
        val server = StatusServer(0, token, { Snapshot() }, bindAddress = InetAddress.getLoopbackAddress())
        server.start()
        try {
            fun get(path: String, auth: Boolean = true): Pair<Int, String> =
                Socket(InetAddress.getLoopbackAddress(), server.localPort).use { s ->
                    s.soTimeout = 3_000
                    val req = "GET $path HTTP/1.1\r\nHost: robot\r\n" + (if (auth) "Authorization: Bearer $token\r\n" else "") + "\r\n"
                    s.getOutputStream().write(req.toByteArray(StandardCharsets.UTF_8))
                    val text = s.getInputStream().readBytes().toString(StandardCharsets.UTF_8)
                    text.substringAfter(' ').substringBefore(' ').toInt() to text.substringAfter("\r\n\r\n")
                }
            // Route absente tant que le journal n'est pas branché.
            assertEquals(404, get(StatusServer.CONSUMPTION_PATH).first)
            server.consumption = { since -> log.since(since).map { it.publicMap() } }
            assertEquals(401, get(StatusServer.CONSUMPTION_PATH, auth = false).first)
            val (code, body) = get(StatusServer.CONSUMPTION_PATH + "?since=${first.id}")
            assertEquals(200, code)
            @Suppress("UNCHECKED_CAST") val m = Json.parse(body) as Map<String, Any?>
            val entries = m["entries"] as List<*>
            assertEquals(1, entries.size)
            assertEquals("Lait", (entries[0] as Map<*, *>)["ingredient"])
            assertEquals(500L, (entries[0] as Map<*, *>)["grams"])
            assertEquals(2, ((Json.parse(get(StatusServer.CONSUMPTION_PATH).second) as Map<*, *>)["entries"] as List<*>).size)
            // L'état reste servi comme avant.
            assertEquals(200, get(StatusServer.PATH).first)
        } finally {
            server.stop()
        }
    }
}
