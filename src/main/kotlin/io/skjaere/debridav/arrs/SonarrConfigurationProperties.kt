package io.skjaere.debridav.arrs

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "sonarr")
class SonarrConfigurationProperties : ArrConfiguration {
    @ConfigProperty(name = "Integration Enabled", description = "Enable Sonarr integration")
    override var integrationEnabled: Boolean = false
    @ConfigProperty(name = "Host", description = "Sonarr host")
    override var host: String = ""
    @ConfigProperty(name = "Port", description = "Sonarr port")
    @Suppress("MagicNumber")
    override var port: Int = 8989
    @ConfigProperty(name = "API Base Path", description = "Sonarr API base path", advanced = true)
    override var apiBasePath: String = "/api/v3"
    @ConfigProperty(name = "API Key", description = "Sonarr API key", sensitive = true)
    override var apiKey: String = ""
    @ConfigProperty(name = "Category", description = "Sonarr category")
    override var category: String = ""
}
