package io.skjaere.debridav.config.auth

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "debridav.auth")
class AuthConfigurationProperties {
    var enabled: Boolean = false
    var jwtSecret: String = ""
    @Suppress("MagicNumber")
    var tokenExpirationHours: Long = 24
    var protectQbittorrentApi: Boolean = false
    var protectSabnzbdApi: Boolean = false
    var protectActuator: Boolean = false
}
