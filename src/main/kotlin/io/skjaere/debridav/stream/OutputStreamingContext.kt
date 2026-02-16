package io.skjaere.debridav.stream

data class OutputStreamingContext(
    val counter: ByteCounter,
    val file: String,
    val client: String
)
