package io.skjaere.debridav

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.skjaere.debridav.configuration.DebridavConfiguration
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class ApiHttpClient {
    val lock = Mutex()
    private val logger = LoggerFactory.getLogger(ApiHttpClient::class.java)

    @Bean
    fun httpClient(debridavConfiguration: DebridavConfiguration): HttpClient {
        val client = HttpClient(CIO) {
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
        client.plugin(HttpSend).intercept { request ->
            if (lock.isLocked) {
                do {
                    delay(500L)
                } while (lock.isLocked)
            }
            val originalCall = execute(request)
            if (originalCall.response.status == HttpStatusCode.TooManyRequests) {
                var result = originalCall
                lock.withLock {
                    do {
                        val waitMs = (result.response.headers["x-ratelimit-after"]?.toLong() ?: 1L) * 1000L
                        logger.info("Waiting $waitMs milliseconds...")
                        delay(waitMs)
                        result = execute(request)
                    } while (result.response.status == HttpStatusCode.TooManyRequests)
                }
                result
            } else originalCall
        }
        return client
    }
}
