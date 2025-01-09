package io.skjaere.debridav.debrid.client.easynews

import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "easynews")
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('premiumize')}")
data class EasynewsConfigurationProperties(
    val apiBaseUrl: String,
    val username: String,
    val password: String,
)

