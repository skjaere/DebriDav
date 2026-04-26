package io.skjaere.debridav.usenet

import io.skjaere.debridav.config.ConfigProperty
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import io.skjaere.nzbstreamer.config.StreamingConfig
import org.slf4j.LoggerFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
}

@Configuration
class NzbStreamerConfiguration {
    private val logger = LoggerFactory.getLogger(NzbStreamerConfiguration::class.java)

    @Bean
    fun nzbStreamer(props: NntpConfigurationProperties): NzbStreamer {
        val nntpConfigs = buildNntpConfigs(props)
        val streamingConfig = StreamingConfig(
            concurrency = props.concurrency,
            readAheadSegments = props.readAheadSegments ?: props.concurrency
        )
        logger.info(
            "Creating NzbStreamer with {} pool(s), concurrency={}",
            nntpConfigs.size, streamingConfig.concurrency
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
            streamingConfig
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
