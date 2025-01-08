package io.skjaere.debridav.debrid.client.easynews

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SearchResults(
    val dlPort: Int,
    val dlFarm: String,
    @SerialName("downURL") val downUrl: String,
    val sid: String,
    val data: List<Item>
) {

    @Serializable
    data class Item(
        @SerialName("0") val hash: String,
        @SerialName("2") val ext: String,
        @SerialName("10") val releaseName: String,
        val id: String,
        val sig: String,
        val rawSize: Long,
        val size: Long,
        val runtime: Long,
    )
}