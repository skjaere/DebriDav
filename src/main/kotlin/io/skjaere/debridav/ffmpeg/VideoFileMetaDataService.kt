package io.skjaere.debridav.ffmpeg

import com.google.gson.JsonSyntaxException
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.fs.DatabaseFileService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.bramp.ffmpeg.FFprobe
import org.slf4j.LoggerFactory
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.stereotype.Service
import java.util.concurrent.TimeUnit

@Service
class VideoFileMetaDataService(
    private val ffprobe: FFprobe,
    private val debridavConfiguration: DebridavConfiguration
) {
    private val logger = LoggerFactory.getLogger(DatabaseFileService::class.java)
    private val threadPool = ThreadPoolTaskExecutor()

    init {
        threadPool.corePoolSize = 8
        threadPool.initialize()
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    suspend fun getMetadataFromUrl(path: String): VideMetaData = withContext(Dispatchers.IO) {
        try {
            logger.info("validating $path")
            val deferred = threadPool.submit { ffprobe.probe("${debridavConfiguration.mountPath}$path") }
            deferred.get(60, TimeUnit.SECONDS)
            logger.info("$path was successfully validated")
            VideMetaData.Success
        } catch (e: JsonSyntaxException) { //https://github.com/bramp/ffmpeg-cli-wrapper/issues/357
            VideMetaData.Success
        } catch (e: Exception) {
            logger.warn("$path failed validation with ${e.javaClass.simpleName}")
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
