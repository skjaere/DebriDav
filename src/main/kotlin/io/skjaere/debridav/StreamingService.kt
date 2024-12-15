package io.skjaere.debridav

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.timeout
import io.ktor.client.request.headers
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.skjaere.debridav.debrid.client.DebridClient
import io.skjaere.debridav.debrid.model.CachedFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retry
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

@Service
class StreamingService(
    private val throttlingService: ThrottlingService,
    private val httpClient: HttpClient,
    private val debridClients: List<DebridClient>
) {
    companion object {
        val OK_RESPONSE_RANGE = 200..299
        const val STREAMING_TIMEOUT_MS = 20_000_000L
    }

    private val logger = LoggerFactory.getLogger(StreamingService::class.java)

    @Suppress("SwallowedException", "MagicNumber")
    suspend fun streamDebridLink(
        debridLink: CachedFile,
        range: Range?,
        fileSize: Long,
        outputStream: OutputStream
    ): Result {
        return flow {
            throttlingService.throttle(
                "${debridLink.provider}-link-stream",
            ) {
                try {
                    httpClient.prepareGet(debridLink.link) {
                        headers {
                            range?.let {
                                append(HttpHeaders.Range, getByteRange(range, fileSize))
                            }
                        }
                        timeout {
                            requestTimeoutMillis = STREAMING_TIMEOUT_MS
                        }
                    }.execute(tryPipeResponse(debridLink, outputStream))
                } catch (e: ClientAbortException) {
                    emit(Result.OK)
                }
            }
        }
            .retry(3) { e -> shouldRetryStreaming(e) }
            .catch {
                outputStream.close()
                emit(mapExceptionToResult(it))
            }
            .onEach {
                logger.debug("Streaming of {} complete", debridLink.path.split("/").last())
                logger.debug("Streaming result of {} was {}", debridLink.path, it)
            }
            .first()
    }

    private fun FlowCollector<Result>.tryPipeResponse(
        debridLink: CachedFile,
        outputStream: OutputStream
    ): suspend (response: HttpResponse) -> Unit = { resp ->
        if (resp.status == HttpStatusCode.TooManyRequests) {
            val waitMs =
                debridClients.first { it.getProvider() == debridLink.provider }.getMsToWaitFrom429Response(resp)

            throttlingService.openCircuitBreaker("${debridLink.provider}-link-stream", waitMs)
            throw RateLimitException()

        }
        if (resp.status.value.isNotOk()) {
            logger.error(
                "Got response: ${resp.status.value} from $debridLink with body: ${
                    resp.body<String>()
                }"
            )
            throw LinkNotFoundException()
        }
        resp.body<ByteReadChannel>().toInputStream().use { inputStream ->
            outputStream.use { usableOutputStream ->
                logger.info("Begin streaming of {}", debridLink.path)
                inputStream.transferTo(usableOutputStream)
                logger.info("done streaming{}", debridLink.path)
                emit(Result.OK)
            }
        }
    }

    @Suppress("MagicNumber")
    private suspend fun shouldRetryStreaming(e: Throwable) = when (e) {
        is RateLimitException -> {
            logger.debug("Got 429. Retrying")
            true
        }

        is LinkNotFoundException -> true

        is ClientAbortException -> false

        is IOException -> {
            logger.warn("Encountered an IO exception", e)
            delay(10_000)
            true
        }

        else -> false
    }

    private fun mapExceptionToResult(e: Throwable): Result {
        return when (e) {
            is ClientAbortException -> {
                logger.debug("Client aborted the stream", e)
                Result.OK
            }

            is FileNotFoundException -> Result.DEAD_LINK
            else -> {
                logger.error("Error encountered while streaming", e)
                Result.ERROR
            }
        }
    }

    private fun getByteRange(range: Range, fileSize: Long): String {
        val start = range.start ?: 0
        val finish = range.finish ?: fileSize
        val byteRange = "bytes=$start-$finish"
        return byteRange
    }

    fun Int.isOkResponse() = this in OK_RESPONSE_RANGE
    fun Int.isNotOk() = !this.isOkResponse()

    enum class Result {
        DEAD_LINK, ERROR, OK
    }

    class RateLimitException : Exception()
    class LinkNotFoundException : Exception()
}
