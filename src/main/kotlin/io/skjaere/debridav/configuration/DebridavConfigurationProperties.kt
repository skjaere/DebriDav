package io.skjaere.debridav.configuration

import io.skjaere.debridav.debrid.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration


private const val KILOBYTE = 1024

@ConfigurationProperties(prefix = "debridav")
data class DebridavConfigurationProperties(
    val rootPath: String,
    val downloadPath: String,
    val mountPath: String,
    var debridClients: List<DebridProvider>,
    val waitAfterMissing: Duration,
    val waitAfterProviderError: Duration,
    val waitAfterNetworkError: Duration,
    val waitAfterClientError: Duration,
    val retriesOnProviderError: Long,
    val delayBetweenRetries: Duration,
    val connectTimeoutMilliseconds: Long,
    val readTimeoutMilliseconds: Long,
    val shouldDeleteNonWorkingFiles: Boolean,
    val torrentLifetime: Duration,
    val enableFileImportOnStartup: Boolean,
    val chunkCachingSizeThreshold: Int,
    val chunkCachingGracePeriod: Duration,
    val defaultCategories: List<String>,
    val localEntityMaxSizeMb: Int,
    val cacheMaxSizeGb: Double,
) {
    init {
        require(debridClients.isNotEmpty()) {
            "No debrid providers defined"
        }
        require((cacheMaxSizeGb * KILOBYTE) > localEntityMaxSizeMb) {
            "debridav.cache-max-size-gb must be greater than debridav.chunk-caching-size-threshold in Gb"
        }
    }
}
