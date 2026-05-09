package io.skjaere.debridav.arrs.client.models.radarr

import kotlinx.serialization.Serializable

@Serializable
data class RadarrParseResponse(
    /**
     * Null when Radarr's `/parse` endpoint can't match the input to any movie in its
     * library (e.g. a TV-show release name being parsed during the repair-fanout path,
     * where we hit every arr blindly). Was non-nullable previously, which made the JSON
     * deserializer throw on every TV-show name passed through the Radarr fanout step.
     */
    val movie: Movie? = null,
) {
    @Serializable
    data class Movie(
        val id: Long,
        val movieFileId: Long = 0,
        // Populated inline by Radarr's /parse when the movie already has an
        // imported file. Used by the repair pipeline to verify the unhealthy
        // copy in debridav is actually the one Radarr is referencing — see
        // ArrPathMatcher and RadarrApiClient.deleteFileAndSearch.
        val movieFile: MovieFile? = null,
    )

    @Serializable
    data class MovieFile(
        val id: Long,
        val path: String,
    )
}
