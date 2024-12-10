package io.skjaere.debridav.debrid.client.premiumize

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "premiumize")
class PremiumizeConfiguration(
    val baseUrl: String,
    val apiKey: String
)
