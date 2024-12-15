package io.skjaere.debridav.debrid.client.torbox

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "torbox")
class TorBoxConfiguration(
    val apiKey: String,
    private val baseUrl: String,
    private val version: String,
) {
    val apiUrl: String = "$baseUrl/$version"
}
