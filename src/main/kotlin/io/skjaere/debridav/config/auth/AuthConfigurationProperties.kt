package io.skjaere.debridav.config.auth

import org.springframework.boot.context.properties.ConfigurationProperties

// JWT-bearer auth config. The API surface (/api and sensitive /actuator endpoints) is
// authenticated unconditionally, so there's no master enable/disable toggle here. WebDAV
// basic auth is governed separately by whether `debridav.webdav-username` and
// `debridav.webdav-password` are both set (see DebridavConfigurationProperties).
@ConfigurationProperties(prefix = "debridav.auth")
class AuthConfigurationProperties {
    var jwtSecret: String = ""
    @Suppress("MagicNumber")
    var tokenExpirationHours: Long = 24
}
