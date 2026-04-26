package io.skjaere.debridav.pgmq

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.ObjectMapper
import com.vdsirotkin.pgmq.PgmqClient
import com.vdsirotkin.pgmq.domain.PgmqEntry
import kotlin.time.Duration
import kotlin.time.toKotlinDuration
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.time.Instant
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@Suppress("MagicNumber", "LongParameterList")
class PgmqConsumer<T : Any>(
    private val pgmqClient: PgmqClient,
    private val objectMapper: ObjectMapper,
    private val queueName: String,
    private val messageType: Class<T>,
    private val concurrency: Int,
    private val visibilityTimeout: java.time.Duration,
    private val pollInterval: java.time.Duration,
    private val maxReadCount: Long = DEFAULT_MAX_READ_COUNT,
    private val handler: (T, Long) -> Unit
) : SmartLifecycle {
    private val deadLetterQueue = "${queueName}_dlq"

    private val logger = LoggerFactory.getLogger(PgmqConsumer::class.java)

    @Volatile
    private var running = false
    private var executor: ExecutorService? = null

    override fun start() {
        if (running) return
        running = true
        logger.info("Starting {} consumer(s) for queue '{}'", concurrency, queueName)
        val exec = Executors.newFixedThreadPool(concurrency) { r ->
            Thread(r, "pgmq-$queueName").apply { isDaemon = true }
        }
        executor = exec
        repeat(concurrency) { index ->
            exec.submit { pollLoop(index) }
        }
    }

    @Suppress("LoopWithTooManyJumpStatements")
    private fun pollLoop(workerIndex: Int) {
        val vt: Duration = visibilityTimeout.toKotlinDuration()
        val sleepMs = pollInterval.toMillis()

        while (running) {
            try {
                val entries = pgmqClient.readBatch(queueName, 1, vt)
                if (entries.isEmpty()) {
                    Thread.sleep(sleepMs)
                    continue
                }
                val entry = entries.first()
                try {
                    val message = objectMapper.readValue(entry.message, messageType)
                    handler(message, entry.messageId)
                    pgmqClient.archive(queueName, entry.messageId)
                } catch (e: JsonProcessingException) {
                    logger.error(
                        "Malformed message {} on queue '{}'; dead-lettering (retry can't help)",
                        entry.messageId, queueName, e
                    )
                    deadLetter(entry, e)
                } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                    val attempt = entry.readCounter
                    if (attempt >= maxReadCount) {
                        logger.error(
                            "Message {} on queue '{}' exhausted {} attempts; dead-lettering",
                            entry.messageId, queueName, attempt, e
                        )
                        deadLetter(entry, e)
                    } else {
                        logger.error(
                            "Message {} on queue '{}' failed attempt {}/{}; will retry after visibility timeout",
                            entry.messageId, queueName, attempt, maxReadCount, e
                        )
                    }
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
                logger.error("Error reading from queue '{}': {}", queueName, e.message, e)
                try {
                    Thread.sleep(sleepMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
        }
        logger.info("Worker {} for queue '{}' stopped", workerIndex, queueName)
    }

    override fun stop() {
        running = false
        executor?.let { exec ->
            exec.shutdownNow()
            if (!exec.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("Executor for queue '{}' did not terminate within 30s", queueName)
            }
        }
        executor = null
        logger.info("Stopped consumer(s) for queue '{}'", queueName)
    }

    override fun isRunning(): Boolean = running

    override fun getPhase(): Int = Int.MAX_VALUE - 1

    private fun deadLetter(entry: PgmqEntry, cause: Throwable) {
        val envelope = mapOf(
            "originalQueue" to queueName,
            "originalMessageId" to entry.messageId,
            "originalPayload" to entry.message,
            "enqueuedAt" to entry.enqueuedAt.toString(),
            "readCount" to entry.readCounter,
            "failureClass" to cause.javaClass.name,
            "failureMessage" to cause.message,
            "failedAt" to Instant.now().toString()
        )
        try {
            pgmqClient.send(deadLetterQueue, envelope)
            pgmqClient.archive(queueName, entry.messageId)
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            logger.error(
                "Failed to dead-letter message {} to queue '{}'; leaving visible for manual inspection",
                entry.messageId, deadLetterQueue, e
            )
        }
    }

    companion object {
        const val DEFAULT_MAX_READ_COUNT: Long = 5
    }
}
