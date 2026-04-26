package io.skjaere.debridav.health

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/health-queue")
class HealthQueueController(private val healthQueueService: HealthQueueService) {

    @GetMapping("/check")
    fun getHealthCheckStatus(): ResponseEntity<HealthQueueStatusResponse> =
        ResponseEntity.ok(healthQueueService.getHealthCheckStatus())

    @GetMapping("/check/history")
    fun getHealthCheckHistory(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "") search: String
    ): ResponseEntity<HealthQueueHistoryResponse> =
        ResponseEntity.ok(healthQueueService.getHealthCheckHistory(page, size, search))

    @GetMapping("/repair")
    fun getRepairStatus(): ResponseEntity<HealthQueueStatusResponse> =
        ResponseEntity.ok(healthQueueService.getRepairStatus())

    @GetMapping("/repair/history")
    fun getRepairHistory(
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
        @RequestParam(defaultValue = "") search: String
    ): ResponseEntity<HealthQueueHistoryResponse> =
        ResponseEntity.ok(healthQueueService.getRepairHistory(page, size, search))
}
