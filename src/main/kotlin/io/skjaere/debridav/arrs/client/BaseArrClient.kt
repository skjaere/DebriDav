package io.skjaere.debridav.arrs.client

import io.ktor.client.statement.HttpResponse
import io.skjaere.debridav.arrs.client.models.HistoryResponse

sealed interface BaseArrClient {
    suspend fun failed(historyId: Long)
    suspend fun history(episodeId: Long): HistoryResponse
    suspend fun parse(itemName: String): HttpResponse
}
