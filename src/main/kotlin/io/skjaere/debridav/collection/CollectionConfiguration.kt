package io.skjaere.debridav.collection

import io.ktor.client.HttpClient
import io.ktor.client.engine.apache5.Apache5
import io.skjaere.debridav.configuration.DebridavUserAgent
import io.skjaere.newznab.Newznab
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class CollectionConfiguration {
    /**
     * The indexer client's own Apache5 client (debridav has several engines on its classpath). Newznab closes only the
     * client it derives from this one, so the context closes this one. Not offered for injection by type: the other
     * HttpClient beans are.
     */
    @Bean(destroyMethod = "close", autowireCandidate = false)
    fun newznabHttpClient(): HttpClient = HttpClient(Apache5)

    /** Sends debridav's User-Agent. Closing it stops any request still running for a search nobody waits for. */
    @Bean(destroyMethod = "close")
    fun newznab(userAgent: DebridavUserAgent): Newznab = Newznab {
        this.userAgent = userAgent.value
        httpClient = newznabHttpClient()
    }
}
