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
import io.skjaere.debridav.arrs.SonarrConfigurationProperties
import io.skjaere.debridav.arrs.client.models.sonarr.SonarrEpisodeFile
import io.skjaere.debridav.arrs.client.models.sonarr.SonarrParseResponse
import io.skjaere.debridav.config.ConfigurationTester
import io.skjaere.debridav.config.TestResult
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Component
import kotlin.reflect.KClass

@Component
@ConditionalOnExpression($$"${sonarr.integration-enabled:true}")
class SonarrApiClient(
    private val httpClient: HttpClient,
    private val sonarrConfigurationProperties: SonarrConfigurationProperties
) : BaseArrClient by DefaultBaseArrClient(httpClient, sonarrConfigurationProperties),
    ArrClient, ConfigurationTester {
    private val logger = LoggerFactory.getLogger(SonarrApiClient::class.java)

    override suspend fun getItemIdFromName(name: String): Long? {
        return parse(name).body<SonarrParseResponse>().episodes.firstOrNull()?.id
    }

    override fun getCategory(): String = sonarrConfigurationProperties.category

    @Suppress("ReturnCount")
    override suspend fun deleteFileAndSearch(name: String, expectedPaths: Set<String>): DeleteAndSearchResult {
        if (expectedPaths.isEmpty()) {
            logger.debug("No expected paths provided for '{}' — skipping Sonarr lookup", name)
            return DeleteAndSearchResult.NotImported
        }
        val parseResponse = parse(name).body<SonarrParseResponse>()
        val episodes = parseResponse.episodes
        if (episodes.isEmpty()) {
            logger.warn("No episodes found for '{}' in Sonarr", name)
            return DeleteAndSearchResult.NotImported
        }

        // Sonarr's /parse returns episodeFileId but does NOT include the EpisodeFile
        // object inline — we have to follow up with GET /episodefile/{id} to learn
        // each file's path. Only act on episodes whose imported file lives at one of
        // the paths we expect for the unhealthy debridav copy. Without this guard, the
        // same release name imported from a different download client (or a duplicate
        // NZB/torrent in debridav) would be wrongly deleted+searched here.
        val candidates = episodes.filter { it.episodeFileId > 0 }
        if (candidates.isEmpty()) {
            logger.info(
                "Sonarr has {} episode match(es) for '{}' but none have an imported file yet — " +
                        "leaving Sonarr's state alone",
                episodes.size, name,
            )
            return DeleteAndSearchResult.NotImported
        }
        val pathByEpisodeFileId = candidates.associate { it.episodeFileId to fetchEpisodeFilePath(it.episodeFileId) }
        val matched = candidates.filter { ep ->
            val path = pathByEpisodeFileId[ep.episodeFileId]
            path != null && expectedPaths.any { ArrPathMatcher.matches(path, it) }
        }
        if (matched.isEmpty()) {
            logger.info(
                "Sonarr has matches for '{}' but none point at expected paths {} " +
                        "(checked {} episode file(s); path-mismatch — leaving Sonarr's copy alone)",
                name, expectedPaths, candidates.size,
            )
            return DeleteAndSearchResult.ImportedElsewhere(pathByEpisodeFileId.values.filterNotNull())
        }

        matched.forEach { episode ->
            val path = pathByEpisodeFileId[episode.episodeFileId]
            logger.info(
                "Deleting episode file {} (path={}) for '{}'",
                episode.episodeFileId, path, name,
            )
            val deleteResponse = httpClient.delete(
                "${sonarrConfigurationProperties.getApiBaseUrl()}/episodefile/${episode.episodeFileId}"
            ) {
                accept(ContentType.Application.Json)
                header("X-Api-Key", sonarrConfigurationProperties.apiKey)
            }
            if (!deleteResponse.status.isSuccess()) {
                logger.error(
                    "Failed to delete episode file {} for '{}': {} {}",
                    episode.episodeFileId,
                    name,
                    deleteResponse.status,
                    deleteResponse.bodyAsText()
                )
            }
        }

        val episodeIds = matched.map { it.id }
        logger.info("Triggering EpisodeSearch for episodes {} for '{}'", episodeIds, name)
        val searchResponse = httpClient.post("${sonarrConfigurationProperties.getApiBaseUrl()}/command") {
            accept(ContentType.Application.Json)
            header("X-Api-Key", sonarrConfigurationProperties.apiKey)
            contentType(ContentType.Application.Json)
            setBody("""{"name": "EpisodeSearch", "episodeIds": $episodeIds}""")
        }
        if (!searchResponse.status.isSuccess()) {
            logger.error(
                "Failed to trigger EpisodeSearch for '{}': {} {}",
                name,
                searchResponse.status,
                searchResponse.bodyAsText()
            )
        }
        return DeleteAndSearchResult.Replaced
    }

    // Looks up the on-disk path Sonarr currently reports for an imported episode.
    // /parse won't volunteer this; the path lives on the EpisodeFile resource and we
    // need it to verify the unhealthy debridav file is the one Sonarr is referencing
    // before we delete+research the episode.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchEpisodeFilePath(episodeFileId: Long): String? {
        return try {
            val response = httpClient.get(
                "${sonarrConfigurationProperties.getApiBaseUrl()}/episodefile/$episodeFileId"
            ) {
                accept(ContentType.Application.Json)
                header("X-Api-Key", sonarrConfigurationProperties.apiKey)
            }
            if (!response.status.isSuccess()) {
                logger.warn(
                    "Failed to fetch Sonarr episode file {}: {} {}",
                    episodeFileId, response.status, response.bodyAsText(),
                )
                return null
            }
            response.body<SonarrEpisodeFile>().path.takeIf { it.isNotBlank() }
        } catch (e: Exception) {
            logger.warn("Error fetching Sonarr episode file {}: {}", episodeFileId, e.message)
            null
        }
    }

    override val configurationClass: KClass<*> = SonarrConfigurationProperties::class
    override val label: String = "Sonarr"

    @Suppress("TooGenericExceptionCaught")
    override suspend fun test(overrides: Map<String, String>): TestResult = try {
        val host = overrides["sonarr.host"] ?: sonarrConfigurationProperties.host
        val port = overrides["sonarr.port"]?.toIntOrNull() ?: sonarrConfigurationProperties.port
        val apiBasePath = overrides["sonarr.api-base-path"] ?: sonarrConfigurationProperties.apiBasePath
        val apiKey = overrides["sonarr.api-key"] ?: sonarrConfigurationProperties.apiKey
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
