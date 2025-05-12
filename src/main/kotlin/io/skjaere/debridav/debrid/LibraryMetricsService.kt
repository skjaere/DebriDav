package io.skjaere.debridav.debrid


import io.prometheus.metrics.core.metrics.Gauge
import io.prometheus.metrics.model.registry.PrometheusRegistry
import io.skjaere.debridav.repository.DebridFileContentsRepository
import io.skjaere.debridav.repository.LibraryStats
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

@Component
class LibraryMetricsService(
    private val debridFileContentsRepository: DebridFileContentsRepository,
    prometheusRegistry: PrometheusRegistry
) {

    private val cachedStatusGauge = Gauge.builder()
        .name("debridav.library.metrics")
        .help("Metrics for library files")
        .labelNames("provider", "type")
        .register(prometheusRegistry)

    private val librarySizeGauge = Gauge.builder()
        .name("debridav.library.size")
        .labelNames("source")
        .help("Metrics for library files")
        .register(prometheusRegistry)

    @Scheduled(fixedRate = 60000)
    fun recordLibraryMetrics() {
        val numberOfTorrentEntities = debridFileContentsRepository.numberOfRemotelyCachedTorrentEntities()
        librarySizeGauge
            .labelValues("torrent")
            .set(numberOfTorrentEntities.toDouble())

        val numberOfUsenetEntities = debridFileContentsRepository.numberOfRemotelyCachedUsenetEntities()
        librarySizeGauge
            .labelValues("usenet")
            .set(numberOfUsenetEntities.toDouble())

        debridFileContentsRepository.getLibraryMetricsTorrents()
            .toLibraryTorrentStats(numberOfTorrentEntities)
            .forEach {
                cachedStatusGauge
                    .labelValues(it.provider, it.type)
                    .set(it.count.toDouble())
            }
    }

    fun List<Map<String, Any>>.toLibraryTorrentStats(numberOfTotalEntities: Long): List<LibraryStats> {
        return this.map {
            LibraryStats(
                (it["provider"] as String).replace("\"", ""),
                (it["type"] as String).replace("\"", ""),
                (it["count"] as Long)
            )
        }.groupBy { it.provider }
            .mapValues { entry ->
                if (entry.value.sumOf { it.count } < numberOfTotalEntities) {
                    val mutableEntryValue = entry.value.toMutableList()
                    mutableEntryValue.add(
                        LibraryStats(
                            entry.key,
                            "Unknown",
                            numberOfTotalEntities - entry.value.sumOf { it.count }
                        )
                    )
                    mutableEntryValue
                } else entry.value.toMutableList()
            }.values.flatten()

    }
}
