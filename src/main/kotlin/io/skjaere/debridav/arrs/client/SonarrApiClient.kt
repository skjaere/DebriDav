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

    override suspend fun deleteFileAndSearch(name: String): Boolean {
        val parseResponse = parse(name).body<SonarrParseResponse>()
        val episodes = parseResponse.episodes
        if (episodes.isEmpty()) {
            logger.warn("No episodes found for '{}' in Sonarr", name)
            return false
        }

        episodes.filter { it.episodeFileId > 0 }.forEach { episode ->
            logger.info("Deleting episode file {} for '{}'", episode.episodeFileId, name)
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

        val episodeIds = episodes.map { it.id }
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
        return true
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
