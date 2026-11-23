package io.skjaere.debridav.collection.play

import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter

@RestController
class PlayController(
    private val playService: PlayService,
    private val registry: PlaySessionRegistry,
) {
    /** Starts playing a movie or episode; a user's earlier play ends. Needs login, as everything in the collection. */
    @PostMapping("/api/v1/collection/play")
    fun start(@RequestBody request: PlayRequest): ResponseEntity<PlayStarted> {
        val user = SecurityContextHolder.getContext().authentication?.name ?: "anonymous"
        return ResponseEntity.status(HttpStatus.CREATED).body(playService.start(request, user))
    }

    /** The play's progress, as server-sent events: everything so far, then each new step. */
    @GetMapping("/api/v1/collection/sessions/{token}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(@PathVariable token: String): ResponseEntity<SseEmitter> {
        val session = registry.find(token) ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(session.events.subscribe())
    }

    /** Ends the play, letting go of all it holds. */
    @DeleteMapping("/api/v1/collection/sessions/{token}")
    fun close(@PathVariable token: String): ResponseEntity<Void> =
        if (registry.close(token)) ResponseEntity.noContent().build() else ResponseEntity.notFound().build()

    /** The same, for navigator.sendBeacon when the player page goes away: it can only POST. */
    @PostMapping("/api/v1/collection/sessions/{token}/close")
    fun closeOnLeave(@PathVariable token: String): ResponseEntity<Void> = close(token)

    @ExceptionHandler(PlayRejected::class)
    fun rejected(e: PlayRejected): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("message" to e.message))
}
