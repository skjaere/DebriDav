package io.skjaere.debridav.test.arrs

import io.skjaere.debridav.arrs.client.ArrPathMatcher
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArrPathMatcherTest {

    @Test
    fun `matches when arr path equals expected path exactly`() {
        assertTrue(
            ArrPathMatcher.matches(
                arrPath = "/data/downloads/Show.S01E01.NTb/Show.S01E01.NTb.mkv",
                expectedPath = "/data/downloads/Show.S01E01.NTb/Show.S01E01.NTb.mkv",
            )
        )
    }

    @Test
    fun `tolerates duplicated slashes in either path`() {
        // Real-world: arrs occasionally double-slash when concatenating mount + relative,
        // and we sometimes double-slash when concatenating mountPath + downloadPath if
        // mountPath has a trailing slash. The comparison normalises both.
        assertTrue(
            ArrPathMatcher.matches(
                arrPath = "/data//downloads/Release.Name-Grp/file.mkv",
                expectedPath = "/data/downloads//Release.Name-Grp/file.mkv",
            )
        )
    }

    @Test
    fun `tolerates trailing whitespace`() {
        assertTrue(
            ArrPathMatcher.matches(
                arrPath = "  /data/downloads/Release.Name-Grp/file.mkv  ",
                expectedPath = "/data/downloads/Release.Name-Grp/file.mkv",
            )
        )
    }

    @Test
    fun `tolerates leading slashes being missing on one side`() {
        // The normaliser always re-anchors to '/'. Either side could come in
        // without a leading slash from upstream wiring (we've seen Sonarr return
        // both forms across versions).
        assertTrue(
            ArrPathMatcher.matches(
                arrPath = "data/downloads/x/y.mkv",
                expectedPath = "/data/downloads/x/y.mkv",
            )
        )
    }

    @Test
    fun `does not match when paths differ in any segment`() {
        // Two different torrents/NZBs of the same release; the arr has the OTHER copy.
        // This is the bug we're guarding against: filenames are identical but the
        // physical files are at different paths.
        assertFalse(
            ArrPathMatcher.matches(
                arrPath = "/data/downloads/Release.Name-Grp-A/file.mkv",
                expectedPath = "/data/downloads/Release.Name-Grp-B/file.mkv",
            )
        )
    }

    @Test
    fun `does not match when mount-prefix differs`() {
        // The arr has the file at a different mount root (e.g. imported via a
        // different download client that mounts debridav at a different point,
        // or manually placed by the user). The unhealthy debridav copy is NOT
        // what the arr is using — must not be deleted.
        assertFalse(
            ArrPathMatcher.matches(
                arrPath = "/different-mount/downloads/Release/file.mkv",
                expectedPath = "/data/downloads/Release/file.mkv",
            )
        )
    }

    @Test
    fun `does not match when arr path is null`() {
        // Episode/movie without an imported file (episodeFileId == 0 or arr's
        // /parse didn't include the episodeFile inline) — no path to match.
        assertFalse(ArrPathMatcher.matches(arrPath = null, expectedPath = "/data/downloads/x/y.mkv"))
    }

    @Test
    fun `does not match when arr path is blank`() {
        assertFalse(ArrPathMatcher.matches(arrPath = "   ", expectedPath = "/data/downloads/x/y.mkv"))
    }
}
