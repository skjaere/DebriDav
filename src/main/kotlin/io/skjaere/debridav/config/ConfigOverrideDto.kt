package io.skjaere.debridav.config

data class ConfigOverrideDto(
    val key: String,
    val name: String?,
    val effectiveValue: String?,
    val defaultValue: String?,
    val hasOverride: Boolean,
    val sensitive: Boolean,
    val group: String,
    val description: String,
    val type: String,
    val advanced: Boolean
)
