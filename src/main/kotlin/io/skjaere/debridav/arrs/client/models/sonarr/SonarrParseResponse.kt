package io.skjaere.debridav.arrs.client.models.sonarr

import kotlinx.serialization.Serializable

@Serializable
data class SonarrParseResponse(
    val episodes: List<Episode>
) {
    @Serializable
    data class Episode(
        val id: Long,
        val episodeFileId: Long = 0,
        // Populated inline by Sonarr's /parse when the episode already has an
        // imported file. Used by the repair pipeline to verify the unhealthy
        // copy in debridav is actually the one Sonarr is referencing — see
        // ArrPathMatcher and SonarrApiClient.deleteFileAndSearch.
        val episodeFile: EpisodeFile? = null,
    )

    @Serializable
    data class EpisodeFile(
        val id: Long,
        val path: String,
    )
}
