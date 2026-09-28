package glass.kagerou.piru.model

/**
 * PSID (Piru Substance ID) primitives — the stable substance-identity scheme.
 *
 * This is the Kotlin port of `pipeline/psid.py`; the two must stay in lockstep.
 * A change to the grammar or the check-character algorithm has to land on both
 * sides, or a PSID minted by the pipeline fails to validate in the app. The
 * pipeline is the source of truth — this port exists so the app can *validate*
 * and *compose* a PSID at its deep-link and import boundary without a
 * round-trip.
 *
 * Grammar:
 *
 * ```
 * P1-<FAMILY>-<stereo>-<salt>-<release>-<chk>
 * ```
 *
 * - `P1` — scheme version, so the grammar can evolve without stranding stored ids.
 * - `FAMILY` — a 14-char skeleton hash. For a structure-bearing substance whose
 *   InChIKey connectivity block (block 1) is unique and trusted in the catalog,
 *   the FAMILY *is* that block 1 verbatim (14 uppercase letters), so it
 *   cross-references PubChem. For a structure-less row, or a member of a
 *   same-block-1 collision of *distinct* drugs, the FAMILY is a name-hash in the
 *   same alphabet with a sentinel leading digit — real block-1s are always 14
 *   letters, so a leading digit unambiguously marks "this is a name-hash".
 * - `<stereo>` / `<salt>` / `<release>` — the three orthogonal form facets;
 *   `0` = racemic / freebase / standard (unspecified).
 * - `<chk>` — an ISO 7064 MOD 37,36 hybrid check character over the key body
 *   (scheme + family + facets, separators stripped). It detects every
 *   single-character substitution and nearly all adjacent transpositions, so a
 *   truncated or mistyped PSID fails fast instead of silently resolving to a
 *   *different* valid substance.
 */
object PSID {

    const val SCHEME: String = "P1"
    const val UNSPECIFIED_FACET: String = "0"

    /** ISO 7064 radix-36 alphabet: a character's value is its index (0-9, then A-Z). */
    private const val ALPHABET = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val M = 36 // radix
    private const val N = 37 // modulus (m + 1)
    private const val FAMILY_LENGTH = 14

    /** Value of a radix-36 alphabet character, or null when out of alphabet. */
    private fun valueOf(ch: Char): Int? = ALPHABET.indexOf(ch).takeIf { it >= 0 }

    /**
     * ISO 7064 MOD 37,36 hybrid check character over [body] (all characters in
     * the radix-36 alphabet). Detects every single-character substitution and
     * nearly all adjacent transpositions. Returns null if [body] contains a
     * character outside the alphabet — a malformed body has no defined check.
     */
    fun iso7064CheckChar(body: String): Char? {
        var p = M
        for (ch in body) {
            val v = valueOf(ch) ?: return null
            p = (p + v) % M
            if (p == 0) p = M
            p = (p * 2) % N
        }
        return ALPHABET[(N - p) % M]
    }

    /**
     * True when [family] is a real InChIKey connectivity block — 14 uppercase
     * ASCII letters — as opposed to a sentinel-digit name-hash.
     */
    fun isBlock1Family(family: String): Boolean =
        family.length == FAMILY_LENGTH && family.all { it.isUppercaseAsciiLetter() }

    /** True when [family] is a name-hash: sentinel leading digit + 13 uppercase letters. */
    fun isNameHashFamily(family: String): Boolean {
        if (family.length != FAMILY_LENGTH) return false
        return family[0].isAsciiDigit() && family.substring(1).all { it.isUppercaseAsciiLetter() }
    }

    /** A FAMILY is either a real block-1 or a name-hash — never anything else. */
    fun isWellformedFamily(family: String): Boolean =
        isBlock1Family(family) || isNameHashFamily(family)

    private fun body(family: String, stereo: String, salt: String, release: String): String =
        SCHEME + family + stereo + salt + release

    /**
     * The full check-valid PSID string for a FAMILY plus facets, or null if the
     * family or facets are not well-formed — so a caller cannot accidentally
     * mint a PSID the parser would reject.
     */
    fun compose(
        family: String,
        stereo: String = UNSPECIFIED_FACET,
        salt: String = UNSPECIFIED_FACET,
        release: String = UNSPECIFIED_FACET,
    ): String? {
        if (!isWellformedFamily(family)) return null
        if (!isFacet(stereo) || !isFacet(salt) || !isFacet(release)) return null
        val chk = iso7064CheckChar(body(family, stereo, salt, release)) ?: return null
        return "$SCHEME-$family-$stereo-$salt-$release-$chk"
    }

    /** A parsed PSID's structural components; the check character has already verified. */
    data class Components(
        val family: String,
        val stereo: String,
        val salt: String,
        val release: String,
    )

    /** A facet segment is one or more radix-36 alphabet characters. */
    private fun isFacet(segment: String): Boolean =
        segment.isNotEmpty() && segment.all { valueOf(it) != null }

    /**
     * Parse a PSID into its components when it is well-formed AND its check
     * character is valid; otherwise null. This is the fail-fast gate for PSIDs
     * arriving from deep links and imports.
     */
    fun parse(psid: String): Components? {
        val parts = psid.split("-")
        if (parts.size != 6) return null
        // Indexed rather than destructured: `List` only supplies component1..5,
        // and the POSITIONAL order is the grammar, so naming each slot beats a
        // six-way destructuring that would not compile anyway.
        val scheme = parts[0]
        val family = parts[1]
        val stereo = parts[2]
        val salt = parts[3]
        val release = parts[4]
        val chk = parts[5]
        if (scheme != SCHEME || !isWellformedFamily(family)) return null
        if (!isFacet(stereo) || !isFacet(salt) || !isFacet(release)) return null
        if (chk.length != 1) return null
        val expected = iso7064CheckChar(body(family, stereo, salt, release)) ?: return null
        if (expected.toString() != chk) return null
        return Components(family = family, stereo = stereo, salt = salt, release = release)
    }

    /** True when [psid] parses and its check character verifies. */
    fun isValid(psid: String): Boolean = parse(psid) != null
}

/**
 * ASCII A–Z, matching the pipeline's `isalpha() and isupper()` gate for the
 * FAMILY alphabet without pulling in lowercase or Unicode letters.
 */
private fun Char.isUppercaseAsciiLetter(): Boolean = this in 'A'..'Z'

/** ASCII 0–9, matching the sentinel-digit test. */
private fun Char.isAsciiDigit(): Boolean = this in '0'..'9'
