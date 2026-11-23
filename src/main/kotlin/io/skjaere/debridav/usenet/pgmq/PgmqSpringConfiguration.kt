package io.skjaere.debridav.usenet.pgmq

import com.fasterxml.jackson.databind.ObjectMapper
import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.pgmq.PgmqConfigurationProperties
import io.skjaere.debridav.pgmq.PgmqConsumer
import io.skjaere.debridav.usenet.NzbImportService
import io.skjaere.debridav.usenet.NzbImportTaskData
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class PgmqSpringConfiguration {

    @Bean
    fun nzbImportConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        nzbImportService: NzbImportService
    ): PgmqConsumer<NzbImportMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "nzb_import",
        messageType = NzbImportMessage::class.java,
        concurrency = props.importConcurrency,
        visibilityTimeout = props.importVisibilityTimeout,
        pollInterval = props.importPollInterval,
        maxReadCount = props.maxReadCount,
        // Retryable failures are thrown back for another attempt; once the attempts run out,
        // fail the import so the arr sees it and searches again.
        onDeadLetter = { msg, cause -> nzbImportService.markFailedAfterRetries(msg.toTaskData(), cause) },
    ) { msg, _ ->
        nzbImportService.executeImport(msg.toTaskData())
    }

    private fun NzbImportMessage.toTaskData() = NzbImportTaskData(nzbBytesBase64, usenetDownloadId, nzbImportRecordId)

    @Bean
    fun nzbHealthCheckConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        healthCheckHandler: NzbHealthCheckHandler
    ): PgmqConsumer<NzbHealthCheckMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "nzb_health_check",
        messageType = NzbHealthCheckMessage::class.java,
        concurrency = props.healthCheckConcurrency,
        visibilityTimeout = props.healthCheckVisibilityTimeout,
        pollInterval = props.healthCheckPollInterval,
        maxReadCount = props.maxReadCount
    ) { msg, _ ->
        healthCheckHandler.handle(msg)
    }

    @Bean
    fun nzbHealthRepairConsumer(
        pgmqClient: PgmqClient,
        pgmqObjectMapper: ObjectMapper,
        props: PgmqConfigurationProperties,
        healthRepairHandler: NzbHealthRepairHandler
    ): PgmqConsumer<NzbHealthRepairMessage> = PgmqConsumer(
        pgmqClient = pgmqClient,
        objectMapper = pgmqObjectMapper,
        queueName = "nzb_health_repair",
        messageType = NzbHealthRepairMessage::class.java,
        concurrency = props.healthRepairConcurrency,
        visibilityTimeout = props.healthRepairVisibilityTimeout,
        pollInterval = props.healthRepairPollInterval,
        maxReadCount = props.maxReadCount
    ) { msg, msgId ->
        healthRepairHandler.handle(msg, msgId)
    }
}
