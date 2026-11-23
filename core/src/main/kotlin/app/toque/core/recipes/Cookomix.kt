package app.toque.core.recipes

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import java.text.Normalizer
import kotlin.math.ceil

/**
 * Convertit UNE recette Cookomix (écrite pour le Thermomix) en recette perso Toque, pour un usage
 * strictement privé sur son propre robot. Portage fidèle de l'outil `cookomix2toque.py` : mêmes
 * règles, mêmes textes, même identifiant `perso-ckx-…` (une recette importée par l'un ou l'autre
 * est remplacée, pas dupliquée).
 *
 * - Chaque étape machine est marquée « à vérifier » : Toque demande de la confirmer avant de la
 *   lancer, et ses propres sécurités restent appliquées au lancement.
 * - Les réglages Thermomix sans équivalent exact sont ramenés vers le plus doux et signalés (⚠).
 * - Ni photo ni texte de présentation ne sont recopiés ; l'auteur et l'adresse sont cités.
 *
 * Les CGU de Cookomix interdisent la reproduction : outil personnel, une recette à la fois, rien
 * n'est publié ni partagé. Ne pas proposer en amont sans l'accord de Cookomix.
 */
object Cookomix {
    // --- Monsieur Cuisine Connect, d'après Toque (Mcc.kt, Safety.kt) ---------------------------------
    val TEMPERATURES_MC = listOf(37, 45, 50, 55, 60, 65, 70, 75, 80, 85, 90, 95, 100, 105, 110, 115, 120, 125, 130)
    private const val VITESSE_MAX_CHAUFFE = 3
    private const val VITESSE_MAX_INVERSE = 3
    private const val VITESSE_MAX_FOUET = 4
    private const val PETRIR_MAX_S = 120
    private const val DUREE_MAX_S = 99 * 60
    private const val DUREE_MAX_RAPIDE_S = 15 * 60
    private const val VAROMA_C = 120

    /** Seules adresses acceptées : une page de recette de cookomix.com, en HTTPS. */
    val URL_RECETTE = Regex("^https://(www\\.)?cookomix\\.com/recettes/[^/?#]+/?$")
    private val URL_DANS_TEXTE = Regex("https?://(?:www\\.)?cookomix\\.com/recettes/([A-Za-z0-9%._~-]+)", RegexOption.IGNORE_CASE)

    private val CATEGORIES = mapOf(
        "dessert" to "Desserts", "plat principal" to "Plats principaux", "entree" to "Entrées",
        "accompagnement" to "Accompagnements", "soupe" to "Soupes", "sauce" to "Sauces",
        "boisson" to "Boissons", "petit-dejeuner" to "Petit-déjeuner", "aperitif" to "Snacks",
    )

    private val UNITES = listOf(
        "grammes?" to "g", "kilogrammes?" to "kg", "millilitres?" to "ml", "centilitres?" to "cl",
        "litres?" to "l", "cuill[eè]res? [àa] caf[ée]" to "c. à c.", "cuill[eè]res? [àa] soupe" to "c. à s.",
    ).map { (motif, court) -> Regex(motif, RegexOption.IGNORE_CASE) to court }

    /** Espaces, y compris insécables (le `\s` de Python les reconnaît, pas celui de Java). */
    private const val WS = "[\\s\\u00A0\\u2007\\u202F]"
    private val I = RegexOption.IGNORE_CASE

    class Result(val recipe: Map<String, Any?>, val approx: List<String>) {
        val title: String get() = recipe["title"] as String
        val id: String get() = recipe["id"] as String
    }

    class Refus(message: String) : Exception(message)

    /**
     * Retrouve l'adresse d'une recette dans un texte partagé (« Civet de lapin https://www.cookomix.com/recettes/… »),
     * ramenée à sa forme canonique ; null si le texte n'en contient pas.
     */
    fun findUrl(text: String): String? {
        val m = URL_DANS_TEXTE.find(text) ?: return null
        val nom = m.groupValues[1].trimEnd('.', ',', ')')
        if (nom.isEmpty()) return null
        return "https://www.cookomix.com/recettes/$nom/"
    }

