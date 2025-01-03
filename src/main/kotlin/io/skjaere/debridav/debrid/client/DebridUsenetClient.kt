package io.skjaere.debridav.debrid.client

import io.skjaere.debridav.debrid.client.torbox.model.usenet.GetUsenetResponseListItemFile
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.addNzb.AddNzbResponse
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.downloadinfo.DownloadInfo
import io.skjaere.debridav.debrid.client.torbox.model.usenet.responses.requestdl.RequestDownloadLinkResponse
import io.skjaere.debridav.debrid.model.DebridFile
import io.skjaere.debridav.fs.DebridUsenetFileContents
import java.io.InputStream

interface DebridUsenetClient : DebridClient {
    suspend fun addNzb(inputStream: InputStream, fileName: String): AddNzbResponse

    suspend fun getDownloads(ids: List<Long>): Map<Long, DownloadInfo>

    suspend fun getStreamableLink(downloadId: Long, downloadFileId: String): RequestDownloadLinkResponse

    suspend fun isCached(debridUsenetFileContents: DebridUsenetFileContents): Boolean

    suspend fun getDownloadInfo(id: Long): DownloadInfo

    suspend fun getCachedFilesFromUsenetInfoListItem(
        listItemFile: GetUsenetResponseListItemFile,
        downloadId: Long
    ): DebridFile
}
