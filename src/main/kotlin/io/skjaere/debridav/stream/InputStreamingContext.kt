package io.skjaere.debridav.stream

import io.skjaere.debridav.debrid.DebridProvider

data class InputStreamingContext(
    val inputStream: ResettableCountingInputStream,
    val provider: DebridProvider,
    val file: String
)
