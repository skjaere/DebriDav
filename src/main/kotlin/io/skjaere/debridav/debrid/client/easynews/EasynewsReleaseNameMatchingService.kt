package io.skjaere.debridav.debrid.client.easynews

import org.springframework.stereotype.Service

@Service
class EasynewsReleaseNameMatchingService {
    fun matches(magnetTitle: String, enTitle: String): Boolean {
        val normalizedMagnetTitle = magnetTitle
            .lowercase()
            .replace("\\[(.*?)\\]".toRegex(), "")
            .filter { it.isLetterOrDigit() }

        val normalizedEnTitle = enTitle
            .lowercase()
            .filter { it.isLetterOrDigit() }

        return normalizedEnTitle.startsWith(normalizedMagnetTitle.lowercase()) && !normalizedEnTitle.endsWith("sample")
    }
}
