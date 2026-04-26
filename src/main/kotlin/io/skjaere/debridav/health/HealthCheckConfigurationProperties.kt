package io.skjaere.debridav.health

import io.skjaere.debridav.config.ConfigProperty
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

private const val TORRENT_INTERVAL_DAYS = 1L
private const val NZB_INTERVAL_DAYS = 7L
private const val DEFAULT_POLL_RATE_MINUTES = 5L

@ConfigurationProperties(prefix = "health-check")
class HealthCheckConfigurationProperties {
    @ConfigProperty(name = "Repair Enabled", description = "Enable automatic repair of unhealthy torrents and NZBs")
    var repairEnabled: Boolean = true

    @ConfigProperty(name = "Torrent Interval", description = "How often to reverify torrent availability")
    var torrentInterval: Duration = Duration.ofDays(TORRENT_INTERVAL_DAYS)

    @ConfigProperty(name = "Torrent Poll Rate", description = "How often to poll for torrents needing health checks")
    var torrentPollRate: Duration = Duration.ofMinutes(DEFAULT_POLL_RATE_MINUTES)

    @ConfigProperty(name = "NZB Interval", description = "How often to reverify NZB segments")
    var nzbInterval: Duration = Duration.ofDays(NZB_INTERVAL_DAYS)

    @ConfigProperty(name = "NZB Poll Rate", description = "How often to poll for NZBs needing health checks")
    var nzbPollRate: Duration = Duration.ofMinutes(DEFAULT_POLL_RATE_MINUTES)
}
