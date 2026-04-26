package io.skjaere.debridav.rclone

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Listens for [FileSystemChangedEvent]s and POSTs to rclone's `/vfs/refresh`
 * RC endpoint so rclone sees new/moved/deleted files without waiting for its
 * directory cache to expire.
 *
 * Events are coalesced within a 500 ms window: the first event in a quiet
 * period schedules a flush, subsequent events append to the pending set
 * without rescheduling, and at the window's end every unique path gets one
 * refresh call. Max latency from event to refresh is one window plus HTTP
 * round-trip.
 *
 * Entirely opt-in. Disabled by default — enable from the UI's Core config
 * page (`debridav.rclone-cache-invalidation-enabled`) and set the three
 * `DEBRIDAV_RCLONE_RC-*` env vars. If either the toggle is off or the URL
 * is blank, events are dropped with no network I/O.
 */
@Component
class RcloneCacheInvalidator(
    private val debridavConfig: DebridavConfigurationProperties,
    private val rcloneConfig: RcloneConfigurationProperties,
    private val httpClient: HttpClient,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val lock = ReentrantLock()
    private val pending = mutableSetOf<String>()
    private var flushScheduled = false

    private val scheduler: ScheduledExecutorService =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "rclone-cache-invalidator").apply { isDaemon = true }
        }
    private val ioScope = CoroutineScope(SupervisorJob())

    @EventListener
    fun onChange(event: FileSystemChangedEvent) {
        if (!debridavConfig.rcloneCacheInvalidationEnabled
            || rcloneConfig.rcUrl.isBlank()
            || event.paths.isEmpty()
        ) return

        // Include every ancestor of each changed path so rclone refreshes
        // the parent listings too — otherwise a new directory (e.g. the
        // /downloads/<release>/ created by an NZB import) can't be refreshed
        // directly because rclone hasn't seen it yet.
        val withAncestors = event.paths.flatMap(::ancestors).toSet()
        lock.withLock {
            pending.addAll(withAncestors)
            if (!flushScheduled) {
                flushScheduled = true
                scheduler.schedule(::flush, COALESCE_WINDOW_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    internal fun flush() {
        val paths = lock.withLock {
            val snap = pending.toSet()
            pending.clear()
            flushScheduled = false
            snap
        }
        if (paths.isEmpty()) return
        // Refresh shallowest-first: rclone has to learn about a directory
        // before we can refresh into it.
        val ordered = paths.sortedBy { if (it == "/") 0 else it.count { ch -> ch == '/' } }
        ioScope.launch {
            ordered.forEach { path ->
                runCatching { refresh(path) }
                    .onFailure { logger.warn("rclone refresh failed for '{}': {}", path, it.message) }
            }
        }
    }

    internal fun ancestors(path: String): Set<String> {
        if (path == "/" || path.isEmpty()) return setOf("/")
        val result = linkedSetOf("/")
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        val builder = StringBuilder()
        for (part in parts) {
            builder.append('/').append(part)
            result.add(builder.toString())
        }
        return result
    }

    private suspend fun refresh(dir: String) {
        val url = rcloneConfig.rcUrl.trimEnd('/') + "/vfs/refresh"
        // rclone's /vfs/refresh rejects dir="/" with "file does not exist" —
        // the root is represented by omitting the dir param entirely, which
        // refreshes the whole VFS.
        val body = if (dir == "/") "{}" else Json.encodeToString(
            RefreshRequest.serializer(),
            RefreshRequest(dir = dir),
        )
        val response = httpClient.post(url) {
            contentType(ContentType.Application.Json)
            if (rcloneConfig.rcUser.isNotBlank()) {
                val creds = "${rcloneConfig.rcUser}:${rcloneConfig.rcPassword}"
                val encoded = Base64.getEncoder().encodeToString(creds.toByteArray())
                header(HttpHeaders.Authorization, "Basic $encoded")
            }
            setBody(body)
        }
        if (!response.status.isSuccess()) {
            logger.warn(
                "rclone refresh for '{}' returned {}: {}",
                dir, response.status, response.bodyAsText()
            )
        }
    }

    @PreDestroy
    fun shutdown() {
        scheduler.shutdown()
        runCatching { scheduler.awaitTermination(1, TimeUnit.SECONDS) }
        ioScope.cancel()
    }

    @Serializable
    private data class RefreshRequest(val dir: String, val recursive: String = "false")

    companion object {
        const val COALESCE_WINDOW_MS = 500L
    }
}
