package io.skjaere.debridav.usenet.sabnzbd

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class AddNzbResponse(
    val status: Boolean,
    @SerialName("nzo_ids") val nzoIds: List<String>
)
