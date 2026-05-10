package io.skjaere.debridav.config

import io.skjaere.debridav.usenet.NntpPoolReadinessGate
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.cloud.context.refresh.ContextRefresher
import org.springframework.context.ApplicationListener
import org.springframework.context.annotation.Lazy
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.stereotype.Component

@Component
class DatabasePropertySourceInitializer(
    private val environment: ConfigurableEnvironment,
    private val repository: ConfigOverrideRepository,
    private val contextRefresher: ContextRefresher,
    @Lazy private val configOverrideService: ConfigOverrideService,
    private val nntpPoolReadinessGate: NntpPoolReadinessGate,
) : ApplicationListener<ApplicationReadyEvent> {

    private val logger = LoggerFactory.getLogger(DatabasePropertySourceInitializer::class.java)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        try {
            val propertySource = getOrCreatePropertySource()
            val overrides = repository.findAll().associate { it.propKey to (it.propValue ?: "") }
            if (overrides.isNotEmpty()) {
                propertySource.replaceAll(overrides)
                contextRefresher.refreshEnvironment()
                logger.info("Loaded {} database config override(s) and refreshed environment", overrides.size)
                // Pool sync first: the streaming-config sync's verification-concurrency
                // derivation reads the live pool list, so pools must already reflect
                // the override state by the time we compute it.
                configOverrideService.syncRunningNntpPools()
                // Push streaming knobs (concurrency / readAheadSegments /
                // verificationConcurrency) to the live NzbStreamer. Without this,
                // overrides for these keys land in NntpConfigurationProperties on
                // refresh but never reach the streamer (which captured the yaml
                // values at construction).
                configOverrideService.syncRunningStreamingConfig()
            } else {
                logger.info("No database config overrides found")
            }
        } finally {
            // Always release the gate, even if loading overrides fails — the consumers
            // shouldn't block forever if config bootstrap throws. In the failure case the
            // pool is whatever application.yaml configured at bean-init time.
            nntpPoolReadinessGate.markReady()
        }
    }

    fun getOrCreatePropertySource(): DatabasePropertySource {
        val sources = environment.propertySources
        val existing = sources.get(DatabasePropertySource.NAME)
        if (existing != null) {
            return existing as DatabasePropertySource
        }
        val propertySource = DatabasePropertySource()
        sources.addFirst(propertySource)
        return propertySource
    }
}
