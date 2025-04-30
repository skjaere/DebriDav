package io.skjaere.debridav.debrid.client.torbox

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.plugin
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

private const val LOCK_WAIT_POLL_TIME = 500L

private const val ONE_SECOND = 1000L

@Configuration
class TorBoxHttpClientConfiguration {
    val lock = Mutex()
    private val logger = LoggerFactory.getLogger(TorBoxHttpClientConfiguration::class.java)

    @Bean
    @ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('torbox')}")
    fun torboxHttpClient(): HttpClient {
        val client = HttpClient(CIO) {
            install(ContentNegotiation) {
                json(
                    Json {
                        prettyPrint = true
                        isLenient = true
                        ignoreUnknownKeys = true
                    }
                )
            }
            install(HttpTimeout)
        }

        client.plugin(HttpSend).intercept { request ->
            if (lock.isLocked) {
                do {
                    delay(LOCK_WAIT_POLL_TIME)
                } while (lock.isLocked)
            }
            val originalCall = execute(request)
            if (originalCall.response.status == HttpStatusCode.TooManyRequests) {
                var result = originalCall
                lock.withLock {
                    do {
                        val waitMs = (result.response.headers["x-ratelimit-after"]?.toLong() ?: 1L) * ONE_SECOND
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
