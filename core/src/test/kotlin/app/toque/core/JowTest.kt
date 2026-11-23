package app.toque.core

import app.toque.core.json.Json
import app.toque.core.net.RecipeInbox
import app.toque.core.recipes.Jow
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.UserRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pages d'essai INVENTÉES (aucun contenu de Jow dans le dépôt : CGU art. 18), avec la même structure
 * de données que les pages jow.fr. Pour relire des conversions réelles sans les publier :
 * `JOW_PAGES=<dossier de pages .html> JOW_DUMP=<fichier> gradle :core:test --tests '*JowTest*'`.
 */
class JowTest {
    private val pages = listOf("essai-soupe", "essai-gratin")

    private fun page(name: String): String = javaClass.getResourceAsStream("/jow/$name.html")!!.readBytes().toString(Charsets.UTF_8)

    private fun steps(r: Jow.Result) = (r.recipe["steps"] as List<*>).map { it as Map<*, *> }

    @Test fun dump() {
        val dest = System.getenv("JOW_DUMP") ?: return
        val dir = System.getenv("JOW_PAGES")?.let(::File)
        val htmls = dir?.listFiles { f -> f.name.endsWith(".html") }?.sortedBy { it.name }?.map { it.readText() } ?: pages.map(::page)
        val out = StringBuilder()
        for (html in htmls) {
            val r = Jow.convertitPage(html, "", 3, "2026-11-23")
            out.append("=== ${r.title} (${r.covers} pers.) ${r.id} photo=${r.photoUrl}\n")
            out.append(Json.write(r.recipe["ingredients"], true)).append('\n')
            for (m in steps(r)) out.append(if (m["machine"] != null) "[R] " else "[ ] ").append(m["text"]).append('\n')
            out.append("approx: ${r.approx}\n\n")
            val recipe = UserRecipes.imported(RecipeCodec.fromMap(r.recipe, RecipeSource.USER)!!)
            assertNull(recipe.title, UserRecipes.problem(recipe))
        }
        File(dest).writeText(out.toString())
    }

