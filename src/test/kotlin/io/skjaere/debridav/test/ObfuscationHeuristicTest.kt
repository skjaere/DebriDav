package io.skjaere.debridav.test

import io.skjaere.debridav.usenet.ObfuscationHeuristic
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ObfuscationHeuristicTest {

    // --- Definite obfuscated: pattern-1 (32-char hex MD5) -----------------------------

    @ParameterizedTest
    @ValueSource(strings = [
        "b082fa0beaa644d3aa01045d5b8d0b36.mkv",
        "deadbeefdeadbeefdeadbeefdeadbeef.mp4",
        "0123456789abcdef0123456789abcdef.bin",
    ])
    fun `32-char hex basename is obfuscated`(name: String) {
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    @Test
    fun `mixed-case hex is not caught by the 32-hex rule (matches only lowercase)`() {
        // SABnzbd's regex is intentionally lowercase-only; mixed-case 32-char names
        // fall through to other heuristics. This locks in that behaviour so a future
        // 'tighten the regex' change doesn't accidentally drop SABnzbd parity.
        val mixed = "B082Fa0BEAA644D3AA01045D5B8D0B36.mkv"
        // It will still be reported obfuscated by the fallthrough default, but not
        // by the HEX_32 rule — exercised indirectly via the fallthrough cases.
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(mixed))
    }

    // --- Definite obfuscated: pattern-2 (40+ char hex/dot) ----------------------------

    @ParameterizedTest
    @ValueSource(strings = [
        "0675e29e9abfd2.f7d069dab0b853283cc1b069a25f82.6547.mkv",
        "abcdef0123456789abcdef0123456789abcdef0123.bin",
        // 40 chars exactly is the threshold
        "0123456789012345678901234567890123456789.mkv",
    ])
    fun `40-plus char hex-dot basename is obfuscated`(name: String) {
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    // --- Definite obfuscated: pattern-3 (bracket tags + 30-char hex) ------------------

    @Test
    fun `bracketed tags plus 30-char hex run is obfuscated`() {
        // Hex run is 30 chars exactly to land on the rule's threshold.
        val name = "[Group] something 5937bc5e32146ebef89a622e4a23ff [Subgroup].mkv"
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name))
    }

    @Test
    fun `single bracket tag with 30-char hex falls through to default obfuscated`() {
        // The bracket rule requires >= 2 bracket-tag matches. We construct a name
        // with only one bracket and 30+ hex chars run-on, no spaces or year tokens
        // that would trip the NOT-obfuscated heuristics. Confirms the bracket rule
        // does NOT fire for single-bracket inputs (the fallthrough default does).
        val name = "5937bc5e32146ebef89a622e4a23ff12abc[Group].mkv"
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name))
    }

    // --- Definite obfuscated: pattern-4 (abc.xyz prefix) ------------------------------

    @ParameterizedTest
    @ValueSource(strings = [
        "abc.xyz.a4c567edbcbf27.BLA.mkv",
        "abc.xyz.something.mp4",
    ])
    fun `abc-dot-xyz prefix is obfuscated`(name: String) {
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    // --- Definite NOT obfuscated: well-formed release names ---------------------------

    @ParameterizedTest
    @ValueSource(strings = [
        // Real-shape release names — multi-dot, mixed case, source/quality tags.
        "Release.Name.2024.1080p.WEB-DL.H264.mkv",
        "Release.Name.S01E01.1080p.WEB-DL.x264-Group.mkv",
        "Release.Name.2023.2160p.UHD.BluRay.x265-Group.mkv",
        // Spaces instead of dots
        "Release Name 2024 1080p WEB DL.mkv",
    ])
    fun `real-shape release names are not obfuscated`(name: String) {
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    @Test
    fun `two-word title with separator is not obfuscated`() {
        // Hits the "upper>=2, lower>=2, separator>=1" rule.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("Release Title.mkv"))
    }

    @Test
    fun `lowercase three-word title with separators is not obfuscated`() {
        // Hits the "separators >= 3" rule even with no uppercase.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("this.is.a.release.mkv"))
    }

    @Test
    fun `title with year and separator is not obfuscated`() {
        // Hits the "letters + 4-digit year + separator" rule.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("Title 2024.mkv"))
    }

    @Test
    fun `single-word capitalised title is not obfuscated`() {
        // Hits the "capital first + low upper-to-lower ratio" rule.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("Catullus.mp4"))
    }

    // --- The lowercase-short-word extension we layer on top of SABnzbd ----------------

    @ParameterizedTest
    @ValueSource(strings = [
        "testfile.bin",
        "sample.mkv",
        "intro.mp4",
        "disc.bin",
        "video.mkv",
    ])
    fun `short lowercase single-word names are not obfuscated`(name: String) {
        // SABnzbd's pure algorithm would treat these as obfuscated (no uppercase, no
        // separators, no digits, fails every positive case). Our extension catches
        // them — guarding against renaming legitimate debug or sample uploads.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    @Test
    fun `long lowercase no-digit name above the extension cap is still obfuscated`() {
        // 17 chars — one past the 16-char cap. Stays in fallthrough = obfuscated.
        val name = "abcdefghijklmnopq.mkv"
        assertEquals(17, name.substringBefore('.').length)
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name))
    }

    @Test
    fun `lowercase name with a digit falls through to the default`() {
        // The extension explicitly excludes anything with a digit (`disc1.bin`-style
        // names slip through the other heuristics if they're short — but a digit
        // implies the uploader probably typed it deliberately, so it's not a hash).
        // Verify the extension does NOT catch this; fallthrough applies.
        val name = "disc1bin.mkv" // no separator, has a digit, fails every rule
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name))
    }

    // --- Hyphen-as-separator + letter-dominant extension (our additions) -------------

    @ParameterizedTest
    @ValueSource(strings = [
        "release-name-2024",
        "rar5-streaming-test",
        "queue-file-resolution-test",
        "some-release-Group",
        "lowercase-multi-hyphen-name-2024.mkv",
    ])
    fun `hyphenated release-style names are not obfuscated`(name: String) {
        // SABnzbd doesn't recognise '-' as a separator; we do, because hyphens are
        // ubiquitous in real release naming (`Group-Quality-Year-...`). The
        // letter-dominant rule then catches lowercase hyphen-separated names that
        // SABnzbd's positive cases miss.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    @Test
    fun `digit-dominant hex-with-dot patterns are NOT caught by SABnzbd's heuristic`() {
        // Known-permissive: SABnzbd's `Beast 2020` rule (letters>=4 + digits>=4 + sep>=1)
        // classifies hex-with-dot patterns like `5d.f4ce97a04bcd2` as not obfuscated
        // because a-f letters count as letters. This is a SABnzbd quirk we inherit;
        // documenting it here so a future reader sees the gap. To fix it tightly would
        // require pattern-matching for hex-letter runs vs word-letter runs — beyond the
        // current heuristic's scope.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("5d.f4ce97a04bcd2.mkv"))
    }

    // --- Mixed-case alphanumeric hashes (the original looksObscured target case) ------

    @ParameterizedTest
    @ValueSource(strings = [
        "oYBVxskQWIaXbaZLlbhl55MS78I05WgC.mp4",
        "aBCdEf1234567890abCdEf1234567890.bin",
        "3709r79e19m1m18m23a15h85z04853.mkv",
    ])
    fun `mixed-case alphanumeric hash basenames are obfuscated`(name: String) {
        // Not caught by SABnzbd's lowercase-hex rules but fall through to the default
        // because none of the positive (NOT-obfuscated) heuristics match.
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated(name), name)
    }

    // --- Path handling & extension stripping ------------------------------------------

    @Test
    fun `directory prefix is stripped before evaluation`() {
        // The heuristic considers only the final segment.
        assertTrue(
            ObfuscationHeuristic.isProbablyObfuscated(
                "Release.Name.2024/b082fa0beaa644d3aa01045d5b8d0b36.mkv"
            )
        )
        assertFalse(
            ObfuscationHeuristic.isProbablyObfuscated(
                "b082fa0beaa644d3aa01045d5b8d0b36/Release.Name.2024.1080p.WEB-DL.mkv"
            )
        )
    }

    @Test
    fun `final extension is stripped before evaluation`() {
        // Basename "abc" — short, all-lowercase, no separators, no digits — caught by
        // our extension. The `.bin` shouldn't influence the heuristic.
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("abc.bin"))
    }

    @Test
    fun `name with no extension is evaluated as-is`() {
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated("Catullus"))
        assertTrue(ObfuscationHeuristic.isProbablyObfuscated("b082fa0beaa644d3aa01045d5b8d0b36"))
    }

    @Test
    fun `dotfile retains its leading dot through extension-stripping`() {
        // `.hidden` could otherwise lose its whole content to extension-stripping; our
        // stripLastExtension returns the input unchanged when the dot is at position 0.
        // The leading dot then counts as a separator, which combined with 6 lowercase
        // letters trips the letter-dominant rule → not obfuscated. We're not testing
        // the classification value here so much as that the heuristic gets to see the
        // letters at all (rather than getting an empty string from over-eager stripping).
        assertFalse(ObfuscationHeuristic.isProbablyObfuscated(".hidden"))
    }
}
