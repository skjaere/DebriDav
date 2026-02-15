package io.skjaere.debridav.usenet

import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import io.skjaere.nzbstreamer.config.SeekConfig
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "nntp")
data class NntpConfigurationProperties(
    val host: String = "",
    val port: Int = 563,
    val username: String = "",
    val password: String = "",
    val useTls: Boolean = true,
    val concurrency: Int = 4,
    val readAheadSegments: Int? = null,
    val forwardThresholdBytes: Long = 102400L
)

@Configuration
class NzbStreamerConfiguration {
    @Bean
    @ConditionalOnProperty("nntp.host", matchIfMissing = false)
    fun nzbStreamer(props: NntpConfigurationProperties): NzbStreamer {
        return NzbStreamer.fromConfig(
            NntpConfig(
                host = props.host,
                port = props.port,
                username = props.username,
                password = props.password,
                useTls = props.useTls,
                concurrency = props.concurrency,
                readAheadSegments = props.readAheadSegments ?: props.concurrency
            ),
            SeekConfig(
                forwardThresholdBytes = props.forwardThresholdBytes
            )
        )
    }
}
