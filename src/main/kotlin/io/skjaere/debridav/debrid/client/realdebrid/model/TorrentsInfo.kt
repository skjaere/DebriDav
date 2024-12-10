package io.skjaere.debridav.debrid.client.realdebrid.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TorrentsInfo(
    val id: String,
    val filename: String,
    @JsonNames("original_filename") val originalFilename: String,
    val hash: String,
    val bytes: Long,
    @JsonNames("original_bytes") val originalBytes: Long,
    val host: String,
    val split: Int,
    val progress: Int,
    val status: String,
    val added: String,
    val files: List<File>,
    val links: List<String>
)

@Serializable
data class File(
    val id: Int,
    val path: String,
    val bytes: Long,
    val selected: Int
)
