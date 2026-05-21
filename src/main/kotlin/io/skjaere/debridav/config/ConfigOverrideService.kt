package io.skjaere.debridav.config

import io.skjaere.debridav.usenet.NntpConfigurationProperties
import io.skjaere.debridav.usenet.NntpPoolProperties
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.config.NntpConfig
import jakarta.transaction.Transactional
import org.slf4j.LoggerFactory
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.EnumerablePropertySource
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class ConfigOverrideService(
    private val repository: ConfigOverrideRepository,
    private val environment: ConfigurableEnvironment,
    private val registry: ConfigPropertyRegistry,
    private val nntpConfig: NntpConfigurationProperties,
    private val contextRefresher: ContextRefresher,
    private val dbPropertySourceInitializer: DatabasePropertySourceInitializer,
    private val nzbStreamer: NzbStreamer? = null
) {
    private val logger = LoggerFactory.getLogger(ConfigOverrideService::class.java)
    companion object {
        private const val MASKED = "***"
        private const val POOL_PREFIX = "nntp.pools["
        private const val POOLS_MANAGED_KEY = "nntp.pools._managed"
        private const val NNTP_CONCURRENCY_KEY = "nntp.concurrency"
        private const val NNTP_READ_AHEAD_KEY = "nntp.read-ahead-segments"
        private const val NNTP_VERIFICATION_CONCURRENCY_KEY = "nntp.verification-concurrency"
        private const val NNTP_CB_FAILURE_THRESHOLD_KEY = "nntp.circuit-breaker-failure-threshold"
        private const val NNTP_CB_COOLDOWN_KEY = "nntp.circuit-breaker-cooldown-ms"
        private const val NNTP_SEGMENT_FETCH_TIMEOUT_KEY = "nntp.segment-fetch-timeout-ms"
        // Defaults must match StreamingConfig's defaults in nzb-streamer — duplicated here
        // because StreamingConfig isn't a Spring bean we can reach through @Value.
        private const val DEFAULT_CB_FAILURE_THRESHOLD = 5
        private const val DEFAULT_CB_COOLDOWN_MS = 60_000L
        // StreamingConfig.segmentFetchTimeoutMs default in nzb-streamer is 5_000L.
        // Update here if you change it there — drift would break unset-override-→-revert.
        private const val DEFAULT_SEGMENT_FETCH_TIMEOUT_MS = 5_000L
    }

    fun listAll(): List<ConfigOverrideDto> {
        val overrides = repository.findAllByPropKeyIn(registry.properties.keys)
            .associateBy { it.propKey }

        return registry.properties.map { (key, meta) ->
            val override = overrides[key]
            val defaultValue = getDefaultValue(key)
            val effectiveValue = override?.propValue ?: defaultValue

            ConfigOverrideDto(
                key = key,
                name = meta.name,
                effectiveValue = if (meta.sensitive) effectiveValue?.let { MASKED } else effectiveValue,
                defaultValue = if (meta.sensitive) defaultValue?.let { MASKED } else defaultValue,
                hasOverride = override != null,
                sensitive = meta.sensitive,
                group = meta.group,
                description = meta.description,
                type = meta.type,
                advanced = meta.advanced
            )
        }
    }

    fun getEffective(key: String): ConfigOverrideDto {
        val meta = registry.getMeta(key)
            ?: throw KeyNotWhitelistedException(key)

        val override = repository.findByPropKey(key)
        val defaultValue = getDefaultValue(key)
        val effectiveValue = override?.propValue ?: defaultValue

        return ConfigOverrideDto(
            key = key,
            name = meta.name,
            effectiveValue = if (meta.sensitive) effectiveValue?.let { MASKED } else effectiveValue,
            defaultValue = if (meta.sensitive) defaultValue?.let { MASKED } else defaultValue,
            hasOverride = override != null,
            sensitive = meta.sensitive,
            group = meta.group,
            description = meta.description,
            type = meta.type,
            advanced = meta.advanced
        )
    }

    fun upsert(key: String, value: String?): ConfigOverrideDto {
        val meta = registry.getMeta(key)
            ?: throw KeyNotWhitelistedException(key)

        val now = Instant.now()
        val entity = repository.findByPropKey(key) ?: ConfigOverride().apply {
            propKey = key
            createdAt = now
        }
        entity.propValue = value
        entity.sensitive = meta.sensitive
        entity.updatedAt = now
        repository.save(entity)

        refreshEnvironment()
        syncRunningStreamingConfigIfRelevant(key)

        return getEffective(key)
    }

    fun delete(key: String): ConfigOverrideDto {
        if (!registry.isWhitelisted(key)) {
            throw KeyNotWhitelistedException(key)
        }

        val override = repository.findByPropKey(key)
            ?: throw OverrideNotFoundException(key)
        repository.delete(override)

        refreshEnvironment()
        syncRunningStreamingConfigIfRelevant(key)

        return getEffective(key)
    }

    /**
     * Push the current NntpConfigurationProperties values for `concurrency`,
     * `readAheadSegments`, and `verificationConcurrency` to the running NzbStreamer.
     *
     * `StreamingConfig` is captured into NzbStreamer at construction time, so a
     * Spring context refresh that rebinds NntpConfigurationProperties would otherwise
     * leave the live streamer running with the old values forever. Pools have an
     * equivalent live-sync path in [syncRunningNntpPools] — this is the counterpart
     * for the streaming knobs that flow through StreamingConfig. All current
     * StreamingConfig fields are tunable at runtime via this sync.
     *
     * Called both at startup (from [DatabasePropertySourceInitializer] after
     * `syncRunningNntpPools` has reconciled the pool list — the verification-
     * concurrency default reads the live pool list) and on every per-key
     * `upsert`/`delete`. The setters are idempotent on equal values, so re-applying
     * all three on each change is effectively the same as applying only the changed
     * one — and lets startup reuse the same path.
     */
    fun syncRunningStreamingConfig() {
        if (nzbStreamer == null) return
        val concurrency = nntpConfig.concurrency
        val readAhead = nntpConfig.readAheadSegments ?: concurrency
        val verificationConcurrency = nntpConfig.verificationConcurrency
            ?: derivedDefaultVerificationConcurrency(concurrency)
        val cbFailureThreshold = nntpConfig.circuitBreakerFailureThreshold ?: DEFAULT_CB_FAILURE_THRESHOLD
        val cbCooldownMs = nntpConfig.circuitBreakerCooldownMs ?: DEFAULT_CB_COOLDOWN_MS
        val segmentFetchTimeoutMs = nntpConfig.segmentFetchTimeoutMs ?: DEFAULT_SEGMENT_FETCH_TIMEOUT_MS
        nzbStreamer.setStreamingConcurrency(concurrency)
        nzbStreamer.setReadAheadSegments(readAhead)
        nzbStreamer.setVerificationConcurrency(verificationConcurrency)
        nzbStreamer.setCircuitBreakerConfig(cbFailureThreshold, cbCooldownMs)
        nzbStreamer.setSegmentFetchTimeoutMs(segmentFetchTimeoutMs)
        logger.info(
            "Synced NzbStreamer streaming config: concurrency={}, readAheadSegments={}, " +
                "verificationConcurrency={}, circuitBreakerFailureThreshold={}, circuitBreakerCooldownMs={}, " +
                "segmentFetchTimeoutMs={}",
            concurrency, readAhead, verificationConcurrency,
            cbFailureThreshold, cbCooldownMs, segmentFetchTimeoutMs,
        )
    }

    private fun syncRunningStreamingConfigIfRelevant(key: String) {
        if (key in setOf(
                NNTP_CONCURRENCY_KEY,
                NNTP_READ_AHEAD_KEY,
                NNTP_VERIFICATION_CONCURRENCY_KEY,
                NNTP_CB_FAILURE_THRESHOLD_KEY,
                NNTP_CB_COOLDOWN_KEY,
                NNTP_SEGMENT_FETCH_TIMEOUT_KEY,
            )
        ) {
            syncRunningStreamingConfig()
        }
    }

    // Mirrors NzbStreamerConfiguration.nzbStreamer()'s default: when
    // nntp.verification-concurrency is unset, we use sum(pool.maxConnections)
    // bounded below by streaming concurrency. Pool list is read from the live
    // streamer so saveNntpPools changes are picked up.
    private fun derivedDefaultVerificationConcurrency(streamingConcurrency: Int): Int {
        val totalConnections = nzbStreamer?.getPoolConfigs().orEmpty().sumOf { it.maxConnections }
        return totalConnections.coerceAtLeast(streamingConcurrency)
    }

    private fun refreshEnvironment() {
        val propertySource = dbPropertySourceInitializer.getOrCreatePropertySource()
        val overrides = repository.findAll().associate { it.propKey to (it.propValue ?: "") }
        propertySource.replaceAll(overrides)
        contextRefresher.refreshEnvironment()
        logger.info("Refreshed environment with {} database override(s)", overrides.size)
    }

    @Suppress("ReturnCount")
    private fun getDefaultValue(key: String): String? {
        for (source in environment.propertySources) {
            if (source.name == DatabasePropertySource.NAME) continue
            if (source is EnumerablePropertySource<*>) {
                val value = source.getProperty(key)
                if (value != null) return value.toString()
            } else {
                val value = source.getProperty(key)
                if (value != null) return value.toString()
            }
        }
        return null
    }

    fun getNntpPools(): List<NntpPoolDto> = readNntpPools().map { it.maskPassword() }

    /**
     * Internal — returns pools with **plaintext** passwords. Used by [saveNntpPools] to
     * resolve [MASKED] sentinels against the previously-stored value and by
     * [syncRunningNntpPools] to push live config into the streamer. Never expose this
     * directly through a controller; callers headed to a response body should go through
     * [getNntpPools] so passwords are masked on the way out.
     */
    private fun readNntpPools(): List<NntpPoolDto> {
        val overrides = repository.findAllByPropKeyStartingWith(POOL_PREFIX)
        if (overrides.isNotEmpty()) {
            return parsePoolOverrides(overrides).sortedBy { it.priority }
        }
        // No pool rows in the DB. Distinguish "user has explicitly emptied the
        // list" from "first boot, never configured via UI": a managed-marker
        // row is written by saveNntpPools on every save, so its presence means
        // we should honor the empty state and not fall back to env defaults.
        val managed = repository.findByPropKey(POOLS_MANAGED_KEY) != null
        return if (managed) {
            emptyList()
        } else {
            nntpConfig.pools.map { it.toDto() }.sortedBy { it.priority }
        }
    }

    private fun NntpPoolDto.maskPassword(): NntpPoolDto =
        if (password.isNotEmpty()) copy(password = MASKED) else this

    @Transactional
    fun saveNntpPools(pools: List<NntpPoolDto>) {
        // Resolve MASKED sentinels against the current state. The UI sends pools back
        // with password=MASKED for entries the user didn't touch — matching by
        // (host, port, username) lets us preserve those passwords even if the user
        // reorders the list. A pool whose identity tuple doesn't match anything in the
        // current state is treated as a new pool: MASKED becomes empty.
        val existingByIdentity = readNntpPools()
            .associateBy { Triple(it.host, it.port, it.username) }
        val resolvedPools = pools.map { pool ->
            if (pool.password == MASKED) {
                val existing = existingByIdentity[Triple(pool.host, pool.port, pool.username)]
                pool.copy(password = existing?.password ?: "")
            } else {
                pool
            }
        }
        repository.deleteAllByPropKeyStartingWith(POOL_PREFIX)
        val now = Instant.now()
        if (repository.findByPropKey(POOLS_MANAGED_KEY) == null) {
            repository.save(ConfigOverride().apply {
                propKey = POOLS_MANAGED_KEY
                propValue = "true"
                sensitive = false
                createdAt = now
                updatedAt = now
            })
        }
        repository.flush()
        resolvedPools.forEachIndexed { i, pool ->
            val entries = mapOf(
                "nntp.pools[$i].host" to pool.host,
                "nntp.pools[$i].port" to pool.port.toString(),
                "nntp.pools[$i].username" to pool.username,
                "nntp.pools[$i].password" to pool.password,
                "nntp.pools[$i].use-tls" to pool.useTls.toString(),
                "nntp.pools[$i].max-connections" to pool.maxConnections.toString(),
                "nntp.pools[$i].priority" to pool.priority.toString()
            )
            for ((key, value) in entries) {
                val entity = ConfigOverride().apply {
                    propKey = key
                    propValue = value
                    sensitive = key.endsWith(".password")
                    createdAt = now
                    updatedAt = now
                }
                repository.save(entity)
            }
        }
        syncRunningPools(resolvedPools)
    }

    fun syncRunningNntpPools() {
        // Use the unmasked internal accessor so the live streamer gets the actual
        // passwords, not the [MASKED] sentinel.
        syncRunningPools(readNntpPools())
    }

    private fun syncRunningPools(saved: List<NntpPoolDto>) {
        if (nzbStreamer == null) return
        val savedConfigs = saved.map { it.toNntpConfig() }.toSet()
        val runningConfigs = nzbStreamer.getPoolConfigs().toSet()

        val toRemove = runningConfigs - savedConfigs
        val toAdd = savedConfigs - runningConfigs

        toRemove.forEach { nzbStreamer.removePool(it) }
        toAdd.forEach { nzbStreamer.addPool(it) }

        if (toRemove.isNotEmpty() || toAdd.isNotEmpty()) {
            logger.info("Synced NNTP pools: removed={}, added={}", toRemove.size, toAdd.size)
        }
    }

    private fun NntpPoolDto.toNntpConfig() = NntpConfig(
        host = host,
        port = port,
        username = username,
        password = password,
        useTls = useTls,
        maxConnections = maxConnections,
        priority = priority
    )

    private fun parsePoolOverrides(overrides: List<ConfigOverride>): List<NntpPoolDto> {
        val poolMap = mutableMapOf<Int, MutableMap<String, String>>()
        val regex = Regex("""nntp\.pools\[(\d+)]\.(.+)""")
        for (ov in overrides) {
            val match = regex.matchEntire(ov.propKey) ?: continue
            val index = match.groupValues[1].toInt()
            val field = match.groupValues[2]
            poolMap.getOrPut(index) { mutableMapOf() }[field] = ov.propValue ?: ""
        }
        return poolMap.toSortedMap().map { (_, fields) ->
            NntpPoolDto(
                host = fields["host"] ?: "",
                port = fields["port"]?.toIntOrNull() ?: 563,
                username = fields["username"] ?: "",
                password = fields["password"] ?: "",
                useTls = fields["use-tls"]?.toBooleanStrictOrNull() ?: true,
                maxConnections = fields["max-connections"]?.toIntOrNull() ?: 8,
                priority = fields["priority"]?.toIntOrNull() ?: 0
            )
        }
    }

    private fun NntpPoolProperties.toDto() = NntpPoolDto(
        host = host,
        port = port,
        username = username,
        password = password,
        useTls = useTls,
        maxConnections = maxConnections,
        priority = priority
    )
}

class KeyNotWhitelistedException(val key: String) :
    RuntimeException("Property key '$key' is not whitelisted for override")

class OverrideNotFoundException(val key: String) :
    RuntimeException("No override found for key '$key'")
