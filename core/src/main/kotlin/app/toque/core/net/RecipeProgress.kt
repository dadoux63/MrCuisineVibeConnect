package app.toque.core.net

/**
 * Où en est la recette guidée affichée par l'interface, pour la domotique (lecture seule).
 *
 * L'interface (CookScreen) en est la seule source : elle l'envoie au processus machine, qui ne fait
 * que le garder pour le serveur d'état. Rien ici ne touche au moteur ni à la chauffe.
 */
data class RecipeProgress(
    val title: String,
    /** Étape affichée, comptée à partir de 1. */
    val step: Int,
    val stepCount: Int,
    val stepText: String,
    /** La recette attend un geste de la personne (même règle que l'anneau vert de la molette). */
    val waiting: Boolean,
    /** Ce qu'elle attend : voir [WAIT_REASONS] ; vide quand un programme lancé d'ici tourne. */
    val waitingFor: String,
    /** Pesée demandée à cette étape (0 sinon) et son ingrédient. */
    val weighG: Int = 0,
    val weighLabel: String = "",
    /** Ingrédients nommés par l'étape, quantités adaptées (« 200 g farine »). */
    val ingredients: List<String> = emptyList(),
    /** Horloge monotone du système (SystemClock.elapsedRealtime) à l'arrivée sur l'étape. */
    val stepSinceMs: Long = 0,
) {
    fun toMap(): Map<String, Any?> = linkedMapOf(
        "title" to title,
        "step" to step,
        "stepCount" to stepCount,
        "stepText" to stepText,
        "waiting" to waiting,
        "waitingFor" to waitingFor,
        "weighG" to weighG,
        "weighLabel" to weighLabel,
        "ingredients" to ingredients,
        "stepSinceMs" to stepSinceMs,
    )

    /**
     * Ce qui est publié (liste blanche, textes tronqués). [nowMs] : même horloge que [stepSinceMs].
     */
    fun publicMap(nowMs: Long): Map<String, Any?> = linkedMapOf(
        "title" to title.take(MAX_TEXT),
        "step" to step,
        "step_count" to stepCount,
        "step_text" to stepText.take(MAX_TEXT),
        "waiting" to waiting,
        "waiting_for" to waitingFor.takeIf { it in WAIT_REASONS },
        "weigh_g" to weighG,
        "weigh_label" to weighLabel.take(MAX_TEXT),
        "ingredients" to ingredients.take(MAX_INGREDIENTS).map { it.take(MAX_TEXT) },
        "step_elapsed_s" to if (stepSinceMs > 0) maxOf(0L, (nowMs - stepSinceMs) / 1000) else null,
    )

    companion object {
        /** weigh : pesée ; hand : étape à la main ; start : réglage à lancer ; next : étape faite, passer à la suite ; finish : dernière étape. */
        val WAIT_REASONS = setOf("weigh", "hand", "start", "next", "finish")
        const val MAX_TEXT = 300
        const val MAX_INGREDIENTS = 12

        fun fromMap(m: Map<String, Any?>): RecipeProgress? {
            val title = m["title"] as? String ?: return null
            val ingredients = (m["ingredients"] as? List<*>)?.filterIsInstance<String>()
                ?: (m["ingredients"] as? Array<*>)?.filterIsInstance<String>() ?: emptyList()
            return RecipeProgress(
                title = title,
                step = (m["step"] as? Number)?.toInt() ?: 0,
                stepCount = (m["stepCount"] as? Number)?.toInt() ?: 0,
                stepText = m["stepText"] as? String ?: "",
                waiting = m["waiting"] as? Boolean ?: false,
                waitingFor = m["waitingFor"] as? String ?: "",
                weighG = (m["weighG"] as? Number)?.toInt() ?: 0,
                weighLabel = m["weighLabel"] as? String ?: "",
                ingredients = ingredients,
                stepSinceMs = (m["stepSinceMs"] as? Number)?.toLong() ?: 0,
            )
        }
    }
}
