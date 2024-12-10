package io.skjaere.debridav.debrid.client.premiumize.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
data class SuccessfulDirectDownloadResponse(
    override val status: String,
    val location: String,
    val filename: String,
    val filesize: Long,
    val content: List<Content>
) : DirectDownloadResponse

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class Content(
    val path: String,
    val size: Long,
    val link: String,
    @JsonNames("stream_link") val streamLink: String?,
    @JsonNames("transcode_status") val transcodeStatus: String?
)
