package io.skjaere.debridav.usenet.sabnzbd.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed interface SabnzbdHistoryResponse

@Serializable
data class SabnzbdFullHistoryResponse(
    val history: SabnzbdHistory
) : SabnzbdHistoryResponse

@Serializable
data class SabNzbdHistoryDeleteResponse(
    val status: Boolean,
    @SerialName("nzo_ids") val nzoIds: List<String>
) : SabnzbdHistoryResponse

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
    @SerialName("download_time") val downloadTime: Int,
    val storage: String,
    val status: String,
    @SerialName("nzo_id") val nzoId: String,
    val name: String,
)
