package io.skjaere.debridav.cache

import io.skjaere.debridav.fs.CachedFile
import org.springframework.stereotype.Component

@Component
class StreamPlanningService {

    fun generatePlan(
        chunks: List<FileChunk>, range: LongRange, cachedFile: CachedFile
    ): StreamPlan {
        val sequence = StreamPlan(mutableListOf())

        /*return StreamPlan(
            mutableListOf(
                StreamSource.Remote(LongRange(0, cachedFile.size!! - 1L), cachedFile)
            )
        )*/
        while (!sequence.getTotalRange().contains(range)) {
            val lastPlannedByte = sequence.getLastByte() ?: range.start
            chunks.getLargestRangeWithByte(lastPlannedByte + 1)?.let { nextCachedChunk ->
                sequence.sources.add(
                    StreamSource.Cached(
                        LongRange(
                            if (lastPlannedByte == range.start) lastPlannedByte else lastPlannedByte + 1,
                            smallest(nextCachedChunk.endByte!!, range.endInclusive)
                        ), nextCachedChunk
                    )
                )
            } ?: run {
                chunks.getFirstChunkWithByteAfter(lastPlannedByte + 1)?.let { nextCachedChunkAfter ->
                    sequence.sources.add(
                        StreamSource.Remote(
                            LongRange(
                                if (lastPlannedByte == range.start) lastPlannedByte else lastPlannedByte + 1,
                                nextCachedChunkAfter.startByte!! - 1
                            ), cachedFile
                        )
                    )
                } ?: run {
                    sequence.sources.add(
                        StreamSource.Remote(
                            LongRange(
                                if (lastPlannedByte == range.start) lastPlannedByte else lastPlannedByte + 1,
                                range.endInclusive
                            ), cachedFile
                        )
                    )
                }
            }
        }
        return sequence
    }

    fun LongRange.contains(other: LongRange): Boolean {
        return this.start <= other.start && this.endInclusive >= other.endInclusive
    }

    fun List<FileChunk>.getLargestRangeWithByte(byteNo: Long): FileChunk? =
        this.filter { it.getRange().contains(byteNo) }.maxByOrNull { it.getRange().last - byteNo }

    fun List<FileChunk>.getFirstChunkWithByteAfter(byteNo: Long): FileChunk? =
        this.filter { it.startByte!! >= byteNo }.minByOrNull { it.getRange().start }

    sealed interface StreamSource {
        val range: LongRange

        data class Cached(override val range: LongRange, val fileChunk: FileChunk) : StreamSource
        data class Remote(override val range: LongRange, val cachedFile: CachedFile) : StreamSource
    }

    private fun smallest(first: Long, second: Long): Long {
        return listOf(first, second).minOf { it }
    }

    data class StreamPlan(
        val sources: MutableList<StreamSource>,
    ) {
        fun getTotalRange(): LongRange =
            LongRange(sources.minOfOrNull { it.range.first } ?: 0, sources.maxOfOrNull { it.range.last } ?: 0)

        fun getLastByte(): Long? = sources.maxOfOrNull { it.range.last }
    }
}