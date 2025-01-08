package io.skjaere.debridav.debrid.client.easynews

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "easynews")
data class EasynewsConfigurationProperties(
    val apiBaseUrl: String,
    val username: String,
    val password: String,
)

