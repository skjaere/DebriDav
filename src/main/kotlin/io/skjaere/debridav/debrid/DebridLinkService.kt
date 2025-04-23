package io.skjaere.debridav.debrid

import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.debrid.client.DebridCachedContentClient
import io.skjaere.debridav.debrid.client.model.ClientErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.GetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NetworkErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.NotCachedGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.ProviderErrorGetCachedFilesResponse
import io.skjaere.debridav.debrid.client.model.SuccessfulGetCachedFilesResponse
import io.skjaere.debridav.debrid.model.DebridClientError
import io.skjaere.debridav.debrid.model.DebridError
import io.skjaere.debridav.debrid.model.DebridProviderError
import io.skjaere.debridav.debrid.model.UnknownDebridError
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.ClientError
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.DebridCachedTorrentContent
import io.skjaere.debridav.fs.DebridCachedUsenetReleaseContent
import io.skjaere.debridav.fs.DebridFile
import io.skjaere.debridav.fs.DebridFileContents
import io.skjaere.debridav.fs.MissingFile
import io.skjaere.debridav.fs.NetworkError
import io.skjaere.debridav.fs.ProviderError
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retry
import kotlinx.coroutines.flow.transformWhile
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

const val RETRIES = 3L

@Service
@Suppress("LongParameterList")
class DebridLinkService(
    private val debridCachedContentService: DebridCachedContentService,
    private val fileService: DatabaseFileService,
    private val debridavConfigurationProperties: DebridavConfigurationProperties,
    private val debridClients: List<DebridCachedContentClient>,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(DebridLinkService::class.java)

    suspend fun getCheckedLinks(file: RemotelyCachedEntity): Flow<CachedFile> {
        val debridFileContents = file.contents!!
        return getFlowOfDebridLinks(debridFileContents)
            .retry(RETRIES)
            .catch { e ->
                logger.error("Uncaught exception encountered while getting links", e)
                if (e is DebridError) emit(mapExceptionToDebridFile(e))
            }
            .transformWhile { debridLink ->
                if (debridLink !is NetworkError) {
                    updateContentsOfDebridFile(file, debridFileContents, debridLink)
                }
                if (debridLink is CachedFile) {
                    emit(debridLink)
                }
                debridLink !is CachedFile
            }
    }

    private fun mapExceptionToDebridFile(e: DebridError): DebridFile {
        when (e) {
            is DebridClientError -> ClientError()
            is DebridProviderError -> ProviderError()
            is UnknownDebridError -> io.skjaere.debridav.fs.UnknownError()
        }
    }

    private suspend fun getFlowOfDebridLinks(debridFileContents: DebridFileContents): Flow<DebridFile> = flow {
        debridavConfigurationProperties.debridClients
            .map { debridClients.getClient(it) }
            .map { debridClient ->
                debridFileContents.debridLinks
                    .firstOrNull { it.provider == debridClient.getProvider() }
                    ?.let { debridFile ->
                        emitDebridFile(
                            debridFile,
                            debridFileContents,
                            debridClient.getProvider()
                        )
                    } ?: run { emit(getFreshDebridLink(debridFileContents, debridClient)) }
            }
    }

    private suspend fun getFreshDebridLink(
        debridFileContents: DebridFileContents,
        debridClient: DebridCachedContentClient
    ): DebridFile {
        val key = when (debridFileContents) {
            is DebridCachedTorrentContent -> TorrentMagnet(debridFileContents.magnet!!)
            is DebridCachedUsenetReleaseContent -> UsenetRelease(debridFileContents.releaseName!!)
            else -> error("Unknown DebridFileContents: ${debridFileContents.javaClass.simpleName}")
        }
        return debridFileContents.debridLinks
            .firstOrNull { it.provider == debridClient.getProvider() }
            ?.let { debridFile ->
                if (debridFile is CachedFile) {
                    debridClient.getStreamableLink(key, debridFile)
                        ?.let { link ->
                            debridFile.link = link
                            debridFile
                        }
                } else null
            } ?: run {
            if (debridClient.isCached(key)) {
                return debridCachedContentService.getCachedFiles(key, listOf(debridClient))
                    .map { response ->
                        mapResponseToDebridFile(response, debridFileContents, debridClient)
                    }.first()
            } else {
                MissingFile(debridClient.getProvider(), Instant.now(clock).toEpochMilli())
            }
        }
    }

    private fun mapResponseToDebridFile(
        response: GetCachedFilesResponse,
        debridFileContents: DebridFileContents,
        debridClient: DebridCachedContentClient
    ): DebridFile {
        return when (response) {
            is SuccessfulGetCachedFilesResponse -> {
                if (response.getCachedFiles().size == 1) {
                    response.getCachedFiles().first()
                } else {
                    response.getCachedFiles()
                        .firstOrNull { fileMatches(it, debridFileContents) }
                        ?: run {
                            logger.warn(
                                "Could not match any file in response ${response.getCachedFiles()} " +
                                        "from ${response.debridProvider} to ${debridFileContents.originalPath}"
                            )
                            MissingFile(debridClient.getProvider(), Instant.now(clock).toEpochMilli())
                        }
                }
            }

            is ProviderErrorGetCachedFilesResponse -> ProviderError(
                debridClient.getProvider(),
                Instant.now(clock).toEpochMilli()
            )

            is NotCachedGetCachedFilesResponse -> MissingFile(
                debridClient.getProvider(),
                Instant.now(clock).toEpochMilli()
            )

            is NetworkErrorGetCachedFilesResponse -> NetworkError(
                debridClient.getProvider(),
                Instant.now(clock).toEpochMilli()
            )

            is ClientErrorGetCachedFilesResponse -> ClientError(
                debridClient.getProvider(),
                Instant.now(clock).toEpochMilli(),
            )
        }
    }

    private suspend fun FlowCollector<DebridFile>.emitDebridFile(
        debridFile: DebridFile,
        debridFileContents: DebridFileContents,
        debridProvider: DebridProvider
    ) {
        when (debridFile) {
            is CachedFile -> emitWorkingLink(debridFile, debridFileContents, debridProvider)
            is MissingFile -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)
            is ProviderError -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)
            is ClientError -> emitRefreshedResult(debridFile, debridFileContents, debridProvider)
            is NetworkError -> emit(
                NetworkError(
                    debridProvider,
                    Instant.now(clock).toEpochMilli()
                )
            )

            is io.skjaere.debridav.fs.UnknownError -> emit(
                io.skjaere.debridav.fs.UnknownError(
                    debridProvider,
                    Instant.now(clock).toEpochMilli()
                )
            )
        }
    }

    private suspend fun FlowCollector<DebridFile>.emitRefreshedResult(
        debridFile: DebridFile,
        debridFileContents: DebridFileContents,
        debridProvider: DebridProvider
    ) {
        if (linkShouldBeReChecked(debridFile)) {
            emit(getFreshDebridLink(debridFileContents, debridClients.getClient(debridProvider)))
        } else {
            emit(debridFile)
        }
    }

    private suspend fun FlowCollector<DebridFile>.emitWorkingLink(
        debridFile: CachedFile,
        debridFileContents: DebridFileContents,
        debridProvider: DebridProvider
    ) {
        if (debridClients
                .first { it.getProvider() == debridProvider }
                .isLinkAlive(debridFile)
        ) {
            emit(debridFile)
        } else {
            emit(getFreshDebridLink(debridFileContents, debridClients.getClient(debridProvider)))
        }
    }

    private fun updateContentsOfDebridFile(
        file: RemotelyCachedEntity,
        debridFileContents: DebridFileContents,
        debridLink: DebridFile
    ) {
        debridFileContents.replaceOrAddDebridLink(debridLink)
        fileService.writeDebridFileContentsToFile(file, debridFileContents)
    }

    private fun fileMatches(
        it: CachedFile,
        debridFileContents: DebridFileContents
    ) = it.path!!.normalize().split("/").last() == debridFileContents.originalPath!!.normalize().split("/").last()
            || it.size == debridFileContents.size!!
            || debridFileContents.originalPath!!.contains(it.path!!)
            || it.path!!.contains(debridFileContents.originalPath!!)

    fun String.normalize() = this.replace(" ", "").replace(".", "")
    private fun linkShouldBeReChecked(debridFile: DebridFile): Boolean {
        return when (debridFile) {
            is MissingFile -> debridavConfigurationProperties.waitAfterMissing
            is ProviderError -> debridavConfigurationProperties.waitAfterProviderError
            is NetworkError -> debridavConfigurationProperties.waitAfterNetworkError
            is ClientError -> debridavConfigurationProperties.waitAfterClientError
            is CachedFile -> error("should never happen")
            else -> error("Unknown type ${debridFile.javaClass.simpleName}")
        }.let {
            return Instant.ofEpochMilli(debridFile.lastChecked!!)
                .isBefore(Instant.now(clock).minus(it))
        }
    }

    fun List<DebridCachedContentClient>.getClient(debridProvider: DebridProvider): DebridCachedContentClient =
        this.first { it.getProvider() == debridProvider }

}
