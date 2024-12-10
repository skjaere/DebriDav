package io.skjaere.debridav.debrid.client.realdebrid.model

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

typealias HashResponse = Map<String, List<HosterResponse>>
typealias HosterResponse = Map<Int, FileNameAndSize>
typealias RealDebridIsCachedResponse = Map<String, HashResponse>

@Serializable
data class FileNameAndSize(
    val filename: String,
    val filesize: Long
)

object RealDebridIsCachedSuccessfulResponseSerializer : KSerializer<RealDebridIsCachedResult> {
    private val hosterResponseSerializer: KSerializer<HosterResponse> =
        MapSerializer(Int.serializer(), FileNameAndSize.serializer())
    private val hashResponseSerializer: KSerializer<HashResponse> = MapSerializer(
        String.serializer(), ListSerializer(
            hosterResponseSerializer
        )
    )
    private val mapSerializer = MapSerializer(String.serializer(), hashResponseSerializer)

    override fun deserialize(decoder: Decoder): RealDebridIsCachedResult {
        return RealDebridIsCachedResult(mapSerializer.deserialize(decoder))
    }

    override val descriptor: SerialDescriptor = mapSerializer.descriptor

    override fun serialize(encoder: Encoder, value: RealDebridIsCachedResult) {
        return mapSerializer.serialize(encoder, value.result)
    }
}

@Serializable(with = RealDebridIsCachedSuccessfulResponseSerializer::class)
data class RealDebridIsCachedResult(
    val result: RealDebridIsCachedResponse
)
