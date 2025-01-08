package io.skjaere.debridav.sonarr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.ContentType
import io.ktor.http.headers
import io.skjaere.debridav.sonarr.models.HistoryResponse
import io.skjaere.debridav.sonarr.models.ParseResponse
import org.springframework.stereotype.Component

@Component
class SonarrClient(
    private val httpClient: HttpClient, private val sonarrConfiguration: SonarrConfiguration
) {

    suspend fun parse(torrentName: String): ParseResponse {
        return httpClient.get("${sonarrConfiguration.apiBaseUrl}/parse") {
            url {
                parameters.append("title", torrentName)
                parameters.append("apiKey", sonarrConfiguration.apiKey)
            }
            headers {
                accept(ContentType.Application.Json)
                append("X-Api-Key", sonarrConfiguration.apiKey)
            }
        }.body<ParseResponse>()
    }

    suspend fun history(episodeId: Long): HistoryResponse {
        return httpClient.get("${sonarrConfiguration.apiBaseUrl}/history") {
            url {
                parameters.append("sortKey", "date")
                parameters.append("sortDir", "desc")
                parameters.append("episodeId", episodeId.toString())
                parameters.append("apiKey", sonarrConfiguration.apiKey)
            }
            headers {
                accept(ContentType.Application.Json)
                append("X-Api-Key", sonarrConfiguration.apiKey)
            }
        }.body<HistoryResponse>()
    }

    suspend fun failed(historyId: Long) {

        val response = httpClient.post("${sonarrConfiguration.apiBaseUrl}/history/failed/$historyId") {
            url {
                parameters.append("apiKey", sonarrConfiguration.apiKey)
            }
            headers {
                accept(ContentType.Application.Json)
                append("X-Api-Key", sonarrConfiguration.apiKey)
            }
        }
        response
    }
}