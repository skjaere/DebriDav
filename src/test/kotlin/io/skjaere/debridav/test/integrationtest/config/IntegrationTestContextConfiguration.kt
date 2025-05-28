package io.skjaere.debridav.test.integrationtest.config

import io.prometheus.metrics.model.registry.PrometheusRegistry
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory
import org.springframework.boot.web.servlet.server.ServletWebServerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

@Configuration
@EnableAutoConfiguration
class IntegrationTestContextConfiguration {
    @Bean
    fun servletWebServerFactory(): ServletWebServerFactory {
        return TomcatServletWebServerFactory()
    }

    @Bean
    @Primary
    fun staticClock(): Clock = Clock.fixed(Instant.EPOCH, ZoneId.systemDefault())

    @Bean
    fun prometheusRegistry() = PrometheusRegistry()
}

