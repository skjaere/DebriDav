package io.skjaere.debridav.config

data class ConfigTestResultDto(
    val prefix: String,
    val label: String,
    val success: Boolean,
    val message: String,
    val durationMs: Long
)

data class TestablePrefixDto(
    val prefix: String,
    val label: String
)
