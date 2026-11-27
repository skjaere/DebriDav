package io.skjaere.debridav.collection.hls

/** A file read by range: a release streamed from Usenet, or a file on disk. */
interface ByteSource {
    val size: Long

    /** Up to [length] bytes from [offset]; fewer at the end of the file. */
    suspend fun read(offset: Long, length: Int): ByteArray
}

/**
 * Where a Matroska file's video keyframes are, in seconds, and how long it lasts: what its HLS segments are cut at. Read
 * from its Cues with a few range reads (its head, then the Cues the Seek Head points to), never the whole file. A file
 * without Cues, or that isn't Matroska, has none: it can't be cut into segments without reading all of it.
 */
data class MatroskaIndex(val keyframes: List<Double>, val durationSeconds: Double) {
    companion object {
        suspend fun read(source: ByteSource): MatroskaIndex? = EbmlReader(source).index()
    }
}

/** An element in a buffer: its [id] (marker bits kept, as the spec writes ids), where its data starts, its size. */
private class Element(val id: Long, val start: Int, val dataStart: Int, val size: Long) {
    val known: Boolean get() = size >= 0
    val end: Long get() = dataStart + size
}

@Suppress("MagicNumber")
private class EbmlReader(private val source: ByteSource) {
    private var timecodeScale = DEFAULT_TIMECODE_SCALE
    private var duration: Double? = null
    private var videoTrack: Long? = null
    private val seeks = mutableMapOf<Long, Long>()

    @Suppress("ReturnCount") // A parser that gives up at the first part the file doesn't have: guard returns read best.
    suspend fun index(): MatroskaIndex? {
        val head = source.read(0, HEAD_BYTES)
        val segment = segmentIn(head) ?: return null
        val segmentStart = segment.dataStart.toLong()
        val cuesInHead = scanTopLevel(head, segment.dataStart)
        if (duration == null || videoTrack == null) readAt(segmentStart, INFO, ::readInfo)
        if (videoTrack == null) readAt(segmentStart, TRACKS, ::readTracks)
        val cuesPosition = cuesInHead ?: seeks[CUES]?.let { segmentStart + it } ?: return null
        val cues = readElement(cuesPosition)?.takeIf { (buffer, element) -> element.id == CUES && buffer.isNotEmpty() }
            ?: return null
        val ticks = cueTimes(cues.first, cues.second)
        val keyframes = ticks.distinct().sorted().map { it.toDouble() * timecodeScale / NANOS }
        if (keyframes.isEmpty()) return null
        val seconds = duration?.let { it * timecodeScale / NANOS } ?: keyframes.last()
        return MatroskaIndex(keyframes, seconds)
    }

    /** The Segment, after the EBML header at the start of the file; none if the file isn't Matroska. */
    private fun segmentIn(head: ByteArray): Element? {
        val ebml = element(head, 0)?.takeIf { it.id == EBML && it.known && it.end <= head.size } ?: return null
        return element(head, ebml.end.toInt())?.takeIf { it.id == SEGMENT }
    }

    /** The Segment's children in [head]: the Seek Head, Info and Tracks read; where the Cues start, if they're here. */
    private fun scanTopLevel(head: ByteArray, from: Int): Long? {
        var cues: Long? = null
        var child = element(head, from)
        while (child != null) {
            val whole = child.known && child.end <= head.size
            when (child.id) {
                SEEK_HEAD -> if (whole) readSeekHead(head, child)
                INFO -> if (whole) readInfo(head, child)
                TRACKS -> if (whole) readTracks(head, child)
                CUES -> cues = child.start.toLong()
            }
            // What comes after a child that runs past the head isn't in it. Nor is its end an Int: a size is the
            // file's to say, up to 2^56, and wrapped it could point back here, round and round.
            val last = child.id == CLUSTER || !whole
            child = if (last) null else element(head, child.end.toInt())
        }
        return cues
    }

    /** Reads the element the Seek Head says [id] is, if it does, and hands it to [read]. */
    private suspend fun readAt(segmentStart: Long, id: Long, read: (ByteArray, Element) -> Unit) {
        val at = seeks[id] ?: return
        readElement(segmentStart + at)?.let { (buffer, element) -> if (element.id == id) read(buffer, element) }
    }

    /** The element at [position], whole: its bytes from its header on, and the element in them. */
    @Suppress("ReturnCount") // A parser that gives up at the first part the file doesn't have: guard returns read best.
    private suspend fun readElement(position: Long): Pair<ByteArray, Element>? {
        // A position is the file's to say: it may point anywhere, even before the file.
        if (position !in 0 until source.size) return null
        val header = source.read(position, MAX_HEADER_BYTES)
        val element = element(header, 0)?.takeIf { it.known && it.end <= MAX_ELEMENT_BYTES } ?: return null
        val bytes = source.read(position, element.end.toInt())
        return if (bytes.size.toLong() < element.end) null else bytes to element
    }

    private fun readSeekHead(buffer: ByteArray, seekHead: Element) {
        children(buffer, seekHead).filter { it.id == SEEK }.forEach { seek ->
            var id: Long? = null
            var position: Long? = null
            children(buffer, seek).forEach {
                when (it.id) {
                    SEEK_ID -> id = uint(buffer, it)
                    SEEK_POSITION -> position = uint(buffer, it)
                }
            }
            val (seekId, seekPosition) = id to position
            if (seekId != null && seekPosition != null) seeks.putIfAbsent(seekId, seekPosition)
        }
    }