    // ---------------------------------------------------------------------------------------------
    fun sansAccents(t: String): String =
        Normalizer.normalize(t, Normalizer.Form.NFD).filter { Character.getType(it) != Character.NON_SPACING_MARK.toInt() }

    fun slug(t: String, n: Int = 40): String {
        val s = sansAccents(t).lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
        return s.take(n).trim('-').ifEmpty { "recette" }
    }

    /** Texte d'étape : sans balises <timer/>, sans « du Thermomix ». */
    fun nettoie(t: String?): String {
        var s = (t ?: "").replace(Regex("<[^>]+>"), "")
        s = unescape(s)
        s = s.replace(Regex("$WS+dans le bol du Thermomix", I), " dans le bol")
        s = Regex("\\b(du|le|au) Thermomix\\b", I).replace(s) { m ->
            when (m.groupValues[1].lowercase()) { "du" -> "du robot"; "le" -> "le robot"; else -> "au robot" }
        }
        s = s.replace(Regex("\\bThermomix\\b", I), "robot")
        s = s.replace(Regex("gobelet doseur", I), "bouchon doseur")
        return s.replace(Regex("$WS+"), " ").trim()
    }

    fun dureeTxt(s: Int): String {
        if (s < 60) return "$s s"
        val m = s / 60
        val r = s % 60
        return "$m min" + if (r != 0) " $r s" else ""
    }

    fun isoMinutes(v: Any?): Int {
        val m = Regex("^P(?:T)?(?:(\\d+)H)?(?:(\\d+)M)?").find(pyStr(v)) ?: return 0
        return (m.groupValues[1].toIntOrNull() ?: 0) * 60 + (m.groupValues[2].toIntOrNull() ?: 0)
    }

    /** Température du tableau du Connect, jamais au-dessus de celle demandée (sauf < 37 °C). */
    fun temperatureMc(t: Int): Pair<Int, String?> {
        if (t in TEMPERATURES_MC) return t to null
        if (t < TEMPERATURES_MC[0]) return TEMPERATURES_MC[0] to "$t °C absent : 37 °C (minimum du robot)"
        val plusBas = TEMPERATURES_MC.filter { it <= t }.max()
        return plusBas to "$t °C absent : $plusBas °C (palier inférieur)"
    }

    // --- Lecture de la page ----------------------------------------------------------------------
    class Page(val recette: Map<String, Any?>?, val pas: Map<String, Any?>?, val canonical: String)

