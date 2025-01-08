package io.skjaere.debridav.debrid.client.torbox.model

import io.skjaere.debridav.debrid.client.torbox.model.usenet.CreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.DownloadSlotsFullUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.FailedCreateUsenetDownloadResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.SuccessfulCreateUsenetDownloadResponse
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object CreateUsenetDownloadResponseSerializer :
    JsonContentPolymorphicSerializer<CreateUsenetDownloadResponse>(CreateUsenetDownloadResponse::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<CreateUsenetDownloadResponse> {
        return if (element.jsonObject["success"]?.jsonPrimitive?.boolean == true) {
            SuccessfulCreateUsenetDownloadResponse.serializer()
        } else if (element.jsonObject["error"]?.jsonPrimitive?.content == "ACTIVE_LIMIT") {
            DownloadSlotsFullUsenetDownloadResponse.serializer()
        } else {
            FailedCreateUsenetDownloadResponse.serializer()
        }
    }
}