package io.skjaere.debridav.arrs.client.models.sonarr

import kotlinx.serialization.Serializable

@Serializable
data class SonarrParseResponse(
    val episodes: List<Episode>
) {
    @Serializable
    data class Episode(
        val id: Long
    )
}
