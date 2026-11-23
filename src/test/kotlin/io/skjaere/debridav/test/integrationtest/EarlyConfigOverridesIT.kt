package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.config.ConfigOverride
import io.skjaere.debridav.config.ConfigOverrideRepository
import io.skjaere.debridav.config.DatabaseOverridesEnvironmentPostProcessor
import io.skjaere.debridav.config.DatabasePropertySource
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.`is`
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.SpringApplication
import org.springframework.boot.logging.DeferredLogFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.time.Instant

/**
 * The config overrides saved from the UI are applied before the application context is created,
 * so settings read only at startup take them too. Runs the loader against the test database with
 * an environment like the one it gets at startup.
 */
@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["debridav.debrid-clients=premiumize"]
)
@MockServerTest
class EarlyConfigOverridesIT {

    @Autowired
    lateinit var configOverrideRepository: ConfigOverrideRepository

    @Value("\${spring.datasource.url}")
    lateinit var url: String

    @Value("\${spring.datasource.username}")
    lateinit var username: String

    @Value("\${spring.datasource.password}")
    lateinit var password: String

    private val loader = DatabaseOverridesEnvironmentPostProcessor(DeferredLogFactory { it.get() })

    @AfterEach
    fun tearDown() = configOverrideRepository.deleteAll()

    @Test
    fun `saved overrides are applied before startup, except ones that can't bind`() {
        //given
        configOverrideRepository.save(override("sonarr.integration-enabled", "true"))
        configOverrideRepository.save(override("health-check.torrent-poll-rate", "PT1M"))
        configOverrideRepository.save(override("debridav.local-entity-max-size-mb", "1.5"))

        //when
        val environment = startupEnvironment(url)
        loader.postProcessEnvironment(environment, SpringApplication())

        //then
        assertThat(environment.getProperty("sonarr.integration-enabled"), `is`("true"))
        assertThat(environment.getProperty("health-check.torrent-poll-rate"), `is`("PT1M"))
        assertThat("an Int of 1.5", environment.getProperty("debridav.local-entity-max-size-mb"), nullValue())
    }

    @Test
    fun `startup goes on without early overrides when the table or the database isn't there`() {
        // First start: Flyway hasn't created config_override yet.
        val noTable = startupEnvironment(url.replaceAfterLast("/", "postgres"))
        loader.postProcessEnvironment(noTable, SpringApplication())
        assertThat(noTable.propertySources.get(DatabasePropertySource.NAME), nullValue())

        val unreachable = startupEnvironment("jdbc:postgresql://localhost:1/debridav")
        loader.postProcessEnvironment(unreachable, SpringApplication())
        assertThat(unreachable.propertySources.get(DatabasePropertySource.NAME), nullValue())
    }

    @Test
    fun `nothing is read when early loading is turned off`() {
        configOverrideRepository.save(override("sonarr.integration-enabled", "true"))
        val environment =
            startupEnvironment(url, mapOf(DatabaseOverridesEnvironmentPostProcessor.ENABLED_KEY to "false"))

        loader.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty("sonarr.integration-enabled"), nullValue())
    }

    private fun startupEnvironment(url: String, extra: Map<String, Any> = emptyMap()) = StandardEnvironment().apply {
        propertySources.addFirst(
            MapPropertySource(
                "startup",
                mapOf<String, Any>(
                    "spring.datasource.url" to url,
                    "spring.datasource.username" to username,
                    "spring.datasource.password" to password,
                ) + extra
            )
        )
    }

    private fun override(key: String, value: String) = ConfigOverride().apply {
        propKey = key
        propValue = value
        createdAt = Instant.now()
        updatedAt = Instant.now()
    }
}
