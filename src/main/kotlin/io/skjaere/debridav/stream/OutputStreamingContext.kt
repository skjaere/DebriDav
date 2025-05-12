package io.skjaere.debridav.stream

import io.skjaere.debridav.debrid.DebridProvider

data class OutputStreamingContext(
    val outputStream: ResettableCountingOutputStream,
    val provider: DebridProvider,
    val file: String
)
