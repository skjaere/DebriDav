package io.skjaere.debridav.debrid.client.easynews

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "easynews")
class EasynewsConfigurationProperties {
    @ConfigProperty(name = "API Base URL", description = "Easynews API base URL", advanced = true)
    lateinit var apiBaseUrl: String
    @ConfigProperty(name = "Username", description = "Easynews username")
    lateinit var username: String
    @ConfigProperty(name = "Password", description = "Easynews password", sensitive = true)
    lateinit var password: String
    @ConfigProperty(name = "Enabled for Torrents", description = "Enable Easynews for torrents")
    var enabledForTorrents: Boolean = false
    @ConfigProperty(name = "Rate Limit Window", description = "Easynews rate limit window")
    var rateLimitWindowDuration: Duration = Duration.ZERO
    @ConfigProperty(name = "Allowed Requests in Window", description = "Easynews allowed requests per window")
    var allowedRequestsInWindow: Int = 0
    @ConfigProperty(name = "Connect Timeout", description = "Easynews connect timeout")
    var connectTimeout: Int = 0
    @ConfigProperty(name = "Socket Timeout", description = "Easynews socket timeout")
    var socketTimeout: Int = 0
}
