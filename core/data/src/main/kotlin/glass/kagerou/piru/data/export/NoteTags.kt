package glass.kagerou.piru.data.export

import java.util.Locale

/**
 * Hashtags inside a note's text.
 *
 * Ported from `Piru/Utilities/TagExtractor.swift`.
 *
 * ## Why this exists only on the way in
 * This build has no tag editor and nothing reads a dose's tags back, so the
 * extractor has exactly one caller: the PsychonautWiki importer. PW has no tag
 * field at all — its ingestions carry one free-text note — so the only way a tag
 * survives the journey from PW is to be found in that text.
 *
 * The pattern is `#(\w+)`, lowercased, de-duplicated **keeping the first
 * occurrence's position**, which is what the Swift does with its `seen` set: the
 * returned list is in reading order, not sorted.
 */
internal object NoteTags {

    private val PATTERN = Regex("#(\\w+)")

    fun extract(text: String): List<String> {
        val seen = HashSet<String>()
        val out = mutableListOf<String>()
        for (match in PATTERN.findAll(text)) {
            val tag = match.groupValues[1].lowercase(Locale.ROOT)
            if (seen.add(tag)) out += tag
        }
        return out
    }
}
