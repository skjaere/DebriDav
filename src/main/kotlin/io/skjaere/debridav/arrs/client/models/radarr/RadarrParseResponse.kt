package io.skjaere.debridav.arrs.client.models.radarr

import kotlinx.serialization.Serializable

@Serializable
data class RadarrParseResponse(
    val movie: Movie
) {
    @Serializable
    data class Movie(
        val id: Long
    )
}
