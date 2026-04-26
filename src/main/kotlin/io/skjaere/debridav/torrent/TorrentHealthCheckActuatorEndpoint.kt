package io.skjaere.debridav.torrent

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.stereotype.Component

@Component
@Endpoint(id = "torrenthealthcheck")
class TorrentHealthCheckActuatorEndpoint(
    private val torrentHealthCheckService: TorrentHealthCheckService
) {
    @WriteOperation
    fun triggerHealthCheck() {
        torrentHealthCheckService.triggerFullHealthCheck()
    }
}
