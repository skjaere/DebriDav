package io.skjaere.debridav

import io.milton.http.Range
import io.skjaere.debridav.debrid.model.CachedFile
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import org.apache.catalina.connector.ClientAbortException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.io.FileNotFoundException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URI

@Service
class StreamingService {
    companion object {
        val OK_RESPONSE_RANGE = 200..299
    }

    private val logger = LoggerFactory.getLogger(StreamingService::class.java)

    suspend fun streamDebridLink(
        debridLink: CachedFile,
        range: Range?,
        fileSize: Long,
        outputStream: OutputStream
    ): Result {
        val connection = openConnection(debridLink.link!!)
        range?.let {
            val byteRange = getByteRange(range, fileSize)
            logger.debug("applying byterange: {}  from {}", byteRange, range)
            connection.setRequestProperty("Range", byteRange)
        }
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
            logger.error("Error encountered while streaming", it)
            emit(mapExceptionToResult(it))
        }.first()
    }

    private fun mapExceptionToResult(e: Throwable): Result {
        return when (e) {
            is ClientAbortException -> Result.OK
            is FileNotFoundException -> Result.DEAD_LINK
            else -> Result.ERROR
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

    fun openConnection(link: String): HttpURLConnection {
        return URI(link).toURL().openConnection() as HttpURLConnection
    }

    enum class Result {
        DEAD_LINK, ERROR, OK
    }
}