    @Test fun allAreValidUserRecipes() {
        for (name in pages) {
            val r = Jow.convertitPage(page(name), "", 3, "2026-11-23")
            val recipe = UserRecipes.imported(RecipeCodec.fromMap(r.recipe, RecipeSource.USER)!!)
            assertTrue(recipe.id.startsWith("perso-jow-essai-"))
            assertNull(recipe.title, UserRecipes.problem(recipe))
            assertTrue(recipe.steps.filter { it.machine != null }.all { it.needsReview })
            assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe), RecipeSource.USER))
        }
    }

    @Test fun findsTheRecipeInASharedLink() {
        assertEquals("https://jow.fr/recipes/61cc2424c5541a0ede3f47b7",
            Jow.findUrl("Regarde https://app.jow.com/EC0U?action=recipe&recipeId=61cc2424c5541a0ede3f47b7&source=jow"))
        assertEquals("https://jow.fr/recipes/soupe-8l365tj97dswkvun0e5z", Jow.findUrl("https://jow.fr/recipes/soupe-8l365tj97dswkvun0e5z?coversCount=2"))
        assertNull(Jow.findUrl("https://www.cookomix.com/recettes/x/"))
        assertTrue(Jow.URL_RECETTE.matches("https://jow.fr/recipes/61cc2424c5541a0ede3f47b7"))
        assertTrue(Jow.URL_RECETTE.matches("https://jow.fr/recipes/soupe-8l365tj97dswkvun0e5z"))
        assertFalse(Jow.URL_RECETTE.matches("https://jow.fr.example.com/recipes/x"))
        assertTrue(Jow.URL_PHOTO.matches("https://static.jow.fr/550x550/recipes/DakJ1PehZ3Ni1w.png"))
        assertFalse(Jow.URL_PHOTO.matches("https://example.com/550x550/recipes/x.png"))
    }

    @Test fun theInboxAcceptsAJowShareWithCovers() {
        val r = RecipeInbox.parse("{\"url\": \"https://app.jow.com/EC0U?action=recipe&recipeId=61a4ef596a167b0f040af8ad&source=jow\", \"couverts\": 2}", "application/json")!!
        assertTrue(r.jow)
        assertEquals("https://jow.fr/recipes/61a4ef596a167b0f040af8ad", r.url)
        assertEquals(2, r.couverts)
        val t = RecipeInbox.parse("Soupe https://app.jow.com/EC0U?action=recipe&recipeId=61cc2424c5541a0ede3f47b7&source=jow", "text/plain")!!
        assertEquals(Jow.COUVERTS_DEFAUT, t.couverts)
        assertFalse(RecipeInbox.parse("https://www.cookomix.com/recettes/bechamel-thermomix/", "text/plain")!!.jow)
        assertNull(RecipeInbox.parse("https://jow.fr.evil.example/recipes/x", "text/plain"))
    }

    @Test fun simmeredSoupGoesToTheRobot() {
        val r = Jow.convertitPage(page("essai-soupe"), "", 3, "2026-11-23")
        assertEquals(3, r.covers)
        assertEquals("https://jow.fr/recipes/essai-soupe-poulet-aaaaaaaaaaaaaaaaaaaa", (r.recipe["notes"] as String).lines().first().removePrefix("Recette Jow : "))
        val s = steps(r)
        assertTrue(s[0]["machine"] == null)                                   // oignon en gros morceaux…
        val m = s.mapNotNull { it["machine"] as Map<*, *>? }
        // hacher les carottes 5 s ; oignon ajouté et haché grossièrement 4 s ; revenir 4 min (borne haute) ;
        // concentré 1 min ; mijoter 12 min + 5 min de montée, feu doux ; crème 2 min.
        assertEquals(listOf(5, 4, 240, 60, 17 * 60, 120), m.map { it["seconds"] })
        assertEquals(listOf(0, 0, 120, 100, 90, 100), m.map { it["tempC"] })
        assertEquals(listOf(5, 4, 1, 1, 1, 1), m.map { it["speed"] })
        assertEquals(listOf(false, false, true, true, true, true), m.map { it["reverse"] })
        assertTrue((s[2]["text"] as String).startsWith("Ajouter au hachis du bol"))
        assertTrue((s[3]["text"] as String).startsWith("Laisser le hachis dans le bol."))
        assertTrue(s.any { (it["text"] as String).contains("600 ml d'eau") })
        assertTrue(s.first { (it["text"] as String).contains("effiloch") }["machine"] == null)
        assertEquals("https://static.jow.fr/550x550/recipes/EssaiSoupe.png", r.photoUrl)
        val placard = (r.recipe["ingredients"] as List<*>)[1] as Map<*, *>
        assertEquals("Du placard", placard["group"])
    }

    @Test fun ovenFryingAndSlicingStayManual() {
        val r = Jow.convertitPage(page("essai-gratin"), "", 3, "2026-11-23")
        assertEquals(4, r.covers) // Jow ne propose que 2, 4, 6, 8
        val s = steps(r)
        assertEquals("Préchauffez le four à 200°C.", s[0]["text"])
        assertTrue(s[1]["machine"] == null)                                   // rondelles
        val m = s.mapNotNull { it["machine"] as Map<*, *>? }
        // râper 10 s ; eau 2 000 ml chauffée ; pâtes 10 min (temps du paquet à reporter).
        assertEquals(listOf(10, 16 * 60, 600), m.map { it["seconds"] })
        assertTrue(s.any { (it["text"] as String).startsWith("Égoutter") })
        assertTrue(s.first { (it["text"] as String).contains("dorer") }["machine"] == null)
        assertTrue(s.first { (it["text"] as String).contains("enfournez") }["machine"] == null)
        assertTrue((s.first { (it["text"] as String).startsWith("Mettre dans le bol") }["text"] as String).endsWith("Réserver à part."))
    }

    @Test fun mashedPotatoesWithTheWhisk() {
        val html = page("essai-gratin").replace("Servez dès la sortie du four.",
            "Écrasez les pommes de terre à la fourchette avec le beurre.")
        val r = Jow.convertitPage(html, "", 3, "2026-11-23")
        val s = steps(r)
        val puree = s.first { (it["text"] as String).contains("Écraser en purée") }
        val m = puree["machine"] as Map<*, *>
        assertEquals("whisk", m["accessory"])
        assertEquals(3, m["speed"])
        assertEquals(0, m["tempC"])
        assertEquals("Retirer le fouet.", s[s.indexOf(puree) + 1]["text"])
    }
}
