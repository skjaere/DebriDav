package io.skjaere.debridav

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.milton.servlet.SpringMiltonFilter
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import kotlinx.serialization.json.Json
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.core.task.TaskExecutor
import org.springframework.core.task.VirtualThreadTaskExecutor
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@ConfigurationPropertiesScan("io.skjaere.debridav")
@EnableScheduling
@EnableAsync
class DebridavConfiguration {
    @Bean
    fun miltonFilterFilterRegistrationBean(): FilterRegistrationBean<SpringMiltonFilter> {
        val registration = FilterRegistrationBean(SpringMiltonFilter())
        registration.setName("MiltonFilter")
        registration.addUrlPatterns("/*")
        registration.addInitParameter("milton.exclude.paths", "/files,/api,/version,/sabnzbd,/actuator")
        registration.addInitParameter(
            "resource.factory.class", "io.skjaere.debrid.resource.StreamableResourceFactory"
        )
        registration.addInitParameter(
            "controllerPackagesToScan", "io.skjaere.debrid"
        )
        registration.addInitParameter("contextConfigClass", "io.skjaere.debridav.MiltonConfiguration")

        return registration
    }


    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun httpClient(debridavConfigurationProperties: DebridavConfigurationProperties): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds
        }
        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }
            )
        }
    }

    @Bean
    fun virtualThreadTaskExecutor(): TaskExecutor = VirtualThreadTaskExecutor("debridav-")

    @Bean
    fun usenetConversionService(converters: List<Converter<*, *>>): DefaultConversionService {
        val conversionService = DefaultConversionService()
        converters.forEach { conversionService.addConverter(it) }
        return conversionService
    }
}
