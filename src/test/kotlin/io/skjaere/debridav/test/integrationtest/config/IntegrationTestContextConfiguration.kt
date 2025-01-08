/*
package io.skjaere.debridav.test.integrationtest.config

import io.ktor.client.HttpClient
import io.mockk.mockk
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfiguration
import io.skjaere.debridav.debrid.model.CachedFile
import net.bramp.ffmpeg.FFprobe
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.servlet.server.ServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId


@Configuration
class IntegrationTestContextConfiguration {
    @Bean
    fun servletWebServerFactory(): ServletWebServerFactory {
        return TomcatServletWebServerFactory()
    }

    @Bean
    @Primary
    fun staticClock(): Clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

    @Bean
    fun realDebridClient(realDebridConfiguration: RealDebridConfiguration, httpClient: HttpClient): RealDebridClient {
        return RealDebridClientProxy(realDebridConfiguration, httpClient)
    }

    @Bean
    @Primary
    fun ffprobeMock(): FFprobe = mockk<FFprobe>()
}

class RealDebridClientProxy(
    realDebridConfiguration: RealDebridConfiguration,
    httpClient: HttpClient
) : RealDebridClient(realDebridConfiguration, httpClient) {
    final var realDebridClient: RealDebridClient? = null

    init {
        realDebridClient = RealDebridClient(realDebridConfiguration, httpClient)
    }

    override suspend fun isCached(key: String): Boolean {
        return realDebridClient!!.isCached(key)
    }

    override suspend fun getCachedFiles(magnet: String): List<CachedFile> {
        return realDebridClient!!.getCachedFiles(magnet)
    }
}
*/
