package io.skjaere.debridav.ui

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "debridav.ui")
class UiConfigurationProperties {
    var grafana: GrafanaConfig = GrafanaConfig()
}

class GrafanaConfig {
    var baseUrl: String = ""
    var apiKey: String = ""
}
