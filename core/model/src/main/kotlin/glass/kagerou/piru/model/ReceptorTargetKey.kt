package glass.kagerou.piru.model

/**
 * The canonical key for a receptor target name.
 *
 * Ported from `Piru/Data/Pharmacology/ReceptorTargetKey.swift`.
 *
 * One fold, shared by the mechanism summary's binding dedup, the display
 * canonicalizer and the signature-axis matcher, so a target stated three
 * different ways in three tables still lands on one key. A measured binding row
 * often restates a curated target under a wordier name — "DAT (release, [3H]-DA
 * uptake)" against the curated "DAT" — because the graded flagship rows use the
 * bare name while the enrichment layer appends the assay in parentheses.
 */
object ReceptorTargetKey {

    /**
     * The case-preserving display form: strips a non-leading parenthetical
     * qualifier and everything after it, leading enantiomer prefixes, and a
     * trailing " receptor"/" receptors".
     *
     * A parenthetical that *opens* the string is the whole name and is kept:
     * stripping it turned `"(prodrug — no direct affinity)"` into an empty dedup
     * key, which then matched every other empty key.
     */
    fun display(raw: String): String {
        var s = raw.trim { it.isUnicodeWhitespace() }
        val open = s.indexOf('(')
        // `open != 0` rather than `open >= 0`: a leading parenthesis is kept.
        if (open > 0) s = s.substring(0, open).trim { it.isUnicodeWhitespace() }
        // Written as escapes for the minus sign in `(−)-`, which is U+2212 and not
        // U+002D — the two look identical on screen and the catalog carries both.
        for (prefix in listOf("(+)-", "(−)-", "(-)-", "(±)-")) {
            s = s.replace(prefix, "")
        }
        for (suffix in listOf(" receptors", " receptor")) {
            if (s.endsWith(suffix)) {
                s = s.dropLast(suffix.length)
                break
            }
        }
        return s.trim { it.isUnicodeWhitespace() }
    }

    /**
     * The case- and whitespace-insensitive fold of [display], for dictionary keys
     * and dedup.
     *
     * Splits on Unicode whitespace and rejoins with a single space, so a target
     * with a tab or a doubled space in it folds onto the same key as its clean
     * spelling.
     *
     * ## Why [isUnicodeWhitespace] and not `Char.isWhitespace()`
     * Swift's `Character.isWhitespace` is the Unicode `White_Space` property and
     * Java's is not, so the obvious `filterNot { it.isWhitespace() }` disagrees with
     * the reference exactly on the invisible characters — a target name carrying
     * U+00A0 would never dedup against the same target written with a plain space.
     * See [isUnicodeWhitespace] for the full note, including the one character it
     * still does not match.
     */
    fun fold(raw: String): String {
        val displayed = display(raw).lowercase()
        val out = StringBuilder(displayed.length)
        var i = 0
        while (i < displayed.length) {
            if (displayed[i].isUnicodeWhitespace()) {
                i++
                continue
            }
            val start = i
            while (i < displayed.length && !displayed[i].isUnicodeWhitespace()) i++
            if (out.isNotEmpty()) out.append(' ')
            out.append(displayed, start, i)
        }
        return out.toString()
    }

}
