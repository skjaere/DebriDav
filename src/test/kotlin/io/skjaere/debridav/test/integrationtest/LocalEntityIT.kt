package io.skjaere.debridav.test.integrationtest

import io.skjaere.debridav.DebriDavApplication
import io.skjaere.debridav.MiltonConfiguration
import io.skjaere.debridav.configuration.DebridavConfigurationProperties
import io.skjaere.debridav.fs.DatabaseFileService
import io.skjaere.debridav.fs.LocalContentsService
import io.skjaere.debridav.resource.FileResource
import io.skjaere.debridav.test.integrationtest.config.IntegrationTestContextConfiguration
import io.skjaere.debridav.test.integrationtest.config.MockServerTest
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import java.io.ByteArrayOutputStream

@SpringBootTest(
    classes = [DebriDavApplication::class, IntegrationTestContextConfiguration::class, MiltonConfiguration::class],
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
@MockServerTest
class LocalEntityIT {
    @Autowired
    lateinit var databaseFileService: DatabaseFileService

    @Autowired
    lateinit var localContentsService: LocalContentsService

    @Autowired
    lateinit var debridavConfigurationProperties: DebridavConfigurationProperties

    @Autowired
    lateinit var entityManager: EntityManager

    @Test
    fun `that writing and reading works`() {
        // given
        val contents = "this is the contents of the file"
        val localEntity = databaseFileService.createLocalFile(
            "/test.txt", contents.toByteArray().inputStream(), contents.toByteArray().size.toLong()
        )

        // when
        val resource = FileResource(localEntity, databaseFileService, localContentsService, debridavConfigurationProperties)
        val out = ByteArrayOutputStream()
        resource.sendContent(out, null, null, null)

        //then
        assertEquals(contents, out.toString())

        databaseFileService.deleteFile(localEntity)
    }

    @Test
    fun `that deleting a local entity also deletes the large object`() {
        // given
        val preSaveLobCount = getLargeObjectCount()
        val contents = "this is the contents of the file"
        val localEntity = databaseFileService.createLocalFile(
            "/test.txt", contents.toByteArray().inputStream(), contents.toByteArray().size.toLong()
        )

        // when
        val resource = FileResource(localEntity, databaseFileService, localContentsService, debridavConfigurationProperties)
        val out = ByteArrayOutputStream()
        resource.sendContent(out, null, null, null)
        assertEquals(
            preSaveLobCount + 1,
            getLargeObjectCount()
        )

        //then
        databaseFileService.deleteFile(localEntity)
        val result = getLargeObjectCount()
        assertEquals(preSaveLobCount, result)
    }

    private fun getLargeObjectCount(): Long =
        entityManager.createNativeQuery("select count(*) from pg_largeobject").resultList.first() as Long
}
