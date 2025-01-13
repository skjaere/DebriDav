package io.skjaere.debridav.arrs.client

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.headers

import io.skjaere.debridav.arrs.ArrConfiguration
import io.skjaere.debridav.arrs.client.models.HistoryResponse

open class DefaultBaseArrClient(
    private val httpClient: HttpClient,
    private val arrConfiguration: ArrConfiguration
) : BaseArrClient {
    override suspend fun failed(historyId: Long) {
        httpClient.post("${arrConfiguration.getApiBaseUrl()}/history/failed/$historyId") {
            url {
                parameters.append("apiKey", arrConfiguration.apiKey)
            }
            headers {
                accept(ContentType.Application.Json)
                append("X-Api-Key", arrConfiguration.apiKey)
            }
        }
    }

    override suspend fun history(episodeId: Long): HistoryResponse {
        return httpClient.get("${arrConfiguration.getApiBaseUrl()}/history") {
            url {
                parameters.append("sortKey", "date")
                parameters.append("sortDir", "desc")
                parameters.append("episodeId", episodeId.toString())
                parameters.append("apiKey", arrConfiguration.apiKey)
            }
            headers {
                accept(ContentType.Application.Json)
                append("X-Api-Key", arrConfiguration.apiKey)
            }
        }.body<HistoryResponse>()
    }

    override suspend fun parse(itemName: String): HttpResponse {
        val response = httpClient.get("${arrConfiguration.getApiBaseUrl()}/parse") {
            url {
                parameters.append("title", itemName)
                parameters.append("apiKey", arrConfiguration.apiKey)
            }
            headers {
                accept(ContentType.Application.Json)
                append("X-Api-Key", arrConfiguration.apiKey)
            }
        }
        return response
    }
}
