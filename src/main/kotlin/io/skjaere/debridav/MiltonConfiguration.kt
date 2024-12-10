package io.skjaere.debridav

import io.milton.config.HttpManagerBuilder
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.DebridService
import io.skjaere.debridav.fs.FileService
import io.skjaere.debridav.resource.StreamableResourceFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class MiltonConfiguration {
    @Bean("milton.http.manager")
    fun httpManagerBuilder(
        fileService: FileService,
        debridService: DebridService,
        streamingService: StreamingService,
        debridavConfiguration: DebridavConfiguration
    ): HttpManagerBuilder {
        val builder = HttpManagerBuilder()
        builder.resourceFactory = StreamableResourceFactory(
            fileService,
            debridService,
            streamingService,
            debridavConfiguration
        )
        return builder
    }
}
