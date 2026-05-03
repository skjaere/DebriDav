package io.skjaere.debridav.usenet.nzb

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType
import io.skjaere.compressionutils.RarFileEntry
import io.skjaere.compressionutils.SevenZipFileEntry
import io.skjaere.compressionutils.SplitInfo
import io.skjaere.nntp.YencHeaders
import io.skjaere.nzbstreamer.metadata.ExtractedMetadata
import io.skjaere.nzbstreamer.nzb.NzbDocument
import io.skjaere.nzbstreamer.nzb.NzbFile
import io.skjaere.nzbstreamer.nzb.NzbSegment
import io.skjaere.nzbstreamer.stream.StreamableFile
import jakarta.persistence.CascadeType
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.FetchType
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.OneToMany
import jakarta.persistence.OrderBy
import jakarta.persistence.Table
import org.hibernate.annotations.Type
import java.time.Instant

@Entity
@Table(name = "nzb_document")
open class NzbDocumentEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    open var id: Long? = null

    /**
     * Per-file metadata + segment lists, normalized into [NzbFileEntity] +
     * [NzbSegmentEntity]. Replaces the old `files` JSONB column (V30 backfilled the
     * relational rows and dropped it). Lazy by default so paths that only touch
     * timestamps/name/category (the sweep, the repair handler) don't pull MB of
     * segment data into the Hibernate session — the big memory + autovacuum win
     * versus the JSONB shape, where every `last_verified` UPDATE rewrote the whole
     * row.
     *
     * Cascade.ALL + orphanRemoval mirrors the DB-level ON DELETE CASCADE so
     * replacing the list in the import path drops old children atomically.
     */
    @OneToMany(
        mappedBy = "document",
        cascade = [CascadeType.ALL],
        orphanRemoval = true,
        fetch = FetchType.LAZY,
    )
    @OrderBy("fileIndex ASC")
    open var files: MutableList<NzbFileEntity> = mutableListOf()

    @Type(JsonBinaryType::class)
    @Column(name = "streamable_files", columnDefinition = "jsonb", nullable = false)
    open var streamableFiles: List<StreamableFileJson> = emptyList()

    @Enumerated(EnumType.STRING)
    @Column(name = "archive_type", nullable = false)
    open var archiveType: NzbArchiveType = NzbArchiveType.RAW

    @Column(name = "last_verified")
    open var lastVerified: Instant? = null

    @Column(name = "health_check_enqueued_at")
    open var healthCheckEnqueuedAt: Instant? = null

    @Column(name = "category")
    open var category: String? = null

    @Column(name = "name")
    open var name: String? = null

    @Column(name = "download_id")
    open var downloadId: String? = null
}

enum class NzbArchiveType {
    RAW,
    RAR,
    SEVEN_ZIP,
    RAR_IN_SEVEN_ZIP,
    RAR_IN_RAR,
    SEVEN_ZIP_IN_RAR,
    SEVEN_ZIP_IN_SEVEN_ZIP;

    companion object {
        fun from(metadata: ExtractedMetadata): NzbArchiveType = when (metadata) {
            is ExtractedMetadata.Raw -> RAW
            is ExtractedMetadata.Archive -> when {
                metadata.entries.any { it is RarFileEntry } -> RAR
                metadata.entries.any { it is SevenZipFileEntry } -> SEVEN_ZIP
                else -> RAW
            }
            is ExtractedMetadata.NestedArchive -> nestedType(metadata)
        }

        private val RAR_EXTENSIONS = setOf("rar", "r00", "r01", "s00", "s01", "part01.rar", "part001.rar")
        private val SEVEN_ZIP_EXTENSIONS = setOf("7z", "7z.001", "7z.002")

        private fun looksLikeRarPath(path: String): Boolean {
            val lower = path.lowercase()
            return RAR_EXTENSIONS.any { lower.endsWith(".$it") } ||
                    Regex("""\.(r|s)\d{2,3}$""").containsMatchIn(lower) ||
                    Regex("""\.part\d+\.rar$""").containsMatchIn(lower)
        }

        private fun looksLike7zPath(path: String): Boolean {
            val lower = path.lowercase()
            return SEVEN_ZIP_EXTENSIONS.any { lower.endsWith(".$it") } ||
                    Regex("""\.7z\.\d{3}$""").containsMatchIn(lower)
        }

        private fun nestedType(metadata: ExtractedMetadata.NestedArchive): NzbArchiveType {
            val outerIsRar = metadata.outerEntries.any { it is RarFileEntry }
            val outerIs7z = metadata.outerEntries.any { it is SevenZipFileEntry }

            // Inner entries are always TranslatedFileEntry after nested archive processing,
            // so we infer the inner archive type from the archive-extension filenames
            // present in the outer entries (those are the inner archive volumes).
            val innerVolumeNames = metadata.outerEntries
                .filter { !it.isDirectory }
                .map { it.path }
            val innerIsRar = innerVolumeNames.any { looksLikeRarPath(it) }
            val innerIs7z = innerVolumeNames.any { looksLike7zPath(it) }

            return when {
                outerIs7z && innerIsRar -> RAR_IN_SEVEN_ZIP
                outerIs7z && innerIs7z -> SEVEN_ZIP_IN_SEVEN_ZIP
                outerIsRar && innerIsRar -> RAR_IN_RAR
                outerIsRar && innerIs7z -> SEVEN_ZIP_IN_RAR
                else -> RAW
            }
        }
    }
}

data class StreamableFileJson(
    val path: String,
    val totalSize: Long,
    val startVolumeIndex: Int,
    val startOffsetInVolume: Long,
    val continuationHeaderSize: Long,
    val endOfArchiveSize: Long,
    val preComputedSplits: List<SplitInfoJson>? = null
) : java.io.Serializable

data class SplitInfoJson(
    val volumeIndex: Int,
    val dataStartPosition: Long,
    val dataSize: Long
) : java.io.Serializable

/**
 * Materialise the streaming model from the relational shape. Touching `files` on a
 * lazy entity triggers Hibernate's collection fetch, and touching `segments` per file
 * triggers another — so callers MUST invoke this from inside a transaction whose
 * fetch plan resolves both eagerly (e.g. via a `JOIN FETCH` query) to avoid an
 * N+1 over hundreds of files. See [NzbDocumentRepository.findByIdWithFiles].
 */
fun NzbDocumentEntity.toNzbDocument(): NzbDocument = NzbDocument(
    files = files.map { file ->
        NzbFile(
            poster = "",
            date = 0,
            subject = "",
            groups = emptyList(),
            segments = file.segments.map { seg ->
                NzbSegment(
                    articleId = seg.articleId,
                    number = seg.segmentNumber,
                    bytes = seg.bytes
                )
            },
            yencHeaders = YencHeaders(
                line = 128,
                size = file.yencSize,
                name = "",
                partEnd = file.yencPartEnd
            )
        )
    }
)

fun StreamableFileJson.toStreamableFile(): StreamableFile = StreamableFile(
    path = path,
    totalSize = totalSize,
    startVolumeIndex = startVolumeIndex,
    startOffsetInVolume = startOffsetInVolume,
    continuationHeaderSize = continuationHeaderSize,
    endOfArchiveSize = endOfArchiveSize,
    preComputedSplits = preComputedSplits?.map {
        SplitInfo(
            volumeIndex = it.volumeIndex,
            dataStartPosition = it.dataStartPosition,
            dataSize = it.dataSize
        )
    }
)

fun NzbDocumentEntity.findStreamableFile(path: String): StreamableFile? =
    streamableFiles.firstOrNull { it.path == path }?.toStreamableFile()
