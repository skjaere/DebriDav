package io.skjaere.debridav.arrs

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "radarr")
class RadarrConfigurationProperties : ArrConfiguration {
    @ConfigProperty(name = "Integration Enabled", description = "Enable Radarr integration")
    override var integrationEnabled: Boolean = false
    @ConfigProperty(name = "Host", description = "Radarr host")
    override var host: String = ""
    @ConfigProperty(name = "Port", description = "Radarr port")
    @Suppress("MagicNumber")
    override var port: Int = 7878
    @ConfigProperty(name = "API Base Path", description = "Radarr API base path", advanced = true)
    override var apiBasePath: String = "/api/v3"
    @ConfigProperty(name = "API Key", description = "Radarr API key", sensitive = true)
    override var apiKey: String = ""
    @ConfigProperty(name = "Category", description = "Radarr category")
    override var category: String = ""
}
