package io.skjaere.debridav

import io.milton.http.Range
import io.skjaere.debridav.debrid.client.DebridClient
import io.skjaere.debridav.debrid.client.easynews.EasynewsConfigurationProperties
import io.skjaere.debridav.debrid.model.CachedFile
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.*

@Service
class StreamingService(
    private val debridClients: List<DebridClient>,
    private val easynewsConfiguration: EasynewsConfigurationProperties
) {
    companion object {
        val OK_RESPONSE_RANGE = 200..299
    }

    private val logger = LoggerFactory.getLogger(StreamingService::class.java)

    private fun getBasicAuth(): String =
        "${easynewsConfiguration.username}:${easynewsConfiguration.password}".let {
            "Basic ${Base64.getEncoder().encodeToString(it.toByteArray())}"
        }

    @Suppress("SwallowedException", "MagicNumber")
    suspend fun streamDebridLink(
        debridLink: CachedFile,
        range: Range?,
        fileSize: Long,
        outputStream: OutputStream
    ): Result {
        val connection = openConnection(debridLink.link)
        range?.let {
            getByteRange(range, fileSize)?.let { bytes ->
                logger.debug("applying byterange: {}  from {}", bytes, range)
                connection.setRequestProperty("Range", bytes)
            }
        }
        connection.setRequestProperty("Authorization", getBasicAuth())

        return flow {
            if (connection.responseCode.isNotOk()) {
                logger.error(
                    "Got response: ${connection.responseCode} from $debridLink with body: ${
                        connection.inputStream?.bufferedReader()?.readText() ?: ""
                    }"
                )
                emit(Result.DEAD_LINK)
            }
            connection.inputStream.use { inputStream ->
                logger.debug("Begin streaming of {}", debridLink)
                inputStream.transferTo(outputStream)
                logger.debug("Streaming of {} complete", debridLink)
                emit(Result.OK)
            }
        }.catch {
            emit(mapExceptionToResult(it))
        }.first()
    }

    fun openConnection(link: String): HttpURLConnection {
        return URI(link).toURL().openConnection() as HttpURLConnection
    }

    fun getByteRange(range: Range, fileSize: Long): String? {
        val start = range.start ?: 0
        val finish = range.finish ?: (fileSize - 1)
        return if (start == 0L && finish == (fileSize - 1)) {
            null
        } else "bytes=$start-$finish"
    }

    /*private fun FlowCollector<Result>.tryPipeResponse(
        debridLink: CachedFile,
        outputStream: OutputStream
    ): suspend (response: HttpResponse) -> Unit = { resp ->
        if (resp.status.value.isNotOk()) {
            logger.error(
                "Got response: ${resp.status.value} from $debridLink with body: ${
                    resp.body<String>()
                }"
            )
            throw LinkNotFoundException()
        }
        resp.bodyAsChannel().toInputStream().use { inputStream ->
            outputStream.use { usableOutputStream ->
                logger.info("Begin streaming of {}", debridLink.path)
                withContext(Dispatchers.IO) {
                    inputStream.transferTo(usableOutputStream)
                }
                logger.info("done streaming{}", debridLink.path)
                emit(Result.OK)
            }
        }
    }*/

    @Suppress("MagicNumber")
    private suspend fun shouldRetryStreaming(e: Throwable) = when (e) {
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

    fun Int.isOkResponse() = this in OK_RESPONSE_RANGE
    fun Int.isNotOk() = !this.isOkResponse()

    enum class Result {
        DEAD_LINK, ERROR, OK
    }

    class LinkNotFoundException : Exception()
}
