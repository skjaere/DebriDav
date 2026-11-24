package io.skjaere.debridav.collection.indexer

import io.skjaere.debridav.config.TestEndpointRateLimiter
import kotlinx.coroutines.runBlocking
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/collection/indexers")
class IndexerController(
    private val service: IndexerService,
    private val testEndpointRateLimiter: TestEndpointRateLimiter,
) {
    @GetMapping
    fun list(): List<IndexerDto> = service.list()

    @PostMapping
    fun create(@RequestBody request: IndexerRequest): ResponseEntity<IndexerDto> =
        ResponseEntity.status(HttpStatus.CREATED).body(service.create(request))

    @PutMapping("/{id}")
    fun update(@PathVariable id: Long, @RequestBody request: IndexerRequest): IndexerDto = service.update(id, request)

    @DeleteMapping("/{id}")
    fun delete(@PathVariable id: Long): ResponseEntity<Void> {
        service.delete(id)
        return ResponseEntity.noContent().build()
    }

    /** Tests an indexer as typed, saved or not; rate limited like the other connection tests. */
    @PostMapping("/test")
    fun test(@RequestBody request: IndexerTestRequest): ResponseEntity<IndexerTestResultDto> {
        val user = SecurityContextHolder.getContext().authentication?.name ?: "anonymous"
        if (!testEndpointRateLimiter.tryAcquire(user)) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).body(
                IndexerTestResultDto(success = false, message = "Too many tests: try again in a minute"),
            )
        }
        return ResponseEntity.ok(runBlocking { service.test(request) })
    }

    @ExceptionHandler(IndexerRejected::class)
    fun rejected(e: IndexerRejected): ResponseEntity<Map<String, String?>> =
        ResponseEntity.badRequest().body(mapOf("error" to e.message))

    @ExceptionHandler(NoSuchIndexer::class)
    fun missing(e: NoSuchIndexer): ResponseEntity<Map<String, String?>> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(mapOf("error" to e.message))
}
