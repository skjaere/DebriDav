package io.skjaere.debridav

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.skjaere.debridav.configuration.DebridavConfiguration
import kotlinx.serialization.json.Json
import net.bramp.ffmpeg.FFprobe
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.scheduling.annotation.EnableScheduling
import java.time.Clock

@Configuration
@ConfigurationPropertiesScan("io.skjaere.debridav")
@EnableScheduling
class Configuration {
    @Bean
    @Primary
    fun objectMapper(): ObjectMapper = jacksonObjectMapper()

    @Bean
    fun clock(): Clock = Clock.systemDefaultZone()

    @Bean
    fun httpClient(debridavConfiguration: DebridavConfiguration): HttpClient = HttpClient(CIO) {
        install(HttpTimeout) {
            connectTimeoutMillis = debridavConfiguration.connectTimeoutMilliseconds
            requestTimeoutMillis = debridavConfiguration.readTimeoutMilliseconds
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
    fun usenetConversionService(converters: List<Converter<*, *>>): DefaultConversionService {
        val conversionService = DefaultConversionService()
        converters.forEach { conversionService.addConverter(it) }
        return conversionService
    }

    @Bean
    fun ffprobe(): FFprobe = FFprobe("/usr/bin/ffprobe")
}
