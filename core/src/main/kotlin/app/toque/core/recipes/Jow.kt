package app.toque.core.recipes

import app.toque.core.json.Json
import app.toque.core.json.list
import app.toque.core.json.obj
import app.toque.core.json.str
import kotlin.math.ceil

/**
 * Convertit UNE recette Jow (cuisine classique : casserole, poêle, four) en recette perso Toque pour
 * le Monsieur Cuisine, pour un usage strictement privé sur son propre robot.
 *
 * Jow n'écrit pas pour un robot : les réglages sont DÉDUITS du texte par des règles fixes (pas de
 * modèle de langage), donc prévisibles et toujours les mêmes pour une même recette :
 *
 * - « faites revenir / fondre / suer » dans une casserole ou une poêle → 120 °C, sens inverse, vitesse 1 ;
 * - « laissez cuire / mijoter / poursuivez la cuisson / portez à ébullition » → 100 °C (90 °C à feu
 *   doux), sens inverse, vitesse 1, avec le temps de montée en température quand on vient de verser
 *   l'eau ;
 * - pâtes, riz ou pommes de terre « dans l'eau bouillante » → eau chauffée dans le bol puis cuisson ;
 * - « hachez / ciselez / concassez / râpez », bol vide → quelques secondes à vitesse 5 à 8 ;
 * - « mixez » → vitesse 8, sans chauffe ;
 * - tout le reste reste à la main : découpe en lamelles, rondelles ou cubes (le robot hache, il ne
 *   tranche pas), coloration à la poêle (« dorer », « saisir », « griller »), four, purée écrasée
 *   (la lame la rendrait élastique), effilochage, dressage.
 *
 * Chaque étape machine est marquée « à vérifier » : Toque demande de la confirmer avant de la
 * lancer, et ses propres limites (vitesse 3 en chauffe et en sens inverse…) restent appliquées.
 *
 * Les CGU de Jow (art. 18) interdisent la reproduction de leurs contenus : outil personnel (copie
 * privée), une recette à la fois, rien n'est publié ni partagé. Ne pas proposer en amont.
 */
object Jow {
    /** Couverts par défaut (foyer de 3) quand le téléphone n'en précise pas. */
    const val COUVERTS_DEFAUT = 3

    private const val REVENIR_C = 120
    private const val CUIRE_C = 100
    private const val DOUX_C = 90
    private const val DUREE_MAX_S = 99 * 60

    /** Page de recette jow.fr (identifiant ou nom lisible), en HTTPS. */
    val URL_RECETTE = Regex("^https://(www\\.)?jow\\.fr/recipes/[A-Za-z0-9-]+(\\?[^#]*)?$")
    val URL_PHOTO = Regex("^https://static\\.jow\\.fr/\\d+x\\d+/recipes/[A-Za-z0-9_-]+\\.(jpe?g|png|webp)$", RegexOption.IGNORE_CASE)

    private val ID_DANS_TEXTE = Regex("[?&]recipeId=([0-9a-f]{24})\\b", RegexOption.IGNORE_CASE)
    private val PAGE_DANS_TEXTE = Regex("https?://(?:www\\.)?jow\\.fr/(?:[a-z]{2}/)?recipes/([A-Za-z0-9-]+)", RegexOption.IGNORE_CASE)

    private val I = RegexOption.IGNORE_CASE

    class Result(val recipe: Map<String, Any?>, val approx: List<String>, val photoUrl: String?, val covers: Int) {
        val title: String get() = recipe["title"] as String
        val id: String get() = recipe["id"] as String
    }

    /**
     * Adresse canonique d'une recette dans un texte partagé : lien de l'appli
     * (`https://app.jow.com/…?action=recipe&recipeId=…`) ou page jow.fr. Null s'il n'y en a pas.
     */
    fun findUrl(text: String): String? {
        ID_DANS_TEXTE.find(text)?.let { return "https://jow.fr/recipes/" + it.groupValues[1].lowercase() }
        val m = PAGE_DANS_TEXTE.find(text) ?: return null
        return "https://jow.fr/recipes/" + m.groupValues[1]
    }

