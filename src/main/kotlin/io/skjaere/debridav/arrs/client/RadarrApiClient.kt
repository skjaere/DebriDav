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

    override suspend fun getItemIdFromName(name: String): Long {
        return parse(name).body<RadarrParseResponse>().movie.id
    }

    override fun getCategory(): String = radarrConfigurationProperties.category

    override suspend fun deleteFileAndSearch(name: String): Boolean {
        val parseResponse = parse(name).body<RadarrParseResponse>()
        val movie = parseResponse.movie

        if (movie.id == 0L) {
            logger.warn("No movie found for '{}' in Radarr", name)
            return false
        }

        if (movie.movieFileId > 0) {
            logger.info("Deleting movie file {} for '{}'", movie.movieFileId, name)
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
        return true
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
