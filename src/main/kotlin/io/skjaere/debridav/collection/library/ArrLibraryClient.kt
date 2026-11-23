package io.skjaere.debridav.collection.library

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.skjaere.debridav.arrs.ArrConfiguration
import io.skjaere.debridav.arrs.RadarrConfigurationProperties
import io.skjaere.debridav.arrs.SonarrConfigurationProperties
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import org.springframework.stereotype.Component

/** Sonarr or Radarr couldn't be asked, or answered with an error: the collection can't be shown as it is. */
class ArrUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** An image as the arr sent it. */
class ArrImageData(val bytes: ByteArray, val contentType: String)

/** Reads the collection from Sonarr and Radarr: what each has in its library, and their posters. */
@Component
class ArrLibraryClient(
    private val httpClient: HttpClient,
    private val sonarr: SonarrConfigurationProperties,
    private val radarr: RadarrConfigurationProperties,
) {
    /** Radarr's movies; none while its integration is off. */
    suspend fun movies(): List<RadarrMovieJson> =
        if (!radarr.integrationEnabled) emptyList() else get(radarr, "Radarr", "/movie").body()

    /** Sonarr's series; none while its integration is off. */
    suspend fun series(): List<SonarrSeriesJson> =
        if (!sonarr.integrationEnabled) emptyList() else get(sonarr, "Sonarr", "/series").body()

    /** One series' episodes, specials included. */
    suspend fun episodes(seriesId: Long): List<SonarrEpisodeJson> =
        if (!sonarr.integrationEnabled) {
            emptyList()
        } else {
            get(sonarr, "Sonarr", "/episode") { parameter("seriesId", seriesId) }.body()
        }

    /** One series' details, or null when Sonarr doesn't have it. */
    suspend fun series(seriesId: Long): SonarrSeriesJson? =
        if (!sonarr.integrationEnabled) null else getOrNull(sonarr, "Sonarr", "/series/$seriesId")?.body()

    /** One movie's details, or null when Radarr doesn't have it. */
    suspend fun movie(movieId: Long): RadarrMovieJson? =
        if (!radarr.integrationEnabled) null else getOrNull(radarr, "Radarr", "/movie/$movieId")?.body()

    /** A movie's or a series' [cover] (poster or fanart) from the arr's media covers; null when it has none. */
    suspend fun image(kind: LibraryKind, id: Long, cover: Cover): ArrImageData? {
        val (arr, name) = when (kind) {
            LibraryKind.Movie -> radarr to "Radarr"
            LibraryKind.Series -> sonarr to "Sonarr"
        }
        val response = if (arr.integrationEnabled) getOrNull(arr, name, "/mediacover/$id/${cover.fileName}") else null
        return response?.let { ArrImageData(it.readRawBytes(), it.contentType()?.toString() ?: "image/jpeg") }
    }

    private suspend fun get(
        arr: ArrConfiguration,
        name: String,
        path: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse = getOrNull(arr, name, path, block) ?: throw ArrUnavailable("$name has no $path")

    /** The answer to a GET of [path], or null for a 404; anything else that isn't a success is [ArrUnavailable]. */
    private suspend fun getOrNull(
        arr: ArrConfiguration,
        name: String,
        path: String,
        block: HttpRequestBuilder.() -> Unit = {},
    ): HttpResponse? {
        val response = send(arr, name, path, block)
        return when {
            response.status == HttpStatusCode.NotFound -> null
            response.status.isSuccess() -> response
            else -> throw ArrUnavailable("$name answered ${response.status.value}")
        }
    }

    /** GETs [path] from [arr] with its key; not reaching it at all is [ArrUnavailable]. */
    private suspend fun send(
        arr: ArrConfiguration,
        name: String,
        path: String,
        block: HttpRequestBuilder.() -> Unit,
    ): HttpResponse = try {
        httpClient.get("${arr.getApiBaseUrl()}$path") {
            accept(ContentType.Application.Json)
            header("X-Api-Key", arr.apiKey)
            block()
        }
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        throw ArrUnavailable("$name couldn't be reached: ${e.message ?: e::class.simpleName}", e)
    }
}

enum class LibraryKind { Movie, Series }

enum class Cover(val fileName: String) { Poster("poster.jpg"), Fanart("fanart.jpg") }

@Serializable
data class ArrLanguageJson(val name: String? = null)

@Serializable
data class ArrImageJson(val coverType: String? = null, val url: String? = null)

@Serializable
data class RadarrMovieJson(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val imdbId: String? = null,
    val tmdbId: Int? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val hasFile: Boolean = false,
    val originalLanguage: ArrLanguageJson? = null,
    val images: List<ArrImageJson> = emptyList(),
)

@Serializable
data class SonarrSeasonStatisticsJson(val totalEpisodeCount: Int = 0, val episodeFileCount: Int = 0)

@Serializable
data class SonarrSeasonJson(val seasonNumber: Int, val statistics: SonarrSeasonStatisticsJson? = null)

@Serializable
data class SonarrSeriesJson(
    val id: Long,
    val title: String,
    val year: Int? = null,
    val tvdbId: Int? = null,
    val tmdbId: Int? = null,
    val imdbId: String? = null,
    val tvMazeId: Int? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val originalLanguage: ArrLanguageJson? = null,
    val images: List<ArrImageJson> = emptyList(),
    val seasons: List<SonarrSeasonJson> = emptyList(),
)

@Serializable
data class SonarrEpisodeJson(
    val id: Long,
    val seasonNumber: Int,
    val episodeNumber: Int,
    val title: String? = null,
    val airDate: String? = null,
    val runtime: Int? = null,
    val overview: String? = null,
    val hasFile: Boolean = false,
)
