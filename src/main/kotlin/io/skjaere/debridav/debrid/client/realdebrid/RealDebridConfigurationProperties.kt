package io.skjaere.debridav.debrid.client.realdebrid

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "real-debrid")
class RealDebridConfigurationProperties(
    val apiKey: String,
    var baseUrl: String,
    val syncEnabled: Boolean,
)
