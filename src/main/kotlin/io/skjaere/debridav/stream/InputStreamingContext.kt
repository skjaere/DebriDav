package io.skjaere.debridav.stream

import io.skjaere.debridav.debrid.DebridProvider
import java.util.*

data class InputStreamingContext(
    val inputStream: ResettableCountingInputStream,
    val provider: DebridProvider,
    val file: String,
    val uniqueId: String
) {
    constructor(
        inputStream: ResettableCountingInputStream,
        provider: DebridProvider,
        file: String
    ) : this(inputStream, provider, file, UUID.randomUUID().toString())
}
