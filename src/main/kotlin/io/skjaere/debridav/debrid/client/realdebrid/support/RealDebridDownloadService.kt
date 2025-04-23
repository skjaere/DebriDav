package io.skjaere.debridav.debrid.client.realdebrid.support

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.accept
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.headers
import io.skjaere.debridav.debrid.client.realdebrid.RealDebridConfigurationProperties
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownload
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownloadEntity
import io.skjaere.debridav.debrid.client.realdebrid.model.RealDebridDownloadRepository
import io.skjaere.debridav.ratelimiter.TimeWindowRateLimiter
import io.skjaere.debridav.torrent.TorrentHash
import jakarta.transaction.Transactional
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression
import org.springframework.stereotype.Service

private const val BULK_SIZE = 100

@Service
@ConditionalOnExpression("#{'\${debridav.debrid-clients}'.contains('real_debrid')}")
class RealDebridDownloadService(
    private val realDebridDownloadRepository: RealDebridDownloadRepository,
    private val realDebridConfigurationProperties: RealDebridConfigurationProperties,
    private val httpClient: HttpClient,
    private val realDebridRateLimiter: TimeWindowRateLimiter
) {
    @Transactional
    fun syncDownloadsToDatabase(): Unit = runBlocking {
        realDebridDownloadRepository.deleteAll()
        getListOfDownloads().asSequence()
            .map { mapDownloadToRdtEntity(it) }
            .toList()
            .let { realDebridDownloadRepository.saveAll(it) }
    }

    suspend fun saveDownload(realDebridDownload: RealDebridDownload): RealDebridDownloadEntity {
        return updateDownloadValues(
            realDebridDownloadRepository.getByDownloadIdIgnoreCase(realDebridDownload.id) ?: RealDebridDownloadEntity(),
            realDebridDownload
        ).let { realDebridDownloadRepository.save(it) }
    }

    suspend fun getAllDownloadsForLinks(links: Set<String>): Set<RealDebridDownloadEntity> =
        realDebridDownloadRepository.findAllByLinkIsInIgnoreCase(links)

    fun getDownloadByHashAndFilenameAndSize(
        filename: String,
        size: Long,
        hash: TorrentHash
    ): RealDebridDownloadEntity? =
        realDebridDownloadRepository.getDownloadByHashAndFilenameAndSize(filename, size, hash.hash)

    fun deleteDownload(download: RealDebridDownloadEntity) {
        realDebridDownloadRepository.delete(download)
    }

    @Suppress("MagicNumber")
    suspend fun getListOfDownloads(): List<RealDebridDownload> {
        var offset = 0
        val bulkSize = BULK_SIZE
        val downloads = mutableListOf<RealDebridDownload>()
        var bulk: List<RealDebridDownload>
        do {
            bulk = getListOfDownloadsWithOffset(offset, bulkSize)
            downloads.addAll(bulk)
            offset += bulkSize
        } while (bulk.size == bulkSize)

        return downloads
    }

    private fun updateDownloadValues(
        realDebridDownloadEntity: RealDebridDownloadEntity,
        realDebridDownload: RealDebridDownload
    ): RealDebridDownloadEntity {
        val updated = mapDownloadToRdtEntity(realDebridDownload)
        updated.id = realDebridDownloadEntity.id
        return updated
    }

    @Suppress("MagicNumber")
    private suspend fun getListOfDownloadsWithOffset(offset: Int, numItems: Int): List<RealDebridDownload> {
        val resp = realDebridRateLimiter.doWithRateLimit {
            httpClient
                .get("${realDebridConfigurationProperties.baseUrl}/downloads") {
                    headers {
                        accept(ContentType.Application.Json)
                        bearerAuth(realDebridConfigurationProperties.apiKey)
                        contentType(ContentType.Application.FormUrlEncoded)
                    }
                    url {
                        parameters.append("limit", numItems.toString())
                        if (offset > 0) parameters.append("offset", offset.toString())
                    }
                }
        }
        if (resp.status.value == 204) return emptyList()
        return resp.body()
    }

    private fun mapDownloadToRdtEntity(download: RealDebridDownload): RealDebridDownloadEntity {
        val realDebridDownloadEntity = RealDebridDownloadEntity()
        realDebridDownloadEntity.downloadId = download.id
        realDebridDownloadEntity.filename = download.filename
        realDebridDownloadEntity.download = download.download
        realDebridDownloadEntity.link = download.link
        realDebridDownloadEntity.mimeType = download.mimeType
        realDebridDownloadEntity.fileSize = download.fileSize

        return realDebridDownloadEntity
    }
}
