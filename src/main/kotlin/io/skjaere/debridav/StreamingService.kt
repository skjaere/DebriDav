package io.skjaere.debridav

import io.ktor.client.call.body
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.milton.http.Range
import io.skjaere.debridav.debrid.DebridClient
import io.skjaere.debridav.debrid.model.CachedFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.withContext
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.io.IOException
import java.io.OutputStream

@Service
class StreamingService(
    private val debridClients: List<DebridClient>
) {
    private val logger = LoggerFactory.getLogger(StreamingService::class.java)

    @Suppress("SwallowedException", "MagicNumber")
    suspend fun streamDebridLink(
        debridLink: CachedFile,
        range: Range?,
        outputStream: OutputStream
    ): Result {
        val debridClient = debridClients.first { it.getProvider() == debridLink.provider }
        return flow {
            try {
                debridClient
                    .prepareStreamUrl(debridLink, range)
                    .execute(tryPipeResponse(debridLink, outputStream))
            } catch (e: ClientAbortException) {
                emit(Result.OK)
            }

        }.retryWhen { cause, attempt ->
            if (attempt <= 5 && shouldRetryStreaming(cause)) {
                delay(5_000 * attempt)
                true
            } else false
        }.catch {
            outputStream.close()
            emit(mapExceptionToResult(it))
        }.onEach {
            logger.debug("Streaming of {} complete", debridLink.path.split("/").last())
            logger.debug("Streaming result of {} was {}", debridLink.path, it)
        }.first()
    }

    private fun FlowCollector<Result>.tryPipeResponse(
        debridLink: CachedFile,
        outputStream: OutputStream
    ): suspend (response: HttpResponse) -> Unit = { resp ->
        if (!resp.status.isSuccess()) {
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
                emit(Result.OK)
            }
        }
    }

    @Suppress("MagicNumber")
    private suspend fun shouldRetryStreaming(e: Throwable) = when (e) {
        is LinkNotFoundException -> true

        is ClientAbortException -> {
            false
        }

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

    enum class Result {
        DEAD_LINK, ERROR, OK
    }

    class LinkNotFoundException : Exception()
}
