package io.skjaere.debridav.sonarr

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sonarr")
data class SonarrConfiguration(
    val apiBaseUrl: String,
    val apiKey: String,
    val category: String,
)