package io.skjaere.debridav.test

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import io.mockk.mockk
import io.skjaere.debridav.debrid.client.easynews.EasynewsClient
import io.skjaere.debridav.debrid.client.easynews.EasynewsConfigurationProperties
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.springframework.web.multipart.MultipartFile

class EasnyNewsTest {

    val httpClient = HttpClient(CIO) {

        install(ContentNegotiation) {
            json(
                Json {
                    prettyPrint = true
                    isLenient = true
                    ignoreUnknownKeys = true
                }
            )
        }

    }

    val config = EasynewsConfigurationProperties(
        apiBaseUrl = "https://members.easynews.com/3.0/api",
        username = "uxlrvsntac",
        password = "jzel-fukb-brps"
    )


    private val underTest = EasynewsClient(httpClient, config)
    private val nzbFile = mockk<MultipartFile>()

    /*    @Test
        fun thatItWorks() {
            //given
            every { nzbFile.originalFilename } returns "Gangs of New York 2002 PROPER BluRay 1080p DTS-HD MA 5 1 AVC REMUX-FraMeSToR"
            runTest {
                val r = underTest.isCached(nzbFile)
            }

        }*/

    @Test
    fun thatPathGenerationWorks() {
        val files = listOf<DbItem>(
            Directory("/"),
            Directory("/a"),
            File("/a/b/b_file.ext"),
            Directory("/a/b/c"),
            Directory("/a/b/c/d"),
            File("/a/b/c/d/d_file.ext"),
        )

        fun moveItem(path: String, destination: String): List<DbItem> {
            val filesToMove = files
                .filter { it.path.startsWith("$path/") }
                .map {
                    //it.path = it.path.replace("$path/", "$destination/")
                    val subPath = it.path.substringAfter("$path/")
                    it.path = "$destination/$subPath"
                    it
                }
            return filesToMove
        }

        val result = moveItem("/a/b", "/a/d")
        assert(result.isNotEmpty())
    }

    sealed interface DbItem {
        var path: String
    }

    data class Directory(override var path: String) : DbItem
    data class File(override var path: String) : DbItem
}
