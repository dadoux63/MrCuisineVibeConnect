package app.toque.recipes

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.net.RecipeInbox
import app.toque.core.net.StatusServer
import app.toque.core.recipes.Cookomix
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.UserRecipes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reçoit, depuis le téléphone, l'adresse d'une recette Cookomix : la page est téléchargée par le robot,
 * convertie (voir [Cookomix]) puis ajoutée à Mes recettes. Processus de l'interface uniquement : le
 * pilotage (« :machine ») n'est jamais concerné.
 *
 * Usage strictement personnel (CGU de Cookomix) : une recette à la fois, rien n'est publié.
 */
object CookomixInbox {
    private var server: RecipeInbox? = null

    val port: Int get() = RecipeInbox.DEFAULT_PORT

    /** Ouvre ou ferme le serveur selon les réglages. Un échec (port pris…) est noté au journal. */
    @Synchronized
    fun apply(app: ToqueApp) {
        val want = app.prefs.cookomixInbox
        if (want == (server != null)) return
        server?.stop()
        server = null
        if (!want) return
        if (app.prefs.homeToken.length < StatusServer.MIN_TOKEN) app.prefs.homeToken = StatusServer.newToken()
        val s = RecipeInbox(
            port = RecipeInbox.DEFAULT_PORT,
            token = { app.prefs.homeToken },
            importer = { req -> import(app, req) },
            log = { line -> app.log.write("cookomix", line) },
        )
        try {
            s.start()
            server = s
        } catch (e: Exception) {
            app.log.write("cookomix", "réception impossible à ouvrir : $e")
        }
    }

    private fun import(app: ToqueApp, req: RecipeInbox.Request): RecipeInbox.Reply {
        val html = try {
            req.html ?: Cookomix.telecharge(req.url)
        } catch (e: Cookomix.Refus) {
            return RecipeInbox.Reply.error(422, e.message ?: "adresse refusée")
        } catch (e: Exception) {
            app.log.write("cookomix", "téléchargement impossible ${req.url} : $e")
            return RecipeInbox.Reply.error(502, tr("Téléchargement impossible depuis le robot : %s", e.message ?: e.toString()))
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        val converted = try {
            Cookomix.convertitPage(html, req.url, today)
        } catch (e: Cookomix.Refus) {
            return RecipeInbox.Reply.error(422, e.message ?: "conversion refusée")
        }
        val decoded = RecipeCodec.fromMap(converted.recipe, RecipeSource.USER)
            ?: return RecipeInbox.Reply.error(422, "recette sans titre")
        var recipe = UserRecipes.imported(decoded)
        UserRecipes.problem(recipe)?.let { return RecipeInbox.Reply.error(422, it) }
        val before = app.recipes.get(recipe.id)
        // Une photo déjà ajoutée à la main est gardée (comme l'outil du Mac).
        if (before != null && before.image.isNotEmpty()) recipe = recipe.copy(image = before.image)
        app.recipes.upsertAll(listOf(recipe), rebuild = false)
        // L'index de recherche est reconstruit à part : la réponse part tout de suite.
        Thread({
            try { app.recipes.refresh() } catch (e: Exception) { app.log.write("cookomix", "réindexation : $e") }
            Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(app, tr("Recette reçue : %s", recipe.title), Toast.LENGTH_LONG).show() } catch (_: Exception) { }
            }
        }, "toque-inbox-index").start()
        val machine = recipe.steps.count { it.machine != null }
        app.log.write("cookomix", "${if (before == null) "ajoutée" else "mise à jour"} : ${recipe.id} (${recipe.steps.size} étapes, " +
            "$machine réglages, ${converted.approx.size} adaptations)")
        val message = tr("« %s » %s dans Mes recettes : %d ingrédients, %d étapes dont %d réglages machine, tous à vérifier sur le robot.",
            recipe.title, if (before == null) tr("ajoutée") else tr("mise à jour"), recipe.allIngredients.size, recipe.steps.size, machine)
        return RecipeInbox.Reply.ok(linkedMapOf(
            "message" to message,
            "id" to recipe.id,
            "title" to recipe.title,
            "updated" to (before != null),
            "steps" to recipe.steps.size,
            "machine_steps" to machine,
            "warnings" to converted.approx,
        ))
    }
}
