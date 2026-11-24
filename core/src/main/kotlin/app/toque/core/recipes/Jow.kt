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
    private class Etat(val couverts: Int, val eau: Double?, val pates: Double?, val etapes: MutableList<Map<String, Any?>>,
                       val soupe: Boolean = false, val auDressage: String = "") {
        /** Le bol contient une préparation en cours (cuisson, eau de cuisson…). */
        var bolOccupe = false
        /** Indice (dans [etapes]) du hachis resté dans le bol, en attente de la suite ; -1 : aucun. */
        var hachis = -1
        val hachisDansLeBol: Boolean get() = hachis >= 0
        val approx = ArrayList<String>()
        /** Légumes préparés en gros morceaux, hachés au robot juste avant la cuisson. */
        val aHacher = ArrayList<String>()
        var aHacherCru = false
        /** Le bol contient de l'eau de cuisson (pâtes, pommes de terre…) jusqu'à l'égouttage. */
        var dansLeau = false
        /** Eau déjà chauffée dans le bol (« préparez une casserole d'eau bouillante »). */
        var eauChaude = false
        /** Préparation froide dans le bol (pâte, appareil, crème) : mélanges sans chauffe. */
        var froid = false
        /** Un bouillon a été préparé à part (cube dissous) : le verser fait monter le bol en température. */
        var bouillonPret = false
        var risotto = false
        /** La préparation vient d'être égouttée hors du bol (étape précédente). */
        var egouttee = false
        var poeleSeule = false

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
    fun duree(t: String): Int? = durees(t).firstOrNull()

    /** Toutes les durées d'un texte, dans l'ordre (sauf « au bout de 15 minutes », simple repère). */
    fun durees(t: String): List<Int> {
        val propre = t.replace(Regex("(au bout de|après|apres|pendant les?)\\s+\\d+(\\s*(à|a|-)\\s*\\d+)?\\s*(minutes?|min)\\b", I), "")
        return DUREE.findAll(propre).map { m ->
            val n = (m.groupValues[2].ifEmpty { m.groupValues[1] }).toInt()
            val u = m.groupValues[3].lowercase()
            when {
                u.startsWith("h") -> n * 3600
                u.startsWith("s") -> n
                else -> n * 60
            }
        }.toList()
    }

    private val DUREE = Regex("(\\d+)(?:\\s*(?:à|a|-|ou)\\s*(\\d+))?\\s*(minutes?|min\\b|mn\\b|secondes?|sec\\b|s\\b|heures?|h\\b)", I)

    private val RECIPIENT_ROBOT = Regex("\\b(casserole|poele|sauteuse|cocotte|faitout|marmite|wok)\\b")
    private val HORS_ROBOT = Regex("\\b(saladier|plat|assiette|bol|moule|plaque|ramequin|tupperware|boite)\\b")

    private fun reglage(sec: Int, tempC: Int, vitesse: Int, inverse: Boolean): String {
        val r = arrayListOf(Cookomix.dureeTxt(sec))
        if (tempC > 0) r += "$tempC °C"
        r += "vitesse $vitesse"
        if (inverse) r += "sens inverse"
        return r.joinToString(" / ")
    }

    private fun machine(sec: Int, tempC: Int, vitesse: Int, inverse: Boolean, fouet: Boolean = false) = linkedMapOf<String, Any?>(
        "program" to "manuel", "seconds" to sec, "speed" to vitesse, "tempC" to tempC,
        "reverse" to inverse, "accessory" to if (fouet) "whisk" else "blade", "pulses" to 0,
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

    private val TRANSVASER = "Transvaser le contenu du bol dans un récipient."
    private val FECULENTS = Regex("\\b(pates|tagliatelle|spaghetti|penne|fusilli|coquillettes|macaronis?|riz|quinoa|boulgour|semoule|gnocchis?|ravioles?|ravioli|tortellini|lasagnes?)\\b")
    private val AROMATES = Regex("\\b(oignons?|echalotes?|poireaux?|ail)\\b")
    private val LEGUMES_SOUPE = Regex("\\b(carottes?|celeri|poivrons?|courgettes?|navets?)\\b")

    /** Une étape de Jow → une ou plusieurs étapes Toque. */
    private fun etape(brut: String, ingredients: String, e: Etat, n: Int): List<Map<String, Any?>> {
        val texte = adapte(parPersonne(brut, e.couverts))
        val s = sa(brut)
        fun main(note: String? = null): List<Map<String, Any?>> {
            val t = if (note != null) "$texte ($note)" else texte
            return listOf(linkedMapOf("text" to t))
        }
        fun robot(verbe: String, sec: Int, tempC: Int, vitesse: Int, inverse: Boolean, notes: List<String> = emptyList(),
                  avant: String = "", propre: String? = null, fouet: Boolean = false, avec: Boolean = true): Map<String, Any?> {
            var t = (if (avant.isNotEmpty()) "$avant " else "") + (propre ?: texte)
            if (avec && ingredients.isNotEmpty()) t += " Avec : $ingredients."
            t += " $verbe : ${reglage(sec, tempC, vitesse, inverse)}."
            if (notes.isNotEmpty()) {
                t += " ⚠ " + notes.joinToString(" ; ") + "."
                e.approx += "étape $n : " + notes.joinToString(" ; ")
            }
            if (tempC > 0) e.froid = false
            return linkedMapOf("text" to t, "machine" to machine(minOf(sec, DUREE_MAX_S), tempC, vitesse, inverse, fouet), "needsReview" to true)
        }
        fun texteSeul(t: String): Map<String, Any?> = linkedMapOf("text" to t)
        fun libere() { e.bolOccupe = false; e.dansLeau = false; e.eauChaude = false; e.froid = false }

        /** Légumes en attente, hachés ensemble ; [garde] : le hachis reste dans le bol pour la suite. */
        fun hachageDiffere(garde: Boolean): List<Map<String, Any?>> {
            if (e.aHacher.isEmpty() || e.bolOccupe) return emptyList()
            val notes = arrayListOf("hachis plutôt que des morceaux taillés")
            if (e.aHacherCru) notes.add(0, "garder quelques lamelles d'oignon crues pour le service")
            val debut = if (e.hachisDansLeBol) "Ajouter au hachis du bol :" else "Mettre dans le bol :"
            var t = robot("Hacher grossièrement", 4, 0, 4, false, notes, propre = "$debut ${e.aHacher.joinToString(", ")}.", avec = false)
            e.aHacher.clear()
            e.aHacherCru = false
            if (garde) e.hachis = e.etapes.size
            else { e.hachis = -1; t = LinkedHashMap(t).apply { put("text", (get("text") as String) + " Réserver le hachis à part.") } }
            return listOf(t)
        }
        /** Le bol doit être vide pour la suite : légumes en attente hachés et réservés, hachis réservé. */
        fun bolVide(): List<Map<String, Any?>> {
            val h = hachageDiffere(garde = false)
            e.reserveHachis()
            return h
        }
        fun ensuite(r: List<Map<String, Any?>>): List<Map<String, Any?>> {
            val sort = Regex("\\b(reservez(?!-en)|transvasez|versez (le tout |la preparation |la pate )?dans (un|le) (plat|saladier|moule))").containsMatchIn(s)
            if (!sort) return r
            libere()
            return r + texteSeul(TRANSVASER)
        }

        val dansUnRecipientRobot = RECIPIENT_ROBOT.containsMatchIn(s)
        val ailleurs = HORS_ROBOT.containsMatchIn(s) && !dansUnRecipientRobot
        val pendantCeTemps = Regex("pendant ce temps|en parallele").containsMatchIn(s)
        val egoutte = Regex("\\begoutt").containsMatchIn(s)

        // 1. Ce qui ne se fait jamais dans le bol.
        val prechauffe = Regex("^\\s*(Préchauffez[^.]*\\.)\\s*(.+)$", RegexOption.DOT_MATCHES_ALL).find(brut)
        if (prechauffe != null)
            return listOf(texteSeul(prechauffe.groupValues[1].trim())) + etape(prechauffe.groupValues[2], ingredients, e, n + 1)
        val prepareEau = Regex("^\\s*(Préparez une casserole d'eau (bouillante|salée)[^.]*\\.)\\s*(.*)$", RegexOption.DOT_MATCHES_ALL).find(brut)
        if (prepareEau != null && !e.bolOccupe) {
            val avant = bolVide()
            val eau = 1500
            val monte = 12 * 60
            e.bolOccupe = true; e.dansLeau = true; e.eauChaude = true
            val chauffe = linkedMapOf<String, Any?>(
                "text" to "Verser $eau ml d'eau et 1 c. à c. de sel dans le bol. Chauffer : ${reglage(monte, CUIRE_C, 1, false)}.",
                "machine" to machine(monte, CUIRE_C, 1, false), "needsReview" to true)
            val reste = prepareEau.groupValues[3].trim()
            return avant + chauffe + (if (reste.isNotEmpty()) etape(reste, ingredients, e, n + 1) else emptyList())
        }
        if (Regex("\\b(servez|dressez|c'est pret|degustez)").containsMatchIn(s) && !Regex("\\b(cuire|mijoter|revenir)").containsMatchIn(s)) return main()
        if (Regex("\\b(prechauff|enfourn|passez au four|cuire au four|sous le gril|pre-cuire|precuire)").containsMatchIn(s)) {
            if (Regex("\\benfourn").containsMatchIn(s)) libere()
            return main("au four, hors robot")
        }
        if (Regex("\\beffilochez").containsMatchIn(s)) return main("à la main")
        if (Regex("\\b(retournez|retournant|de chaque cote|sur chaque face)").containsMatchIn(s) ||
            (Regex("\\b(puits)\\b").containsMatchIn(s) && Regex("(oe|œ)ufs?\\b").containsMatchIn(s)) ||
            (e.poeleSeule && Regex("\\b(cuire|revenir|versez)").containsMatchIn(s) && !Regex("eau bouillante").containsMatchIn(s))) {
            val t = if (e.bolOccupe && !e.dansLeau && !egoutte) "transvaser dans une poêle et cuire à la poêle, hors robot : la lame ne convient pas"
                else "à la poêle, hors robot : à retourner ou à laisser prendre sans mélanger"
            e.approx += "étape $n : $t"
            if (e.bolOccupe && !e.dansLeau) libere()
            return main(t)
        }

        // 2. Purée : pommes de terre au fouet (la lame les rendrait élastiques), légumes à la lame.
        if (Regex("\\b(ecrasez|presse-puree|reduisez en puree)").containsMatchIn(s) ||
            (Regex("\\bmixez\\b").containsMatchIn(s) && Regex("puree").containsMatchIn(s))) {
            e.reserveHachis()
            val pdt = Regex("pommes? de terre|patates?").containsMatchIn(s) || !Regex("\\b(brocolis?|carottes?|courges?|potimarron|chou|petits pois|celeri|panais)").containsMatchIn(s)
            val avec = Regex("\\bavec ([^.]+?)\\.").find(brut)?.groupValues?.get(1)?.trim()
            val quoi = if (pdt) "les pommes de terre" else "les légumes"
            val dejaDansLeBol = e.bolOccupe && !e.dansLeau
            val propre = (if (dejaDansLeBol) "" else if (e.egouttee) "Remettre la préparation dans le bol" + (if (avec != null) ", avec $avec" else "") + "." else "Égoutter $quoi et les remettre dans le bol, sans l'eau" + (if (avec != null) ", avec $avec" else "") + ".") +
                (if (pdt) " Mettre le fouet." else "")
            e.bolOccupe = true; e.dansLeau = false
            return if (pdt) listOf(
                robot("Écraser en purée", 30, 0, 3, false, listOf("au fouet, vitesse 3 : prolonger de 10 s si des morceaux restent"), propre = propre.trim(), fouet = true),
                texteSeul("Retirer le fouet."))
            else listOf(robot("Mixer en purée", 30, 0, 6, false, listOf("monter la vitesse progressivement, bouchon doseur en place"), propre = propre.trim()))
        }

        // 3. Cuisson à l'eau bouillante (pâtes, riz, pommes de terre, légumes) : eau chauffée dans le bol.
        if (Regex("eau bouillante|dans l'eau chaude").containsMatchIn(s) && Regex("\\b(cuire|cuisez|plongez|ajoutez)").containsMatchIn(s)) {
            if (e.bolOccupe && !e.eauChaude) return main("hors robot : le bol est occupé")
            val avant = bolVide()
            val notes = ArrayList<String>()
            val feculent = FECULENTS.containsMatchIn(s)
            val p = (if (feculent) e.pates else null) ?: 300.0
            val out = ArrayList<Map<String, Any?>>(avant)
            if (!e.eauChaude) {
                // Pâtes : 5 fois leur poids d'eau ; pommes de terre et légumes : juste de quoi les couvrir.
                var eau = if (feculent && !Regex("pommes de terre").containsMatchIn(s)) (p * 5).toInt().coerceIn(1000, 2000) else maxOf(800.0, p).toInt()
                eau = (Math.round(eau / 100.0) * 100).toInt()
                if (eau + p > Portions.BOWL_MAX_G) {
                    eau = maxOf(800, ((Portions.BOWL_MAX_G - p) / 100).toInt() * 100)
                    if (eau + p > Portions.BOWL_MAX_G) notes += "plus de ${Portions.BOWL_MAX_G / 1000.0} l au total dans le bol : cuire en deux fois".replace('.', ',')
                }
                val monte = (ceil(eau / 1000.0 * 8)).toInt().coerceIn(6, 16) * 60
                out += linkedMapOf<String, Any?>(
                    "text" to "Verser $eau ml d'eau et 1 c. à c. de sel dans le bol. Chauffer : ${reglage(monte, CUIRE_C, 1, false)}.",
                    "machine" to machine(monte, CUIRE_C, 1, false), "needsReview" to true)
            }
            val d = duree(brut)
            val cuisson = d ?: 600
            if (d == null) notes += "temps de cuisson du paquet à reporter (10 min par défaut)"
            val quoi = Regex("\\b(pâtes|tagliatelle|spaghetti|penne|fusilli|coquillettes|riz|pommes de terre|gnocchis?|ravioles?|ravioli|tortellini|brocolis?|haricots verts|petits pois|chou-fleur|carottes?|légumes)\\b", I)
                .find(brut)?.value?.lowercase() ?: "les ingrédients"
            val article = when {
                quoi == "riz" -> "le riz"
                quoi == "chou-fleur" -> "le chou-fleur"
                quoi.startsWith("les ") -> quoi
                else -> "les $quoi"
            }
            e.bolOccupe = true; e.dansLeau = true; e.eauChaude = false
            out += robot("Cuire", cuisson, CUIRE_C, 1, true, notes,
                propre = "Ajouter $article dans l'eau bouillante, fermer le couvercle." + (if (d == null) " Temps du paquet." else ""))
            if (egoutte) {
                out += texteSeul("Égoutter avec le panier de cuisson ou une passoire" +
                    (if (Regex("eau de cuisson").containsMatchIn(s)) ", en gardant une petite louche d'eau de cuisson." else "."))
                libere()
            }
            return out
        }
        // Cuisson au micro-ondes : à l'eau dans le bol.
        if (Regex("micro-?ondes?").containsMatchIn(s) && Regex("\\b(cuire|cuisez)").containsMatchIn(s) && !e.bolOccupe) {
            val avant = bolVide()
            val d = maxOf(15 * 60, (duree(brut) ?: 5 * 60) * 3)
            e.bolOccupe = true; e.dansLeau = true
            return avant + robot("Cuire à l'eau", d, CUIRE_C, 1, true, listOf("remplace le micro-ondes : vérifier la cuisson à la pointe du couteau"),
                propre = "Mettre dans le bol avec 500 ml d'eau, fermer le couvercle.")
        }

        // 4. Hacher, ciseler, concasser, râper, mixer : bol vide.
        val hache = Regex("\\b(hachez|ciselez|concassez|rapez|mixez|mixer)\\b").find(s)?.value
        if (hache != null && !Regex("\\b(faites|laissez|poursuivez)\\b.*\\b(cuire|revenir|mijoter)").containsMatchIn(s)) {
            if (hache.startsWith("mix")) {
                e.reserveHachis()
                return listOf(robot("Mixer", duree(brut) ?: 60, 0, 8, false, listOf("monter la vitesse progressivement ; si c'est chaud, bouchon doseur en place")))
            }
            if (e.bolOccupe) return main("au couteau : le bol est occupé")
            if ((hache == "hachez" || hache == "ciselez") && e.aHacher.isNotEmpty()) {
                // D'autres légumes attendent déjà : tout est haché ensemble avant la cuisson.
                e.aHacher += ingredients.ifEmpty { "légumes de l'étape" }
                return listOf(texteSeul("$texte (le robot les hachera avec les précédents)"))
            }
            val avant = bolVide()
            val (verbe, sec, v) = when (hache) {
                "rapez" -> Triple("Râper", 10, 8)
                "concassez" -> Triple("Concasser", 3, 5)
                else -> Triple("Hacher", 5, 5)
            }
            val r = robot(verbe, sec, 0, v, false, emptyList(), "Mettre dans le bol, en morceaux.")
            if (hache == "rapez") return avant + LinkedHashMap(r).apply { put("text", (get("text") as String) + " Réserver à part.") }
            e.hachis = e.etapes.size + avant.size
            return avant + r
        }

        // 5. Risotto : tout le bouillon d'un coup, le robot remue.
        if (e.risotto && Regex("\\blouche").containsMatchIn(s)) {
            if (!e.bolOccupe) return main()
            e.reserveHachis()
            return listOf(robot("Cuire", 18 * 60, CUIRE_C, 1, true, listOf("au robot, tout le bouillon d'un coup au lieu de louche par louche ; goûter le riz à la fin"),
                propre = "Verser tout le bouillon chaud dans le bol, fermer le couvercle."))
        }
        if (e.risotto && Regex("faire de meme|faites de meme|nouvelle louche|plus de bouillon").containsMatchIn(s)) return emptyList()

        // 6. Coloration : possible au robot quand la préparation y est déjà (130 °C), sinon à la poêle.
        val dorer = Regex("\\b(faites[- ](les |le |la )?(dorer|griller|colorer)|faites saisir|saisissez|grillez|dorez)\\b").containsMatchIn(s)
        if (dorer) {
            if (e.bolOccupe && !e.dansLeau && !pendantCeTemps && (dansUnRecipientRobot || !ailleurs)) {
                e.reserveHachis()
                val d = duree(brut) ?: 240
                return ensuite(listOf(robot("Faire dorer", d, 130, 1, true, listOf("le robot colore peu : 130 °C, sens inverse"))))
            }
            e.approx += "étape $n : coloration à la poêle, hors robot"
            return main("à la poêle, hors robot : le robot ne dore pas")
        }

        // 7. Cuisson dans une casserole ou une poêle : dans le bol.
        val revenir = Regex("\\b(revenir|suer|fondre|rissoler)\\b").containsMatchIn(s)
        val cuire = Regex("\\b(mijoter|laissez cuire|faites(-les|-le|-la)? cuire|poursuivez (la )?cuisson|portez a ebullition|cuire|jusqu'a absorption)\\b").containsMatchIn(s)
        val melange = Regex("\\b(melangez|melanger|remuez)\\b").containsMatchIn(s)
        val chauffeHuile = Regex("faites chauffer").containsMatchIn(s) && Regex("\\b(huile|beurre)\\b").containsMatchIn(s)
        if (Regex("dissoudre|preparez le bouillon").containsMatchIn(s)) e.bouillonPret = true
        val cuissonAuBol = !ailleurs && !pendantCeTemps
        if (e.dansLeau && cuissonAuBol && (revenir || chauffeHuile || cuire) && !Regex("\\bdans l'eau\\b").containsMatchIn(s)) {
            e.approx += "étape $n : à la poêle, le bol sert à la cuisson à l'eau"
            return main("à la poêle, hors robot : le bol sert à la cuisson à l'eau")
        }
        val tout = durees(brut)
        if (cuissonAuBol && (dansUnRecipientRobot || e.bolOccupe || e.aHacher.isNotEmpty() || e.hachisDansLeBol) && (revenir || chauffeHuile)) {
            val avantTout = hachageDiffere(garde = true)
            if (avantTout.isNotEmpty()) return avantTout + etape(brut, ingredients, e, n + 1)
            val notes = ArrayList<String>()
            val d = tout.firstOrNull() ?: 180.also { notes += "durée non indiquée : 3 min" }
            val avant = if (e.hachisDansLeBol) "Laisser le hachis dans le bol." else ""
            e.hachis = -1
            e.bolOccupe = true
            val r = arrayListOf(robot("Faire revenir", d, REVENIR_C, 1, true, notes, avant))
            // « … revenir 4 min, puis fond d'eau et poursuivez la cuisson 5 min » : deux réglages.
            if (tout.size >= 2 && Regex("\\b(poursuivez|laissez (cuire|mijoter)|mijoter)").containsMatchIn(s)) {
                val doux = Regex("feu doux|fremir|baissez le feu").containsMatchIn(s)
                r += robot("Puis cuire", tout[1], if (doux) DOUX_C else CUIRE_C, 1, true, propre = "Suite de l'étape, couvercle fermé.", avec = false)
            }
            return ensuite(r)
        }
        if (cuissonAuBol && (e.bolOccupe || dansUnRecipientRobot || e.aHacher.isNotEmpty() || e.hachisDansLeBol) && (cuire || (melange && tout.isNotEmpty()))) {
            val avantTout = hachageDiffere(garde = true)
            if (avantTout.isNotEmpty()) return avantTout + etape(brut, ingredients, e, n + 1)
            val notes = ArrayList<String>()
            val absorption = Regex("jusqu'a absorption").containsMatchIn(s)
            var d = tout.firstOrNull() ?: (if (absorption) 120 else 300.also { notes += "durée non indiquée : 5 min" })
            val doux = Regex("feu doux|fremir|fremissement|baissez le feu").containsMatchIn(s)
            // Liquide versé maintenant (pas « l'eau de cuisson » réservée ni un fond d'eau) : temps de montée en température.
            val peu = Regex("(fond|filet|peu|verre|trait|cuillere)s? d'eau").containsMatchIn(s)
            val verse = Regex("\\b(ajoutez|versez|mouillez|rajoutez)\\b").containsMatchIn(s)
            val eau = !peu && Regex("(d'|de l'|l')eau\\b(?! de cuisson)").containsMatchIn(s)
            val bouillon = e.bouillonPret && Regex("\\b(le|du) bouillon\\b").containsMatchIn(s)
            if (verse && (eau || bouillon) && Regex("ebullition|mijoter|laissez cuire|couvrez|couvercle").containsMatchIn(s)) {
                val l = (e.eau ?: (250.0 * e.couverts)) / 1000.0
                val monte = (ceil(l * 8)).toInt().coerceIn(3, 12)
                d += monte * 60
                notes += "dont $monte min de montée en température"
            }
            val avant = if (e.hachisDansLeBol && !e.bolOccupe) "Laisser le hachis dans le bol." else ""
            e.hachis = -1
            e.bolOccupe = true
            return ensuite(listOf(robot("Cuire", d, if (doux) DOUX_C else CUIRE_C, 1, true, notes, avant)))
        }

        // 8. Préparations froides : blancs en neige, appareil, pâte, mélanges « dans un saladier ».
        val neige = Regex("blancs? en neige|montez les blancs").containsMatchIn(s) && !Regex("incorporez").containsMatchIn(s)
        val blanchir = Regex("blanchi").containsMatchIn(s) && Regex("\\b(fouettez|battez)").containsMatchIn(s)
        val fouetter = Regex("\\b(fouettez|battez|battre)\\b").containsMatchIn(s)
        val saladier = Regex("\\bdans un (second |grand |autre )?(saladier|bol|recipient)\\b").containsMatchIn(s)
        val froidLibre = !e.bolOccupe || e.froid || (e.hachisDansLeBol && !e.dansLeau)
        if (Regex("incorporez (delicatement|petit a petit)|a l'aide d'une maryse|a la maryse|delicatement").containsMatchIn(s) && e.froid && Regex("blancs").containsMatchIn(s))
            return main("à la spatule, hors lame : pour garder les blancs en neige")
        if (neige && (!e.bolOccupe)) {
            val avant = bolVide()
            e.bolOccupe = false
            return avant + listOf(
                robot("Monter en neige", 4 * 60, 0, 4, false, listOf("bol et fouet propres et secs ; prolonger si les blancs ne sont pas fermes"),
                    avant = "Mettre le fouet.", fouet = true),
                texteSeul("Retirer le fouet et réserver les blancs dans un saladier. Rincer et sécher le bol."))
        }
        if (blanchir && froidLibre) {
            val avant = if (!e.froid) bolVide() else emptyList()
            e.bolOccupe = true; e.froid = true
            return avant + listOf(robot("Fouetter", 3 * 60, 0, 4, false, listOf("jusqu'à ce que le mélange blanchisse"),
                avant = "Mettre le fouet.", propre = texte.replace(Regex("\\bDans un (second |grand |autre )?saladier\\b"), "Dans le bol"), fouet = true),
                texteSeul("Retirer le fouet."))
        }
        if ((saladier && (melange || fouetter)) || (e.froid && (melange || fouetter) && Regex("\\b(ajoutez|incorporez|versez)").containsMatchIn(s))) {
            if (!Regex("\\b(cuire|chauff|revenir|mijoter)").containsMatchIn(s) && froidLibre && !(pendantCeTemps && e.bolOccupe && !e.froid)) {
                val garde = e.hachisDansLeBol && !e.froid
                val avant = if (garde) "Laisser le hachis dans le bol." else ""
                val sorties = if (!e.froid && !garde) bolVide() else emptyList()
                e.hachis = -1
                // Morceaux (pommes de terre, légumes) ou mélange « délicat » : sens inverse, doucement.
                val doux = Regex("delicatement|maryse").containsMatchIn(s) ||
                    (!fouetter && Regex("\\b(ajoutez|incorporez)\\b").containsMatchIn(s) && Regex("pommes de terre|morceaux|legumes|lardons|des\\b").containsMatchIn(s))
                e.bolOccupe = true; e.froid = true
                val propre = texte.replace(Regex("\\bDans un (second |grand |autre )?saladier\\b"), "Dans le bol").replace(Regex("\\bdans un (second |grand |autre )?saladier\\b"), "dans le bol")
                return sorties + ensuite(listOf(if (doux) robot("Mélanger délicatement", 15, 0, 2, true, avant = avant, propre = propre)
                    else robot("Mélanger", 20, 0, 4, false, avant = avant, propre = propre)))
            }
        }
        if (!ailleurs && e.bolOccupe && Regex("\\b(ajoutez|rajoutez|incorporez)\\b").containsMatchIn(s) && melange) {
            e.reserveHachis()
            return ensuite(listOf(robot("Mélanger", 30, 0, 1, true)))
        }

        // 9. Légumes à tailler : aromates (et légumes des soupes) hachés grossièrement juste avant la cuisson.
        val coupe = Regex("\\b(lamelles|rondelles|demi-lunes|des|morceaux|emincez|ciselez|batonnets|tronçons|troncons)\\b").containsMatchIn(s)
        val aromate = AROMATES.find(s)?.value
        val legume = aromate ?: (if (e.soupe) LEGUMES_SOUPE.find(s)?.value else null)
        if (legume != null && coupe && !(pendantCeTemps && e.bolOccupe) && !Regex("\\b(cuire|revenir|ajoutez)\\b").containsMatchIn(s)) {
            if (legume.startsWith("oignon") && e.auDressage.contains("oignon")) e.aHacherCru = true
            e.aHacher += ingredients.ifEmpty { legume }
            val t = Regex("\\b(en )?(fines |grosses |petites )?(lamelles|rondelles|demi-lunes|dés|bâtonnets)\\b( de [0-9][^.,]*)?", I)
                .replace(texte, "en gros morceaux")
            return listOf(texteSeul("$t (le robot les hachera)"))
        }

        // 10. Le reste : à la main.
        if (Regex("\\b(reservez(?!-en)|egouttez|transvasez|versez (le tout |la preparation )?dans (un|le) (plat|saladier|moule))").containsMatchIn(s) ||
            (ailleurs && Regex("\\b(ajoutez|disposez|versez|placez)\\b").containsMatchIn(s) && !e.dansLeau)) {
            if (e.bolOccupe) libere()
            e.egouttee = egoutte
            return main()
        }
        e.egouttee = false
        if (Regex("\\b(lamelles|rondelles|demi-lunes|cubes|dés|tranches|morceaux|bâtonnets|quartiers|julienne|sommités)\\b", I).containsMatchIn(brut) &&
            Regex("\\b(coupez|emincez|taillez|detaillez|decoupez)\\b").containsMatchIn(s)) return main("au couteau : le robot hache, il ne tranche pas")
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
            approx += "Jow propose cette recette pour ${options.take(4).joinToString(", ")}… personnes : convertie pour $choisi, puis quantités recalculées pour $n dans Toque (temps de cuisson de la version pour $choisi)"
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
        val famille0 = sa(r["family"].obj().str("name").orEmpty() + " " + titre)
        val soupe = Regex("\\b(bouillon|soupe|veloute|potage|mijote|curry|chili|ragout|blanquette|minestrone|ramen|pot-au-feu)").containsMatchIn(famille0)
        val dernier = sa(etapesJow.lastOrNull()?.str("label").orEmpty())
        val e = Etat(n, eau, pates, etapes, soupe, dernier)
        e.risotto = Regex("\\brisotto").containsMatchIn(sa(titre))
        e.poeleSeule = Regex("\\b(tortilla|omelette|crepes?|galettes?|pancakes?|frittata|blinis?)\\b").containsMatchIn(sa(titre))

        // Pré-tri : un hachis « pendant ce temps » (persil, ail…) pendant une cuisson au bol est fait
        // avant, au robot ; un fromage « râpé » sans étape de râpage est râpé au début.
        val ordre = ArrayList(etapesJow)
        val premierBol = ordre.indexOfFirst { val t = sa(it.str("label").orEmpty()); RECIPIENT_ROBOT.containsMatchIn(t) || Regex("eau bouillante").containsMatchIn(t) }
        if (premierBol >= 0) {
            val avances = ordre.withIndex().filter { (i, d) ->
                val t = sa(d.str("label").orEmpty())
                i > premierBol && Regex("^pendant ce temps,?\\s*(hachez|ciselez|rapez)\\b").containsMatchIn(t)
            }
            for ((_, d) in avances.reversed()) ordre.remove(d)
            avances.forEachIndexed { k, (_, d) ->
                val sans = (d.str("label") ?: "").replace(Regex("^\\s*Pendant ce temps,?\\s*", I), "").replaceFirstChar { it.uppercaseChar() }
                ordre.add(premierBol + k, LinkedHashMap(d).apply { put("label", sans) })
                approx += "« ${sans.trimEnd('.')} » avancé avant la cuisson, pour le faire au robot"
            }
        }
        val fromages = r["constituents"].list().map { it.obj() }.filter {
            Regex("\\b(comte|parmesan|emmental|gruyere|cheddar|beaufort|grana|pecorino|mimolette)\\b").containsMatchIn(sa(it.str("name").orEmpty()))
        }
        val toutLeTexte = sa(etapesJow.joinToString(" ") { it.str("label").orEmpty() })
        val debutRapage = if (ordre.isNotEmpty() && sa(ordre[0].str("label").orEmpty()).startsWith("prechauff")) 1 else 0
        for (f in fromages.reversed()) {
            val nom = sa(f.str("name").orEmpty()).split(' ', '(').first()
            if (Regex("rape").containsMatchIn(sa(f.str("name").orEmpty()))) continue
            if (Regex("\\b$nom (rape|en poudre)").containsMatchIn(toutLeTexte) && !Regex("\\brapez\\b[^.]*\\b$nom").containsMatchIn(toutLeTexte))
                ordre.add(debutRapage, linkedMapOf("label" to "Râpez le ${f.str("name")!!.lowercase()}.", "involvedIngredients" to listOf(mapOf("id" to f.str("id")))))
        }
        for (d in ordre) {
            val label = (d.str("label") ?: "").trim()
            if (label.isEmpty()) continue
            val impliques = d["involvedIngredients"].list().mapNotNull { parId[it.obj().str("id")] }.joinToString(", ")
            etapes += etape(label, impliques, e, etapes.size + 1)
        }
        if (e.aHacher.isNotEmpty()) {
            // Légumes jamais cuits au bol : à couper au couteau, comme Jow l'écrit.
            e.approx += "légumes à couper au couteau : " + e.aHacher.joinToString(", ")
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
