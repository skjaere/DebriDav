package io.skjaere.debridav.ffmpeg

import com.google.gson.JsonSyntaxException
import io.skjaere.debridav.fs.DatabaseFileService
import kotlin.time.measureTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bramp.ffmpeg.FFprobe
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class VideoFileMetaDataService(
    private val ffprobe: FFprobe
) {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun getMetadataFromUrl(path: String): VideMetaData = withContext(Dispatchers.IO) {
        try {
            //TODO: add timeout
            val took = measureTime {
                ffprobe.probe("/data$path")
            }
            logger.info("Validation of $path took $took")
            VideMetaData.Success

        } catch (e: JsonSyntaxException) { //https://github.com/bramp/ffmpeg-cli-wrapper/issues/357
            VideMetaData.Success
        } catch (e: Exception) {
            VideMetaData.Error("$path failed validation: ${e.message}")
        }
    }
}

sealed interface VideMetaData {
    data object Success : VideMetaData

    class Error(
        val errorMessage: String,
    ) : VideMetaData
}
