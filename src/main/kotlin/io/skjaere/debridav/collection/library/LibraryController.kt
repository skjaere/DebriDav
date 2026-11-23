package io.skjaere.debridav.collection.library

import kotlinx.coroutines.runBlocking
import org.springframework.http.CacheControl
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class MovieDto(
    val id: Long,
    val title: String,
    val year: Int?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val imdbId: String?,
    val tmdbId: Int?,
    /** ISO 639-1, e.g. "en". */
    val originalLanguage: String?,
    val hasFile: Boolean,
    /** Where the page loads the poster from; null when the arr has none. */
    val poster: String?,
)

data class SeasonDto(val number: Int, val episodes: Int)

data class SeriesDto(
    val id: Long,
    val title: String,
    val year: Int?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val tvdbId: Int?,
    val tmdbId: Int?,
    val imdbId: String?,
    val tvMazeId: Int?,
    val originalLanguage: String?,
    val seasons: List<SeasonDto>,
    val poster: String?,
)

data class EpisodeDto(
    val id: Long,
    val season: Int,
    val episode: Int,
    val title: String?,
    val airDate: LocalDate?,
    val overview: String?,
    val runtimeMinutes: Int?,
    val hasFile: Boolean,
)

/** The collection pages' content: what Radarr and Sonarr have in their libraries. */
@RestController
@RequestMapping("/api/v1/collection")
class LibraryController(private val arrs: ArrLibraryClient) {

    @GetMapping("/movies")
    fun movies(): List<MovieDto> = runBlocking {
        arrs.movies().sortedBy { it.title.lowercase(Locale.ROOT) }.map { it.toDto() }
    }

    @GetMapping("/series")
    fun series(): List<SeriesDto> = runBlocking {
        arrs.series().sortedBy { it.title.lowercase(Locale.ROOT) }.map { it.toDto() }
    }

    @GetMapping("/series/{id}/episodes")
    fun episodes(@PathVariable id: Long): List<EpisodeDto> = runBlocking {
        arrs.episodes(id).sortedWith(compareBy({ it.seasonNumber }, { it.episodeNumber })).map { it.toDto() }
    }

    /** A poster or fanart, fetched from the arr with its key; cached by the browser for a day. */
    @GetMapping("/images/{kind}/{id}/{cover}")
    fun image(
        @PathVariable kind: String,
        @PathVariable id: Long,
        @PathVariable cover: String,
    ): ResponseEntity<ByteArray> {
        val libraryKind = LIBRARY_KINDS[kind]
        val wanted = Cover.entries.firstOrNull { it.name.lowercase() == cover }
        if (libraryKind == null || wanted == null) return ResponseEntity.badRequest().build()
        val image = runBlocking { arrs.image(libraryKind, id, wanted) }
        return if (image == null) {
            ResponseEntity.notFound().build()
        } else {
            ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(image.contentType))
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePrivate())
                .body(image.bytes)
        }
    }

    @ExceptionHandler(ArrUnavailable::class)
    fun unavailable(e: ArrUnavailable): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(mapOf("message" to e.message))

    private fun RadarrMovieJson.toDto() = MovieDto(
        id = id,
        title = title,
        year = year,
        overview = overview,
        runtimeMinutes = runtime?.takeIf { it > 0 },
        imdbId = imdbId?.takeIf { it.isNotBlank() },
        tmdbId = tmdbId?.takeIf { it > 0 },
        originalLanguage = languageCode(originalLanguage?.name),
        hasFile = hasFile,
        poster = posterPath("movie", id, images),
    )

    private fun SonarrSeriesJson.toDto() = SeriesDto(
        id = id,
        title = title,
        year = year,
        overview = overview,
        runtimeMinutes = runtime?.takeIf { it > 0 },
        tvdbId = tvdbId?.takeIf { it > 0 },
        tmdbId = tmdbId?.takeIf { it > 0 },
        imdbId = imdbId?.takeIf { it.isNotBlank() },
        tvMazeId = tvMazeId?.takeIf { it > 0 },
        originalLanguage = languageCode(originalLanguage?.name),
        seasons = seasons.sortedBy { it.seasonNumber }
            .map { SeasonDto(it.seasonNumber, it.statistics?.totalEpisodeCount ?: 0) },
        poster = posterPath("series", id, images),
    )

    private fun SonarrEpisodeJson.toDto() = EpisodeDto(
        id = id,
        season = seasonNumber,
        episode = episodeNumber,
        title = title,
        airDate = airDate?.let { date -> try { LocalDate.parse(date) } catch (_: DateTimeParseException) { null } },
        overview = overview,
        runtimeMinutes = runtime?.takeIf { it > 0 },
        hasFile = hasFile,
    )

    private fun posterPath(kind: String, id: Long, images: List<ArrImageJson>): String? =
        "/api/v1/collection/images/$kind/$id/poster".takeIf { images.any { it.coverType == "poster" } }

    private companion object {
        val LIBRARY_KINDS = mapOf("movie" to LibraryKind.Movie, "series" to LibraryKind.Series)

        /** English language names, as the arrs give them, to ISO 639-1 codes: "German" to "de". */
        val LANGUAGE_CODES: Map<String, String> = Locale.getISOLanguages()
            .associateBy { Locale.of(it).getDisplayLanguage(Locale.ENGLISH).lowercase(Locale.ROOT) }

        fun languageCode(name: String?): String? = name?.lowercase(Locale.ROOT)?.let(LANGUAGE_CODES::get)
    }
}
