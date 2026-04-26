package io.skjaere.debridav.configuration

import io.skjaere.debridav.config.ConfigProperty
import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties(prefix = "debridav")
class DebridavConfigurationProperties {
    @ConfigProperty(name = "Download Path", description = "Download path")
    lateinit var downloadPath: String

    @ConfigProperty(name = "Mount Path", description = "Mount path")
    lateinit var mountPath: String

    @ConfigProperty(name = "Debrid Clients", description = "Enabled debrid providers (comma-separated)")
    var debridClients: List<DebridProvider> = emptyList()

    @ConfigProperty(name = "Wait After Missing", description = "Wait duration after missing file", advanced = true)
    var waitAfterMissing: Duration = Duration.ZERO

    @ConfigProperty(
        name = "Wait After Provider Error",
        description = "Wait duration after provider error",
        advanced = true
    )
    var waitAfterProviderError: Duration = Duration.ZERO

    @ConfigProperty(
        name = "Wait After Network Error",
        description = "Wait duration after network error",
        advanced = true
    )
    var waitAfterNetworkError: Duration = Duration.ZERO

    @ConfigProperty(name = "Wait After Client Error", description = "Wait duration after client error", advanced = true)
    var waitAfterClientError: Duration = Duration.ZERO

    @ConfigProperty(
        name = "Retries on Provider Error",
        description = "Number of retries on provider error",
        advanced = true
    )
    var retriesOnProviderError: Long = 0

    @ConfigProperty(name = "Delay Between Retries", description = "Delay between retries", advanced = true)
    var delayBetweenRetries: Duration = Duration.ZERO

    @ConfigProperty(name = "Connect Timeout", description = "HTTP connect timeout in ms", advanced = true)
    var connectTimeoutMilliseconds: Long = 0

    @ConfigProperty(name = "Read Timeout", description = "HTTP read timeout in ms", advanced = true)
    var readTimeoutMilliseconds: Long = 0

    @ConfigProperty(name = "Delete Non-Working Files", description = "Delete non-working files", advanced = true)
    var shouldDeleteNonWorkingFiles: Boolean = false

    @ConfigProperty(name = "Torrent Lifetime", description = "Torrent lifetime duration", advanced = true)
    var torrentLifetime: Duration = Duration.ZERO

    @ConfigProperty(name = "Default Categories", description = "Default categories (comma-separated)", advanced = true)
    var defaultCategories: List<String> = emptyList()

    @ConfigProperty(name = "Max Local Entity Size (MB)", description = "Max local entity size in MB", advanced = true)
    var localEntityMaxSizeMb: Int = 0

    @ConfigProperty(name = "WebDAV Username", description = "WebDAV username", group = "webdav")
    var webdavUsername: String? = null

    @ConfigProperty(name = "WebDAV Password", description = "WebDAV password", sensitive = true, group = "webdav")
    var webdavPassword: String? = null

    @ConfigProperty(
        name = "Rclone Cache Invalidation",
        description = "Notify rclone to refresh its directory cache on file create / move / delete"
    )
    var rcloneCacheInvalidationEnabled: Boolean = false

    fun isWebdavAuthEnabled(): Boolean = !webdavUsername.isNullOrBlank() && !webdavPassword.isNullOrBlank()
}
