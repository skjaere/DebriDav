package io.skjaere.debridav.debrid.client


import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.head
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpStatement
import io.ktor.http.HttpHeaders
import io.ktor.http.isSuccess
import io.milton.http.Range
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridClient
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.ratelimiter.NoLimitRateLimiter
import io.skjaere.debridav.ratelimiter.RateLimiter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import org.apache.commons.io.FileUtils
import org.slf4j.LoggerFactory

const val RETRIES = 3L

class DefaultStreamableLinkPreparer(
    override val httpClient: HttpClient,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val rateLimiter: RateLimiter,
) : StreamableLinkPreparable {
    private val logger = LoggerFactory.getLogger(RealDebridClient::class.java)

    constructor(httpClient: HttpClient, debridavConfigurationProperties: DebridavConfigurationProperties)
            : this(httpClient, debridavConfigurationProperties, NoLimitRateLimiter())

    @Suppress("MagicNumber")
    override suspend fun prepareStreamUrl(debridLink: CachedFile, range: Range?): HttpStatement {
        return rateLimiter.doWithRateLimit {
            httpClient.prepareGet(debridLink.link!!) {
                headers {
                    range?.let { range ->
                        getByteRange(range, debridLink.size!!)?.let { byteRange ->
                            logger.info(
                                "Applying byteRange $byteRange " +
                                        "for ${debridLink.link}" +
                                        " (${FileUtils.byteCountToDisplaySize(byteRange.getSize())}) "
                            )

                            append(HttpHeaders.Range, "bytes=${byteRange.start}-${byteRange.end}")
                        }
                    }
                }
                timeout {
                    requestTimeoutMillis = 20_000_000
                    socketTimeoutMillis = 10_000
                    connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds.toLong()
                }
            }
        }
    }

    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean = flow {
        rateLimiter.doWithRateLimit {
            emit(httpClient.head(debridLink.link!!).status.isSuccess())
        }
    }.retry(RETRIES)
        .first()
}
