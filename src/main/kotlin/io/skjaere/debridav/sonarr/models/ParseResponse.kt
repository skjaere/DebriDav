package io.skjaere.debridav.sonarr.models

import kotlinx.serialization.Serializable

@Serializable
data class ParseResponse(
    val episodes: List<Episodes>
) {
    @Serializable
    data class Episodes(
        val id: Long
    )
}