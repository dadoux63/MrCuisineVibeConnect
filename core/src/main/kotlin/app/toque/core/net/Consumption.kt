package app.toque.core.net

import app.toque.core.json.Json
import java.io.File

/** Un ingrédient réellement pesé pendant une recette guidée. [id] croît strictement (sert de curseur). */
data class ConsumedEntry(
    val id: Long,
    val ingredient: String,
    val grams: Int,
    val recipe: String,
    /** Heure de la pesée, en millisecondes depuis 1970. */
    val at: Long,
) {
    /** Ce qui part sur le réseau : liste blanche, comme pour l'état de la machine. */
    fun publicMap(): Map<String, Any?> = linkedMapOf(
        "id" to id,
        "ingredient" to ingredient,
        "grams" to grams,
        "recipe" to recipe,
        "at" to at,
    )
}

/**
 * Journal des ingrédients pesés pendant les recettes guidées, pour la liste de courses (Home Assistant).
 *
 * - Écrit par l'interface, lu par le serveur d'état (processus « :machine ») : un simple fichier JSON du
 *   dossier de l'appli, réécrit en entier puis renommé, pour qu'un lecteur ne voie jamais un fichier à moitié écrit.
 * - Au plus [max] lignes : les plus anciennes partent d'abord.
 * - Rien dans ce journal ne pilote la machine.
 */
class ConsumptionLog(
    private val file: File,
    private val max: Int = MAX_ENTRIES,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    @Synchronized
    fun add(ingredient: String, grams: Int, recipe: String): ConsumedEntry? {
        val name = clean(ingredient, 60)
        if (name.isEmpty() || grams < MIN_GRAMS || grams > MAX_GRAMS) return null
        val list = read().toMutableList()
        val now = clock()
        // Curseur strictement croissant, même si l'horloge du robot recule (synchro réseau tardive).
        val id = maxOf(now, (list.lastOrNull()?.id ?: 0L) + 1)
        val e = ConsumedEntry(id, name, grams, clean(recipe, 80), now)
        list += e
        while (list.size > max) list.removeAt(0)
        write(list)
        return e
    }

    /** Toutes les entrées, de la plus ancienne à la plus récente ; vide si le fichier manque ou est abîmé. */
    fun entries(): List<ConsumedEntry> = read()

    /** Entrées postérieures au curseur [since] (0 : tout). */
    fun since(since: Long): List<ConsumedEntry> = read().filter { it.id > since }

    @Synchronized
    fun clear() {
        file.delete()
    }

    private fun read(): List<ConsumedEntry> = try {
        if (!file.isFile) emptyList() else parse(file.readText(Charsets.UTF_8))
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(list: List<ConsumedEntry>) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".part")
        tmp.writeText(Json.write(mapOf("entries" to list.map { it.publicMap() })), Charsets.UTF_8)
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    companion object {
        const val MAX_ENTRIES = 200
        /** En dessous, c'est le bruit de la balance (ou une pesée abandonnée), pas un ingrédient. */
        const val MIN_GRAMS = 5
        /** Le bol ne contient pas plus ; au-delà, la pesée n'a pas de sens. */
        const val MAX_GRAMS = 5_000

        private fun clean(s: String, max: Int) = s.replace(Regex("\\s+"), " ").trim().take(max)

        fun parse(text: String): List<ConsumedEntry> {
            val root = Json.parse(text) as? Map<*, *> ?: return emptyList()
            val arr = root["entries"] as? List<*> ?: return emptyList()
            return arr.mapNotNull { any ->
                val m = any as? Map<*, *> ?: return@mapNotNull null
                val id = (m["id"] as? Number)?.toLong() ?: return@mapNotNull null
                val name = m["ingredient"] as? String ?: return@mapNotNull null
                val grams = (m["grams"] as? Number)?.toInt() ?: return@mapNotNull null
                ConsumedEntry(id, name, grams, m["recipe"] as? String ?: "", (m["at"] as? Number)?.toLong() ?: id)
            }.sortedBy { it.id }
        }
    }
}

/**
 * Ce qui a été versé pendant UNE étape de pesée d'une recette guidée.
 *
 * - [arrive] : en arrivant sur l'étape ; [tared] dit si Toque a remis la balance à zéro (tare automatique).
 *   Sans tare, le poids affiché comprend le contenu du bol : rien n'est compté.
 * - [tare] : appui sur « Tare » pendant l'étape (plusieurs ingrédients pesés à la suite) ; le poids affiché
 *   juste avant est ajouté, puis la balance repart de zéro (l'étape est désormais tarée).
 * - [leave] : en quittant l'étape ; renvoie le total versé, ou null s'il n'y a rien de fiable à noter.
 */
class WeighTally {
    private var tared = false
    private var poured = 0

    fun arrive(tared: Boolean) {
        this.tared = tared
        poured = 0
    }

    fun tare(shownGrams: Int) {
        if (tared && shownGrams > 0) poured += shownGrams
        tared = true
    }

    fun leave(shownGrams: Int, hasSample: Boolean): Int? {
        if (!tared) return null
        val total = poured + if (hasSample && shownGrams > 0) shownGrams else 0
        tared = false
        poured = 0
        return total.takeIf { it >= ConsumptionLog.MIN_GRAMS }
    }
}

/** Nom d'ingrédient à noter pour l'étape de pesée [stepIndex] : celui de la liste d'ingrédients quand on le retrouve. */
object ConsumedName {
    fun of(r: app.toque.core.recipes.Recipe, stepIndex: Int): String? {
        val step = r.steps.getOrNull(stepIndex) ?: return null
        val w = step.weigh ?: return null
        val linked = try {
            app.toque.core.recipes.QuantityLinks.weighings(r)[stepIndex]?.let { r.allIngredients.getOrNull(it)?.name }
        } catch (_: Exception) { null }
        val phrase = app.toque.core.recipes.QuantityLinks.phrase(step.text, w.grams)
        return listOf(linked, phrase, w.label, step.text).firstNotNullOfOrNull { tidy(it) }
    }

    /** « échalotes (60 g) » → « Échalotes » ; « de beurre » → « Beurre » ; « farine. » → « Farine ». */
    fun tidy(s: String?): String? {
        val t = (s ?: return null).replace(Regex("\\([^)]*\\)"), " ").replace(Regex("\\s+"), " ")
            .trim().trimEnd('.', ',', ';', ':', '!').trim()
            .replace(Regex("^(de la |de l['’]|du |des |de |d['’])", RegexOption.IGNORE_CASE), "").trim()
        if (t.isEmpty()) return null
        return t.take(60).replaceFirstChar { it.uppercaseChar() }
    }
}
