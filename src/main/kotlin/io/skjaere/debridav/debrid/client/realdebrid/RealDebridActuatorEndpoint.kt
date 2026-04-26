package io.skjaere.debridav.debrid.client.realdebrid

import org.springframework.boot.actuate.endpoint.annotation.Endpoint
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation
import org.springframework.stereotype.Component

@Component
@Endpoint(id = "realdebrid")
class RealDebridActuatorEndpoint(
    private val realDebridClient: RealDebridClient
) {
    @WriteOperation
    fun setTorrentImportEnabled(torrentImportEnabled: Boolean) {
        realDebridClient.torrentImportEnabled = torrentImportEnabled
        if (torrentImportEnabled) {
            realDebridClient.syncTorrentsTask()
        }
    }

    @ReadOperation
    fun getTorrentImportEnabled(): Boolean = realDebridClient.torrentImportEnabled
}
