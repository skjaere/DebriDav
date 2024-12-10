package io.skjaere.debridav.debrid.client.realdebrid

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "real-debrid")
class RealDebridConfiguration(
    val apiKey: String,
    val baseUrl: String
)
