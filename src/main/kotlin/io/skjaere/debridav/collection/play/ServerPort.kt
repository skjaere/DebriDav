package io.skjaere.debridav.collection.play

import org.springframework.boot.web.server.context.WebServerInitializedEvent
import org.springframework.context.ApplicationListener
import org.springframework.stereotype.Component

/** The port debridav serves on, for ffprobe and FFmpeg to read a play's release from its session stream. */
@Component
class ServerPort : ApplicationListener<WebServerInitializedEvent> {
    @Volatile
    private var current: Int = DEFAULT_PORT

    val port: Int get() = current

    override fun onApplicationEvent(event: WebServerInitializedEvent) {
        current = event.webServer.port
    }

    /** Where a session's release streams from, on this machine. */
    fun sourceUri(session: PlaySession): String =
        "http://127.0.0.1:$port/api/v1/collection/sessions/${session.token}/source"

    private companion object {
        const val DEFAULT_PORT = 8080
    }
}
