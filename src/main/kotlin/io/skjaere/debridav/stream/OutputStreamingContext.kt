package io.skjaere.debridav.stream

data class OutputStreamingContext(
    val outputStream: ResettableCountingOutputStream,
    val file: String
)
