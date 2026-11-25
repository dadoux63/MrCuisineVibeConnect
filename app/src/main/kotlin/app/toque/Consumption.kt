package app.toque

import android.content.Context
import app.toque.core.net.ConsumptionLog
import java.io.File

/** Journal des ingrédients pesés en recette guidée, partagé par l'interface (écriture) et le serveur d'état (lecture). */
object Consumption {
    @Volatile private var log: ConsumptionLog? = null

    fun of(context: Context): ConsumptionLog =
        log ?: synchronized(this) {
            log ?: ConsumptionLog(File(context.applicationContext.filesDir, "consommation.json")).also { log = it }
        }
}
