package io.skjaere.debridav.usenet

import io.skjaere.debridav.config.ConfigProperty
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import io.skjaere.nzbstreamer.config.SegmentCacheConfig
import io.skjaere.nzbstreamer.config.StreamingConfig
import io.skjaere.nzbstreamer.stream.SegmentCache
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.nio.file.Path
import java.time.Duration

data class NntpPoolProperties(
    val host: String = "",
    val port: Int = 563,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = true,
    val maxConnections: Int = 8,
    val priority: Int = 0
)

@Suppress("MagicNumber")
data class NntpCacheProperties(
    @ConfigProperty(
        name = "Cache Enabled",
        description = "Enable on-disk caching of NNTP segments. Requires cacheDir to be writable."
    )
    var enabled: Boolean = false,
    @ConfigProperty(
        name = "Cache Directory",
        description = "Directory where decoded segment files and the index sidecar are written."
    )
    var dir: String = "/var/cache/debridav/nzb-segments",
    @ConfigProperty(
        name = "Cache Max Bytes",
        description = "Soft cap on total bytes stored in the segment cache before LRU eviction kicks in."
    )
    var maxBytes: Long = 10L * 1024 * 1024 * 1024,
)

@Suppress("MagicNumber")
@ConfigurationProperties(prefix = "nntp")
class NntpConfigurationProperties {
    @ConfigProperty(name = "Concurrency", description = "NNTP streaming concurrency")
    var concurrency: Int = 4
    @ConfigProperty(
        name = "Read Ahead Segments",
        description = "Segments to prefetch per stream. Leave empty to default to the concurrency value."
    )
    var readAheadSegments: Int? = null
    var pools: List<NntpPoolProperties> = emptyList()
    var cache: NntpCacheProperties = NntpCacheProperties()
}

@Configuration
class NzbStreamerConfiguration {
    private val logger = LoggerFactory.getLogger(NzbStreamerConfiguration::class.java)

    @Bean
    fun nzbStreamer(props: NntpConfigurationProperties): NzbStreamer {
        val nntpConfigs = buildNntpConfigs(props)
        // Health-check STAT commands run at NntpPriority.HEALTH_CHECK, the lowest tier — any
        // streaming demand preempts them on the same connection. So there's no contention
        // cost to letting the verification semaphore use every connection across every pool;
        // we just want STAT throughput to scale with the connection budget instead of being
        // capped at the streaming-tuned `concurrency` (4 by default).
        val totalConnections = nntpConfigs.sumOf { it.maxConnections }
        val streamingConfig = StreamingConfig(
            concurrency = props.concurrency,
            verificationConcurrency = totalConnections.coerceAtLeast(props.concurrency),
            readAheadSegments = props.readAheadSegments ?: props.concurrency
        )
        val segmentCache = buildSegmentCache(props.cache)
        logger.info(
            "Creating NzbStreamer with {} pool(s), concurrency={}, verificationConcurrency={}, " +
                    "cacheEnabled={}",
            nntpConfigs.size, streamingConfig.concurrency, streamingConfig.verificationConcurrency,
            segmentCache != null,
        )
        nntpConfigs.forEachIndexed { index, config ->
            logger.info(
                "  pool[{}]: host='{}', port={}, useTls={}, username='{}', maxConnections={}, priority={}",
                index, config.host, config.port, config.useTls, config.username, config.maxConnections,
                config.priority
            )
        }
        return NzbStreamer.fromConfig(
            nntpConfigs,
            streamingConfig,
            segmentCache = segmentCache,
        )
    }

    private fun buildSegmentCache(cacheProps: NntpCacheProperties): SegmentCache? {
        if (!cacheProps.enabled) return null
        logger.info(
            "NNTP segment cache enabled: dir='{}', maxBytes={}",
            cacheProps.dir, cacheProps.maxBytes,
        )
        return SegmentCache(
            SegmentCacheConfig(
                cacheDir = Path.of(cacheProps.dir),
                maxBytes = cacheProps.maxBytes,
            )
        )
    }

    private fun buildNntpConfigs(props: NntpConfigurationProperties): List<NntpConfig> {
        return props.pools.sortedBy { it.priority }.map { pool ->
            NntpConfig(
                host = pool.host,
                port = pool.port,
                username = pool.username,
                password = pool.password,
                useTls = pool.useTls,
                maxConnections = pool.maxConnections,
                priority = pool.priority
            )
        }
    }
}
