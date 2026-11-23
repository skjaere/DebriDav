package io.skjaere.debridav.config

import io.skjaere.debridav.config.auth.AuthSetupWarnings
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
    private val authSetupWarnings: AuthSetupWarnings,
    private val registry: ConfigPropertyRegistry,
) : ApplicationListener<ApplicationReadyEvent> {

    private val logger = LoggerFactory.getLogger(DatabasePropertySourceInitializer::class.java)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        try {
            val propertySource = getOrCreatePropertySource()
            val overrides = applicableOverrides()
            if (overrides.isNotEmpty()) {
                applyOrDropAll(propertySource, overrides)
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
        // After the overrides, which can set the credentials and the SABnzbd key.
        authSetupWarnings.logWarnings()
    }

    /**
     * The stored overrides that bind to their property's type. One that doesn't (saved before
     * values were validated, or by hand) would make the refresh throw and stop the app from
     * starting, so it is skipped with an error naming it, and left in the table to fix or delete.
     */
    private fun applicableOverrides(): Map<String, String> =
        repository.findAll().mapNotNull { override ->
            val value = override.propValue ?: ""
            val error = registry.bindingError(override.propKey, value)
            if (error != null) {
                logger.error(
                    "Ignoring the config override {}={}: {}. Fix or delete it under Configuration",
                    override.propKey, if (override.sensitive) "***" else value, error,
                )
                null
            } else {
                override.propKey to value
            }
        }.toMap()

    /** Last resort for an override [applicableOverrides] can't check: start without any. */
    private fun applyOrDropAll(propertySource: DatabasePropertySource, overrides: Map<String, String>) {
        propertySource.replaceAll(overrides)
        try {
            contextRefresher.refreshEnvironment()
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            logger.error("Applying the database config overrides failed; starting without them", e)
            propertySource.replaceAll(emptyMap())
            contextRefresher.refreshEnvironment()
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
