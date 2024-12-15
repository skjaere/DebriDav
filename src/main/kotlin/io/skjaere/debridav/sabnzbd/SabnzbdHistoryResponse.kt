package io.skjaere.debridav.sabnzbd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SabnzbdHistoryResponse(
    val history: SabnzbdHistory
)

@Serializable
data class SabnzbdHistory(
    val slots: List<HistorySlot>
)

@Serializable
data class HistorySlot(

    @SerialName("fail_message") val failMessage: String,
    val bytes: Long,
    val category: String,
    @SerialName("nzb_name") val nzbName: String,
    @SerialName("download_time") val downloadTime: String,
    val storage: String,
    val status: String,
    @SerialName("nzo_id") val nzoId: String,
    val name: String,
)
