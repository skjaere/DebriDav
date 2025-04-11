package io.skjaere.debridav.debrid.client.torbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "torbox")
class TorBoxConfiguration(
    val apiKey: String,
    val baseUrl: String,
    val version: String,
)
