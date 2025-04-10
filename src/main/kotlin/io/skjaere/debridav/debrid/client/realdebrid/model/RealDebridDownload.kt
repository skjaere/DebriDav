package io.skjaere.debridav.debrid.client.realdebrid.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
@OptIn(ExperimentalSerializationApi::class)
data class RealDebridDownload(
    val id: String,
    val filename: String,
    val mimeType: String,
    @JsonNames("filesize") val fileSize: Long,
    val link: String,
    val host: String,
    val chunks: Int,
    val download: String,
    val streamable: Int
)
