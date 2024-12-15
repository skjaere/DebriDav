package io.skjaere.debridav.fs

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

object DebridFileContentsSerializer :
    JsonContentPolymorphicSerializer<DebridFileContents>(DebridFileContents::class) {
    override fun selectDeserializer(element: JsonElement): DeserializationStrategy<DebridFileContents> {
        return when {
            //element.jsonObject["type"] == null || element.jsonObject["type"]?.jsonPrimitive?.content == "TORRENT" ->
            element.jsonObject["usenetDownloadId"] == null ->
                DebridTorrentFileContents.serializer()

            else -> DebridUsenetFileContents.serializer()
        }
    }
}
