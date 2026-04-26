package io.skjaere.debridav.config

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
    @Lazy private val configOverrideService: ConfigOverrideService
) : ApplicationListener<ApplicationReadyEvent> {

    private val logger = LoggerFactory.getLogger(DatabasePropertySourceInitializer::class.java)

    override fun onApplicationEvent(event: ApplicationReadyEvent) {
        val propertySource = getOrCreatePropertySource()
        val overrides = repository.findAll().associate { it.propKey to (it.propValue ?: "") }
        if (overrides.isNotEmpty()) {
            propertySource.replaceAll(overrides)
            contextRefresher.refreshEnvironment()
            logger.info("Loaded {} database config override(s) and refreshed environment", overrides.size)
            configOverrideService.syncRunningNntpPools()
        } else {
            logger.info("No database config overrides found")
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