    private fun readInfo(buffer: ByteArray, info: Element) {
        children(buffer, info).forEach {
            when (it.id) {
                TIMECODE_SCALE -> timecodeScale = uint(buffer, it)
                DURATION -> duration = float(buffer, it)
            }
        }
    }

    private fun readTracks(buffer: ByteArray, tracks: Element) {
        children(buffer, tracks).filter { it.id == TRACK_ENTRY }.forEach { entry ->
            var number: Long? = null
            var type: Long? = null
            children(buffer, entry).forEach {
                when (it.id) {
                    TRACK_NUMBER -> number = uint(buffer, it)
                    TRACK_TYPE -> type = uint(buffer, it)
                }
            }
            if (type == VIDEO_TRACK && videoTrack == null) videoTrack = number
        }
    }

    /** The times of the cue points that point into the video track (all of them, when that isn't known). */
    private fun cueTimes(buffer: ByteArray, cues: Element): List<Long> =
        children(buffer, cues).filter { it.id == CUE_POINT }.mapNotNull { point ->
            var time: Long? = null
            val tracks = mutableListOf<Long>()
            children(buffer, point).forEach { child ->
                when (child.id) {
                    CUE_TIME -> time = uint(buffer, child)
                    CUE_TRACK_POSITIONS ->
                        children(buffer, child).filter { it.id == CUE_TRACK }.forEach { tracks += uint(buffer, it) }
                }
            }
            time.takeIf { videoTrack == null || videoTrack in tracks }
        }.toList()

    private fun children(buffer: ByteArray, parent: Element): Sequence<Element> = sequence {
        var position = parent.dataStart
        val end = minOf(parent.end, buffer.size.toLong()).toInt()
        while (position < end) {
            val child = element(buffer, position)?.takeIf { it.known && it.end <= end } ?: break
            yield(child)
            position = child.end.toInt()
        }
    }

    @Suppress("ReturnCount") // A parser that gives up at the first part the file doesn't have: guard returns read best.
    private fun element(buffer: ByteArray, position: Int): Element? {
        val idLength = vintLength(buffer, position) ?: return null
        val sizeAt = position + idLength
        val sizeLength = vintLength(buffer, sizeAt) ?: return null
        if (sizeAt + sizeLength > buffer.size) return null
        var id = 0L
        for (i in 0 until idLength) id = (id shl 8) or (buffer[position + i].toLong() and 0xFF)
        val marker = 0x80 ushr (sizeLength - 1)
        var size = (buffer[sizeAt].toLong() and 0xFF) and (marker - 1).toLong()
        var allOnes = size == (marker - 1).toLong()
        for (i in 1 until sizeLength) {
            val byte = buffer[sizeAt + i].toLong() and 0xFF
            allOnes = allOnes && byte == 0xFFL
            size = (size shl 8) or byte
        }
        return Element(id, position, sizeAt + sizeLength, if (allOnes) UNKNOWN_SIZE else size)
    }

    /** How many bytes the variable-length integer at [position] takes: one more than its first byte's leading zeros. */
    @Suppress("ReturnCount") // A parser that gives up at the first part the file doesn't have: guard returns read best.
    private fun vintLength(buffer: ByteArray, position: Int): Int? {
        if (position >= buffer.size) return null
        val first = buffer[position].toInt() and 0xFF
        if (first == 0) return null
        return Integer.numberOfLeadingZeros(first) - (Int.SIZE_BITS - Byte.SIZE_BITS) + 1
    }

    private fun uint(buffer: ByteArray, element: Element): Long {
        var value = 0L
        for (i in 0 until element.size.toInt()) {
            value = (value shl 8) or (buffer[element.dataStart + i].toLong() and 0xFF)
        }
        return value
    }

    private fun float(buffer: ByteArray, element: Element): Double? {
        val bits = uint(buffer, element)
        return when (element.size) {
            4L -> java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
            8L -> java.lang.Double.longBitsToDouble(bits)
            else -> null
        }
    }

    private companion object {
        const val HEAD_BYTES = 256 * 1024
        const val MAX_HEADER_BYTES = 12
        /** Cues of even a very long file are a few MB; anything far bigger is not Cues. */
        const val MAX_ELEMENT_BYTES = 64L * 1024 * 1024
        const val UNKNOWN_SIZE = -1L
        const val DEFAULT_TIMECODE_SCALE = 1_000_000L
        const val NANOS = 1e9
        const val VIDEO_TRACK = 1L

        const val EBML = 0x1A45DFA3L
        const val SEGMENT = 0x18538067L
        const val SEEK_HEAD = 0x114D9B74L
        const val SEEK = 0x4DBBL
        const val SEEK_ID = 0x53ABL
        const val SEEK_POSITION = 0x53ACL
        const val INFO = 0x1549A966L
        const val TIMECODE_SCALE = 0x2AD7B1L
        const val DURATION = 0x4489L
        const val TRACKS = 0x1654AE6BL
        const val TRACK_ENTRY = 0xAEL
        const val TRACK_NUMBER = 0xD7L
        const val TRACK_TYPE = 0x83L
        const val CUES = 0x1C53BB6BL
        const val CUE_POINT = 0xBBL
        const val CUE_TIME = 0xB3L
        const val CUE_TRACK_POSITIONS = 0xB7L
        const val CUE_TRACK = 0xF7L
        const val CLUSTER = 0x1F43B675L
    }
}
