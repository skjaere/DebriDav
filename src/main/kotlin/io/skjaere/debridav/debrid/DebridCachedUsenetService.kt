/*
package io.skjaere.debridav.debrid


import io.ktor.utils.io.errors.IOException
import io.skjaere.debridav.configuration.DebridavConfiguration
import io.skjaere.debridav.debrid.client.DebridCachedUsenetClient
import io.skjaere.debridav.debrid.client.model.ClientErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.GetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NetworkErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NotCachedGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.ProviderErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.SuccessfulGetCachedFilesResponse
import io.skjaere.debridav.debrid.model.CachedFile
import io.skjaere.debridav.debrid.model.ClientError
import io.skjaere.debridav.debrid.model.ClientErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.GeneralErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.IsCachedResult
import io.skjaere.debridav.debrid.model.MissingFile
import io.skjaere.debridav.debrid.model.NetworkError
import io.skjaere.debridav.debrid.model.ProviderError
import io.skjaere.debridav.debrid.model.ProviderErrorIsCachedResponse
import io.skjaere.debridav.debrid.model.SuccessfulIsCachedResult
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.fs.DebridCachedUsenetFileContents
import io.skjaere.debridav.fs.DebridProvider
import io.skjaere.debridav.sabnzbd.UsenetDownload
import io.skjaere.debridav.sabnzbd.UsenetDownloadStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant


@Service
@Suppress("TooManyFunctions")
class DebridCachedUsenetService(
    private val debridUsenetClients: List<DebridCachedUsenetClient>,
    private val debridavConfiguration: DebridavConfiguration,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(DebridCachedContentService::class.java)


    suspend fun addNzb(releaseName: String, category: String): List<DebridCachedUsenetFileContents> = coroutineScope {
        isCached(releaseName).let { isCachedResponse ->
            if (isCachedResponse.all { it is SuccessfulIsCachedResult && !it.isCached }) {
                listOf()
            } else {
                val usenetDownload = UsenetDownload()
                usenetDownload.debridProvider = DebridProvider.EASYNEWS
                usenetDownload.wasCached = true
                usenetDownload.name = releaseName
                usenetDownload.completed = true
                usenetDownload.status = UsenetDownloadStatus.COMPLETED


                getDebridProviderResponses(isCachedResponse, releaseName)
                    .getDebridFileContents(releaseName)
            }
        }
    }

    private suspend fun getDebridProviderResponses(
        isCachedResponse: List<IsCachedResult>,
        releaseName: String
    ): GetCachedFilesResponses = coroutineScope {
        isCachedResponse
            .filter { !(it is SuccessfulIsCachedResult && !it.isCached) }
            .map { isCachedResult ->
                async {
                    getCachedFilesResponseWithRetries(
                        releaseName,
                        debridUsenetClients.first()
                    )
                }
            }
            .awaitAll()
            .plus(
                isCachedResponse
                    .filter { (it is SuccessfulIsCachedResult && !it.isCached) }
                    .map { NotCachedGetCachedFilesResponse(it.debridProvider) }
            )

    }

    @Suppress("TooGenericExceptionCaught")
    fun isCached(releaseName: String): List<IsCachedResult> = runBlocking {
        debridavConfiguration.debridClients
            .map { debridProvider ->
                async {
                    try {
                        SuccessfulIsCachedResult(
                            debridUsenetClients.first().isCached(releaseName),
                            debridProvider
                        )
                    } catch (e: DebridError) {
                        mapIsCachedExceptionToError(releaseName, debridProvider, e)
                    } catch (e: Exception) {
                        logger.error("Unknown error", e)
                        GeneralErrorIsCachedResponse(e, debridProvider)
                    }
                }
            }.awaitAll()
    }

    private fun mapIsCachedExceptionToError(
        releaseName: String,
        debridProvider: DebridProvider,
        e: DebridError
    ): IsCachedResult {
        logger.error(
            "Received response: ${e.message} " +
                    "with status: ${e.statusCode} " +
                    "on endpoint: ${e.endpoint} " +
                    "while processing release :${releaseName}"
        )
        return when (e) {
            is DebridClientError -> ClientErrorIsCachedResponse(e, debridProvider)

            is DebridProviderError -> ProviderErrorIsCachedResponse(e, debridProvider)

            is UnknownDebridError -> GeneralErrorIsCachedResponse(e, debridProvider)
        }
    }


    fun GetCachedFilesResponses.getDebridFileContents(releaseName: String): List<DebridCachedUsenetFileContents> = this
        .getDistinctFiles()
        .map { filePath ->
            debridavConfiguration.debridClients.mapNotNull { provider ->
                this.getResponseByFileWithPathAndProvider(filePath, provider)
            }
        }.map { cachedFiles ->
            createDebridFileContents(cachedFiles, releaseName)
        }.toList()

    fun GetCachedFilesResponses.getDistinctFiles(): List<String> = this
        .flatMap { it.getCachedFiles() }
        .map { it.path }
        .distinct()

    fun GetCachedFilesResponses.getResponseByFileWithPathAndProvider(
        path: String,
        debridProvider: DebridProvider
    ): DebridFile? {
        return when (val response = this.first { it.debridProvider == debridProvider }) {
            is NotCachedGetCachedFilesResponse -> MissingFile(debridProvider, Instant.now(clock).toEpochMilli())
            is ProviderErrorGetCachedFilesResponse -> ProviderError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )

            is NetworkErrorGetCachedFilesResponse -> NetworkError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )

            is SuccessfulGetCachedFilesResponse -> response.getCachedFiles()
                .firstOrNull { it.path.split("/").last() == path.split("/").last() }

            is ClientErrorGetCachedFilesResponse -> ClientError(
                debridProvider,
                Instant.now(clock).toEpochMilli()
            )
        }
    }

    private fun createDebridFileContents(
        cachedFiles: List<DebridFile>,
        releaseName: String
    ) = DebridCachedUsenetFileContents(
        originalPath = cachedFiles.first { it is CachedFile }.let { (it as CachedFile).path },
        size = cachedFiles.first { it is CachedFile }.let { (it as CachedFile).size },
        modified = Instant.now(clock).toEpochMilli(),
        releaseName = releaseName,
        debridLinks = cachedFiles.toMutableList(),
        id = null,
        mimeType = cachedFiles.firstOrNull { it is CachedFile }.let { (it as CachedFile).mimeType },
    )

    suspend fun getCachedFiles(
        magnet: String,
        debridClients: List<DebridCachedUsenetClient>
    ): Flow<GetCachedFilesResponse> = coroutineScope {
        debridClients
            .map { provider ->
                async { getCachedFilesResponseWithRetries(magnet, provider) }
            }.awaitAll()
            .asFlow()
    }

    private suspend fun getCachedFilesResponseWithRetries(
        releaseName: String,
        debridClient: DebridCachedUsenetClient
    ) = tryGetCachedFiles(debridClient, releaseName)
        .retry(debridavConfiguration.retriesOnProviderError) { e ->
            (e.isRetryable()).also { if (it) delay(debridavConfiguration.delayBetweenRetries.toMillis()) }
        }.catch { e ->
            logger.error("error getting cached files from ${debridClient.getProvider()}", e)
            when (e) {
                is DebridProviderError -> emit(ProviderErrorGetCachedFilesResponse(debridClient.getProvider()))
                is DebridClientError -> emit(ClientErrorGetCachedFilesResponse(debridClient.getProvider()))
                is UnknownDebridError -> emit(ProviderErrorGetCachedFilesResponse(debridClient.getProvider()))
                is IOException -> emit(NetworkErrorGetCachedFilesResponse(debridClient.getProvider()))
                else -> emit(NetworkErrorGetCachedFilesResponse(debridClient.getProvider()))
            }
        }.first()

    private fun Throwable.isRetryable(): Boolean {
        return when (this) {
            is IOException -> true
            is DebridProviderError -> true
            else -> false
        }
    }

    private suspend fun tryGetCachedFiles(
        debridClient: DebridCachedUsenetClient,
        magnet: String
    ): Flow<GetCachedFilesResponse> = flow {
        debridClient.getCachedFiles(magnet).let {
            if (it.isEmpty()) {
                emit(NotCachedGetCachedFilesResponse(debridClient.getProvider()))
            } else {
                emit(SuccessfulGetCachedFilesResponse(it, debridClient.getProvider()))
            }
        }
    }
}
*/
