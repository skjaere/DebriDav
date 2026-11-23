package io.skjaere.debridav.ui

import io.micrometer.core.instrument.MeterRegistry
import io.skjaere.debridav.health.HealthQueueService
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.usenet.queue.UsenetQueueService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Lightweight snapshot of the running system for the frontend's fallback
 * overview page (shown when the Grafana monitoring stack isn't deployed).
 *
 * All values are read from the in-process MeterRegistry and existing queue
 * services — no Prometheus scrape required. Polled from the frontend every
 * few seconds.
 */
@RestController
@RequestMapping("/api/v1/summary")
class SummaryController(
    private val meterRegistry: MeterRegistry,
    private val usenetQueueService: UsenetQueueService,
    private val healthQueueService: HealthQueueService,
    private val debridFileRepository: DebridFileContentsRepository,
) {
    @GetMapping
    fun getSummary(): ResponseEntity<SummaryDto> {
        val debridStreams = meterRegistry.find("debridav.input.stream.bitrate").gauges().map { g ->
            StreamDto(
                name = g.id.getTag("file") ?: "(unknown)",
                source = g.id.getTag("provider") ?: "debrid",
                bitrateBytesPerSec = g.value(),
            )
        }
        val nntpStreams = meterRegistry.find("nzb.streams.bitrate").gauges().map { g ->
            StreamDto(
                name = g.id.getTag("name") ?: "(unknown)",
                source = "NNTP",
                bitrateBytesPerSec = g.value(),
            )
        }
        val streams = (debridStreams + nntpStreams).sortedByDescending { it.bitrateBytesPerSec }
        val activeStreams = ActiveStreamsDto(
            count = streams.size,
            bitrateBytesPerSec = streams.sumOf { it.bitrateBytesPerSec },
            streams = streams,
        )

        // debridav.library.size lives in the Prometheus native client registry,
        // not Micrometer, so we bypass both and query the repository directly.
        // Side benefit: fresh values every poll instead of 60s-stale metric ticks.
        val torrentCount = debridFileRepository.numberOfRemotelyCachedTorrentEntities()
        val usenetCount = debridFileRepository.numberOfRemotelyCachedUsenetEntities()
        val library = LibraryDto(
            totalFiles = torrentCount + usenetCount,
            bySource = listOf(
                LibrarySourceDto("torrent", torrentCount),
                LibrarySourceDto("usenet", usenetCount),
            ).filter { it.files > 0 },
        )

        val usenetStatus = usenetQueueService.getQueueStatus()
        val importQueue = QueueCountsDto(
            pending = usenetStatus.pending.size,
            processing = usenetStatus.processing.size,
        )

        val healthCheck = healthQueueService.getHealthCheckStatus()
        val repair = healthQueueService.getRepairStatus()
        val healthQueue = QueueCountsDto(
            pending = healthCheck.count + repair.count,
            processing = 0,
        )

        val cpu = meterRegistry.find("process.cpu.usage").gauge()?.value() ?: 0.0
        val memory = meterRegistry.find("jvm.memory.used").gauges().sumOf { it.value().toLong() }
        val system = SystemDto(cpuUsage = cpu, memoryBytes = memory)

        return ResponseEntity.ok(SummaryDto(activeStreams, library, importQueue, healthQueue, system))
    }
}

data class SummaryDto(
    val activeStreams: ActiveStreamsDto,
    val library: LibraryDto,
    val importQueue: QueueCountsDto,
    val healthQueue: QueueCountsDto,
    val system: SystemDto,
)

data class ActiveStreamsDto(
    val count: Int,
    val bitrateBytesPerSec: Double,
    val streams: List<StreamDto>,
)

data class StreamDto(
    val name: String,
    val source: String,
    val bitrateBytesPerSec: Double,
)

data class LibraryDto(val totalFiles: Long, val bySource: List<LibrarySourceDto>)

data class LibrarySourceDto(val source: String, val files: Long)

data class QueueCountsDto(val pending: Int, val processing: Int)

data class SystemDto(val cpuUsage: Double, val memoryBytes: Long)
