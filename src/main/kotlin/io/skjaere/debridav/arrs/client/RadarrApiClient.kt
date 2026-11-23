package io.skjaere.debridav.arrs.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.skjaere.debridav.arrs.RadarrConfigurationProperties
import io.skjaere.debridav.arrs.client.models.radarr.RadarrMovieFile
import io.skjaere.debridav.arrs.client.models.radarr.RadarrParseResponse
import io.skjaere.debridav.config.ConfigurationTester
import io.skjaere.debridav.config.TestResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
@ConditionalOnExpression("\${radarr.integration-enabled:true}")
class RadarrApiClient(
    private val httpClient: HttpClient,
    private val radarrConfigurationProperties: RadarrConfigurationProperties
) : BaseArrClient by DefaultBaseArrClient(httpClient, radarrConfigurationProperties),
    ArrClient, ConfigurationTester {
    private val logger = LoggerFactory.getLogger(RadarrApiClient::class.java)

    override suspend fun getItemIdFromName(name: String): Long? {
        return parse(name).body<RadarrParseResponse>().movie?.id
    }

    override fun getCategory(): String = radarrConfigurationProperties.category

    @Suppress("ReturnCount")
    override suspend fun deleteFileAndSearch(name: String, expectedPaths: Set<String>): DeleteAndSearchResult {
        if (expectedPaths.isEmpty()) {
            logger.debug("No expected paths provided for '{}' — skipping Radarr lookup", name)
            return DeleteAndSearchResult.NotImported
        }
        val parseResponse = parse(name).body<RadarrParseResponse>()
        val movie = parseResponse.movie

        if (movie == null || movie.id == 0L) {
            logger.warn("No movie found for '{}' in Radarr", name)
            return DeleteAndSearchResult.NotImported
        }

        // Radarr's /parse returns movieFileId but does NOT include the MovieFile
        // object inline — we have to follow up with GET /moviefile/{id} to learn the
        // file's path. Only act when the movie's imported file lives at one of the
        // paths we expect for the unhealthy debridav copy.
        if (movie.movieFileId == 0L) {
            logger.info("Radarr matched '{}' but the movie has no imported file yet", name)
            return DeleteAndSearchResult.NotImported
        }
        val actualPath = fetchMovieFilePath(movie.movieFileId)
        if (actualPath == null || expectedPaths.none { ArrPathMatcher.matches(actualPath, it) }) {
            logger.info(
                "Radarr matched '{}' but its file path '{}' is not in expected paths {} " +
                        "(path-mismatch — leaving Radarr's copy alone)",
                name, actualPath, expectedPaths,
            )
            return DeleteAndSearchResult.ImportedElsewhere(listOfNotNull(actualPath))
        }

        logger.info(
            "Deleting movie file {} (path={}) for '{}'",
            movie.movieFileId, actualPath, name,
        )
        val deleteResponse = httpClient.delete(
            "${radarrConfigurationProperties.getApiBaseUrl()}/moviefile/${movie.movieFileId}"
        ) {
            accept(ContentType.Application.Json)
            header("X-Api-Key", radarrConfigurationProperties.apiKey)
        }
        if (!deleteResponse.status.isSuccess()) {
            logger.error(
                "Failed to delete movie file {} for '{}': {} {}",
                movie.movieFileId,
                name,
                deleteResponse.status,
                deleteResponse.bodyAsText()
            )
        }

        logger.info("Triggering MoviesSearch for movie {} for '{}'", movie.id, name)
        val searchResponse = httpClient.post("${radarrConfigurationProperties.getApiBaseUrl()}/command") {
            accept(ContentType.Application.Json)
            header("X-Api-Key", radarrConfigurationProperties.apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"name": "MoviesSearch", "movieIds": [${movie.id}]}""")
        }
        if (!searchResponse.status.isSuccess()) {
            logger.error(
                "Failed to trigger MoviesSearch for '{}': {} {}",
                name,
                searchResponse.status,
                searchResponse.bodyAsText()
            )
        }
        return DeleteAndSearchResult.Replaced
    }

    // Looks up the on-disk path Radarr currently reports for an imported movie file.
    // /parse won't volunteer this; the path lives on the MovieFile resource and we
    // need it to verify the unhealthy debridav file is the one Radarr is referencing
    // before we delete+research the movie.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchMovieFilePath(movieFileId: Long): String? {
        return try {
            val response = httpClient.get(
                "${radarrConfigurationProperties.getApiBaseUrl()}/moviefile/$movieFileId"
            ) {
                accept(ContentType.Application.Json)
                header("X-Api-Key", radarrConfigurationProperties.apiKey)
            }
            if (!response.status.isSuccess()) {
                logger.warn(
                    "Failed to fetch Radarr movie file {}: {} {}",
                    movieFileId, response.status, response.bodyAsText(),
                )
                return null
            }
            response.body<RadarrMovieFile>().path.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.warn("Error fetching Radarr movie file {}: {}", movieFileId, e.message)
            null
        }
    }

    override val configurationClass: KClass<*> = RadarrConfigurationProperties::class
    override val label: String = "Radarr"

    @Suppress("TooGenericExceptionCaught")
    override suspend fun test(overrides: Map<String, String>): TestResult = try {
        val host = overrides["radarr.host"] ?: radarrConfigurationProperties.host
        val port = overrides["radarr.port"]?.toIntOrNull() ?: radarrConfigurationProperties.port
        val apiBasePath = overrides["radarr.api-base-path"] ?: radarrConfigurationProperties.apiBasePath
        val apiKey = overrides["radarr.api-key"] ?: radarrConfigurationProperties.apiKey
        val baseUrl = "http://$host:$port$apiBasePath"

        val response = httpClient.get("$baseUrl/system/status") {
            accept(ContentType.Application.Json)
            header("X-Api-Key", apiKey)
        }
        if (response.status.isSuccess()) {
            TestResult(success = true, message = "Connected successfully")
        } else {
            TestResult(success = false, message = "HTTP ${response.status.value}: ${response.bodyAsText()}")
        }
    } catch (e: Exception) {
        TestResult(success = false, message = e.message ?: "Unknown error")
    }
}
