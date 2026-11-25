package app.toque.core

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.recipes.Cookomix
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.UserRecipes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Le portage Kotlin doit produire exactement ce que produit `cookomix2toque.py` (fichier
 * `*.attendu.json`, sorti par l'outil Python sur la même page, date fixée au 2026-11-23).
 */
class CookomixTest {
    private fun res(name: String): String =
        javaClass.getResourceAsStream("/cookomix/$name")!!.readBytes().toString(Charsets.UTF_8)

    @Test fun sameRecipeAsThePythonTool() {
        val r = Cookomix.convertitPage(res("essai-cas-difficiles.html"), "", "2026-11-23")
        val expected = Json.parse(res("essai-cas-difficiles.attendu.json")).obj()
        assertEquals(Json.write(expected["recipe"], true), Json.write(r.recipe, true))
        assertEquals(expected["approx"].list(), r.approx)
    }

    @Test fun theConvertedRecipeIsAValidUserRecipe() {
        val r = Cookomix.convertitPage(res("essai-cas-difficiles.html"), "", "2026-11-23")
        val recipe = UserRecipes.imported(RecipeCodec.fromMap(r.recipe, RecipeSource.USER)!!)
        assertEquals("perso-ckx-essai-cas-difficiles", recipe.id)
        assertNull(UserRecipes.problem(recipe))
        assertTrue(recipe.steps.filter { it.machine != null }.all { it.needsReview })
        assertEquals(30, recipe.steps.first { it.weigh != null }.weigh!!.grams)
        // Aller-retour par le format de fichier sans perte.
        assertEquals(recipe, RecipeCodec.decode(RecipeCodec.encode(recipe), RecipeSource.USER))
    }

    @Test fun refusesAPageWithoutStepByStep() {
        try {
            Cookomix.convertitPage(res("essai-brownie-sans-etapes.html"), "https://www.cookomix.com/recettes/brownie/", "2026-11-23")
            fail()
        } catch (e: Cookomix.Refus) {
            assertTrue(e.message!!.contains("sens inverse"))
        }
    }

    @Test fun findsTheAddressInSharedText() {
        assertEquals("https://www.cookomix.com/recettes/civet-lapin-thermomix/",
            Cookomix.findUrl("Civet de lapin au Thermomix https://www.cookomix.com/recettes/civet-lapin-thermomix/?utm_source=x"))
        assertEquals("https://www.cookomix.com/recettes/bechamel-thermomix/",
            Cookomix.findUrl("http://cookomix.com/recettes/bechamel-thermomix"))
        assertNull(Cookomix.findUrl("https://www.cookomix.com/categorie/desserts/"))
        assertNull(Cookomix.findUrl("https://evil.example/https:/www.cookomix.com.evil.example/recettes/x/"))
        assertNull(Cookomix.findUrl("https://192.168.1.1/recettes/x/"))
        assertNotNull(Cookomix.URL_RECETTE.find(Cookomix.findUrl("www.x https://cookomix.com/recettes/a-b/")!!))
    }

    @Test fun photoOnlyFromCookomix() {
        val r = Cookomix.convertitPage(res("essai-cas-difficiles.html"), "", "2026-11-23")
        assertEquals("https://www.cookomix.com/wp-content/uploads/2020/01/essai-1200x900.jpg", r.photoUrl)
        assertEquals("https://www.cookomix.com/wp-content/uploads/a.webp",
            Cookomix.photoUrl(mapOf("image" to mapOf("@type" to "ImageObject", "url" to "https://www.cookomix.com/wp-content/uploads/a.webp"))))
        assertNull(Cookomix.photoUrl(mapOf("image" to "https://evil.example/wp-content/uploads/a.jpg")))
        assertNull(Cookomix.photoUrl(mapOf("image" to "https://www.cookomix.com/wp-content/uploads/a.php")))
        assertNull(Cookomix.photoUrl(emptyMap()))
    }

    @Test fun entities() {
        assertEquals("Crème d'Isigny — 1/2 é", Cookomix.unescape("Cr&egrave;me d&#039;Isigny &mdash; 1/2 &#xe9;"))
        assertEquals("&inconnu;", Cookomix.unescape("&inconnu;"))
    }
}
