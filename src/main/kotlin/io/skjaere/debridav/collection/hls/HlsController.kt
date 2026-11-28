package io.skjaere.debridav.collection.hls

import io.skjaere.debridav.collection.play.PlaySessionRegistry
import kotlinx.coroutines.runBlocking
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.CacheControl
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * The HLS stream of the release a play session plays, for hls.js or Safari's own player: its playlist, its init segment
 * and its segments, made as they're asked for. Addressed by the session's token, like the rest of the session.
 */
@RestController
@RequestMapping("/api/v1/collection/sessions/{token}/hls")
class HlsController(private val registry: PlaySessionRegistry) {

    @GetMapping("/index.m3u8")
    fun playlist(@PathVariable token: String): ResponseEntity<String> {
        val hls = registry.find(token)?.hls ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/vnd.apple.mpegurl"))
            .cacheControl(CacheControl.noCache())
            .body(hls.playlist())
    }

    @GetMapping("/init.mp4")
    fun init(@PathVariable token: String): ResponseEntity<Resource> {
        val hls = registry.find(token)?.hls ?: return ResponseEntity.notFound().build()
        return file(runBlocking { hls.init() }, MediaType.parseMediaType("video/mp4"))
    }

    @GetMapping("/seg{number}.m4s")
    fun segment(@PathVariable token: String, @PathVariable number: Int): ResponseEntity<Resource> {
        val hls = registry.find(token)?.hls ?: return ResponseEntity.notFound().build()
        return file(runBlocking { hls.segment(number) }, MediaType.parseMediaType("video/iso.segment"))
    }

    private fun file(file: HlsFile, type: MediaType): ResponseEntity<Resource> = when (file) {
        is HlsFile.Ready -> ResponseEntity.ok().contentType(type).cacheControl(CacheControl.noStore())
            .body(FileSystemResource(file.path))
        HlsFile.Missing -> ResponseEntity.notFound().build()
        // hls.js asks again after a 5xx, not a 404; and asked again, it's made.
        HlsFile.NotYet -> ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1").build()
    }
}
