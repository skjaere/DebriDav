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
    @ConfigProperty(
        name = "Verification Concurrency",
        description = "Parallel STAT calls fanned out per NZB during health checks. Acts as an upper " +
                "bound — actual parallelism is also gated by the configured NNTP pool's connection " +
                "budget. Leave empty to default to the sum of pool maxConnections (or `concurrency` " +
                "if no pools are configured at startup)."
    )
    var verificationConcurrency: Int? = null
    @ConfigProperty(
        name = "Segment Fetch Timeout",
        description = "Per-segment body fetch deadline in ms. A silently-dropped TCP connection " +
                "would otherwise only fail on the OS keepalive (potentially hours), trapping the " +
                "stream's coroutineScope. On timeout the call falls through to the next pool."
    )
    var segmentFetchTimeoutMs: Long? = null
    @ConfigProperty(
        name = "Circuit Breaker Failure Threshold",
        description = "Number of failures (timeouts, IO errors, NntpException family — anything " +
                "other than a clean 430) within the rolling window before the per-pool circuit " +
                "breaker opens and that pool is skipped for the cooldown period. Sets the " +
                "minimum-calls-in-window too, so smaller values open the breaker faster but also " +
                "react to fewer samples. Inert when only one pool is configured."
    )
    var circuitBreakerFailureThreshold: Int? = null
    @ConfigProperty(
        name = "Circuit Breaker Cooldown (ms)",
        description = "How long an open breaker stays open before transitioning to half-open and " +
                "probing the pool with a single trial call. Lower values resume traffic to a " +
                "recovering pool sooner; higher values give a flaky pool more time to settle."
    )
    var circuitBreakerCooldownMs: Long? = null
    var pools: List<NntpPoolProperties> = emptyList()
    var cache: NntpCacheProperties = NntpCacheProperties()
}

@Configuration
class NzbStreamerConfiguration {
    private val logger = LoggerFactory.getLogger(NzbStreamerConfiguration::class.java)

    @Bean
    fun nzbStreamer(props: NntpConfigurationProperties): NzbStreamer {
        val nntpConfigs = buildNntpConfigs(props)
        // verificationConcurrency caps the in-flight STAT fanout per NZB. The pool's own
        // connection budget gates the actual parallelism, so this can safely sit above the
        // startup-time pool size — DB-driven pool overrides bump maxConnections at runtime,
        // and we want the semaphore to already be sized for that target.
        val totalConnections = nntpConfigs.sumOf { it.maxConnections }
        val streamingConfig = StreamingConfig(
            concurrency = props.concurrency,
            verificationConcurrency = props.verificationConcurrency
                ?: totalConnections.coerceAtLeast(props.concurrency),
            readAheadSegments = props.readAheadSegments ?: props.concurrency,
        ).let { base ->
            // Only override when explicitly set — preserves the StreamingConfig defaults
            // when the env / DB override is absent.
            var b = base
            if (props.segmentFetchTimeoutMs != null) b = b.copy(segmentFetchTimeoutMs = props.segmentFetchTimeoutMs!!)
            if (props.circuitBreakerFailureThreshold != null) {
                b = b.copy(circuitBreakerFailureThreshold = props.circuitBreakerFailureThreshold!!)
            }
            if (props.circuitBreakerCooldownMs != null) {
                b = b.copy(circuitBreakerCooldownMs = props.circuitBreakerCooldownMs!!)
            }
            b
        }
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
