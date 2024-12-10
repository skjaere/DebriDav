package io.skjaere.debridav.debrid.client.premiumize.model

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object DirectDownloadResponseSerializer :
    JsonContentPolymorphicSerializer<DirectDownloadResponse>(DirectDownloadResponse::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<DirectDownloadResponse> {
        return when {
            element.jsonObject["status"]?.jsonPrimitive?.content == "success" ->
                SuccessfulDirectDownloadResponse.serializer()
            else -> UnsuccessfulDirectDownloadResponse.serializer()
        }
    }
}
