package io.skjaere.debridav.configuration

import io.skjaere.debridav.fs.DebridProvider
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration


@ConfigurationProperties(prefix = "debridav")
data class DebridavConfiguration(
    val filePath: String,
    val downloadPath: String,
    val mountPath: String,
    val cacheLocalDebridFilesThresholdMb: Int,
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
) {
    init {
        require(debridClients.isNotEmpty()) {
            "No debrid providers defined"
        }
    }
}
