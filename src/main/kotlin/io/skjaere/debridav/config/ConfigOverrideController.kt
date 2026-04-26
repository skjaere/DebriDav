package io.skjaere.debridav.config

import kotlinx.coroutines.runBlocking
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/config")
class ConfigOverrideController(
    private val service: ConfigOverrideService,
    private val registry: ConfigPropertyRegistry,
    private val nntpPoolTester: NntpPoolTester
) {
    @GetMapping
    fun listAll(): ResponseEntity<List<ConfigOverrideDto>> =
        ResponseEntity.ok(service.listAll())

    @GetMapping("/{key}")
    fun get(@PathVariable key: String): ResponseEntity<ConfigOverrideDto> =
        ResponseEntity.ok(service.getEffective(key))

    @PutMapping("/{key}")
    fun upsert(
        @PathVariable key: String,
        @RequestBody body: UpsertRequest
    ): ResponseEntity<ConfigOverrideDto> =
        ResponseEntity.ok(service.upsert(key, body.value))

    @DeleteMapping("/{key}")
    fun delete(@PathVariable key: String): ResponseEntity<ConfigOverrideDto> =
        ResponseEntity.ok(service.delete(key))

    @GetMapping("/testable")
    fun listTestable(): ResponseEntity<List<TestablePrefixDto>> =
        ResponseEntity.ok(registry.getTestablePrefixes())

    @GetMapping("/nntp-pools")
    fun getNntpPools(): ResponseEntity<List<NntpPoolDto>> =
        ResponseEntity.ok(service.getNntpPools())

    @PutMapping("/nntp-pools")
    fun saveNntpPools(@RequestBody pools: List<NntpPoolDto>): ResponseEntity<List<NntpPoolDto>> {
        service.saveNntpPools(pools)
        return ResponseEntity.ok(service.getNntpPools())
    }

    @PostMapping("/nntp-pools/test")
    fun testNntpPool(@RequestBody pool: NntpPoolDto): ResponseEntity<ConfigTestResultDto> {
        val start = System.currentTimeMillis()
        val result = runBlocking { nntpPoolTester.test(pool) }
        val durationMs = System.currentTimeMillis() - start

        return ResponseEntity.ok(
            ConfigTestResultDto(
                prefix = "nntp",
                label = "NNTP Pool",
                success = result.success,
                message = result.message,
                durationMs = durationMs
            )
        )
    }

    @PostMapping("/test/{prefix}")
    fun test(
        @PathVariable prefix: String,
        @RequestBody(required = false) body: TestRequest?
    ): ResponseEntity<ConfigTestResultDto> {
        val tester = registry.getTester(prefix)
            ?: return ResponseEntity.notFound().build()

        val start = System.currentTimeMillis()
        val result = runBlocking { tester.test(body?.overrides ?: emptyMap()) }
        val durationMs = System.currentTimeMillis() - start

        return ResponseEntity.ok(
            ConfigTestResultDto(
                prefix = prefix,
                label = tester.label,
                success = result.success,
                message = result.message,
                durationMs = durationMs
            )
        )
    }
}

data class UpsertRequest(val value: String? = null)
data class TestRequest(val overrides: Map<String, String> = emptyMap())
