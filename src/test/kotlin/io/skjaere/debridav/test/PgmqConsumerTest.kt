package io.skjaere.debridav.test

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.vdsirotkin.pgmq.PgmqClient
import com.vdsirotkin.pgmq.domain.PgmqEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.skjaere.debridav.pgmq.PgmqConsumer
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.OffsetDateTime
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val QUEUE = "test_queue"
private const val MAX_READS = 3L

class PgmqConsumerTest {

    data class TestMessage(val id: Long = 0)

    private val pgmqClient = mockk<PgmqClient>(relaxed = true)
    private val objectMapper = ObjectMapper().registerModule(KotlinModule.Builder().build())
    private val deadLettered = CopyOnWriteArrayList<Pair<TestMessage, Throwable>>()
    private val handled = CopyOnWriteArrayList<TestMessage>()
    private var consumer: PgmqConsumer<TestMessage>? = null

    @AfterEach
    fun stop() {
        consumer?.stop()
    }

    @Test
    fun `a message that runs out of attempts is passed to the dead-letter hook and dead-lettered`() {
        //given
        deliverOnce(entry(id = 42, readCounter = MAX_READS))

        //when
        start { throw IllegalStateException("still failing") }

        //then
        await().atMost(Duration.ofSeconds(5)).until { deadLettered.isNotEmpty() }
        assertEquals(TestMessage(42), deadLettered.single().first)
        assertEquals("still failing", deadLettered.single().second.message)
        verify(timeout = 5_000) { pgmqClient.send("${QUEUE}_dlq", any(), any()) }
        verify(timeout = 5_000) { pgmqClient.archive(QUEUE, 42) }
    }

    @Test
    fun `a failed attempt with attempts left is neither dead-lettered nor passed to the hook`() {
        //given
        deliverOnce(entry(id = 7, readCounter = MAX_READS - 1))

        //when
        start { handled += it; throw IllegalStateException("failing once") }

        //then
        await().atMost(Duration.ofSeconds(5)).until { handled.isNotEmpty() }
        Thread.sleep(200) // give a wrong dead-letter call time to happen
        assertTrue(deadLettered.isEmpty(), "dead-letter hook calls: $deadLettered")
        verify(exactly = 0) { pgmqClient.send("${QUEUE}_dlq", any(), any()) }
        verify(exactly = 0) { pgmqClient.archive(QUEUE, 7) }
    }

    private fun deliverOnce(entry: PgmqEntry) {
        var delivered = false
        every { pgmqClient.readBatch(QUEUE, 1, any()) } answers {
            if (delivered) emptyList() else listOf(entry).also { delivered = true }
        }
    }

    private fun start(handler: (TestMessage) -> Unit) {
        consumer = PgmqConsumer(
            pgmqClient = pgmqClient,
            objectMapper = objectMapper,
            queueName = QUEUE,
            messageType = TestMessage::class.java,
            concurrency = 1,
            visibilityTimeout = Duration.ofSeconds(1),
            pollInterval = Duration.ofMillis(10),
            maxReadCount = MAX_READS,
            onDeadLetter = { message, cause -> deadLettered += message to cause },
        ) { message, _ -> handler(message) }.also { it.start() }
    }

    private fun entry(id: Long, readCounter: Long) = PgmqEntry(
        id, readCounter, OffsetDateTime.now(), OffsetDateTime.now(), """{"id": $id}"""
    )
}
