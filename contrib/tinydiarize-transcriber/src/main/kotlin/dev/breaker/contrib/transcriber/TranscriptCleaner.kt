package dev.breaker.contrib.transcriber

/**
 * Converts WebVTT output from whisper-cli (-tdrz -ovtt) into a clean timestamped transcript.
 *
 * Ports clean-transcript.py (~/work/hacks/clean-transcript.py). No external dependencies —
 * pure Kotlin, usable from the .kts pipeline script and from Android code alike.
 *
 * The three-stage pipeline (matching the Python original):
 *   1. processVtt     — strip WEBVTT header/notes/styles, extract (timestamp, text) pairs,
 *                       collapse Whisper's prefix-candidate runs (TS1 BA / TS2 BANA / TS3 BANANA
 *                       → TS1 BANANA), preserve [SPEAKER_TURN] markers from tdrz.
 *   2. removeLineStutter — drop near-duplicate adjacent lines (≥80 % similar) unless either
 *                       carries [SPEAKER_TURN].
 *   3. removeWordStutter — remove immediately repeated words ("hello hello" → "hello").
 */
object TranscriptCleaner {

    private const val SPEAKER_TURN = "[SPEAKER_TURN]"
    private const val LINE_SIMILARITY_THRESHOLD = 0.80

    // ── public entry point ────────────────────────────────────────────────

    /**
     * Clean [input] text. Pass [isVtt] = true for WebVTT content (the whisper-cli default);
     * false for plain text (still applies stutter removal).
     */
    fun clean(input: String, isVtt: Boolean = input.trimStart().startsWith("WEBVTT")): String =
        if (isVtt) processVtt(input) else {
            val pass1 = removeLineStutter(cleanText(input))
            removeWordStutter(pass1)
        }

    // ── stage 1: VTT → timestamped lines ────────────────────────────────

    private val TIMESTAMP_LINE = Regex(
        """(?:\[)?(\d{2}:\d{2}:\d{2}\.\d{3})\s*-->\s*\d{2}:\d{2}:\d{2}\.\d{3}(?:])?"""
    )

    private fun processVtt(raw: String): String {
        // Strip WEBVTT header up to first blank line, NOTE/STYLE blocks
        var content = raw.replace(Regex("^WEBVTT.*?\\n\\n", RegexOption.DOT_MATCHES_ALL), "")
        content = content.replace(Regex("NOTE.*?\\n\\n", RegexOption.DOT_MATCHES_ALL), "")
        content = content.replace(Regex("STYLE.*?\\n\\n", RegexOption.DOT_MATCHES_ALL), "")

        val blocks = content.trim().split(Regex("\\n{2,}"))
        val candidates = mutableListOf<Pair<String, String>>() // (HH:MM:SS, text)

        for (block in blocks) {
            val lines = block.trim().lines()
            val tsIdx = lines.indexOfFirst { TIMESTAMP_LINE.containsMatchIn(it) }
            if (tsIdx < 0) continue
            val tsMatch = TIMESTAMP_LINE.find(lines[tsIdx]) ?: continue
            // Keep HH:MM:SS (drop milliseconds for output readability)
            val startTs = tsMatch.groupValues[1].substring(0, 8)
            val rawText = lines.drop(tsIdx + 1).joinToString(" ").trim()
            val text = cleanText(rawText)
            if (text.isNotEmpty()) candidates.add(startTs to text)
        }

        // Collapse prefix-candidate runs: TS1 "BA" / TS2 "BANA" / TS3 "BANANA" → TS1 "BANANA"
        val collapsed = mutableListOf<Pair<String, String>>()
        var i = 0
        while (i < candidates.size) {
            val (ts, text) = candidates[i]
            // Look ahead for a run where each item is a prefix of the next
            var j = i + 1
            while (j < candidates.size && candidates[j - 1].second.isPrefixOf(candidates[j].second)) {
                j++
            }
            // j-1 is the last item of the run; take its text (the longest / most complete)
            collapsed.add(ts to candidates[j - 1].second)
            i = j
        }

        return collapsed.joinToString("\n") { (ts, text) -> "$ts $text" }
    }

    private fun String.isPrefixOf(other: String) = other.startsWith(this)

    private fun cleanText(text: String): String {
        var t = text
        t = t.replace(Regex("<[^>]+>"), "")          // strip HTML tags
        t = t.replace(Regex("^\\w+:\\s+(?=\\[\\d{2}:\\d{2})"), "") // "Jim: [00:01..." → "..."
        t = t.replace(Regex("^\\s+"), "")
        t = t.replace(Regex("\\s+"), " ")
        t = t.replace(Regex("&[a-zA-Z]+;"), " ")
        // Never strip [SPEAKER_TURN] — preserve it exactly
        return t.trim()
    }

    // ── stage 2: line-level stutter removal ─────────────────────────────

    /**
     * Drop a line when it is ≥ [LINE_SIMILARITY_THRESHOLD] similar to the previous line,
     * unless either line carries [SPEAKER_TURN] (speaker boundary always survives).
     */
    fun removeLineStutter(transcript: String, threshold: Double = LINE_SIMILARITY_THRESHOLD): String {
        val lines = transcript.lines()
        val out = mutableListOf<String>()
        for (line in lines) {
            if (out.isEmpty()) { out.add(line); continue }
            val prev = out.last()
            when {
                SPEAKER_TURN in line -> out.add(line)  // always keep speaker boundaries
                SPEAKER_TURN in prev -> out.add(line)  // always keep the line after a boundary
                sequenceSimilarity(prev, line) >= threshold -> { /* drop duplicate */ }
                else -> out.add(line)
            }
        }
        return out.joinToString("\n")
    }

    /** Longest-common-subsequence ratio, matching Python's difflib.SequenceMatcher. */
    private fun sequenceSimilarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val la = a.trim(); val lb = b.trim()
        val m = la.length; val n = lb.length
        // Standard LCS DP
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) for (j in 1..n)
            dp[i][j] = if (la[i - 1] == lb[j - 1]) dp[i - 1][j - 1] + 1
                       else maxOf(dp[i - 1][j], dp[i][j - 1])
        return 2.0 * dp[m][n] / (m + n)
    }

    // ── stage 3: word-level stutter removal ─────────────────────────────

    fun removeWordStutter(text: String): String {
        val doubleWord = Regex("\\b(\\w+)\\s+\\1\\b", RegexOption.IGNORE_CASE)
        val tripleWord = Regex("\\b(\\w+)(\\s+\\1){2,}\\b", RegexOption.IGNORE_CASE)
        var t = text
        // Iterate until stable (matches the Python while loop)
        var prev: String
        do { prev = t; t = doubleWord.replace(t) { it.groupValues[1] } } while (t != prev)
        t = tripleWord.replace(t) { it.groupValues[1] }
        return t
    }
}
