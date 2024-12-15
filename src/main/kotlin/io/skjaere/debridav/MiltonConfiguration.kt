package io.skjaere.debridav

import io.milton.config.HttpManagerBuilder
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.resource.StreamableResourceFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.convert.ConversionService

@Configuration
class MiltonConfiguration {
    @Bean("milton.http.manager")
    fun httpManagerBuilder(
        fileService: FileService,
        debridLinkService: DebridLinkService,
        streamingService: StreamingService,
        debridavConfiguration: DebridavConfiguration,
        usenetConversionService: ConversionService
    ): HttpManagerBuilder {
        val builder = HttpManagerBuilder()
        builder.resourceFactory = StreamableResourceFactory(
            fileService,
            debridLinkService,
            streamingService,
            debridavConfiguration,
            usenetConversionService
        )
        return builder
    }
}
