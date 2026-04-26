package io.skjaere.debridav.usenet.queue

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/queue")
class UsenetQueueController(private val queueService: UsenetQueueService) {
    @GetMapping
    fun getQueueStatus(): ResponseEntity<QueueStatusResponse> =
        ResponseEntity.ok(queueService.getQueueStatus())

    @GetMapping("/history")
    fun getHistory(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "") search: String,
        @RequestParam(defaultValue = "updatedAt") sort: String,
        @RequestParam(defaultValue = "desc") direction: String
    ): ResponseEntity<HistoryPageResponse> =
        ResponseEntity.ok(queueService.getHistory(page, size, search, sort, direction))

    @GetMapping("/{id}/files")
    fun getItemFiles(@PathVariable id: Long): ResponseEntity<List<NzbImportFileJson>> {
        val files = queueService.resolveCurrentFilePaths(id)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(files)
    }
}
