package io.skjaere.debridav.debrid.client.premiumize

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "premiumize")
class PremiumizeConfigurationProperties {
    @ConfigProperty(name = "Base URL", description = "Premiumize base URL", advanced = true)
    lateinit var baseUrl: String
    @ConfigProperty(name = "API Key", description = "Premiumize API key", sensitive = true)
    lateinit var apiKey: String
}
