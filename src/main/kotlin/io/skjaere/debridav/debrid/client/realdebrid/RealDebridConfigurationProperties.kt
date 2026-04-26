package io.skjaere.debridav.debrid.client.realdebrid

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "real-debrid")
class RealDebridConfigurationProperties {
    @ConfigProperty(name = "API Key", description = "Real-Debrid API key", sensitive = true)
    lateinit var apiKey: String
    @ConfigProperty(name = "Base URL", description = "Real-Debrid base URL", advanced = true)
    lateinit var baseUrl: String
    @ConfigProperty(name = "Sync Enabled", description = "Enable Real-Debrid sync")
    var syncEnabled: Boolean = false
    @ConfigProperty(name = "Sync Poll Rate", description = "Real-Debrid sync poll rate")
    var syncPollRate: String = "PT24H"
}