    fun litPage(page: String): Page {
        var recette: Map<String, Any?>? = null
        val ld = Regex("<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
        for (bloc in ld.findAll(page)) {
            val d = try { Json.parse(bloc.groupValues[1]) } catch (_: Exception) { continue }
            val items: List<Any?> = when (d) {
                is List<*> -> d
                is Map<*, *> -> if (d.containsKey("@graph")) d["@graph"].list() else listOf(d)
                else -> emptyList()
            }
            for (x in items) if (x is Map<*, *> && x["@type"] == "Recipe") recette = x.obj()
        }
        var pas: Map<String, Any?>? = null
        val m = Regex("var\\s+stepbystep\\s*=\\s*(\\{.*?\\})\\s*;?\\s*</script>", RegexOption.DOT_MATCHES_ALL).find(page)
        if (m != null) {
            val brut = m.groupValues[1]
            pas = try {
                Json.parse(brut).obj()
            } catch (_: Exception) {
                try { Json.parse(brut.replace(Regex(",\\s*([}\\]])"), "$1")).obj() } catch (_: Exception) { null }
            }
        }
        val canon = Regex("<link[^>]+rel=\"canonical\"[^>]+href=\"([^\"]+)\"").find(page)?.groupValues?.get(1).orEmpty()
        return Page(recette, pas, canon)
    }

    // --- Ingrédients -----------------------------------------------------------------------------
    fun ingredient(brut: String): Map<String, Any?> {
        val ligne = unescape(brut).trim()
        val cut = ligne.lastIndexOf(" - ")
        val nom = if (cut >= 0) ligne.substring(0, cut) else ""
        if (nom.isEmpty()) return linkedMapOf("qty" to "", "unit" to "", "name" to ligne, "note" to "")
        val qte = ligne.substring(cut + 3).trim()
        val m = Regex("^([\\d.,/¼½¾]+)\\s*(.*)$").find(qte)
        var q = m?.groupValues?.get(1) ?: ""
        var u = m?.groupValues?.get(2)?.trim() ?: qte
        for ((motif, court) in UNITES) if (motif.matches(u)) { u = court; break }
        q = q.replace(".", ",")
        return linkedMapOf("qty" to q, "unit" to u, "name" to nom.trim(), "note" to "")
    }

    // --- Étapes ----------------------------------------------------------------------------------
    private class Etat {
        var fouet = false
        var varoma = false
        var couvercle = ""
        val approx = ArrayList<String>()
    }

    /** `float(str(v).replace(",", "."))` de Python, ou null. */
    private fun nombre(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble()
        else -> pyStr(v).trim().replace(',', '.').let { s ->
            if (s.isEmpty() || !Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$").matches(s)) null else s.toDouble()
        }
    }

    /** Valeur « vraie » au sens de Python (`x or défaut`). */
    private fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is String -> v.isNotEmpty()
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    private fun pyStr(v: Any?): String = when (v) {
        null -> "None"
        is Double -> if (v == Math.floor(v) && !v.isInfinite()) String.format(java.util.Locale.ROOT, "%.1f", v) else v.toString()
        else -> v.toString()
    }

    private fun or(v: Any?, defaut: String): String = if (truthy(v)) pyStr(v) else defaut

    /** `%g` de Python pour une température (98.0 → « 98 », 37.5 → « 37.5 »). */
    private fun fmtG(d: Double): String = if (d == Math.floor(d) && Math.abs(d) < 1e15) d.toLong().toString() else d.toString()

    private fun etapeMachine(s: Map<String, Any?>, e: Etat, nEtape: Int): List<Map<String, Any?>> {
        var verbe = or(s["action_verb"], "Mélanger").trim()
        verbe = verbe.take(1).uppercase() + verbe.drop(1)
        val duree = (nombre(s["mix_duration"]) ?: 0.0).toInt()
        val tBrut = or(s["mix_temp"], "0").trim().lowercase()
        val vBrut = or(s["mix_speed"], "").trim().lowercase()
        val inverse = or(s["direction"], "").lowercase() == "inverse"
        val extra = nettoie(or(s["action_desc"], ""))

        val tm = arrayListOf(dureeTxt(duree))
        if (tBrut == "varoma") tm += "Varoma"
        else if ((nombre(tBrut) ?: 0.0) > 0) tm += fmtG(nombre(tBrut)!!) + " °C"
        tm += when (vBrut) { "0" -> "vitesse cuillère"; "pétrin" -> "vitesse pétrin"; "turbo" -> "Turbo"; else -> "vitesse $vBrut" }
        if (inverse) tm += "sens inverse"
        val rappel = "Thermomix : " + tm.joinToString(" / ")

        val notes = ArrayList<String>()
        var program = "manuel"
        var seconds = maxOf(1, duree)
        var speed = 1
        var tempC = 0
        var reverse = false
        var accessory = if (e.fouet) "whisk" else "blade"
        var pulses = 0

        if ("p" in vBrut && "trin" in vBrut) {
            program = "petrir"; accessory = "blade"
            if (duree > PETRIR_MAX_S) {
                seconds = PETRIR_MAX_S
                notes += "pétrissage ramené de ${dureeTxt(duree)} à 2 min (limite du robot) : finir à la main si besoin"
            }
            if (tBrut != "0" && tBrut != "") notes += "pas de chauffe en pétrissage sur le Monsieur Cuisine"
        } else if (vBrut == "turbo") {
            program = "turbo"; seconds = 0
            pulses = maxOf(1, ceil((if (duree != 0) duree else 1).toDouble()).toInt())
            notes += "Turbo : impulsions à lancer à la main, contenu froid uniquement"
        } else {
            var vd = nombre(vBrut)
            if (vd == null) {
                vd = 1.0
                notes += "vitesse « $vBrut » non reconnue : vitesse 1"
            }
            if (vd < 1) {
                notes += "vitesse cuillère : vitesse 1 (la plus lente du robot)"
                vd = 1.0
            } else if (vd != Math.floor(vd)) {
                notes += "vitesse ${fmtG(vd)} : vitesse ${vd.toInt()}"
            }
            var v = vd.toInt()

            if (tBrut == "varoma") {
                // Cuisson manuelle à 120 °C plutôt que le programme Vapeur : la lame continue de
                // tourner comme sur le Thermomix (le contenu du bol ne reste pas sans mélange).
                tempC = VAROMA_C
                notes += "température Varoma : 120 °C"
            } else if ((nombre(tBrut) ?: 0.0) > 0) {
                val (t, n) = temperatureMc(nombre(tBrut)!!.toInt())
                tempC = t
                if (n != null) notes += n
            }

            if (tempC > 0 && v > VITESSE_MAX_CHAUFFE) {
                notes += "vitesse $v en chauffe : vitesse $VITESSE_MAX_CHAUFFE (limite de Toque)"
                v = VITESSE_MAX_CHAUFFE
            }
            if (inverse && v > VITESSE_MAX_INVERSE) {
                notes += "vitesse $v en sens inverse : vitesse $VITESSE_MAX_INVERSE"
                v = VITESSE_MAX_INVERSE
            }
            if (e.fouet && v > VITESSE_MAX_FOUET) {
                notes += "vitesse $v avec le fouet : vitesse $VITESSE_MAX_FOUET"
                v = VITESSE_MAX_FOUET
            }
            if (v > 6 && seconds > DUREE_MAX_RAPIDE_S) {
                notes += "au-delà de la vitesse 6, 15 min au plus"
                seconds = DUREE_MAX_RAPIDE_S
            }
            speed = v
            reverse = inverse
        }

        if (extra.isNotEmpty() && Regex("progressivement", I).containsMatchIn(extra))
            notes += "montée progressive de la vitesse : à faire à la main"

        val reglage = ArrayList<String>()
        when (program) {
            "petrir" -> reglage += "Pétrir " + dureeTxt(seconds)
            "turbo" -> reglage += "Turbo × $pulses"
            else -> {
                reglage += dureeTxt(seconds)
                if (tempC != 0) reglage += "$tempC °C"
                reglage += "vitesse $speed"
                if (reverse) reglage += "sens inverse"
            }
        }
        var phrase = "$verbe : ${reglage.joinToString(" / ")}."
        if (extra.isNotEmpty()) phrase += " " + extra.trimEnd('.') + "."
        if (e.couvercle.isNotEmpty()) {
            phrase = e.couvercle + " " + phrase
            e.couvercle = ""
        }
        phrase += " ($rappel)"
        if (notes.isNotEmpty()) {
            phrase += " ⚠ " + notes.joinToString(" ; ") + "."
            e.approx += "étape $nEtape : " + notes.joinToString(" ; ")
        }

        // Plus de 99 min : plusieurs étapes à la suite.
        val etapes = ArrayList<Map<String, Any?>>()
        var reste = seconds
        var morceau = 0
        while (true) {
            val sec = if (program != "turbo") minOf(reste, DUREE_MAX_S) else seconds
            val machine = linkedMapOf<String, Any?>(
                "program" to program, "seconds" to sec, "speed" to speed, "tempC" to tempC,
                "reverse" to reverse, "accessory" to accessory, "pulses" to pulses,
            )
            val texte = if (morceau == 0) phrase else "Suite de l'étape précédente : ${dureeTxt(sec)}."
            etapes += linkedMapOf("text" to texte, "machine" to machine, "needsReview" to true)
            reste -= sec
            morceau++
            if (program == "turbo" || reste <= 0) break
        }
        if (morceau > 1) e.approx += "étape $nEtape : plus de 99 min, découpée en $morceau étapes"
        return etapes
    }

    private val OU = mapOf(
        "thermomix" to "dans le bol", "varoma" to "dans le cuiseur vapeur (Varoma)", "recipient" to "dans un récipient",
        "panier" to "dans le panier de cuisson",
    )

    private fun etapeTexte(s: Map<String, Any?>, e: Etat): List<Map<String, Any?>> {
        val a = or(s["action"], "")
        var desc = nettoie(or(s["action_desc"], ""))
        fun t(x: String): List<Map<String, Any?>> = listOf(linkedMapOf("text" to x))
        when (a) {
            "add" -> {
                val verbe = or(s["action_verb"], "Ajouter").trim()
                val ouBrut = or(s["addwhere"], "")
                var ou = OU[ouBrut.lowercase()] ?: nettoie(ouBrut)
                // Cookomix écrit souvent le lieu dans la description (« … dans le Varoma ») : pas deux fois.
                if (ouBrut.lowercase() != "thermomix" && Regex("\\b(dans|sur)\\b|r[ée]partir", I).containsMatchIn(desc)) {
                    desc = desc.replace(Regex("\\bdans le Varoma\\b", I), "dans le cuiseur vapeur (Varoma)")
                    ou = ""
                }
                val txt = if (ou.isNotEmpty()) "$verbe $desc $ou." else "$verbe $desc."
                val st = linkedMapOf<String, Any?>("text" to txt.replace(Regex("$WS+"), " "))
                val q = nombre(s["action_extra"])
                if (or(s["abbreviation"], "") == "g" && ouBrut == "thermomix" && q != null && q >= 1) {
                    val nom = desc.replace(Regex("^[\\d.,]+\\s*grammes?\\s*(de |d['’])?", I), "").trim()
                    st["weigh"] = linkedMapOf("label" to nom.take(60), "grams" to Math.rint(q).toInt())
                }
                return listOf(st)
            }
            "addtop" -> { e.couvercle = "Fermer le couvercle avec le bouchon doseur."; return emptyList() }
            "addtop-w" -> { e.couvercle = "Fermer le couvercle sans le bouchon doseur."; return emptyList() }
            "add-accessory" -> {
                val acc = or(s["addaccessory"], "").lowercase()
                if ("whisk" in acc || "fouet" in acc || "papillon" in acc) {
                    e.fouet = true
                    return t("Mettre le fouet sur la lame.")
                }
                if ("varoma" in acc) {
                    e.varoma = true
                    return t("Poser le cuiseur vapeur (équivalent du Varoma) sur le couvercle.")
                }
                return if (desc.isNotEmpty()) t(desc) else emptyList()
            }
            "remove-accessory" -> {
                val low = desc.lowercase()
                if ("varoma" in low) { e.varoma = false; return t("Retirer le cuiseur vapeur.") }
                if ("fouet" in low || "papillon" in low || "whisk" in low) { e.fouet = false; return t("Retirer le fouet.") }
                e.fouet = false
                return if (desc.isNotEmpty()) t(desc) else emptyList()
            }
        }
        return if (desc.isNotEmpty()) t(desc) else emptyList()
    }

    // --- Recette complète ------------------------------------------------------------------------
    /**
     * [aujourdhui] : date du jour au format AAAA-MM-JJ (citée dans les notes).
     * @throws Refus si la page n'a pas les données nécessaires.
     */
    fun convertit(recette: Map<String, Any?>?, pas: Map<String, Any?>?, url: String, aujourdhui: String): Result {
        if (recette == null || recette.isEmpty()) throw Refus("pas de données de recette (schema.org) dans la page")
        val steps = pas?.get("steps")
        if (pas == null || !truthy(steps)) throw Refus("pas d'étapes détaillées (stepbystep) dans la page : le sens inverse serait perdu, conversion refusée")
        var titre = nettoie(or(pas["title"], ""))
        if (titre.isEmpty()) titre = or(recette["name"], "").replace(Regex("\\s+(au|avec le) thermomix$", I), "")
        titre = titre.trim()
        val e = Etat()
        val etapes = ArrayList<Map<String, Any?>>()
        for (sAny in steps.list()) {
            val s = sAny.obj()
            if (or(s["action"], "") == "mix") etapes += etapeMachine(s, e, etapes.size + 1)
            else etapes += etapeTexte(s, e)
        }
        if (e.couvercle.isNotEmpty()) etapes += linkedMapOf<String, Any?>("text" to e.couvercle)

        val auteurAny = recette["author"]
        val auteur = if (auteurAny is Map<*, *>) (auteurAny["name"] as? String) ?: "" else ""
        val rendementTxt = recette["recipeYield"].let { y -> if (y is List<*>) y.joinToString(" ") { pyStr(it) } else or(y, "") }
        val rendement = Regex("\\d+").find(rendementTxt)?.value
        val prep = isoMinutes(if (truthy(recette["prepTime"])) recette["prepTime"] else "")
        val cuisson = isoMinutes(if (truthy(recette["cookTime"])) recette["cookTime"] else "")
        val catBrut = or(recette["recipeCategory"], "")
        val cat = CATEGORIES[sansAccents(catBrut).lowercase()] ?: catBrut
        var notes = "Recette Cookomix${if (auteur.isNotEmpty()) " de $auteur" else ""} : $url\n" +
            "Adaptée automatiquement d'une recette Thermomix le $aujourdhui, pour usage personnel. " +
            "Chaque réglage machine est à vérifier avant de le lancer."
        if (e.approx.isNotEmpty()) notes += "\nAdaptations : " + e.approx.joinToString(" | ")

        var nomUrl = url.trimEnd('/').substringAfterLast('/').ifEmpty { titre }
        nomUrl = nomUrl.replace(Regex("-thermomix$"), "")
        val ingredients = recette["recipeIngredient"].list().filter { pyStr(it).isNotBlank() }.map { ingredient(pyStr(it)) }
        val toque = linkedMapOf<String, Any?>(
            "id" to "perso-ckx-" + slug(nomUrl, 36),
            "title" to titre,
            "category" to cat,
            "tags" to listOf("Cookomix", "Adaptée du Thermomix"),
            "servings" to (rendement?.toIntOrNull() ?: 0),
            "prepMinutes" to prep,
            "totalMinutes" to prep + cuisson,
            "difficulty" to 1,
            "source" to "user",
            "image" to "",
            "favorite" to false,
            "notes" to notes,
            "author" to if (auteur.isNotEmpty()) "$auteur (Cookomix)" else "Cookomix",
            "rating" to 0.0,
            "ratingCount" to 0,
            "language" to "",
            "servingUnit" to "",
            "devices" to emptyList<String>(),
            "ingredients" to listOf(linkedMapOf("group" to "", "items" to ingredients)),
            "steps" to etapes,
        )
        return Result(toque, e.approx)
    }

    /** Page HTML complète → recette Toque. [url] vide : lien canonique de la page. */
    fun convertitPage(html: String, url: String, aujourdhui: String): Result {
        val p = litPage(html)
        return convertit(p.recette, p.pas, url.ifEmpty { p.canonical }, aujourdhui)
    }

    // --- Téléchargement -------------------------------------------------------------------------
    const val MAX_PAGE = 4 * 1024 * 1024

    /**
     * Télécharge la page d'une recette (HTTPS ; sur le robot, avec les racines d'ExtraRoots).
     * Les redirections ne sont suivies que vers une autre page de recette Cookomix.
     */
    fun telecharge(url: String, timeoutMs: Int = 30_000): String {
        var u = url
        repeat(4) {
            if (!URL_RECETTE.matches(u)) throw Refus("adresse inattendue (attendu : https://www.cookomix.com/recettes/<recette>/) : $u")
            val c = java.net.URL(u).openConnection() as java.net.HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = timeoutMs
                c.readTimeout = timeoutMs
                c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 6.0) toque-cookomix/1.0 (usage personnel)")
                c.setRequestProperty("Accept-Language", "fr-FR,fr;q=0.9")
                val code = c.responseCode
                if (code in 300..399) {
                    val loc = c.getHeaderField("Location") ?: throw Refus("redirection sans adresse")
                    u = java.net.URL(java.net.URL(u), loc).toString()
                    return@repeat
                }
                if (code != 200) throw Refus("Cookomix répond $code")
                c.inputStream.use { input ->
                    val out = java.io.ByteArrayOutputStream()
                    val buf = ByteArray(16 * 1024)
                    while (true) {
                        val r = input.read(buf)
                        if (r < 0) break
                        out.write(buf, 0, r)
                        if (out.size() > MAX_PAGE) throw Refus("page trop lourde")
                    }
                    return String(out.toByteArray(), Charsets.UTF_8)
                }
            } finally {
                c.disconnect()
            }
        }
        throw Refus("trop de redirections")
    }

