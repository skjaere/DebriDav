package io.skjaere.debridav.debrid.client.torbox.model.usenet

import io.skjaere.debridav.debrid.client.torbox.model.CreateUsenetDownloadResponseSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable(with = CreateUsenetDownloadResponseSerializer::class)
sealed interface CreateUsenetDownloadResponse

@Serializable
data class FailedCreateUsenetDownloadResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: CreatedDownload?
) : CreateUsenetDownloadResponse

@Serializable
data class ErrorCreateUsenetDownloadResponse(
    val error: String?
) : CreateUsenetDownloadResponse

@Serializable
data class DownloadSlotsFullUsenetDownloadResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: Map<String, Int>
) : CreateUsenetDownloadResponse

@Serializable
data class SuccessfulCreateUsenetDownloadResponse(
    val success: Boolean,
    val error: String?,
    val detail: String?,
    val data: CreatedDownload?
) : CreateUsenetDownloadResponse

@Serializable
data class CreatedDownload(
    val hash: String,
    @SerialName("usenetdownload_id") val usenetDownloadId: String,
    @SerialName("auth_id") val authId: String
)

/*object CreateUsenetDownloadResponseSerializer :
    JsonContentPolymorphicSerializer<CreateUsenetDownloadResponse>(CreateUsenetDownloadResponse::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CreateUsenetDownloadResponse> {
        return when {
            //element.jsonObject["type"] == null || element.jsonObject["type"]?.jsonPrimitive?.content == "TORRENT" ->
            element.jsonObject["data"]?.jsonPrimitive?.isString
                DebridTorrentFileContents.serializer()

            else -> DebridUsenetFileContents.serializer()
        }
    }
}*/
