package io.skjaere.debridav

import io.milton.config.HttpManagerBuilder
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.resource.StreamableResourceFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MiltonConfiguration {

    @Bean("milton.http.manager")
    fun httpManagerBuilder(
        resourceFactory: StreamableResourceFactory
    ): HttpManagerBuilder {
        val builder = HttpManagerBuilder()
        builder.resourceFactory = resourceFactory
        return builder
    }

    @Bean
    fun resourceFactory(
        fileService: FileService,
        debridService: DebridLinkService,
        streamingService: StreamingService,
        debridavConfiguration: DebridavConfiguration
    ): StreamableResourceFactory = StreamableResourceFactory(
        fileService,
        debridService,
        streamingService,
        debridavConfiguration
    )
}
