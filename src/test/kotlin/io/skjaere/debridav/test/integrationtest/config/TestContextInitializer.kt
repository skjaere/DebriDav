package io.skjaere.debridav.test.integrationtest.config

import io.skjaere.mocknntp.testcontainer.MockNntpServerContainer
import org.apache.commons.io.FileUtils
import org.mockserver.configuration.Configuration
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.slf4j.event.Level
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.test.util.TestSocketUtils
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.io.File

class TestContextInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    companion object {
        const val BASE_PATH = "/tmp/debridavtests"

        val postgreSQLContainer: PostgreSQLContainer =
            PostgreSQLContainer(DockerImageName.parse("postgres:16-alpine"))
                .withUsername("postgres")
                .withPassword("postgres")
                .withDatabaseName("debridav")
                // Each cached Spring context holds a Hikari pool open; with ~12 IT
                // contexts and the default max_connections=100, the suite hits
                // "FATAL: sorry, too many clients already". 300 buys plenty of headroom.
                .withCommand("postgres", "-c", "max_connections=300")
        val mockNntpServerContainer: MockNntpServerContainer = MockNntpServerContainer()
    }

    init {
        postgreSQLContainer.start()
        mockNntpServerContainer.start()
    }

    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val port = TestSocketUtils.findAvailableTcpPort()
        val mockserverConfig = Configuration.configuration().logLevel(Level.ERROR)
        val mockServer: ClientAndServer = startClientAndServer(mockserverConfig, port)
        FileUtils.deleteDirectory(File(BASE_PATH))
        applicationContext.beanFactory.registerSingleton("mockServer", mockServer)
        applicationContext.beanFactory.registerSingleton("mockNntpServerContainer", mockNntpServerContainer)
        applicationContext.addApplicationListener(
            ApplicationListener<ContextClosedEvent>() {
                mockServer.stop()
                FileUtils.deleteDirectory(File(BASE_PATH))
            }
        )
        val dbUrl = postgreSQLContainer.jdbcUrl
        val dbUsername = "postgres"
        val dbPassword = "postgres"
        TestPropertyValues.of(
            "premiumize.baseurl=http://localhost:$port/premiumize",
            "realdebrid.baseurl=http://localhost:$port/realdebrid",
            "sonarr.host=localhost",
            "sonarr.port=$port",
            "sonarr.api-base-path=/sonarr/api/v3",
            "radarr.host=localhost",
            "radarr.port=$port",
            "radarr.api-base-path=/radarr/api/v3",
            "mockserver.port=$port",
            "spring.datasource.url=$dbUrl",
            "spring.datasource.username=$dbUsername",
            "spring.datasource.password=$dbPassword",
            "easynews.api-base-url=http://localhost:$port/easynews",
        ).applyTo(applicationContext)
    }
}
