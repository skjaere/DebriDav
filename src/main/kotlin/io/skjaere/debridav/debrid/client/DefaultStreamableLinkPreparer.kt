package io.skjaere.debridav.debrid.client


import io.github.resilience4j.kotlin.ratelimiter.executeSuspendFunction
import io.github.resilience4j.ratelimiter.RateLimiter
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
    private val userAgent: String?
) : StreamableLinkPreparable {
    private val logger = LoggerFactory.getLogger(RealDebridClient::class.java)

    constructor(
        httpClient: HttpClient,
        debridavConfigurationProperties: DebridavConfigurationProperties,
        rateLimiter: RateLimiter
    ) : this(httpClient, debridavConfigurationProperties, rateLimiter, null)

    @Suppress("MagicNumber")
    override suspend fun prepareStreamUrl(debridLink: CachedFile, range: Range?): HttpStatement {
        return rateLimiter.executeSuspendFunction {
            httpClient.prepareGet(debridLink.link!!) {
                headers {
                    range?.let { range ->
                        getByteRange(range, debridLink.size!!)?.let { byteRange ->
                            logger.info(
                                "Applying byteRange $byteRange " +
                                        "for ${debridLink.link}" +
                                        " (${FileUtils.byteCountToDisplaySize(byteRange.getSize())}) "
                            )

                            if (!(range.start == 0L && range.finish == debridLink.size)) {
                                append(HttpHeaders.Range, "bytes=${byteRange.start}-${byteRange.end}")
                            }
                        }
                        userAgent?.let {
                            append(HttpHeaders.UserAgent, it)
                        }
                    }
                }
                timeout {
                    requestTimeoutMillis = 20_000_000
                    socketTimeoutMillis = 10_000
                    connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds
                }
            }
        }
    }

    override fun getStreamParams(
        debridLink: CachedFile,
        range: Range?
    ): StreamHttpParams {
        val headers = mutableMapOf<String, String>()
        range?.let { range ->
            getByteRange(range, debridLink.size!!)?.let { byteRange ->
                headers[HttpHeaders.Range] = "bytes=${byteRange.start}-${byteRange.end}"
            }
        }
        headers[HttpHeaders.UserAgent] = userAgent ?: "DebridAV/0.9.2 (https://github.com/skjaere/DebridAV)"

        return StreamHttpParams(
            headers,
            StreamHttpParams.Timeouts(
                requestTimeoutMillis = 20_000_000,
                socketTimeoutMillis = 10_000,
                connectTimeoutMillis = debridavConfigurationProperties.connectTimeoutMilliseconds
            )
        )
    }


    override suspend fun isLinkAlive(debridLink: CachedFile): Boolean = flow {
        rateLimiter.executeSuspendFunction {
            emit(httpClient.head(debridLink.link!!).status.isSuccess())
        }
    }.retry(RETRIES)
        .first()
}
