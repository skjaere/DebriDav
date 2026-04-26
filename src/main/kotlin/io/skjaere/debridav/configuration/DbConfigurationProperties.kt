package io.skjaere.debridav.configuration

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "debridav.db")
class DbConfigurationProperties {
    var host: String = "localhost"
    @Suppress("MagicNumber")
    var port: Int = 5432
    var databaseName: String = "debridav"
}
