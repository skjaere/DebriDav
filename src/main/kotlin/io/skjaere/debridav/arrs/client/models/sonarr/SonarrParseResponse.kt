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
    )
}

// Sonarr's /api/v3/parse does NOT include the episodeFile object inline —
// only episodeFileId. To get the actual file path for the path-match guard in
// the repair pipeline, the caller does a separate GET /api/v3/episodefile/{id}.
@Serializable
data class SonarrEpisodeFile(
    val id: Long,
    val path: String,
)
