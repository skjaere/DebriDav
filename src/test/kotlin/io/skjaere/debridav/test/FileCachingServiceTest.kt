package io.skjaere.debridav.test

import io.skjaere.debridav.cache.FileChunk
import io.skjaere.debridav.cache.StreamPlanningService
import io.skjaere.debridav.fs.Blob
import io.skjaere.debridav.fs.CachedFile
import io.skjaere.debridav.fs.RemotelyCachedEntity
import kotlin.test.assertEquals
import org.hibernate.engine.jdbc.BlobProxy
import org.junit.jupiter.api.Test

class FileCachingServiceTest {
    private val underTest = StreamPlanningService()

    @Test
    fun `that plan generation works`() {
        //given
        val cachedFile = CachedFile()
        val cachedChunks = listOf(
            getFileChunk(10, 20),
            getFileChunk(30, 50),
            getFileChunk(70, 100)
        )

        //when
        val result = underTest.generatePlan(cachedChunks, LongRange(0, 200), cachedFile)

        assertEquals(LongRange(0, 9), result.sources[0].range)
        assert(result.sources[0] is StreamPlanningService.StreamSource.Remote)
        assertEquals(LongRange(10, 20), result.sources[1].range)
        assert(result.sources[1] is StreamPlanningService.StreamSource.Cached)
        assertEquals(LongRange(21, 29), result.sources[2].range)
        assert(result.sources[2] is StreamPlanningService.StreamSource.Remote)
        assertEquals(LongRange(30, 50), result.sources[3].range)
        assert(result.sources[3] is StreamPlanningService.StreamSource.Cached)
        assertEquals(LongRange(51, 69), result.sources[4].range)
        assert(result.sources[4] is StreamPlanningService.StreamSource.Remote)
        assertEquals(LongRange(70, 100), result.sources[5].range)
        assert(result.sources[5] is StreamPlanningService.StreamSource.Cached)
        assertEquals(LongRange(101, 200), result.sources[6].range)
        assert(result.sources[6] is StreamPlanningService.StreamSource.Remote)
    }

    @Test
    fun `that plan generation works with partial overlaps`() {
        //given
        val cachedFile = CachedFile()
        val cachedChunks = listOf(
            getFileChunk(0, 100),
            getFileChunk(150, 300)
        )

        //when
        val result = underTest.generatePlan(cachedChunks, LongRange(75, 200), cachedFile)

        assertEquals(LongRange(75, 100), result.sources[0].range)
        assert(result.sources[0] is StreamPlanningService.StreamSource.Cached)
        assertEquals(LongRange(101, 149), result.sources[1].range)
        assert(result.sources[1] is StreamPlanningService.StreamSource.Remote)
        assertEquals(LongRange(150, 200), result.sources[2].range)
        assert(result.sources[2] is StreamPlanningService.StreamSource.Cached)

    }

    @Test
    fun `that plan generation works with no cached chunks`() {
        //given
        val cachedFile = CachedFile()
        val cachedChunks = listOf<FileChunk>()

        //when
        val result = underTest.generatePlan(cachedChunks, LongRange(0, 200), cachedFile)
        assertEquals(LongRange(0, 200), result.sources[0].range)
        assert(result.sources[0] is StreamPlanningService.StreamSource.Remote)
    }

    private fun getFileChunk(startByte: Long, endByte: Long): FileChunk {
        val remotelyCachedEntity = RemotelyCachedEntity()
        val fileChunk = FileChunk()
        fileChunk.remotelyCachedEntity = remotelyCachedEntity
        fileChunk.startByte = startByte
        fileChunk.endByte = endByte
        val blob = Blob()
        blob.size = endByte - startByte + 1
        blob.localContents = BlobProxy.generateProxy(LongRange(startByte, endByte).map { it.toByte() }.toByteArray())
        fileChunk.blob = blob
        return fileChunk
    }
}
