package io.skjaere.debridav.collection.library

import java.util.Locale

/** English language names, as Sonarr and Radarr give them, to ISO 639-1 codes: "German" to "de". */
object LanguageCodes {
    private val CODES: Map<String, String> = Locale.getISOLanguages()
        .associateBy { Locale.of(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }

    fun of(name: String?): String? = name?.lowercase(Locale.ROOT)?.let(CODES::get)
}
