package io.skjaere.debridav

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import net.bramp.ffmpeg.FFprobe
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.core.convert.converter.Converter
import org.springframework.core.convert.support.DefaultConversionService
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
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
    fun usenetConversionService(converters: List<Converter<*, *>>): DefaultConversionService {
        val conversionService = DefaultConversionService()
        converters.forEach { conversionService.addConverter(it) }
        return conversionService
    }

    @Bean
    fun ffprobe(): FFprobe = FFprobe("/usr/bin/ffprobe")

    @Bean
    fun threadPoolTaskExecutor(): ThreadPoolTaskExecutor {
        val tpte = ThreadPoolTaskExecutor()
        tpte.corePoolSize = 4
        tpte.initialize()
        return tpte
    }
}
