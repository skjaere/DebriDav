package io.skjaere.debridav.qbittorrent

import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.repository.TorrentRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant

@Component
class TorrentExpirationService(
    private val torrentRepository: TorrentRepository,
    private val debridavConfiguration: DebridavConfiguration
) {
    @Scheduled(fixedDelay = 3600000) // 1 hour
    fun cleanup() {
        torrentRepository.getAllByCreatedBefore(
            Instant.now().minus(
                debridavConfiguration.torrentLifetime
            )
        ).forEach {
            torrentRepository.deleteById(it.id!!)
        }
    }
}
