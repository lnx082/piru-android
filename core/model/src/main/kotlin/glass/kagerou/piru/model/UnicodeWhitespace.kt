package glass.kagerou.piru.model

/**
 * Unicode `White_Space`, as closely as the JDK's two predicates can express it.
 *
 * ## Why this exists rather than `Char.isWhitespace()`
 * Swift's `Character.isWhitespace` is the Unicode `White_Space` property. Java's
 * is not, and Kotlin inherits Java: **U+00A0 NO-BREAK SPACE is `White_Space=Yes`
 * and `Character.isWhitespace(' ')` is false.**
 *
 * So the obvious predicate accepts Swift's answer for every ordinary space and
 * disagrees on exactly the invisible ones — which is the worst place for a
 * silent divergence, because the two spellings are indistinguishable in a source
 * file, in a database dump, and on screen. Both of this port's current callers
 * compare user- or pipeline-supplied strings for equality, where a surviving
 * no-break space means two things that should be one.
 *
 * Swift's `.whitespaces` (used for `trimmingCharacters(in:)`) is the `Zs`
 * category plus tab; [isUnicodeWhitespace] is a superset of that too, and the
 * difference is a character neither caller can produce.
 *
 * ## What it does not match
 * U+0085 NEXT LINE is `White_Space=Yes` and is in neither Java category, so it
 * survives here where Swift would fold it. It is not in `Zs`, not a control Java
 * counts as whitespace, and not a character either caller's data contains.
 */
fun Char.isUnicodeWhitespace(): Boolean = isWhitespace() || Character.isSpaceChar(this)

/** [String.trim] against [isUnicodeWhitespace] rather than the ASCII-biased default. */
fun String.trimUnicodeWhitespace(): String = trim { it.isUnicodeWhitespace() }