    // --- Lecture de la page ----------------------------------------------------------------------
    /** Objet `recipe` des données de la page (Next.js) ; null si absent. */
    fun litPage(html: String): Map<String, Any?>? {
        val m = Regex("<script[^>]+id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).find(html) ?: return null
        val d = try { Json.parse(m.groupValues[1]) } catch (_: Exception) { return null }
        return d.obj()["props"].obj()["pageProps"].obj()["recipe"].obj().takeIf { it.isNotEmpty() }
    }

    // --- Quantités -------------------------------------------------------------------------------
    private val UNITES = mapOf(
        "pièce" to "", "cuillère à soupe" to "c. à s.", "cuillère à café" to "c. à c.", "tranche" to "tranche",
        "poignée" to "poignée", "quartier" to "quartier", "gousse" to "gousse", "bouquet" to "bouquet",
        "noisette" to "noisette", "pincée" to "pincée", "sachet" to "sachet", "boîte" to "boîte", "brin" to "brin",
        "feuille" to "feuille", "botte" to "botte", "filet" to "filet", "branche" to "branche",
    )

    /** Quantité pour [couverts] : (qté, unité), écrites comme à la main ; g et ml pour ce qui se pèse. */
    fun quantite(parCouvert: Double, uniteJow: String, couverts: Int): Pair<String, String> {
        val u = uniteJow.trim().lowercase()
        val total = parCouvert * couverts
        if (total <= 0) return "" to ""
        when (u) {
            "kilogramme" -> return Portions.scaleQty("1", "g", total * 1000)!! to "g"
            "gramme" -> return Portions.scaleQty("1", "g", total)!! to "g"
            "litre" -> return Portions.scaleQty("1", "ml", total * 1000)!! to "ml"
            "centilitre" -> return Portions.scaleQty("1", "ml", total * 10)!! to "ml"
            "millilitre" -> return Portions.scaleQty("1", "ml", total)!! to "ml"
        }
        val court = UNITES[u] ?: u
        val q = Portions.scaleQty("1", court, total) ?: ""
        val n = q.replace(',', '.').split(' ').firstOrNull()?.toDoubleOrNull() ?: 0.0
        val pluriel = court.isNotEmpty() && n >= 2 && !court.contains('.') && !court.endsWith("s")
        return q to if (pluriel) court + "s" else court
    }

    private fun grammes(parCouvert: Double, uniteJow: String, couverts: Int): Double? = when (uniteJow.trim().lowercase()) {
        "kilogramme" -> parCouvert * couverts * 1000
        "litre" -> parCouvert * couverts * 1000
        else -> null
    }

    // --- Étapes ----------------------------------------------------------------------------------
    private class Etat(val couverts: Int, val eau: Double?, val pates: Double?, val etapes: MutableList<Map<String, Any?>>) {
        /** Le bol contient une préparation en cours (cuisson, eau de cuisson…). */
        var bolOccupe = false
        /** Indice (dans [etapes]) du hachis resté dans le bol, en attente de la suite ; -1 : aucun. */
        var hachis = -1
        val hachisDansLeBol: Boolean get() = hachis >= 0
        val approx = ArrayList<String>()

        /** Le hachis ne sert pas à l'étape qui suit dans le bol : on le réserve. */
        fun reserveHachis() {
            if (hachis < 0) return
            val st = LinkedHashMap(etapes[hachis])
            st["text"] = (st["text"] as String) + " Réserver le hachis à part."
            etapes[hachis] = st
            hachis = -1
        }
    }

    private fun sa(t: String) = Cookomix.sansAccents(t).lowercase()

    /** Durée lue dans le texte, en secondes : la borne haute d'un intervalle (« 2 à 3 minutes » → 3 min). */
    fun duree(t: String): Int? {
        val m = Regex("(\\d+)(?:\\s*(?:à|a|-|ou)\\s*(\\d+))?\\s*(minutes?|min\\b|mn\\b|secondes?|sec\\b|s\\b|heures?|h\\b)", I).find(t) ?: return null
        val n = (m.groupValues[2].ifEmpty { m.groupValues[1] }).toInt()
        val u = m.groupValues[3].lowercase()
        return when {
            u.startsWith("h") -> n * 3600
            u.startsWith("s") -> n
            else -> n * 60
        }
    }

    private val RECIPIENT_ROBOT = Regex("\\b(casserole|poele|sauteuse|cocotte|faitout|marmite|wok)\\b")
    private val HORS_ROBOT = Regex("\\b(saladier|plat|assiette|bol|moule|plaque|ramequin|tupperware|boite)\\b")

    private fun reglage(sec: Int, tempC: Int, vitesse: Int, inverse: Boolean): String {
        val r = arrayListOf(Cookomix.dureeTxt(sec))
        if (tempC > 0) r += "$tempC °C"
        r += "vitesse $vitesse"
        if (inverse) r += "sens inverse"
        return r.joinToString(" / ")
    }

    private fun machine(sec: Int, tempC: Int, vitesse: Int, inverse: Boolean) = linkedMapOf<String, Any?>(
        "program" to "manuel", "seconds" to sec, "speed" to vitesse, "tempC" to tempC,
        "reverse" to inverse, "accessory" to "blade", "pulses" to 0,
    )

    /** Texte de Jow adapté au robot : « dans une casserole » → « dans le bol », plus de feu. */
    private fun adapte(t: String): String {
        var s = t.replace(Regex("\\*\\s*Consultez nos (tips|astuces) pour cette étape", I), "")
        s = s.replace("*", "")
        s = s.replace(Regex("\\b(dans|à) (une|la) (casserole|poêle|sauteuse|cocotte|marmite|wok)( chaude)?", I), "dans le bol")
        s = s.replace(Regex("\\bdans la (casserole|poêle|sauteuse|cocotte|marmite)\\b", I), "dans le bol")
        s = s.replace(Regex(",?\\s*(sur|à) feu (moyen|doux|vif|moyen-vif|moyen-doux)", I), "")
        s = s.replace(Regex(",?\\s*en (remuant|mélangeant)( régulièrement)?", I), "")
        s = s.replace(Regex("\\bCouvrez et\\b"), "Fermez le couvercle et")
        s = s.replace(Regex("\\bcouvrez et\\b"), "fermez le couvercle et")
        s = s.replace(Regex("\\s+"), " ").replace(Regex("\\s+([.,])"), "$1").trim()
        return s.replaceFirstChar { it.uppercaseChar() }
    }

    /** Quantités « par personne » du texte, calculées pour le nombre de couverts. */
    private fun parPersonne(t: String, couverts: Int): String =
        Regex("(\\d+(?:[.,]\\d+)?)\\s*(ml|cl|l|g)\\b(\\s*(?:d['’]\\s?|de\\s+)[\\p{L}]+)?\\s+par (personne|pers\\.?|portion)", I).replace(t) { m ->
            val n = m.groupValues[1].replace(',', '.').toDouble() * couverts
            val (q, u) = when (m.groupValues[2].lowercase()) {
                "cl" -> Portions.scaleQty("1", "ml", n * 10)!! to "ml"
                "l" -> Portions.scaleQty("1", "ml", n * 1000)!! to "ml"
                else -> Portions.scaleQty("1", m.groupValues[2].lowercase(), n)!! to m.groupValues[2].lowercase()
            }
            "$q $u${m.groupValues[3]}"
        }

    /** Une étape de Jow → une ou plusieurs étapes Toque. */
    private fun etape(brut: String, ingredients: String, e: Etat, n: Int): List<Map<String, Any?>> {
        val texte = adapte(parPersonne(brut, e.couverts))
        val s = sa(brut)
        fun main(note: String? = null): List<Map<String, Any?>> {
            val t = if (note != null) "$texte ($note)" else texte
            return listOf(linkedMapOf("text" to t))
        }
        fun robot(verbe: String, sec: Int, tempC: Int, vitesse: Int, inverse: Boolean, notes: List<String> = emptyList(),
                  avant: String = "", propre: String? = null): Map<String, Any?> {
            var t = (if (avant.isNotEmpty()) "$avant " else "") + (propre ?: texte)
            if (ingredients.isNotEmpty()) t += " Avec : $ingredients."
            t += " $verbe : ${reglage(sec, tempC, vitesse, inverse)}."
            if (notes.isNotEmpty()) {
                t += " ⚠ " + notes.joinToString(" ; ") + "."
                e.approx += "étape $n : " + notes.joinToString(" ; ")
            }
            return linkedMapOf("text" to t, "machine" to machine(minOf(sec, DUREE_MAX_S), tempC, vitesse, inverse), "needsReview" to true)
        }

        val dansUnRecipientRobot = RECIPIENT_ROBOT.containsMatchIn(s)
        val ailleurs = HORS_ROBOT.containsMatchIn(s) && !dansUnRecipientRobot
        val sortDuBol = Regex("\\b(reservez|egouttez|transvasez|versez (le tout |la preparation )?dans (un|le) (plat|saladier|moule))").containsMatchIn(s)

        // 1. Ce qui ne se fait jamais dans le bol.
        val prechauffe = Regex("^\\s*(Préchauffez[^.]*\\.)\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(brut)
        if (prechauffe != null) {
            return listOf(linkedMapOf<String, Any?>("text" to prechauffe.groupValues[1].trim())) +
                etape(prechauffe.groupValues[2], ingredients, e, n + 1)
        }
        if (Regex("\\b(servez|dressez|c'est pret|degustez)").containsMatchIn(s)) return main()
        if (Regex("\\b(prechauff|enfourn|passez au four|cuire au four|sous le gril)").containsMatchIn(s)) {
            if (Regex("\\benfourn").containsMatchIn(s)) e.bolOccupe = false
            return main("au four, hors robot")
        }
        if (Regex("\\beffilochez").containsMatchIn(s)) return main("à la main")
        if (Regex("\\b(ecrasez|presse-puree)").containsMatchIn(s)) {
            e.bolOccupe = false
            return main("à la main : la lame rendrait la purée élastique")
        }
        if (Regex("\\b(faites (dorer|griller|colorer)|faites saisir|saisissez|grillez|dorez)\\b").containsMatchIn(s)) {
            e.bolOccupe = false
            e.approx += "étape $n : coloration à la poêle, hors robot (le robot ne dore pas)"
            return main("à la poêle, hors robot : le robot ne dore pas")
        }

        // 2. Pâtes, riz, pommes de terre dans l'eau bouillante : eau chauffée dans le bol.
        if (Regex("eau bouillante").containsMatchIn(s) && Regex("\\b(pates|tagliatelle|spaghetti|penne|fusilli|coquillettes|riz|pommes de terre|gnocchi|ravioli|tortellini|lasagne)").containsMatchIn(s)) {
            if (e.bolOccupe) return main("hors robot : le bol est occupé")
            e.reserveHachis()
            val notes = ArrayList<String>()
            val p = e.pates ?: 300.0
            val tubercule = Regex("pommes de terre").containsMatchIn(s)
            // Pâtes : 5 fois leur poids d'eau ; pommes de terre : juste de quoi les couvrir.
            var eau = if (tubercule) maxOf(800.0, p).toInt() else (p * 5).toInt().coerceIn(1000, 2000)
            eau = (Math.round(eau / 100.0) * 100).toInt()
            if (eau + p > Portions.BOWL_MAX_G) {
                eau = maxOf(800, ((Portions.BOWL_MAX_G - p) / 100).toInt() * 100)
                if (eau + p > Portions.BOWL_MAX_G) notes += "plus de ${Portions.BOWL_MAX_G / 1000.0} l au total dans le bol : cuire en deux fois".replace('.', ',')
            }
            val monte = (ceil(eau / 1000.0 * 8)).toInt().coerceIn(6, 16) * 60
            val cuisson = duree(brut) ?: 600
            if (duree(brut) == null) notes += "temps de cuisson du paquet à reporter (10 min par défaut)"
            val chauffe = linkedMapOf<String, Any?>(
                "text" to "Verser $eau ml d'eau et 1 c. à c. de sel dans le bol. Chauffer : ${reglage(monte, CUIRE_C, 1, false)}.",
                "machine" to machine(monte, CUIRE_C, 1, false), "needsReview" to true,
            )
            e.bolOccupe = true
            val quoi = Regex("\\b(pâtes|tagliatelle|spaghetti|penne|fusilli|coquillettes|riz|pommes de terre|gnocchis?|ravioles?|ravioli|tortellini)\\b", I)
                .find(brut)?.value?.lowercase() ?: "les pâtes"
            val article = if (quoi == "riz") "le riz" else if (quoi.startsWith("les ")) quoi else "les $quoi"
            val cuit = robot("Cuire", cuisson, CUIRE_C, 1, true, notes,
                propre = "Ajouter $article dans l'eau bouillante, fermer le couvercle." +
                    (if (tubercule || duree(brut) != null) "" else " Temps du paquet."))
            if (!Regex("egoutt").containsMatchIn(s)) return listOf(chauffe, cuit)
            val egoutte = linkedMapOf<String, Any?>("text" to "Égoutter avec le panier de cuisson ou une passoire" +
                (if (Regex("eau de cuisson").containsMatchIn(s)) ", en gardant une petite louche d'eau de cuisson." else "."))
            e.bolOccupe = false
            return listOf(chauffe, cuit, egoutte)
        }

        // 3. Hacher, ciseler, concasser, râper : bol vide seulement.
        val hache = Regex("\\b(hachez|ciselez|concassez|rapez|mixez|mixer)\\b").find(s)?.value
        if (hache != null && !Regex("\\b(faites|laissez|poursuivez)\\b.*\\b(cuire|revenir|mijoter)").containsMatchIn(s)) {
            if (hache.startsWith("mix")) {
                e.reserveHachis()
                val r = robot("Mixer", duree(brut) ?: 60, 0, 8, false, listOf("monter la vitesse progressivement ; si c'est chaud, bouchon doseur en place"))
                return listOf(r)
            }
            if (e.bolOccupe || e.hachisDansLeBol || Regex("pendant ce temps").containsMatchIn(s)) return main("au couteau : le bol est occupé")
            val (verbe, sec, v) = when (hache) {
                "rapez" -> Triple("Râper", 10, 8)
                "concassez" -> Triple("Concasser", 3, 5)
                else -> Triple("Hacher", 5, 5)
            }
            e.reserveHachis()
            e.hachis = e.etapes.size
            return listOf(robot(verbe, sec, 0, v, false, emptyList(), "Mettre dans le bol, en morceaux."))
        }

        // 4. Cuisson dans une casserole ou une poêle : dans le bol.
        val revenir = Regex("\\b(revenir|suer|fondre|rissoler)\\b").containsMatchIn(s)
        val cuire = Regex("\\b(mijoter|laissez cuire|faites cuire|poursuivez (la )?cuisson|portez a ebullition|cuire)\\b").containsMatchIn(s)
        val melangeMinute = Regex("\\bmelangez\\b").containsMatchIn(s) && duree(brut) != null
        val chauffeHuile = Regex("faites chauffer").containsMatchIn(s) && Regex("\\b(huile|beurre)\\b").containsMatchIn(s)
        val enCours = e.bolOccupe || dansUnRecipientRobot || e.hachisDansLeBol

        if (!ailleurs && (dansUnRecipientRobot || e.bolOccupe) && (revenir || chauffeHuile)) {
            val notes = ArrayList<String>()
            val d = duree(brut) ?: 180.also { notes += "durée non indiquée : 3 min" }
            val avant = if (e.hachisDansLeBol) "Laisser le hachis dans le bol." else ""
            e.hachis = -1
            e.bolOccupe = true
            val r = robot("Faire revenir", d, REVENIR_C, 1, true, notes, avant)
            return listOf(r) + (if (sortDuBol) listOf(linkedMapOf<String, Any?>("text" to "Transvaser le contenu du bol dans un récipient.")).also { e.bolOccupe = false } else emptyList())
        }
        if (!ailleurs && enCours && (cuire || melangeMinute)) {
            val notes = ArrayList<String>()
            var d = duree(brut) ?: 300.also { notes += "durée non indiquée : 5 min" }
            val doux = Regex("feu doux|fremir|fremissement").containsMatchIn(s)
            // Eau versée maintenant (pas « l'eau de cuisson » réservée) : temps de montée en température.
            val versesEau = Regex("(d'|de l'|l')eau\\b(?! de cuisson)").containsMatchIn(s) && Regex("\\b(ajoutez|versez|mouillez)\\b").containsMatchIn(s)
            if (versesEau && Regex("ebullition|mijoter|laissez cuire|couvrez").containsMatchIn(s)) {
                val l = (e.eau ?: 500.0) / 1000.0
                val monte = (ceil(l * 8)).toInt().coerceIn(3, 12)
                d += monte * 60
                notes += "dont $monte min de montée en température"
            }
            val avant = if (e.hachisDansLeBol && !e.bolOccupe) "Laisser le hachis dans le bol." else ""
            if (e.bolOccupe) e.reserveHachis()
            e.hachis = -1
            e.bolOccupe = true
            val r = robot("Cuire", d, if (doux) DOUX_C else CUIRE_C, 1, true, notes, avant)
            return listOf(r) + (if (sortDuBol) listOf(linkedMapOf<String, Any?>("text" to "Transvaser le contenu du bol dans un récipient.")).also { e.bolOccupe = false } else emptyList())
        }
        if (!ailleurs && e.bolOccupe && Regex("\\b(ajoutez|rajoutez|incorporez)\\b").containsMatchIn(s) && Regex("\\b(melangez|melanger)\\b").containsMatchIn(s)) {
            e.reserveHachis()
            val r = robot("Mélanger", 30, 0, 1, true)
            return listOf(r) + (if (sortDuBol) listOf(linkedMapOf<String, Any?>("text" to "Transvaser le contenu du bol dans un récipient.")).also { e.bolOccupe = false } else emptyList())
        }

        // 5. Le reste : à la main (découpe, mélange dans un saladier…).
        if (sortDuBol) e.bolOccupe = false
        if (Regex("\\b(lamelles|rondelles|demi-lunes|cubes|dés|tranches|morceaux|bâtonnets|quartiers|julienne)\\b", I).containsMatchIn(brut) &&
            Regex("\\b(coupez|emincez|taillez|detaillez)\\b").containsMatchIn(s)) return main("au couteau : le robot hache, il ne tranche pas")
        return main()
    }

    // --- Recette complète ------------------------------------------------------------------------
    /** [couverts] : nombre de personnes voulu (ramené au choix proposé par Jow le plus proche au-dessus). */
    fun convertit(r: Map<String, Any?>, url: String, couverts: Int, aujourdhui: String): Result {
        val titre = (r.str("title") ?: "").trim()
        if (titre.isEmpty()) throw Cookomix.Refus("pas de recette dans la page")
        val etapesJow = r["directions"].list().map { it.obj() }
        if (etapesJow.isEmpty()) throw Cookomix.Refus("recette sans étapes")
        val approx = ArrayList<String>()

        val options = r["coversOptions"].list().mapNotNull { (it as? Number)?.toInt() }.filter { it > 0 }.sorted()
        var n = couverts.coerceIn(1, 30)
        if (options.isNotEmpty() && n !in options) {
            val choisi = options.firstOrNull { it >= n } ?: options.last()
            approx += "Jow propose cette recette pour ${options.take(4).joinToString(", ")}… personnes : quantités pour $choisi au lieu de $n"
            n = choisi
        }

        // Ingrédients (et ceux « du placard » : huile, beurre, eau…).
        val parId = HashMap<String, String>()
        var eau: Double? = null
        var pates: Double? = null
        fun ligne(c: Map<String, Any?>, nom: String): Map<String, Any?> {
            val q = (c["quantityPerCover"] as? Number)?.toDouble() ?: 0.0
            val unite = c["unit"].obj().str("name") ?: ""
            val (qty, u) = quantite(q, unite, n)
            val nomS = sa(nom)
            if (nomS == "eau") eau = grammes(q, unite, n)
            if (Regex("\\b(pates|tagliatelle|spaghetti|penne|fusilli|coquillettes|riz|pommes de terre|gnocchi)\\b").containsMatchIn(nomS)) pates = grammes(q, unite, n)
            val note = if (c["isOptional"] == true) "facultatif" else ""
            (c.str("id") ?: c["ingredient"].obj().str("id"))?.let { parId[it] = listOf(qty, u, nom).filter { it.isNotEmpty() }.joinToString(" ") }
            return linkedMapOf("qty" to qty, "unit" to u, "name" to nom, "note" to note)
        }
        val principaux = r["constituents"].list().map { it.obj() }.map { ligne(it, (it.str("name") ?: "").trim()) }
        val placard = r["additionalConstituents"].list().map { it.obj() }
            .map { c -> ligne(c, (c["ingredient"].obj().str("name") ?: c.str("name") ?: "").trim()) }
            .filter { (it["name"] as String).isNotEmpty() }

        val etapes = ArrayList<Map<String, Any?>>()
        val e = Etat(n, eau, pates, etapes)
        for (d in etapesJow) {
            val label = (d.str("label") ?: "").trim()
            if (label.isEmpty()) continue
            val impliques = d["involvedIngredients"].list().mapNotNull { parId[it.obj().str("id")] }.joinToString(", ")
            etapes += etape(label, impliques, e, etapes.size + 1)
        }
        e.reserveHachis()
        approx += e.approx

        val outils = r["requiredTools"].list().mapNotNull { it.obj().str("name") }
        if (outils.any { sa(it) == "four" }) approx.add(0, "four nécessaire : ces étapes restent hors robot")

        val prep = (r["preparationTime"] as? Number)?.toInt() ?: 0
        val cuisson = (r["cookingTime"] as? Number)?.toInt() ?: 0
        val astuce = r["tip"].obj().str("description")?.trim().orEmpty()
        var notes = "Recette Jow : $url\n" +
            "Adaptée automatiquement au robot le $aujourdhui, pour usage personnel, pour $n personnes. " +
            "Chaque réglage machine est déduit du texte : à vérifier avant de le lancer."
        if (outils.isNotEmpty()) notes += "\nUstensiles prévus par Jow : " + outils.joinToString(", ") + "."
        if (astuce.isNotEmpty()) notes += "\nAstuce Jow : $astuce"
        if (approx.isNotEmpty()) notes += "\nAdaptations : " + approx.joinToString(" | ")

        val slugJow = (r.str("slug") ?: titre).replace(Regex("-[a-z0-9]{20}$"), "")
        val groupes = arrayListOf<Map<String, Any?>>(linkedMapOf("group" to "", "items" to principaux))
        if (placard.isNotEmpty()) groupes += linkedMapOf("group" to "Du placard", "items" to placard)
        val famille = r["family"].obj().str("name").orEmpty()
        val toque = linkedMapOf<String, Any?>(
            "id" to "perso-jow-" + Cookomix.slug(slugJow, 36),
            "title" to titre,
            "category" to "Plats principaux",
            "tags" to listOfNotNull("Jow", "Adaptée au robot", famille.ifEmpty { null }),
            "servings" to n,
            "prepMinutes" to prep,
            "totalMinutes" to prep + cuisson,
            "difficulty" to ((r["difficulty"] as? Number)?.toInt() ?: 1).coerceIn(1, 3),
            "source" to "user",
            "image" to "",
            "favorite" to false,
            "notes" to notes,
            "author" to "Jow",
            "rating" to 0.0,
            "ratingCount" to 0,
            "language" to "",
            "servingUnit" to "",
            "devices" to emptyList<String>(),
            "ingredients" to groupes,
            "steps" to etapes,
        )
        val photo = listOfNotNull(r.str("imageUrlHD"), r.str("imageUrl")).firstOrNull { URL_PHOTO.matches(it) }
        return Result(toque, approx, photo, n)
    }

    fun convertitPage(html: String, url: String, couverts: Int, aujourdhui: String): Result {
        val r = litPage(html) ?: throw Cookomix.Refus("pas de données de recette dans la page Jow")
        val canon = Regex("<link[^>]+rel=\"canonical\"[^>]+href=\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        return convertit(r, canon.ifEmpty { url }, couverts, aujourdhui)
    }

    // --- Téléchargement -------------------------------------------------------------------------
    fun telecharge(url: String, timeoutMs: Int = 30_000): String =
        String(Cookomix.download(url, URL_RECETTE, Cookomix.MAX_PAGE, timeoutMs, "Jow"), Charsets.UTF_8)

    fun telechargePhoto(url: String, timeoutMs: Int = 30_000): ByteArray =
        Cookomix.download(url, URL_PHOTO, Cookomix.MAX_PHOTO, timeoutMs, "Jow")
}
