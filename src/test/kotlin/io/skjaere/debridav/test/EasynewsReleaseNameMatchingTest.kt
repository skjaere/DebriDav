package io.skjaere.debridav.test

import io.skjaere.debridav.debrid.client.easynews.EasynewsReleaseNameMatchingService
import org.junit.jupiter.api.Test

class EasynewsReleaseNameMatchingTest {
    private val underTest = EasynewsReleaseNameMatchingService()

    @Test
    fun `that identical release names match`() {
        // given
        val releaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp"
        val easynewsReleaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp"

        // then
        assert(
            underTest.matches(releaseName, easynewsReleaseName)
        )
    }

    @Test
    fun `that spaces instead of dots names match`() {
        // given
        val releaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp"
        val easynewsReleaseName = "Release Name 2025 1080p DV Atmos ReleaseGrp"

        // then
        assert(
            underTest.matches(releaseName, easynewsReleaseName)
        )
    }

    @Test
    fun `that spaces instead of dots and file extension names match`() {
        // given
        val releaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp"
        val easynewsReleaseName = "Release Name 2025 1080p DV Atmos ReleaseGrp.mkv"

        // then
        assert(
            underTest.matches(releaseName, easynewsReleaseName)
        )
    }

    @Test
    fun `that release name with additional tag in brackets match`() {
        // given
        val releaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp"
        val easynewsReleaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp[tAg]"

        // then
        assert(
            underTest.matches(releaseName, easynewsReleaseName)
        )
    }

    @Test
    fun `that release name with multiple additional tag in brackets match`() {
        // given
        val releaseName = "[Tag]Release.Name.2025.1080p.DV.Atmos-ReleaseGrp[another-tAg]"
        val easynewsReleaseName = "Release.Name.2025.1080p.DV.Atmos-ReleaseGrp"

        // then
        assert(
            underTest.matches(releaseName, easynewsReleaseName)
        )
    }
}
