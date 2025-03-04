package io.skjaere.debridav.debrid.client.easynews

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "easynews")
data class EasynewsConfigurationProperties(
    val apiBaseUrl: String,
    val username: String,
    val password: String,
    val enabledForTorrents: Boolean,
    val rateLimitWindowDuration: Duration,
    val allowedRequestsInWindow: Int
)

