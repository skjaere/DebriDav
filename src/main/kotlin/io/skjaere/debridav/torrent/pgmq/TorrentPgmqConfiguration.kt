package io.skjaere.debridav.torrent.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.pgmq.PgmqConfigurationProperties
import io.skjaere.debridav.pgmq.PgmqConsumer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class TorrentPgmqConfiguration {

    @Bean
    fun torrentHealthCheckConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        handler: TorrentHealthCheckHandler
    ): PgmqConsumer<TorrentHealthCheckMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "torrent_health_check",
        messageType = TorrentHealthCheckMessage::class.java,
        concurrency = props.torrentHealthCheckConcurrency,
        visibilityTimeout = props.torrentHealthCheckVisibilityTimeout,
        pollInterval = props.torrentHealthCheckPollInterval,
        maxReadCount = props.maxReadCount
    ) { msg, _ ->
        handler.handle(msg)
    }

    @Bean
    fun torrentHealthRepairConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        handler: TorrentHealthRepairHandler
    ): PgmqConsumer<TorrentHealthRepairMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "torrent_health_repair",
        messageType = TorrentHealthRepairMessage::class.java,
        concurrency = props.torrentHealthRepairConcurrency,
        visibilityTimeout = props.torrentHealthRepairVisibilityTimeout,
        pollInterval = props.torrentHealthRepairPollInterval,
        maxReadCount = props.maxReadCount
    ) { msg, msgId ->
        handler.handle(msg, msgId)
    }
}
