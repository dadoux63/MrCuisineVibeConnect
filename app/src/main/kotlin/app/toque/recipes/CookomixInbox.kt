package app.toque.recipes

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import app.toque.ToqueApp
import app.toque.core.i18n.tr
import app.toque.core.net.RecipeInbox
import app.toque.core.net.StatusServer
import app.toque.core.recipes.Cookomix
import app.toque.core.recipes.Jow
import app.toque.core.recipes.Portions
import app.toque.core.recipes.adjusted
import app.toque.core.recipes.RecipeCodec
import app.toque.core.recipes.RecipeSource
import app.toque.core.recipes.UserRecipes
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Reçoit, depuis le téléphone, l'adresse d'une recette Cookomix ou Jow : la page est téléchargée par le
 * robot, convertie (voir [Cookomix] et [Jow]) puis ajoutée à Mes recettes. Processus de l'interface uniquement : le
 * pilotage (« :machine ») n'est jamais concerné.
 *
 * Usage strictement personnel (CGU de Cookomix et de Jow) : recettes choisies par l'utilisateur, une
 * par une (10 au plus par envoi), rien n'est publié.
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
            req.html ?: if (req.jow) Jow.telecharge(req.url) else Cookomix.telecharge(req.url)
        } catch (e: Cookomix.Refus) {
            return RecipeInbox.Reply.error(422, e.message ?: "adresse refusée")
        } catch (e: Exception) {
            app.log.write("cookomix", "téléchargement impossible ${req.url} : $e")
            return RecipeInbox.Reply.error(502, tr("Téléchargement impossible depuis le robot : %s", e.message ?: e.toString()))
        }
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
        // Nombre de personnes voulu : celui envoyé par le téléphone, sinon celui du foyer (Réglages).
        val wanted = if (req.couverts > 0) req.couverts else app.prefs.householdServings
        // Les deux convertisseurs rendent la même chose : recette (format de fichier), adaptations, photo.
        val (map, approx, photoUrl) = try {
            if (req.jow) Jow.convertitPage(html, req.url, wanted, today).let { Triple(it.recipe, it.approx, it.photoUrl) }
            else Cookomix.convertitPage(html, req.url, today).let { Triple(it.recipe, it.approx, it.photoUrl) }
        } catch (e: Cookomix.Refus) {
            return RecipeInbox.Reply.error(422, e.message ?: "conversion refusée")
        }
        val site = if (req.jow) "Jow" else "Cookomix"
        val decoded = RecipeCodec.fromMap(map, RecipeSource.USER)
            ?: return RecipeInbox.Reply.error(422, "recette sans titre")
        var recipe = UserRecipes.imported(decoded)
        UserRecipes.problem(recipe)?.let { return RecipeInbox.Reply.error(422, it) }
        val before = app.recipes.get(recipe.id)
        // Sans photo demandée (ou si elle échoue), une photo déjà présente est gardée.
        if (before != null && before.image.isNotEmpty()) recipe = recipe.copy(image = before.image)
        val warnings = ArrayList(approx)
        var photo = false
        if (req.photo) {
            val src = photoUrl
            if (src == null) warnings += tr("pas de photo %s dans la page", site)
            else try {
                val name = recipe.id.removePrefix(UserRecipes.ID_PREFIX) + ".jpg"
                val bytes = if (req.jow) Jow.telechargePhoto(src) else Cookomix.telechargePhoto(src)
                savePhoto(bytes, File(app.recipes.imagesDir, name))
                recipe = recipe.copy(image = name)
                photo = true
            } catch (e: Exception) {
                app.log.write("cookomix", "photo non ajoutée $src : $e")
                warnings += tr("photo non ajoutée : %s", e.message ?: e.toString())
            }
        }
        app.recipes.upsertAll(listOf(recipe), rebuild = false)
        // La recette reste telle que la source l'a écrite ; l'adaptation au foyer passe par le même réglage
        // que le bouton − / + de la recette (quantités et pesées recalculées, temps inchangés).
        val people = Portions.importServings(recipe.servings, wanted)
        val previous = app.recipes.adjustmentsOf(recipe.id)
        app.recipes.setAdjustments(recipe.id, previous.withServings(if (people > 0) people else recipe.servings, recipe.servings))
        if (recipe.servings > 0 && people > 0 && people != wanted)
            warnings += tr("recette pour %d : Toque l'adapte de %d à %d personnes, quantités calculées pour %d", recipe.servings,
                Portions.range(recipe.servings).first, Portions.range(recipe.servings).last, people)
        if (recipe.servings <= 0) warnings += tr("nombre de personnes absent de la recette : quantités de la source")
        val shown = recipe.adjusted(app.recipes.adjustmentsOf(recipe.id))
        val load = Portions.bowlLoad(shown)
        if (load.overCapacity) warnings += tr("environ %s kg dans le bol : plus que sa contenance (3 l), à cuisiner en deux fois",
            String.format(Locale.FRANCE, "%.1f", load.grams / 1000.0))
        else if (load.overFastLimit) warnings += tr("environ %s kg dans le bol : pas de vitesse 10 ni de Turbo au-delà de 2,5 l de liquide",
            String.format(Locale.FRANCE, "%.1f", load.grams / 1000.0))
        if (load.overKnead) warnings += tr("%d g de farine à pétrir : 500 g au plus pour une pâte lourde, 600 g pour une pâte légère", load.flourKneaded)
        // « Cette semaine » : recettes prévues (Jow par défaut), retirées de la liste une fois cuisinées.
        if (req.week) app.recipes.addToWeek(recipe.id)
        // L'index de recherche est reconstruit à part : la réponse part tout de suite.
        Thread({
            try { app.recipes.refresh() } catch (e: Exception) { app.log.write("cookomix", "réindexation : $e") }
            Handler(Looper.getMainLooper()).post {
                try { Toast.makeText(app, tr("Recette reçue : %s", recipe.title), Toast.LENGTH_LONG).show() } catch (_: Exception) { }
            }
        }, "toque-inbox-index").start()
        val machine = recipe.steps.count { it.machine != null }
        app.log.write("cookomix", "${if (before == null) "ajoutée" else "mise à jour"} : ${recipe.id} (${recipe.steps.size} étapes, " +
            "$machine réglages, ${approx.size} adaptations, photo : ${if (photo) "oui" else "non"})")
        val message = tr("« %s » %s dans Mes recettes : %d ingrédients, %d étapes dont %d réglages machine, tous à vérifier sur le robot.",
            recipe.title, if (before == null) tr("ajoutée") else tr("mise à jour"), recipe.allIngredients.size, recipe.steps.size, machine) +
            (if (shown.servings > 0 && shown.servings != recipe.servings) " " + tr("Quantités pour %d personnes (recette : %d).", shown.servings, recipe.servings)
            else if (recipe.servings > 0) " " + tr("Pour %d personnes.", recipe.servings) else "") +
            (if (photo) " " + tr("Photo ajoutée.") else "") +
            (if (req.week) " " + tr("Ajoutée à « Cette semaine ».") else "")
        return RecipeInbox.Reply.ok(linkedMapOf(
            "message" to message,
            "id" to recipe.id,
            "title" to recipe.title,
            "updated" to (before != null),
            "steps" to recipe.steps.size,
            "machine_steps" to machine,
            "photo" to photo,
            "servings" to shown.servings,
            "week" to req.week,
            "warnings" to warnings,
        ))
    }

    private const val MAX_WIDTH = 800

    /** Photo réduite à 800 px de large au plus, en JPEG, écrite d'un coup (fichier temporaire puis renommé). */
    private fun savePhoto(bytes: ByteArray, dest: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0) throw IOException(tr("image illisible"))
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_WIDTH) sample *= 2
        var bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: throw IOException(tr("image illisible"))
        if (bmp.width > MAX_WIDTH) {
            val scaled = Bitmap.createScaledBitmap(bmp, MAX_WIDTH, bmp.height * MAX_WIDTH / bmp.width, true)
            if (scaled !== bmp) bmp.recycle()
            bmp = scaled
        }
        dest.parentFile?.mkdirs()
        val tmp = File(dest.parentFile, dest.name + ".part")
        try {
            FileOutputStream(tmp).use { if (!bmp.compress(Bitmap.CompressFormat.JPEG, 85, it)) throw IOException(tr("image illisible")) }
        } finally {
            bmp.recycle()
        }
        if (!tmp.renameTo(dest)) {
            dest.delete()
            if (!tmp.renameTo(dest)) { tmp.delete(); throw IOException(tr("écriture impossible")) }
        }
    }
}
