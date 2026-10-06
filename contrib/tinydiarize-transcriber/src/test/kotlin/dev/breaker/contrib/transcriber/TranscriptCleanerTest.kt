package dev.breaker.contrib.transcriber

import org.junit.Assert.*
import org.junit.Test

class TranscriptCleanerTest {

    // ── VTT parsing ───────────────────────────────────────────────────────

    @Test
    fun `basic VTT block extracts timestamp and text`() {
        val vtt = """
            WEBVTT
            
            00:00:01.000 --> 00:00:03.000
            Hello world
        """.trimIndent()
        val result = TranscriptCleaner.clean(vtt, isVtt = true)
        assertEquals("00:00:01 Hello world", result.trim())
    }

    @Test
    fun `WEBVTT header and NOTE blocks are stripped`() {
        val vtt = """
            WEBVTT - Some title
            
            NOTE This is a note
            
            00:00:05.000 --> 00:00:07.000
            First line
        """.trimIndent()
        val result = TranscriptCleaner.clean(vtt, isVtt = true)
        assertTrue("Should contain 'First line'", result.contains("First line"))
        assertFalse("Should not contain WEBVTT", result.contains("WEBVTT"))
        assertFalse("Should not contain NOTE", result.contains("NOTE"))
    }

    @Test
    fun `prefix-candidate run is collapsed to its last (longest) form`() {
        // whisper-cli emits partial candidates that each prefix the next:
        // TS1 "BA", TS2 "BANA", TS3 "BANANA" → TS1 "BANANA"
        val vtt = buildVtt(
            "00:00:01.000" to "00:00:02.000" to "BA",
            "00:00:01.000" to "00:00:03.000" to "BANA",
            "00:00:01.000" to "00:00:04.000" to "BANANA",
        )
        val result = TranscriptCleaner.clean(vtt, isVtt = true)
        val lines = result.trim().lines().filter { it.isNotBlank() }
        assertEquals("prefix run collapses to one line", 1, lines.size)
        assertTrue("collapsed text is BANANA", lines[0].endsWith("BANANA"))
    }

    @Test
    fun `SPEAKER_TURN marker is preserved through VTT processing`() {
        val vtt = """
            WEBVTT
            
            00:00:10.000 --> 00:00:12.000
            This is speaker one [SPEAKER_TURN]
            
            00:00:12.000 --> 00:00:14.000
            This is speaker two
        """.trimIndent()
        val result = TranscriptCleaner.clean(vtt, isVtt = true)
        assertTrue("SPEAKER_TURN must survive", result.contains("[SPEAKER_TURN]"))
    }

    @Test
    fun `HTML tags are removed from VTT text`() {
        val vtt = """
            WEBVTT
            
            00:00:01.000 --> 00:00:03.000
            <c.colorE5E5E5>Hello world</c>
        """.trimIndent()
        val result = TranscriptCleaner.clean(vtt, isVtt = true)
        assertFalse("No HTML tags", result.contains("<"))
        assertTrue("Text preserved", result.contains("Hello world"))
    }

    @Test
    fun `isVtt auto-detected from WEBVTT header`() {
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val result = TranscriptCleaner.clean(vtt)
        assertTrue(result.contains("Hello"))
    }

    // ── line stutter removal ──────────────────────────────────────────────

    @Test
    fun `near-duplicate adjacent lines are removed`() {
        val text = "00:00:01 Hello there\n00:00:02 Hello there today"
        // "Hello there" vs "Hello there today" — high similarity, should drop second
        val result = TranscriptCleaner.removeLineStutter(text, threshold = 0.8)
        assertEquals(1, result.trim().lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `distinct lines are both kept`() {
        val text = "00:00:01 Hello there\n00:00:10 Completely different content"
        val result = TranscriptCleaner.removeLineStutter(text)
        assertEquals(2, result.trim().lines().filter { it.isNotBlank() }.size)
    }

    @Test
    fun `SPEAKER_TURN line is always kept even when similar to previous`() {
        val text = "00:00:01 Hello there\n00:00:02 Hello there [SPEAKER_TURN]"
        val result = TranscriptCleaner.removeLineStutter(text)
        assertTrue("SPEAKER_TURN line must survive", result.contains("[SPEAKER_TURN]"))
    }

    @Test
    fun `line after SPEAKER_TURN is always kept`() {
        val text = "00:00:01 Hello [SPEAKER_TURN]\n00:00:02 Hello"
        val result = TranscriptCleaner.removeLineStutter(text)
        assertEquals(2, result.trim().lines().filter { it.isNotBlank() }.size)
    }

    // ── word stutter removal ──────────────────────────────────────────────

    @Test
    fun `repeated adjacent words are collapsed`() {
        assertEquals("hello world", TranscriptCleaner.removeWordStutter("hello hello world"))
    }

    @Test
    fun `triple repetition is collapsed`() {
        assertEquals("hello", TranscriptCleaner.removeWordStutter("hello hello hello"))
    }

    @Test
    fun `non-repeated words are unchanged`() {
        val text = "the quick brown fox"
        assertEquals(text, TranscriptCleaner.removeWordStutter(text))
    }

    @Test
    fun `case-insensitive stutter is collapsed`() {
        assertEquals("Hello", TranscriptCleaner.removeWordStutter("Hello hello"))
    }

    // ── fail-loud proof: the tests can go RED ─────────────────────────────

    @Test
    fun `the VTT cleaner goes red when WEBVTT is present in output`() {
        // Mutation: remove the header-stripping step → WEBVTT appears in output
        // This test checks the contract, not a mutation; it verifies the cleaner
        // does NOT pass through the header.
        val vtt = "WEBVTT\n\n00:00:01.000 --> 00:00:02.000\nHello\n"
        val result = TranscriptCleaner.clean(vtt, isVtt = true)
        assertFalse("WEBVTT must not appear in cleaned output", result.contains("WEBVTT"))
    }

    @Test
    fun `similarity returns 1 for identical strings`() {
        // White-box: invoked via removeLineStutter with identical lines
        val text = "00:00:01 Exact same line\n00:00:02 Exact same line"
        val result = TranscriptCleaner.removeLineStutter(text, threshold = 0.8)
        assertEquals("identical lines collapse to one", 1,
            result.trim().lines().filter { it.isNotBlank() }.size)
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun buildVtt(vararg blocks: Triple<String, String, String>): String {
        val header = "WEBVTT\n\n"
        return header + blocks.joinToString("\n\n") { (start, end, text) ->
            "$start --> $end\n$text"
        }
    }

    // Kotlin doesn't have a 3-arg `to`, so alias:
    private infix fun <A, B> Pair<A, B>.to(c: B): Triple<A, B, B> = Triple(first, second, c)
    private infix fun String.to(end: String): Pair<String, String> = Pair(this, end)
}
