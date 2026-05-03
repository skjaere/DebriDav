package io.skjaere.debridav

import io.milton.config.HttpManagerBuilder
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.DebridLinkService
import io.skjaere.debridav.fs.DatabaseFileService
import com.vdsirotkin.pgmq.PgmqClient
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.resource.StreamableResourceFactory
import io.skjaere.debridav.stream.StreamingService
import io.skjaere.nzbstreamer.NzbStreamer
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
        fileService: DatabaseFileService,
        debridService: DebridLinkService,
        streamingService: StreamingService,
        debridavConfigurationProperties: DebridavConfigurationProperties,
        localContentsService: LocalContentsService,
        nzbStreamer: NzbStreamer?,
        pgmqClient: PgmqClient?,
        nzbDocumentLoader: io.skjaere.debridav.usenet.nzb.NzbDocumentLoader?,
    ): StreamableResourceFactory = StreamableResourceFactory(
        fileService,
        debridService,
        streamingService,
        debridavConfigurationProperties,
        localContentsService,
        nzbStreamer,
        pgmqClient,
        nzbDocumentLoader,
    )
}
