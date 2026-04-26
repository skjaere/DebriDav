package io.skjaere.debridav.fs

import io.milton.http.Range
import io.milton.resource.GetableResource
import io.skjaere.debridav.config.auth.JwtService
import io.skjaere.debridav.resource.StreamableResourceFactory
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/stream")
class StreamController(
    private val jwtService: JwtService,
    private val databaseFileService: DatabaseFileService,
    private val streamableResourceFactory: StreamableResourceFactory
) {
    @Suppress("ReturnCount")
    @GetMapping("/t/{token}")
    fun streamByToken(
        @PathVariable token: String,
        request: HttpServletRequest,
        response: HttpServletResponse
    ) {
        val path = jwtService.validateStreamToken(token)
        if (path == null) {
            response.status = HttpStatus.UNAUTHORIZED.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"Invalid or expired token"}""")
            return
        }

        val entity = databaseFileService.getFileAtPath(path)
        if (entity == null || entity is DbDirectory) {
            response.status = HttpStatus.NOT_FOUND.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"File not found"}""")
            return
        }

        val resource = streamableResourceFactory.toFileResource(entity) as? GetableResource
        if (resource == null) {
            response.status = HttpStatus.NOT_FOUND.value()
            response.contentType = "application/json"
            response.writer.write("""{"error":"File not found"}""")
            return
        }

        val contentLength = resource.contentLength
        val contentType = resource.getContentType(null) ?: "application/octet-stream"
        val rangeHeader = request.getHeader("Range")
        val range = parseRangeHeader(rangeHeader, contentLength)

        response.contentType = contentType
        response.setHeader("Accept-Ranges", "bytes")

        if (range != null) {
            val start = range.start ?: 0
            val finish = range.finish ?: (contentLength - 1)
            response.status = HttpServletResponse.SC_PARTIAL_CONTENT
            response.setHeader("Content-Range", "bytes $start-$finish/$contentLength")
            response.setContentLengthLong(finish - start + 1)
        } else {
            response.status = HttpServletResponse.SC_OK
            response.setContentLengthLong(contentLength)
        }

        resource.sendContent(response.outputStream, range, null, contentType)
    }

    @Suppress("ReturnCount")
    private fun parseRangeHeader(header: String?, contentLength: Long): Range? {
        if (header == null || !header.startsWith("bytes=")) return null
        val rangeSpec = header.removePrefix("bytes=")
        val parts = rangeSpec.split("-", limit = 2)
        if (parts.size != 2) return null

        val start = parts[0].toLongOrNull()
        val end = parts[1].toLongOrNull()

        return when {
            start != null && end != null -> Range(start, end)
            start != null -> Range(start, contentLength - 1)
            end != null -> Range(contentLength - end, contentLength - 1)
            else -> null
        }
    }
}
