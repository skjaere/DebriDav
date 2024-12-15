package io.skjaere.debridav.sabnzbd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SabnzbdFullListResponse(
    val queue: Queue,
)

@Serializable
data class Queue(
    val status: String,
    @SerialName("speedlimit") val speedLimit: String,
    @SerialName("speedlimit_abs") val speedLimitAbs: String,
    val paused: Boolean,
    @SerialName("noofslots_total") val noofSlotsTotal: Int,
    @SerialName("noofslots") val noofSlots: Int,
    val limit: Int,
    val start: Int,
    @SerialName("timeleft") val timeLeft: String,
    val speed: String,
    @SerialName("kbpersec") val kbPerSec: String,
    val size: String,
    @SerialName("sizeleft") val sizeLeft: String,
    val mb: String,
    @SerialName("mbleft") val mbLeft: String,
    val slots: List<ListResponseDownloadSlot>
)

@Serializable
data class ListResponseDownloadSlot(
    val status: String,
    val index: Int,
    val password: String,
    @SerialName("avg_age") val avgAge: String,
    val script: String,
    @SerialName("direct_unpack") val directUnpack: String,
    val mb: String,
    @SerialName("mbleft") val mbLeft: String,
    @SerialName("mbmissing") val mbMissing: String,
    val size: String,
    @SerialName("sizeleft") val sizeLeft: String,
    val filename: String,
    val labels: List<String>,
    val priority: String,
    val cat: String,
    @SerialName("timeleft") val timeLeft: String,
    val percentage: String,
    @SerialName("nzo_id") val nzoId: String,
    @SerialName("unpackopts") val unpackOpts: String,
)
