package io.skjaere.debridav.debrid.client.torbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "torbox")
class TorBoxConfigurationProperties(
    val apiKey: String,
    val baseUrl: String,
    val version: String,
    val requestTimeoutMillis: Long,
    val socketTimeoutMillis: Long,

    )
