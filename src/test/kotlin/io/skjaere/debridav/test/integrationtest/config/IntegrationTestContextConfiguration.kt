package io.skjaere.debridav.test.integrationtest.config

import io.ktor.client.HttpClient
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfiguration
import io.skjaere.debridav.fs.CachedFile
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

    /*    @Bean
        @ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
        @Primary
        fun realDebridClient(
            realDebridConfiguration: RealDebridConfiguration,
            httpClient: HttpClient,
            realDebridTorrentsService: RealDebridTorrentsService,
            realDebridDownloadsService: RealDebridDownloadService,
        ): RealDebridClient {
            return RealDebridClientProxy(
                realDebridConfiguration,
                httpClient,
                realDebridTorrentsService,
                realDebridDownloadsService
            )
        }*/
}

class RealDebridClientProxy(
    realDebridConfiguration: RealDebridConfiguration,
    httpClient: HttpClient,
    debridavConfigurationProperties: DebridavConfigurationProperties
) : RealDebridClient(
    realDebridConfiguration,
    httpClient,
    debridavConfigurationProperties
) {
    final var realDebridClient: RealDebridClient? = null

    init {
        realDebridClient =
            RealDebridClient(
                realDebridConfiguration,
                httpClient,
                debridavConfigurationProperties
            )
    }

    override suspend fun isCached(magnet: String): Boolean {
        return realDebridClient!!.isCached(magnet)
    }

    override suspend fun getCachedFiles(magnet: String, params: Map<String, String>): List<CachedFile> {
        return realDebridClient!!.getCachedFiles(magnet, emptyMap())
    }
}
