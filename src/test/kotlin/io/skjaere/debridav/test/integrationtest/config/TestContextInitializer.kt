package io.skjaere.debridav.test.integrationtest.config

import org.apache.commons.io.FileUtils
import org.mockserver.integration.ClientAndServer
import org.mockserver.integration.ClientAndServer.startClientAndServer
import org.springframework.boot.test.util.TestPropertyValues
import org.springframework.context.ApplicationContextInitializer
import org.springframework.context.ApplicationListener
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.context.event.ContextClosedEvent
import org.springframework.test.util.TestSocketUtils
import java.io.File

class TestContextInitializer : ApplicationContextInitializer<ConfigurableApplicationContext> {
    companion object {
        const val BASE_PATH = "/tmp/debridavtests"
    }

    override fun initialize(applicationContext: ConfigurableApplicationContext) {
        val port = TestSocketUtils.findAvailableTcpPort()
        val mockServer: ClientAndServer = startClientAndServer(port)
        FileUtils.deleteDirectory(File(BASE_PATH))
        (applicationContext as ConfigurableApplicationContext).beanFactory.registerSingleton("mockServer",mockServer)
        applicationContext.addApplicationListener(
            ApplicationListener<ContextClosedEvent>() {
                mockServer.stop()
                FileUtils.deleteDirectory(File(BASE_PATH))
            }
        )
        TestPropertyValues.of(
            "premiumize.baseurl=http://localhost:$port/premiumize",
            "realdebrid.baseurl=http://localhost:$port/realdebrid",
            "mockserver.port=$port"
        ).applyTo(applicationContext)
    }
}
