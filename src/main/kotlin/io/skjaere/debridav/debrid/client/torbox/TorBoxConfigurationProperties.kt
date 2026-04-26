package io.skjaere.debridav.debrid.client.torbox

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "torbox")
class TorBoxConfigurationProperties {
    @ConfigProperty(name = "API Key", description = "TorBox API key", sensitive = true)
    lateinit var apiKey: String
    @ConfigProperty(name = "Base URL", description = "TorBox base URL", advanced = true)
    lateinit var baseUrl: String
    @ConfigProperty(name = "API Version", description = "TorBox API version")
    lateinit var version: String
    @ConfigProperty(name = "Request Timeout", description = "TorBox request timeout in ms")
    var requestTimeoutMillis: Long = 0
    @ConfigProperty(name = "Socket Timeout", description = "TorBox socket timeout in ms")
    var socketTimeoutMillis: Long = 0
}
