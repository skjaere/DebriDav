package io.skjaere.debridav.arrs

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sonarr")
data class SonarrConfigurationProperties(
    override val integrationEnabled: Boolean,
    override val host: String,
    override val port: Int,
    override val apiBasePath: String,
    override val apiKey: String,
    override val category: String,
): ArrConfiguration
