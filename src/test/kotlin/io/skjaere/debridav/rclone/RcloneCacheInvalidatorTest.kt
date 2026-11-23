package io.skjaere.debridav.rclone

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RcloneCacheInvalidatorTest {

    private val receivedDirs = ConcurrentLinkedQueue<String>()
    private val receivedBodies = ConcurrentLinkedQueue<String>()
    private lateinit var invalidator: RcloneCacheInvalidator
    private lateinit var debridavConfig: DebridavConfigurationProperties
    private lateinit var rcloneConfig: RcloneConfigurationProperties

    @BeforeEach
    fun setUp() {
        receivedDirs.clear()
        receivedBodies.clear()
        val mockEngine = MockEngine { request ->
            val body = (request.body as? TextContent)?.text ?: ""
            receivedBodies.add(body)
            // extract "dir":"..." — naive parse is fine for a test
            Regex("\"dir\"\\s*:\\s*\"([^\"]+)\"").find(body)?.groupValues?.get(1)
                ?.let { receivedDirs.add(it) }
            respond(
                content = ByteReadChannel("{}"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", "application/json")
            )
        }
        val httpClient = HttpClient(mockEngine)

        debridavConfig = DebridavConfigurationProperties().apply {
            rcloneCacheInvalidationEnabled = true
        }
        rcloneConfig = RcloneConfigurationProperties().apply {
            rcUrl = "http://rclone:5572"
        }
        invalidator = RcloneCacheInvalidator(debridavConfig, rcloneConfig, httpClient)
    }

    @AfterEach
    fun tearDown() {
        invalidator.shutdown()
    }

    @Test
    fun `coalesces multiple events into a single flush per unique path`() {
        invalidator.onChange(FileSystemChangedEvent(setOf("/a", "/b")))
        invalidator.onChange(FileSystemChangedEvent(setOf("/b", "/c")))
        invalidator.onChange(FileSystemChangedEvent(setOf("/a")))

        // Before the window closes, nothing has been sent.
        assertEquals(0, receivedDirs.size, "flush should not happen before window expires")

        // Trigger flush deterministically instead of sleeping through the window.
        invalidator.flush()
        waitForAsyncRefresh()

        assertEquals(setOf("/a", "/b", "/c"), receivedDirs.toSet())
        assertEquals(3, receivedDirs.size, "each unique path refreshed exactly once")
    }

    @Test
    fun `event after a flush starts a fresh window`() {
        invalidator.onChange(FileSystemChangedEvent(setOf("/first")))
        invalidator.flush()
        waitForAsyncRefresh()

        invalidator.onChange(FileSystemChangedEvent(setOf("/second")))
        invalidator.flush()
        waitForAsyncRefresh()

        assertEquals(listOf("/first", "/second"), receivedDirs.toList())
    }

    @Test
    fun `disabled toggle drops events with no HTTP activity`() {
        debridavConfig.rcloneCacheInvalidationEnabled = false
        invalidator.onChange(FileSystemChangedEvent(setOf("/whatever")))
        invalidator.flush()
        waitForAsyncRefresh()

        assertTrue(receivedDirs.isEmpty())
    }

    @Test
    fun `blank rcUrl drops events with no HTTP activity`() {
        rcloneConfig.rcUrl = ""
        invalidator.onChange(FileSystemChangedEvent(setOf("/whatever")))
        invalidator.flush()
        waitForAsyncRefresh()

        assertTrue(receivedDirs.isEmpty())
    }

    @Test
    fun `emitted path fans out to include all ancestors in refresh order`() {
        // A brand-new nested directory (e.g. /downloads/NewRelease) can't be
        // refreshed directly — rclone doesn't know it exists until its parent
        // is refreshed first. Root is included too so a brand-new top-level
        // directory is equally discoverable; the extra root refresh is cheap
        // (a tiny listing re-read).
        invalidator.onChange(FileSystemChangedEvent(setOf("/downloads/NewRelease")))
        invalidator.flush()
        waitForRefreshes(expected = 3)

        assertEquals(3, receivedBodies.size)
        assertEquals("{}", receivedBodies.elementAt(0), "root refresh should go first")
        assertEquals(listOf("/downloads", "/downloads/NewRelease"), receivedDirs.toList())
    }

    @Test
    fun `root path sends an empty body so rclone refreshes the whole VFS`() {
        // rclone's /vfs/refresh rejects dir="/" with "file does not exist";
        // omitting the dir param refreshes the whole VFS instead.
        invalidator.onChange(FileSystemChangedEvent(setOf("/")))
        invalidator.flush()
        waitForAsyncRefresh()

        assertEquals(1, receivedBodies.size)
        val body = receivedBodies.first()
        assertEquals("{}", body, "root should produce empty-object body, not {\"dir\":\"/\"}")
        assertTrue(receivedDirs.isEmpty(), "root path must not be sent as dir")
    }

    /** Wait until the expected number of POSTs have landed. */
    private fun waitForRefreshes(expected: Int, timeoutMs: Long = 2000) = runBlocking {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (receivedBodies.size < expected && System.currentTimeMillis() < deadline) {
            kotlinx.coroutines.delay(20)
        }
    }

    /** Give the IO coroutine scope a moment to execute the posted refreshes. */
    private fun waitForAsyncRefresh() = runBlocking {
        kotlinx.coroutines.delay(150)
    }
}