    // --- Entités HTML ----------------------------------------------------------------------------
    private val ENTITES = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "eacute" to "é", "egrave" to "è", "ecirc" to "ê", "euml" to "ë", "agrave" to "à", "acirc" to "â", "auml" to "ä",
        "ccedil" to "ç", "icirc" to "î", "iuml" to "ï", "ocirc" to "ô", "ouml" to "ö", "ucirc" to "û", "ugrave" to "ù",
        "uuml" to "ü", "oelig" to "œ", "aelig" to "æ", "Eacute" to "É", "Egrave" to "È", "Ecirc" to "Ê", "Agrave" to "À",
        "Acirc" to "Â", "Ccedil" to "Ç", "Icirc" to "Î", "Ocirc" to "Ô", "Ucirc" to "Û", "OElig" to "Œ",
        "laquo" to "«", "raquo" to "»", "lsquo" to "‘", "rsquo" to "’", "ldquo" to "“", "rdquo" to "”",
        "hellip" to "…", "ndash" to "–", "mdash" to "—", "deg" to "°", "frac12" to "½", "frac14" to "¼",
        "frac34" to "¾", "times" to "×", "middot" to "·", "euro" to "€", "shy" to "­", "thinsp" to " ",
        "rarr" to "→", "bull" to "•", "copy" to "©", "reg" to "®", "trade" to "™",
    )

    /** `html.unescape` de Python pour les entités usuelles (numériques et nommées). */
    fun unescape(s: String): String {
        if ('&' !in s) return s
        return Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);").replace(s) { m ->
            val k = m.groupValues[1]
            when {
                k.startsWith("#x") || k.startsWith("#X") -> k.substring(2).toIntOrNull(16)?.let { cp(it) } ?: m.value
                k.startsWith("#") -> k.substring(1).toIntOrNull()?.let { cp(it) } ?: m.value
                else -> ENTITES[k] ?: m.value
            }
        }
    }

    private fun cp(c: Int): String = if (c in 1..0x10FFFF && c !in 0xD800..0xDFFF) String(Character.toChars(c)) else "�"
}
