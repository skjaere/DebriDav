package io.skjaere.debridav

import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthCheck {

    @GetMapping("/health")
    fun checkHealth(): ResponseEntity<String> = ResponseEntity.ok().body("OK")
}
