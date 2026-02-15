package io.skjaere.debridav.usenet

import io.skjaere.debridav.repository.NzbDocumentRepository
import io.skjaere.debridav.usenet.nzb.NzbDocumentEntity
import io.skjaere.debridav.usenet.nzb.NzbFileJson
import io.skjaere.debridav.usenet.nzb.NzbSegmentJson
import io.skjaere.debridav.usenet.nzb.NzbStreamableFileEntity
import io.skjaere.nzbstreamer.NzbStreamer
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.stream.StreamableFile
import kotlinx.coroutines.runBlocking
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Service

@Service
@ConditionalOnBean(NzbStreamer::class)
class NzbImportService(
    private val nzbStreamer: NzbStreamer,
    private val nzbDocumentRepository: NzbDocumentRepository
) {
    @Async
    fun importNzb(nzbBytes: ByteArray): NzbDocumentEntity {
        val metadata = runBlocking { nzbStreamer.prepare(nzbBytes) }
        val streamableFiles = nzbStreamer.resolveStreamableFiles(metadata)
        val entity = toEntity(metadata.orderedArchiveNzb, streamableFiles)
        return nzbDocumentRepository.save(entity)
    }

    private fun toEntity(
        nzbDocument: NzbDocument,
        streamableFiles: List<StreamableFile>
    ): NzbDocumentEntity {
        val entity = NzbDocumentEntity()
        entity.files = nzbDocument.files.map { file ->
            NzbFileJson(
                yencSize = file.yencHeaders!!.size,
                yencPartEnd = file.yencHeaders!!.partEnd,
                segments = file.segments.map { segment ->
                    NzbSegmentJson(
                        articleId = segment.articleId,
                        number = segment.number,
                        bytes = segment.bytes
                    )
                }
            )
        }
        entity.streamableFiles = streamableFiles.map { sf ->
            NzbStreamableFileEntity().also {
                it.document = entity
                it.path = sf.path
                it.totalSize = sf.totalSize
                it.startVolumeIndex = sf.startVolumeIndex
                it.startOffsetInVolume = sf.startOffsetInVolume
                it.continuationHeaderSize = sf.continuationHeaderSize
                it.endOfArchiveSize = sf.endOfArchiveSize
            }
        }.toMutableList()
        return entity
    }
}
