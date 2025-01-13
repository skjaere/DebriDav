package io.skjaere.debridav.arrs.client.models

import kotlinx.serialization.Serializable

@Serializable
data class HistoryResponse(
    val records: List<HistoryRecord>
) {
    @Serializable
    data class HistoryRecord(
        val eventType: String,
        val id: Long
    )
}
