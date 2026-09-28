package glass.kagerou.piru.substance

import glass.kagerou.piru.model.PSID
import glass.kagerou.piru.model.SubstanceCategory
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * End-to-end checks against the bundled `piru-substances.sqlite`.
 *
 * Translated from `pipeline/build/tests/test_sqlite.py`'s
 * `TestBuiltDatabaseInvariants`, which asserts the same things against the same
 * file. The expectations are the upstream ones — where a test here fails, the
 * port is wrong, not the assertion.
 *
 * They skip when the database has not been fetched (`db/fetch-db.sh`), matching
 * the upstream suite's `SkipTest`.
 */
class BuiltDatabaseInvariantsTest {

    @Test
    fun `every substance_forms PSID round-trips through parse`() {
        openBundledSubstanceDb().use { db ->
            // Every substance_forms.psid must parse, have a valid check char,
            // and yield exactly the facets stored beside it — the guarantee that
            // a facet-bearing PSID (deep link / export) fails fast if mistyped
            // rather than resolving to a *different* valid form.
            val bad = db.query("SELECT psid, stereo, salt, release FROM substance_forms")
                .mapNotNull { row ->
                    val psid = row.string("psid") ?: return@mapNotNull "null psid"
                    val parsed = PSID.parse(psid) ?: return@mapNotNull psid
                    val stored = Triple(row.string("stereo"), row.string("salt"), row.string("release"))
                    val found = Triple(parsed.stereo, parsed.salt, parsed.release)
                    if (stored != found) psid else null
                }
            bad.shouldBeEmpty()
        }
    }

    @Test
    fun `isomer brand aliases carry their facet`() {
        openBundledSubstanceDb().use { db ->
            // An enantiomer brand or name alias is annotated with its isomer
            // facet on the parent, so a logged "Focalin" or "Spravato" recovers
            // the enantiomer rather than resolving form-blind.
            val cases = listOf(
                Triple("Focalin", "Methylphenidate", "D"),
                Triple("Spravato", "Ketamine", "S"),
                Triple("Armodafinil", "Modafinil", "R"),
                Triple("Dexmethylphenidate", "Methylphenidate", "D"),
            )
            for ((alias, parent, code) in cases) {
                val row = db.queryOne(
                    "SELECT s.canonical_name, a.isomer FROM aliases a " +
                        "JOIN substances s ON s.id = a.substance_id WHERE lower(a.alias) = lower(?)",
                    listOf(alias),
                ) ?: error("$alias alias missing")
                row.string("canonical_name") shouldBe parent
                row.string("isomer") shouldBe code
            }
        }
    }

    @Test
    fun `physicochemical columns are populated`() {
        openBundledSubstanceDb().use { db ->
            // logP/TPSA/HBA/HBD come mostly from PubChem's computed descriptors;
            // LD50/melting/boiling point come from NPS-DataHub. logD/pKa have no
            // source yet and stay NULL — an honest gap, not a failure.
            val row = db.queryOne(
                "SELECT count(logp) AS logp, count(tpsa) AS tpsa, count(hba) AS hba, " +
                    "count(hbd) AS hbd, count(ld50_oral_mg_per_kg) AS ld50, " +
                    "count(melting_point_c) AS mp, count(boiling_point_c) AS bp FROM substances",
            )!!
            val logp = row.long("logp")!!.toInt()
            val tpsa = row.long("tpsa")!!.toInt()
            val hba = row.long("hba")!!.toInt()
            val hbd = row.long("hbd")!!.toInt()
            val ld50 = row.long("ld50")!!.toInt()
            val mp = row.long("mp")!!.toInt()
            val bp = row.long("bp")!!.toInt()

            val hasNps = ld50 > 0 || mp > 0 || bp > 0
            val pubchemFloor = if (hasNps) 500 else 250
            logp shouldBeGreaterThan pubchemFloor
            tpsa shouldBeGreaterThan pubchemFloor
            hba shouldBeGreaterThan pubchemFloor
            hbd shouldBeGreaterThan pubchemFloor
            if (hasNps) {
                ld50 shouldBeGreaterThan 0
                mp shouldBeGreaterThan 0
                bp shouldBeGreaterThan 0
            }
        }
    }

    @Test
    fun `common substances carry chemistry via the InChIKey fallback`() {
        openBundledSubstanceDb().use { db ->
            // Codeine has no PubChem CID in the catalog, so its logP/TPSA can
            // only arrive through the InChIKey fallback. MDMA and Alprazolam
            // are the recreational and medical spot-checks.
            for (name in listOf("Codeine", "Lisdexamfetamine", "MDMA", "Alprazolam")) {
                val row = db.queryOne(
                    "SELECT logp, tpsa FROM substances WHERE canonical_name = ?",
                    listOf(name),
                ) ?: error("$name missing from catalog")
                row.double("logp") shouldNotBe null
                row.double("tpsa") shouldNotBe null
            }
        }
    }

    // MARK: - Schema and store shape

    @Test
    fun `schema version is 6`() {
        openBundledSubstanceDb().use { db ->
            // Stage A added the isomer facet columns, the substance_forms
            // enumeration and the facet-annotated aliases, bumping this to 6.
            val row = db.queryOne("SELECT value FROM manifest WHERE key = 'schema_version'")
            row!!.string("value") shouldBe "6"
        }
    }

    @Test
    fun `journal mode is delete`() {
        openBundledSubstanceDb().use { db ->
            // The shipped database must be DELETE-mode (self-contained, no -wal
            // or -shm sidecars), or a read-only bundle open fails with
            // SQLITE_CANTOPEN. The Android side opens it read-only too, so this
            // is load-bearing for the port, not just for iOS.
            val row = db.queryOne("PRAGMA journal_mode")
            row!!.string("journal_mode")!!.lowercase() shouldBe "delete"
        }
    }

    @Test
    fun `every category row is a canonical enum value`() {
        openBundledSubstanceDb().use { db ->
            // No category in the table may fall outside the enum. The enum here
            // is the ported SubstanceCategory, so this doubles as a check that
            // the port did not miss a value the catalog actually uses.
            val known = SubstanceCategory.entries.map { it.wireValue }.toSet()
            val bad = db.query("SELECT DISTINCT category FROM categories")
                .mapNotNull { it.string("category") }
                .filter { it !in known }
            bad.shouldBeEmpty()
        }
    }

    @Test
    fun `no two substances share a normalized canonical name`() {
        openBundledSubstanceDb().use { db ->
            val dups = db.query(
                "SELECT normalized_name FROM substances GROUP BY normalized_name HAVING count(*) > 1",
            ).mapNotNull { it.string("normalized_name") }
            dups.shouldBeEmpty()
        }
    }

    @Test
    fun `known InChIKey collisions stay split`() {
        openBundledSubstanceDb().use { db ->
            // Four pairs whose upstream InChIKeys collide but which are
            // genuinely different drugs. The do-not-merge guard must keep them
            // as separate rows — fusing any pair would silently rewrite what a
            // logged dose of one of them means.
            val pairs = listOf(
                "Methylone" to "Cyclobenzaprine",
                "Cannabis" to "THC",
                "CBC" to "CBG",
                "3-MMC" to "Myristicin",
            )
            for ((a, b) in pairs) {
                val idA = db.queryOne(
                    "SELECT id FROM substances WHERE canonical_name = ?",
                    listOf(a),
                )?.long("id") ?: error("$a missing")
                val idB = db.queryOne(
                    "SELECT id FROM substances WHERE canonical_name = ?",
                    listOf(b),
                )?.long("id") ?: error("$b missing")
                (idA != idB) shouldBe true
            }
        }
    }

    // MARK: - Dose rows

    @Test
    fun `release codes ride the PSID release field`() {
        openBundledSubstanceDb().use { db ->
            // Every stored release code must be encodable as a PSID facet, or a
            // facet-bearing PSID cannot be composed at all.
            //
            // Upstream asserts this with a radix-36 regex. Composing a real PSID
            // and parsing it back is the same claim stated against the actual
            // encoder, so a code that no longer fits the grammar fails here even
            // if it still happens to match the alphabet.
            val codes = db.query(
                "SELECT DISTINCT release_form FROM aliases WHERE release_form IS NOT NULL",
            ).mapNotNull { it.string("release_form") }
            codes.isNotEmpty() shouldBe true
            for (code in codes) {
                val composed = PSID.compose(family = "DUGOZIWVEXMGBE", release = code)
                    ?: error("release code '$code' cannot be encoded into a PSID")
                PSID.parse(composed)!!.release shouldBe code
                // "0" is the unspecified sentinel; a real code colliding with it
                // would make the facet unrepresentable.
                (code != PSID.UNSPECIFIED_FACET) shouldBe true
            }
        }
    }

    @Test
    fun `dose-less stubs are flagged consistently`() {
        openBundledSubstanceDb().use { db ->
            // is_stub == 1 exactly when a substance has zero dose, duration,
            // protocol, half-life AND binding rows. Half-life and receptor
            // pharmacology keep a prescription drug — whose therapeutic dose was
            // correctly stripped — out of the stub set, so it does not read as
            // "Limited data".
            val hasData =
                " exists(select 1 from dose_ranges where substance_id=s.id)" +
                    " or exists(select 1 from durations where substance_id=s.id)" +
                    " or exists(select 1 from protocol_dosing where substance_id=s.id)" +
                    " or exists(select 1 from half_lives where substance_id=s.id)" +
                    " or exists(select 1 from bindings where substance_id=s.id)"

            val flagged = db.queryOne("SELECT count(*) AS c FROM substances WHERE is_stub = 1")!!
                .long("c")!!
            flagged shouldBeGreaterThan 0L

            val leaks = db.queryOne(
                "SELECT count(*) AS c FROM substances s WHERE s.is_stub = 1 AND ($hasData)",
            )!!.long("c")!!
            leaks shouldBe 0L

            val missed = db.queryOne(
                "SELECT count(*) AS c FROM substances s WHERE s.is_stub = 0 AND NOT ($hasData)",
            )!!.long("c")!!
            missed shouldBe 0L
        }
    }

    @Test
    fun `elemental fractions are physical mass ratios`() {
        openBundledSubstanceDb().use { db ->
            // A pinned value so a typo (16 for 0.16) is caught, plus the range
            // invariant across every row that carries one.
            val citrate = db.queryOne(
                "SELECT d.elemental_fraction AS f FROM dose_ranges d " +
                    "JOIN substances s ON s.id = d.substance_id " +
                    "WHERE s.canonical_name = 'Magnesium' AND d.salt_form = 'Citrate'",
            )!!.double("f")!!
            (kotlin.math.abs(citrate - 0.16) < 0.005) shouldBe true

            val outOfRange = db.query(
                "SELECT elemental_fraction AS f FROM dose_ranges WHERE elemental_fraction IS NOT NULL",
            ).mapNotNull { it.double("f") }
                .filter { it <= 0.0 || it >= 1.0 }
            outOfRange.shouldBeEmpty()
        }
    }

    @Test
    fun `every salt-tagged dose row carries a salt rank`() {
        openBundledSubstanceDb().use { db ->
            // The universal default-form intent that applies to any salt,
            // mineral or drug. `elemental_fraction` is a mineral-only
            // enrichment decoupled from the salt concept — it is legitimately
            // NULL for drug salts and is range-checked separately.
            val missing = db.query(
                "SELECT s.canonical_name AS name, d.salt_form AS salt FROM dose_ranges d " +
                    "JOIN substances s ON s.id = d.substance_id " +
                    "WHERE d.salt_form IS NOT NULL AND d.salt_rank IS NULL",
            ).map { "${it.string("name")}/${it.string("salt")}" }
            missing.shouldBeEmpty()
        }
    }

    // MARK: - Names

    @Test
    fun `localized names are unique titles`() {
        openBundledSubstanceDb().use { db ->
            // A localized name is a title, so two substances sharing one in a
            // language are two indistinguishable Library rows.
            val shared = db.query(
                "SELECT lang, name_normalized FROM localized_names " +
                    "GROUP BY lang, name_normalized HAVING count(*) > 1",
            ).map { "${it.string("lang")}/${it.string("name_normalized")}" }
            shared.shouldBeEmpty()

            // And one that equals another substance's canonical name collides
            // with that row too.
            val clashes = db.query(
                "SELECT l.lang AS lang, l.name AS name, s.canonical_name AS canonical " +
                    "FROM localized_names l JOIN substances s " +
                    "ON lower(s.canonical_name) = lower(l.name) AND s.id != l.substance_id",
            ).map { "${it.string("lang")}: ${it.string("name")} vs ${it.string("canonical")}" }
            clashes.shouldBeEmpty()
        }
    }

    @Test
    fun `boiling point is above melting point`() {
        openBundledSubstanceDb().use { db ->
            // A boiling point below the melting point is impossible — that is a
            // sublimation point mislabelled as a BP (caffeine was the case).
            val bad = db.query(
                "SELECT canonical_name AS name FROM substances " +
                    "WHERE melting_point_c IS NOT NULL AND boiling_point_c IS NOT NULL " +
                    "AND boiling_point_c < melting_point_c",
            ).mapNotNull { it.string("name") }
            bad.shouldBeEmpty()
        }
    }

    @Test
    fun `no all-lowercase substance names`() {
        openBundledSubstanceDb().use { db ->
            // smart_title_case should have upgraded every all-lowercase name.
            // Only Latin names have case — CJK-named FreeOD entries cannot be
            // title-cased — and numeric-prefixed names like "5-meo-dmt" are
            // exempt because they become 5-MEO-DMT. A tiny bleed is tolerated;
            // the point is to catch hundreds of un-cased names.
            val rows = db.query(
                "SELECT canonical_name AS name FROM substances " +
                    "WHERE canonical_name = lower(canonical_name) " +
                    "AND canonical_name GLOB '*[a-z]*' " +
                    "AND canonical_name NOT GLOB '[0-9]*'",
            ).mapNotNull { it.string("name") }
            rows.size shouldBeLessThan 5
        }
    }

    @Test
    fun `no CJK IUPAC names`() {
        openBundledSubstanceDb().use { db ->
            // iupac_name is Latin-only. FreeOD's Chinese 系统名称 must be gated,
            // with PubChem supplying the English systematic name instead.
            val rows = db.query(
                "SELECT canonical_name AS name FROM substances WHERE iupac_name GLOB '*[一-鿿]*'",
            ).mapNotNull { it.string("name") }
            rows.shouldBeEmpty()
        }
    }

    @Test
    fun `no substance carries a duplicate alias`() {
        openBundledSubstanceDb().use { db ->
            // Case and salt variants of the same alias must be collapsed to one
            // row per substance by the alias-level dedup.
            val n = db.queryOne(
                "SELECT count(*) AS c FROM (" +
                    "SELECT 1 FROM aliases GROUP BY substance_id, alias_normalized HAVING count(*) > 1)",
            )!!.long("c")!!
            n shouldBe 0L
        }
    }

    @Test
    fun `Cannabis does not alias distinct molecules`() {
        openBundledSubstanceDb().use { db ->
            // THC, CBD, cannabidiol, dronabinol and friends are substances with
            // their own entries. Sources sometimes offer them as aliases of the
            // plant, and the ingester's blocklist must drop them on insert.
            val bad = setOf(
                "thc", "cbd", "cannabidiol", "dronabinol", "tetrahydrocannabinol", "delta-9-thc",
            )
            val leaked = db.query(
                "SELECT a.alias AS alias FROM aliases a " +
                    "JOIN substances s ON s.id = a.substance_id " +
                    "WHERE s.canonical_name = 'Cannabis'",
            ).mapNotNull { it.string("alias") }
                .filter { it.lowercase() in bad }
            leaked.shouldBeEmpty()
        }
    }

    @Test
    fun `Cannabidiol is collapsed into CBD`() {
        openBundledSubstanceDb().use { db ->
            // A separate `Cannabidiol` row must not exist — the name-remap folds
            // it into the canonical `CBD` row, which must itself survive.
            db.queryOne("SELECT id FROM substances WHERE canonical_name = 'Cannabidiol'") shouldBe null
            (db.queryOne("SELECT id FROM substances WHERE canonical_name = 'CBD'") != null) shouldBe true
        }
    }

    @Test
    fun `Lithium ships no dose ladder`() {
        openBundledSubstanceDb().use { db ->
            // Prescription-only with a narrow therapeutic index, so it carries no
            // dose ladder, salt-tagged or otherwise. It is the substance the salt
            // exemption used to protect, which makes this the regression guard.
            val n = db.queryOne(
                "SELECT count(*) AS c FROM dose_ranges d " +
                    "JOIN substances s ON s.id = d.substance_id " +
                    "WHERE s.canonical_name = 'Lithium'",
            )!!.long("c")!!
            n shouldBe 0L
        }
    }

    @Test
    fun `no route-suffix canonical names survive`() {
        openBundledSubstanceDb().use { db ->
            // Route-suffix collapse leaves no `<base>-<route>` canonical: the
            // route belongs in the `route` column, not in the name.
            val orphans = db.query(
                "SELECT canonical_name AS name FROM substances WHERE " +
                    "canonical_name LIKE '%-topical' OR canonical_name LIKE '%-inhaled' " +
                    "OR canonical_name LIKE '%-nasal' OR canonical_name LIKE '%-ophthalmic'",
            ).mapNotNull { it.string("name") }
            orphans.shouldBeEmpty()
        }
    }

    // MARK: - Dose gates

    @Test
    fun `fentanyl-class dose ceiling holds`() {
        openBundledSubstanceDb().use { db ->
            // No substance tagged `fentanyl-class-potency` or `fentanyl-analog`
            // may keep a dose row whose highest tier, converted to mg, exceeds
            // 2 mg. The ingest gate should have dropped such rows.
            val violations = mutableListOf<String>()
            for (row in db.query(
                "SELECT s.canonical_name AS name, dr.unit AS unit, dr.route AS route, " +
                    "dr.threshold AS threshold, dr.light_lower AS light_lower, " +
                    "dr.light_upper AS light_upper, dr.common_lower AS common_lower, " +
                    "dr.common_upper AS common_upper, dr.strong_lower AS strong_lower, " +
                    "dr.strong_upper AS strong_upper, dr.heavy AS heavy " +
                    "FROM dose_ranges dr JOIN substances s ON s.id = dr.substance_id " +
                    "WHERE dr.substance_id IN (SELECT substance_id FROM tags " +
                    "WHERE tag IN ('fentanyl-class-potency', 'fentanyl-analog'))",
            )) {
                val factor = unitToMgFactor(row.string("unit")) ?: continue
                val tiers = listOf(
                    "threshold", "light_lower", "light_upper", "common_lower",
                    "common_upper", "strong_lower", "strong_upper", "heavy",
                ).mapNotNull { row.double(it) }
                val max = tiers.maxOrNull()?.times(factor) ?: 0.0
                if (max > 2.0) {
                    violations += "${row.string("name")}/${row.string("route")} ${row.string("unit")} = $max mg"
                }
            }
            violations.shouldBeEmpty()
        }
    }

    @Test
    fun `dose ladders are monotonic within tolerance`() {
        openBundledSubstanceDb().use { db ->
            // Some legacy source-data noise is acceptable — drug.community and
            // TripSit conventions produce overlapping ranges — but the count
            // should not balloon. Upstream's threshold is 120 across all sources.
            val n = db.queryOne(
                "SELECT count(*) AS c FROM dose_ranges WHERE " +
                    "(threshold IS NOT NULL AND light_lower IS NOT NULL AND threshold > light_lower) OR " +
                    "(light_upper IS NOT NULL AND common_lower IS NOT NULL AND light_upper > common_lower) OR " +
                    "(common_upper IS NOT NULL AND strong_lower IS NOT NULL AND common_upper > strong_lower) OR " +
                    "(strong_upper IS NOT NULL AND heavy IS NOT NULL AND strong_upper > heavy)",
            )!!.long("c")!!.toInt()
            n shouldBeLessThan 120
        }
    }

    // MARK: - Identity folding

    @Test
    fun `stereoisomers are folded into their parent`() {
        openBundledSubstanceDb().use { db ->
            // Curated enantiomers fold INTO their racemic parent as the isomer
            // form. The variant is no longer a standalone row — it survives as a
            // searchable alias, and where it carried its own dose ladder the
            // parent now has isomer-tagged rows, so per-enantiomer dosing is
            // preserved rather than flattened.
            val cases = listOf(
                FoldCase("Methylphenidate", "Dexmethylphenidate", "D", hasDose = true),
                FoldCase("Amphetamine", "Dextroamphetamine", "D", hasDose = true),
                FoldCase("Modafinil", "Armodafinil", "R", hasDose = true),
                FoldCase("Citalopram", "Escitalopram", "S", hasDose = false),
            )
            for (c in cases) {
                val parentId = db.queryOne(
                    "SELECT id FROM substances WHERE lower(canonical_name) = lower(?)",
                    listOf(c.parent),
                )?.long("id") ?: error("${c.parent} missing")

                db.queryOne(
                    "SELECT id FROM substances WHERE lower(canonical_name) = lower(?)",
                    listOf(c.variant),
                ) shouldBe null

                (db.queryOne(
                    "SELECT 1 AS x FROM aliases WHERE substance_id = ? AND lower(alias) = lower(?)",
                    listOf(parentId, c.variant),
                ) != null) shouldBe true

                if (c.hasDose) {
                    val n = db.queryOne(
                        "SELECT count(*) AS c FROM dose_ranges WHERE substance_id = ? AND isomer = ?",
                        listOf(parentId, c.isomer),
                    )!!.long("c")!!
                    n shouldBeGreaterThan 0L
                }
            }
        }
    }

    @Test
    fun `route-suffix parents are present and searchable`() {
        openBundledSubstanceDb().use { db ->
            // Folded variants survive as parent aliases, so brand search still
            // works, and the parent exists as a single canonical entry.
            val cases = listOf(
                "Fluticasone" to "Flonase",
                "Beclomethasone" to "QVAR RediHaler",
                "Hydrocortisone" to "Cortaid",
            )
            for ((parent, alias) in cases) {
                val parentId = db.queryOne(
                    "SELECT id FROM substances WHERE canonical_name = ?",
                    listOf(parent),
                )?.long("id") ?: error("route-collapse parent '$parent' missing")
                val n = db.queryOne(
                    "SELECT count(*) AS c FROM aliases WHERE substance_id = ? AND alias = ?",
                    listOf(parentId, alias),
                )!!.long("c")!!
                n shouldBe 1L
            }
        }
    }

    @Test
    fun `reported brand and generic pairs are merged`() {
        openBundledSubstanceDb().use { db ->
            // A brand and its generic must resolve to ONE substance, and the
            // brand must not survive as its own canonical record.
            val cases = listOf(
                "Vyvanse" to "Lisdexamfetamine",
                "Focalin" to "Dexmethylphenidate",
                "Adderall" to "Amphetamine",
            )
            for ((brand, generic) in cases) {
                val genericIds = db.resolveIds(generic)
                genericIds.isNotEmpty() shouldBe true
                (genericIds.first() in db.resolveIds(brand)) shouldBe true
                db.queryOne(
                    "SELECT 1 AS x FROM substances WHERE lower(canonical_name) = lower(?)",
                    listOf(brand),
                ) shouldBe null
            }
        }
    }

    private data class FoldCase(
        val parent: String,
        val variant: String,
        val isomer: String,
        val hasDose: Boolean,
    )

    // MARK: - Salt families

    @Test
    fun `salt families are folded with per-salt ladders`() {
        openBundledSubstanceDb().use { db ->
            // Salt folding collapses variants into a shared parent whose dose
            // ladders are tagged by `salt_form`, leaving no orphan variant.
            //
            // Lithium is deliberately absent: it folds like any salt family, but
            // as a prescription drug it ships no dose ladder at all, so there are
            // no salt-tagged rows to find. Its folding is covered by the
            // orphan test below.
            val parentId = db.queryOne(
                "SELECT id FROM substances WHERE canonical_name = 'Magnesium'",
            )?.long("id") ?: error("salt-family parent 'Magnesium' missing")
            val got = db.query(
                "SELECT DISTINCT salt_form AS salt FROM dose_ranges " +
                    "WHERE substance_id = ? AND salt_form IS NOT NULL",
                listOf(parentId),
            ).mapNotNull { it.string("salt") }.toSet()
            val expected = setOf("Citrate", "Glycinate", "L-Threonate")
            (got.containsAll(expected)) shouldBe true
        }
    }

    @Test
    fun `no un-folded salt variants remain`() {
        openBundledSubstanceDb().use { db ->
            val orphans = db.query(
                "SELECT canonical_name AS name FROM substances WHERE canonical_name IN " +
                    "('Magnesium Citrate','Magnesium Glycinate','Magnesium Threonate'," +
                    "'Lithium Carbonate','Lithium orotate')",
            ).mapNotNull { it.string("name") }
            orphans.shouldBeEmpty()
        }
    }

    @Test
    fun `antacid combos are not treated as salts`() {
        openBundledSubstanceDb().use { db ->
            // A combo product is a mixture, not a salt form, so it stays a
            // standalone row rather than folding into a parent.
            for (combo in listOf("Magnesium/Magaldrate", "Magnesium/Sodium")) {
                (db.queryOne(
                    "SELECT 1 AS x FROM substances WHERE canonical_name = ?",
                    listOf(combo),
                ) != null) shouldBe true
            }
        }
    }

    // MARK: - Prose bounds

    @Test
    fun `no mechanism summary is a whole document`() {
        openBundledSubstanceDb().use { db ->
            // The mechanism summary is a headline slot. A source that dumps a
            // whole FDA label section into it renders as a wall of PK tables on
            // the detail page, so the build bounds it at the single insertion
            // point. 1200 is `MAX_MECHANISM_SUMMARY_CHARS` from
            // `pipeline/build/sqlite.py` — keep it in step with that constant.
            val limit = 1200
            val over = db.query(
                "SELECT s.canonical_name AS name, src.slug AS slug, m.language AS lang, " +
                    "length(m.summary) AS n FROM mechanisms_summary m " +
                    "JOIN substances s ON s.id = m.substance_id " +
                    "JOIN sources src ON src.id = m.source_id " +
                    "WHERE length(m.summary) > ? ORDER BY n DESC",
                listOf(limit),
            ).map { "${it.string("name")}/${it.string("slug")}/${it.string("lang")} = ${it.long("n")}" }
            over.shouldBeEmpty()
        }
    }

    // MARK: - Effect vocabulary

    @Test
    fun `effect vocabulary labels are trilingual`() {
        openBundledSubstanceDb().use { db ->
            // Every vocab_id needs a label in each shipped language, and the
            // derivation is disclosed honestly: zh-Hant is OpenCC-derived and
            // flagged machine_translated, zh-Hans is curated and is not.
            val vocabIds = db.query("SELECT vocab_id AS id FROM effect_vocab")
                .mapNotNull { it.string("id") }.toSet()
            vocabIds.isNotEmpty() shouldBe true
            for (lang in listOf("en", "zh-Hans", "zh-Hant")) {
                val covered = db.query(
                    "SELECT vocab_id AS id FROM effect_vocab_labels WHERE language = ?",
                    listOf(lang),
                ).mapNotNull { it.string("id") }.toSet()
                (vocabIds - covered).shouldBeEmpty()
            }

            val hant = db.queryOne(
                "SELECT min(machine_translated) AS lo, max(machine_translated) AS hi " +
                    "FROM effect_vocab_labels WHERE language = 'zh-Hant'",
            )!!
            hant.long("lo") shouldBe 1L
            hant.long("hi") shouldBe 1L

            val hansMax = db.queryOne(
                "SELECT max(machine_translated) AS hi FROM effect_vocab_labels " +
                    "WHERE language = 'zh-Hans'",
            )!!.long("hi")
            hansMax shouldBe 0L
        }
    }

    // MARK: - Form enumeration

    @Test
    fun `substance_forms enumerates isomers`() {
        openBundledSubstanceDb().use { db ->
            // One row per known (uid, stereo, salt, release), with a composed
            // PSID and display title. The base form is marked default, and even a
            // dose-less folded enantiomer gets an identity and title row so a
            // logged form can be displayed.
            val methylphenidate = db.query(
                "SELECT f.stereo AS stereo, f.salt AS salt, f.release AS release, " +
                    "f.display_name AS name, f.is_default AS is_default " +
                    "FROM substance_forms f JOIN substances s ON s.id = f.substance_id " +
                    "WHERE s.canonical_name = 'Methylphenidate'",
            ).associateBy {
                Triple(it.string("stereo"), it.string("salt"), it.string("release"))
            }
            val base = methylphenidate[Triple("0", "0", "0")]!!
            base.string("name") shouldBe "Methylphenidate"
            base.long("is_default") shouldBe 1L
            methylphenidate[Triple("D", "0", "0")]!!.string("name") shouldBe "Dexmethylphenidate"

            val ketamine = db.query(
                "SELECT f.display_name AS name FROM substance_forms f " +
                    "JOIN substances s ON s.id = f.substance_id WHERE s.canonical_name = 'Ketamine'",
            ).mapNotNull { it.string("name") }.toSet()
            ketamine shouldBe setOf("Ketamine", "Esketamine", "Arketamine")

            val methamphetamine = db.query(
                "SELECT f.display_name AS name FROM substance_forms f " +
                    "JOIN substances s ON s.id = f.substance_id " +
                    "WHERE s.canonical_name = 'Methamphetamine'",
            ).mapNotNull { it.string("name") }.toSet()
            (methamphetamine.contains("Dextromethamphetamine")) shouldBe true
        }
    }

    @Test
    fun `release-bearing aliases carry a release facet`() {
        openBundledSubstanceDb().use { db ->
            // A release-form brand is annotated with its release facet on the
            // parent, so a logged "Concerta" recovers 'XR'. Covers both detection
            // routes: the token-bearing names the regex reads ("Adderall XR") and
            // the tokenless brands only curation knows ("Concerta").
            val cases = listOf(
                Triple("Concerta", "Methylphenidate", "XR"), // curated: no token in the name
                Triple("Ritalin LA", "Methylphenidate", "XR"), // detected: trailing token
                Triple("Adderall XR", "Amphetamine", "XR"),
                Triple("Adderall IR", "Amphetamine", "IR"),
                Triple("Morphine Sulfate Extended-release", "Morphine", "XR"),
                Triple("Kapvay", "Clonidine", "XR"),
                Triple("Vivitrol", "Naltrexone", "DEP"),
                Triple("Invega Sustenna", "Paliperidone", "DEP"),
                Triple("Invega", "Paliperidone", "XR"), // oral ER, NOT the depot sibling
            )
            for ((alias, parent, code) in cases) {
                val row = db.queryOne(
                    "SELECT s.canonical_name AS parent, a.release_form AS release FROM aliases a " +
                        "JOIN substances s ON s.id = a.substance_id WHERE lower(a.alias) = lower(?)",
                    listOf(alias),
                ) ?: error("$alias alias missing")
                row.string("parent") shouldBe parent
                row.string("release") shouldBe code
            }
        }
    }

    @Test
    fun `non-release forms are not tagged with a release facet`() {
        openBundledSubstanceDb().use { db ->
            // The negative half — asserting a form the name never claimed is how
            // this feature does damage. A bare base brand is the unspecified form,
            // not "IR"; a prodrug's long duration comes from metabolism, not a
            // formulation; and a patch is the *route* axis, so tagging it here
            // would conflate two orthogonal axes.
            for (alias in listOf(
                "Adderall", // base brand — the unspecified form, sibling of Adderall XR/IR
                "Ritalin",
                "Vyvanse", // prodrug, not a release form
                "Daytrana", // transdermal patch — route axis, not release
                "Suboxone", // combination product
            )) {
                val rows = db.query(
                    "SELECT a.release_form AS release FROM aliases a WHERE lower(a.alias) = lower(?)",
                    listOf(alias),
                )
                rows.isNotEmpty() shouldBe true
                for (row in rows) {
                    row.string("release") shouldBe null
                }
            }
        }
    }

    @Test
    fun `a cross-axis alias carries both facets`() {
        openBundledSubstanceDb().use { db ->
            // Focalin XR is the D-enantiomer *and* extended-release. The isomer
            // pass matches "focalin" exactly, so it never sees "focalin xr";
            // without the release-stripped fallback this alias would be tagged
            // XR-but-racemic, which asserts the wrong drug.
            for (alias in listOf(
                "Focalin XR",
                "Dexmethylphenidate Hydrochloride Extended-release",
            )) {
                val row = db.queryOne(
                    "SELECT s.canonical_name AS parent, a.isomer AS isomer, " +
                        "a.release_form AS release FROM aliases a " +
                        "JOIN substances s ON s.id = a.substance_id WHERE lower(a.alias) = lower(?)",
                    listOf(alias),
                ) ?: error("$alias alias missing")
                row.string("parent") shouldBe "Methylphenidate"
                row.string("isomer") shouldBe "D"
                row.string("release") shouldBe "XR"
            }

            // Both axes must compose into one title.
            val title = db.queryOne(
                "SELECT f.display_name AS name FROM substance_forms f " +
                    "JOIN substances s ON s.id = f.substance_id " +
                    "WHERE s.canonical_name = 'Methylphenidate' " +
                    "AND f.stereo = 'D' AND f.salt = '0' AND f.release = 'XR'",
            ) ?: error("no (D, XR) form enumerated for Methylphenidate")
            title.string("name") shouldBe "Dexmethylphenidate XR"
        }
    }

    @Test
    fun `substance_forms enumerates release forms`() {
        openBundledSubstanceDb().use { db ->
            // A release-bearing alias spawns its own identity and title row, so a
            // logged brand can be titled from its resolved form. A release row is
            // never the default — the unspecified form is.
            val forms = db.query(
                "SELECT f.stereo AS stereo, f.release AS release, f.display_name AS name, " +
                    "f.is_default AS is_default FROM substance_forms f " +
                    "JOIN substances s ON s.id = f.substance_id " +
                    "WHERE s.canonical_name = 'Methylphenidate' AND f.salt = '0'",
            ).associateBy { it.string("stereo") to it.string("release") }

            forms["0" to "XR"]!!.string("name") shouldBe "Methylphenidate XR"
            forms["0" to "XR"]!!.long("is_default") shouldBe 0L
            forms["0" to "0"]!!.long("is_default") shouldBe 1L

            // `titleSuffix` exists so a code that does not read as a suffix gets
            // prose instead — "Naltrexone Depot", not "Naltrexone DEP".
            val depot = db.queryOne(
                "SELECT f.display_name AS name FROM substance_forms f " +
                    "JOIN substances s ON s.id = f.substance_id " +
                    "WHERE s.canonical_name = 'Naltrexone' AND f.release = 'DEP'",
            )!!
            depot.string("name") shouldBe "Naltrexone Depot"
        }
    }

    @Test
    fun `substance_forms are written in a stable order`() {
        openBundledSubstanceDb().use { db ->
            // Insertion order within a substance must not depend on
            // PYTHONHASHSEED. An unsorted set here once made two builds of
            // identical inputs differ in row order, and the database is published
            // under the checksum of the committed manifest — so an unstable order
            // would break every fetch.
            val bySubstance = mutableMapOf<Long, MutableList<List<String>>>()
            for (row in db.query(
                "SELECT substance_id AS sid, isomer_label AS isomer, salt_label AS salt, " +
                    "release_label AS release FROM substance_forms ORDER BY rowid",
            )) {
                val key = row.long("sid")!!
                bySubstance.getOrPut(key) { mutableListOf() }
                    .add(listOf(row.string("isomer") ?: "", row.string("salt") ?: "", row.string("release") ?: ""))
            }
            // Element-wise lexicographic, matching how Python compares the
            // tuples upstream: Kotlin's `List<String>` is not `Comparable`, so
            // `sorted()` does not apply and an explicit comparator is needed.
            val labels = compareBy<List<String>>(
                { it.getOrElse(0) { "" } },
                { it.getOrElse(1) { "" } },
                { it.getOrElse(2) { "" } },
            )
            val unstable = bySubstance.filterValues { it != it.sortedWith(labels) }.keys
            unstable.shouldBeEmpty()
        }
    }

    // MARK: - Categorisation

    @Test
    fun `known substances land in the categories users expect`() {
        openBundledSubstanceDb().use { db ->
            // Catches regressions where the category-mapping rules silently
            // break. Several entries record a curated override beating a
            // source's own category, which is the point of the set.
            val expectations = mapOf(
                // Antipsychotics
                "Risperidone" to "Antipsychotic",
                "Olanzapine" to "Antipsychotic",
                "Quetiapine" to "Antipsychotic",
                "Aripiprazole" to "Antipsychotic",
                // Antidepressants
                "Fluoxetine" to "Antidepressant",
                "Sertraline" to "Antidepressant",
                "Venlafaxine" to "Antidepressant",
                "Bupropion" to "Antidepressant", // curated beats TripSit's "Stimulant"
                // Pure peripheral H1 antagonists must NOT be pulled into the
                // Deliriant bucket — they are not anticholinergic deliriants.
                "Cetirizine" to "Antihistamine",
                "Loratadine" to "Antihistamine",
                // Anticholinergic deliriants and the first-generation
                // antihistamines, split out of Antihistamine by curated overrides.
                "Diphenhydramine" to "Deliriant",
                "Doxylamine" to "Deliriant",
                "Datura" to "Deliriant", // was Dysdelic; a deliriant, not a κ-hallucinogen
                "Scopolamine" to "Deliriant",
                // Salvia and selective κ-agonist RCs join the salvinorins.
                "Salvia" to "Dysdelic", // curated override beats TripSit's "Dissociative"
                "Salvinorin A" to "Dysdelic",
                "U-51754" to "Dysdelic",
                // Cannabinoids
                "THC" to "Cannabinoid",
                "CBD" to "Cannabinoid",
                "Delta-8-THC" to "Cannabinoid",
                "Propranolol" to "Cardiovascular",
                "Caffeine" to "Stimulant",
                "2C-B" to "Psychedelic", // reclassified from Other / Empathogen
                "Methadone" to "Opioid",
                "Kratom" to "Opioid", // curated override beats PsychonautWiki's "Stimulant"
                "Diazepam" to "Benzodiazepine",
                "Semaglutide" to "Peptide",
                "BPC-157" to "Peptide",
                "Tirzepatide" to "Peptide",
                "Lithium Carbonate" to "Anticonvulsant",
                "Valproate" to "Anticonvulsant",
                "Lamotrigine" to "Anticonvulsant",
                // Source data gets these wrong or omits them entirely: Psilocybin
                // had only a wikidata category ("Other"), Psilocin and Ayahuasca
                // had wrong TripSit categories ("Empathogen").
                "Psilocybin" to "Psychedelic",
                "Psilocin" to "Psychedelic",
                "Ayahuasca" to "Psychedelic",
            )
            val priorities = db.query("SELECT slug AS slug, default_priority AS p FROM sources")
                .associate { it.string("slug")!! to (it.long("p") ?: 999L) }

            val wrong = expectations.mapNotNull { (name, expected) ->
                val actual = db.resolvedCategory(name, priorities)
                if (actual == expected) null else "$name: got $actual, expected $expected"
            }
            wrong.shouldBeEmpty()
        }
    }

    // MARK: - Chemistry

    @Test
    fun `formula agrees with molecular weight`() {
        openBundledSubstanceDb().use { db ->
            // A stored molecular_weight must match the mass computed from its
            // formula — no salt mass on a free-base formula. Tolerance 2%.
            val bad = db.query(
                "SELECT canonical_name AS name, formula AS formula, " +
                    "molecular_weight AS mw FROM substances " +
                    "WHERE formula IS NOT NULL AND molecular_weight IS NOT NULL",
            ).mapNotNull { row ->
                val computed = formulaMass(row.string("formula")) ?: return@mapNotNull null
                val stored = row.double("mw")!!
                if (kotlin.math.abs(stored - computed) / computed > 0.02) {
                    "${row.string("name")}: $stored vs $computed (${row.string("formula")})"
                } else {
                    null
                }
            }
            bad.shouldBeEmpty()
        }
    }

    @Test
    fun `physicochemical columns are present`() {
        openBundledSubstanceDb().use { db ->
            val cols = db.query("PRAGMA table_info(substances)")
                .mapNotNull { it.string("name") }.toSet()
            for (col in listOf(
                "logp", "tpsa", "hba", "hbd", "ld50_oral_mg_per_kg",
                "ld50_dermal_mg_per_kg", "melting_point_c", "boiling_point_c",
            )) {
                (col in cols) shouldBe true
            }
        }
    }

    // MARK: - Products and salt defaults

    @Test
    fun `product durations are seeded`() {
        openBundledSubstanceDb().use { db ->
            // Extended-release brands carry a per-product duration envelope,
            // keyed by the specific product so 'XR' resolves to the right
            // formulation, with the parent resolved for the coverage gate.
            val rows = db.query(
                "SELECT product_normalized AS product, substance_id AS sid, " +
                    "total_min AS lo, total_max AS hi FROM product_durations",
            ).associateBy { it.string("product") }

            ("concerta" in rows) shouldBe true
            ("adderall xr" in rows) shouldBe true
            // Concerta's ~11–12 h envelope, far past methylphenidate IR's ~3–5 h.
            (rows["concerta"]!!.double("hi")!! >= 660.0) shouldBe true
            // Every seeded product must have resolved its parent, or it is dead data.
            for (row in rows.values) {
                row.long("sid") shouldNotBe null
            }
        }
    }

    @Test
    fun `salt rank encodes the curated default`() {
        openBundledSubstanceDb().use { db ->
            // Lithium is absent: no dose rows survive for it to rank, since
            // therapeutic doses are suppressed.
            val row = db.queryOne(
                "SELECT d.salt_form AS salt FROM dose_ranges d " +
                    "JOIN substances s ON s.id = d.substance_id " +
                    "WHERE s.canonical_name = 'Magnesium' AND d.salt_rank = 0",
            ) ?: error("Magnesium: no rank-0 default salt row")
            row.string("salt") shouldBe "Glycinate"
        }
    }

    @Test
    fun `salt-tagged supplement durations are removed`() {
        openBundledSubstanceDb().use { db ->
            // The audit drops the salt-tagged acute durations on Mg/Li
            // supplements, whose curves would be imperceptible. None survive.
            val n = db.queryOne(
                "SELECT count(*) AS c FROM durations d " +
                    "JOIN substances s ON s.id = d.substance_id " +
                    "WHERE s.canonical_name IN ('Magnesium','Lithium') AND d.salt_form IS NOT NULL",
            )!!.long("c")!!
            n shouldBe 0L
        }
    }

    // MARK: - Prose shape

    @Test
    fun `no mechanism summary opens with a label header`() {
        openBundledSubstanceDb().use { db ->
            // Medtap's Pharmacology section is a header/value table, and
            // flattening it left the header inline — every summary began
            // "Mechanism Of Action …" and ran on through absorption, protein
            // binding and half-life.
            val headers = listOf(
                "Mechanism Of Action", "Mechanism of Action", "Pharmacokinetics",
                "Pharmacodynamics", "Absorption", "Protein Binding", "Volume Of Distribution",
            )
            val offenders = db.query(
                "SELECT s.canonical_name AS name, m.summary AS summary " +
                    "FROM mechanisms_summary m JOIN substances s ON s.id = m.substance_id",
            ).filter { row -> headers.any { row.string("summary")!!.startsWith(it) } }
                .mapNotNull { it.string("name") }
            offenders.shouldBeEmpty()
        }
    }

    // MARK: - Effect vocabulary shape

    @Test
    fun `effect vocabulary tables are present`() {
        openBundledSubstanceDb().use { db ->
            val tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'")
                .mapNotNull { it.string("name") }.toSet()
            ("effect_vocab" in tables) shouldBe true
            ("effect_vocab_labels" in tables) shouldBe true

            val effectCols = db.query("PRAGMA table_info(effects)")
                .mapNotNull { it.string("name") }.toSet()
            ("vocab_id" in effectCols) shouldBe true
        }
    }

    @Test
    fun `effect vocabulary is seeded and categorised`() {
        openBundledSubstanceDb().use { db ->
            val n = db.queryOne("SELECT count(*) AS c FROM effect_vocab")!!.long("c")!!.toInt()
            n shouldBeGreaterThan 200

            val uncategorised = db.queryOne(
                "SELECT count(*) AS c FROM effect_vocab WHERE category IS NULL OR category = ''",
            )!!.long("c")!!
            uncategorised shouldBe 0L
        }
    }

    @Test
    fun `effects are linked to the vocabulary`() {
        openBundledSubstanceDb().use { db ->
            // The build-time matcher stamps vocab_id on almost every whitelisted
            // effect row; the few unmatched keep raw text as a fallback.
            val row = db.queryOne("SELECT count(vocab_id) AS linked, count(*) AS total FROM effects")!!
            val linked = row.long("linked")!!.toDouble()
            val total = row.long("total")!!.toDouble()
            (linked / total > 0.98) shouldBe true

            // FK integrity: every non-null vocab_id resolves to a real entry.
            val orphans = db.queryOne(
                "SELECT count(*) AS c FROM effects e " +
                    "LEFT JOIN effect_vocab v ON v.vocab_id = e.vocab_id " +
                    "WHERE e.vocab_id IS NOT NULL AND v.vocab_id IS NULL",
            )!!.long("c")!!
            orphans shouldBe 0L
        }
    }

    @Test
    fun `an English-only substance still resolves localized effects`() {
        openBundledSubstanceDb().use { db ->
            // The controlled vocabulary's payoff: a substance whose effects came
            // from English-only sources still resolves a zh-Hans label for each,
            // through vocab_id, even though no zh effect row was ever ingested
            // for it.
            val sid = db.queryOne(
                "SELECT id FROM substances WHERE canonical_name = 'Caffeine'",
            )?.long("id") ?: error("Caffeine missing")

            val effects = db.query(
                "SELECT e.text AS text, l.label AS zh FROM effects e " +
                    "JOIN effect_vocab_labels l ON l.vocab_id = e.vocab_id " +
                    "AND l.language = 'zh-Hans' WHERE e.substance_id = ?",
                listOf(sid),
            )
            (effects.isNotEmpty()) shouldBe true

            val missing = effects.filter { it.string("zh").isNullOrEmpty() }
                .mapNotNull { it.string("text") }
            missing.shouldBeEmpty()
        }
    }

    // MARK: - Dose values and duplicate debt

    @Test
    fun `salt dose values are unchanged`() {
        openBundledSubstanceDb().use { db ->
            // The metadata and audit passes must not perturb a salt dose value.
            // These are the ranges pinned by the app's own salt-form tests.
            val expected = mapOf(
                ("Magnesium" to "Citrate") to (400.0 to 600.0),
                ("Magnesium" to "Glycinate") to (200.0 to 400.0),
                ("Magnesium" to "L-Threonate") to (1500.0 to 2000.0),
            )
            for ((key, range) in expected) {
                val (parent, salt) = key
                val row = db.queryOne(
                    "SELECT d.common_lower AS lo, d.common_upper AS hi FROM dose_ranges d " +
                        "JOIN substances s ON s.id = d.substance_id " +
                        "WHERE s.canonical_name = ? AND d.salt_form = ?",
                    listOf(parent, salt),
                ) ?: error("$parent $salt: dose row missing")
                row.double("lo") shouldBe range.first
                row.double("hi") shouldBe range.second
            }
        }
    }

    @Test
    fun `unmerged duplicate debt stays bounded`() {
        openBundledSubstanceDb().use { db ->
            // Data-poor records whose canonical name is another substance's alias
            // but which lack the InChIKey confirmation needed to merge safely —
            // auto-merge needs positive structural proof, since distinct drugs
            // sometimes cross-list each other (loratadine ↔ fexofenadine). The
            // bound catches a real dedup REGRESSION, which would spike this into
            // the hundreds; the residual few dozen are obscure RC abbreviation
            // and salt variants awaiting backfill or a curated remap.
            val n = db.queryOne(
                "SELECT count(*) AS c FROM substances s " +
                    "JOIN aliases a ON a.alias_normalized = s.normalized_name " +
                    "AND a.substance_id != s.id " +
                    "WHERE NOT EXISTS (SELECT 1 FROM dose_ranges d WHERE d.substance_id = s.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM durations d WHERE d.substance_id = s.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM bindings d WHERE d.substance_id = s.id) " +
                    "AND NOT EXISTS (SELECT 1 FROM effects d WHERE d.substance_id = s.id)",
            )!!.long("c")!!.toInt()
            n shouldBeLessThan 75
        }
    }

    @Test
    fun `a recognizable substance outranks an obscure RC`() {
        openBundledSubstanceDb().use { db ->
            // The regression that motivated the reproducible popularity signal: a
            // recognizable benzofuran must sort above an obscure RC, rather than
            // below it alphabetically.
            fun popularity(name: String): Double? = db.queryOne(
                "SELECT popularity AS p FROM substances WHERE canonical_name = ?",
                listOf(name),
            )?.double("p")

            val apb = popularity("6-APB")
            val obscure = popularity("2-Bromo-4,5-MDMA")
            org.junit.jupiter.api.Assumptions.assumeTrue(
                apb != null && obscure != null,
                "benchmark substances absent",
            )
            (apb!! > obscure!!) shouldBe true
        }
    }
}

/**
 * Every substance id a name resolves to, as canonical or as an alias.
 *
 * Ported from `TestBuiltDatabaseInvariants._resolve_ids`.
 */
private fun SubstanceDb.resolveIds(name: String): List<Long> {
    val n = name.lowercase()
    return query(
        "SELECT s.id AS id FROM substances s WHERE lower(s.canonical_name) = ? " +
            "UNION SELECT a.substance_id AS id FROM aliases a WHERE lower(a.alias) = ?",
        listOf(n, n),
    ).mapNotNull { it.long("id") }.sorted()
}

/**
 * The numeric tier value's factor into mg, or null when the unit cannot be
 * interpreted as a mass.
 *
 * Ported from `unit_to_mg_factor` in `pipeline/build/sqlite.py`. This is
 * deliberately NOT `DoseUnit.convert`, which answers the opposite way for
 * delivery rates: this check treats a `µg/hr` patch's numeric as a microgram
 * quantity ("the numeric magnitude check still applies"), while `DoseUnit`
 * rejects a rate outright as not a mass — behaviour its own spec pins with
 * "A rate is not a mass". Same question, two different intended answers.
 */
private fun unitToMgFactor(unit: String?): Double? {
    if (unit == null) return 1.0
    val u = unit.lowercase().trim()
    if (u.isEmpty()) return 1.0

    // A per-mass or per-time-of-non-mass unit makes the bare tier value not a
    // mass, so the invariant does not apply cleanly.
    if ("/kg" in u || "/day" in u || "/24h" in u) return null

    return when (u) {
        "mg", "mgs" -> 1.0
        "g", "gram", "grams" -> 1000.0
        "µg", "ug", "mcg", "μg", "micrograms" -> 0.001
        // Patch and per-hour delivery rates: the numeric is still a microgram
        // quantity, so the magnitude check stands.
        "µg/hr", "ug/hr", "mcg/hr", "mcg/hour", "mcg/hr (patch)" -> 0.001
        // Anything else — seeds, drops, sprays, IU, mL, qualified mg,
        // percentages — cannot be safely interpreted, so skip the check.
        else -> null
    }
}

/**
 * A substance id resolved the way the build's upsert merges: canonical name,
 * then alias.
 *
 * Ported from `TestBuiltDatabaseInvariants._resolve_sid`, **minus its third
 * fallback** through `normalise()` (salt-stripped, NFKD-folded). That fallback
 * is not ported because every name in the expectation set was verified to
 * resolve through one of the two steps here. If a name ever stops resolving,
 * this returns null and the assertion names it — which is the signal to port
 * the fallback rather than a silent divergence.
 */
private fun SubstanceDb.resolveSid(name: String): Long? {
    queryOne(
        "SELECT id FROM substances WHERE lower(canonical_name) = lower(?)",
        listOf(name),
    )?.long("id")?.let { return it }
    queryOne(
        "SELECT substance_id AS id FROM aliases WHERE lower(alias) = lower(?) LIMIT 1",
        listOf(name),
    )?.long("id")?.let { return it }
    return null
}

/**
 * The category the app would resolve: the one from the highest-priority
 * (lowest priority number) source that carries a row for this substance.
 *
 * Ported from `TestBuiltDatabaseInvariants._resolved_category`.
 */
private fun SubstanceDb.resolvedCategory(name: String, priorities: Map<String, Long>): String? {
    val sid = resolveSid(name) ?: return null
    val rows = query(
        "SELECT src.slug AS slug, c.category AS category FROM categories c " +
            "JOIN sources src ON src.id = c.source_id WHERE c.substance_id = ?",
        listOf(sid),
    ).map { (priorities[it.string("slug")] ?: 999L) to (it.string("category") ?: "") }
    if (rows.isEmpty()) return null
    // Python sorts (priority, category) tuples; ties therefore break on the
    // category name, so the comparator has to carry both.
    return rows.sortedWith(compareBy({ it.first }, { it.second })).first().second
}

/** Standard atomic weights, from `_ATOMIC_WEIGHT` in `pipeline/build/sqlite.py`. */
private val ATOMIC_WEIGHT: Map<String, Double> = mapOf(
    "H" to 1.008, "B" to 10.81, "C" to 12.011, "N" to 14.007, "O" to 15.999,
    "F" to 18.998, "Na" to 22.990, "Mg" to 24.305, "Al" to 26.982, "Si" to 28.085,
    "P" to 30.974, "S" to 32.06, "Cl" to 35.45, "K" to 39.098, "Ca" to 40.078,
    "Fe" to 55.845, "Zn" to 65.38, "Se" to 78.971, "Br" to 79.904, "I" to 126.904,
    "Li" to 6.94,
)

/**
 * Parse a Hill-notation formula into an element-to-count map. Null or empty
 * yields null; charge suffixes are ignored.
 *
 * Ported from `parse_formula` in `pipeline/build/sqlite.py`.
 */
private fun parseFormula(formula: String?): Map<String, Int>? {
    if (formula.isNullOrEmpty()) return null
    val out = mutableMapOf<String, Int>()
    for (match in Regex("([A-Z][a-z]?)(\\d*)").findAll(formula)) {
        val element = match.groupValues[1]
        if (element.isEmpty()) continue
        val count = match.groupValues[2]
        out[element] = (out[element] ?: 0) + if (count.isEmpty()) 1 else count.toInt()
    }
    return out.ifEmpty { null }
}

/**
 * Molecular weight in g/mol from a Hill formula, or null when the formula is
 * missing or carries an element we do not weigh (peptide three-letter junk,
 * isotope labels) — in which case the stored value is left untouched.
 *
 * Ported from `formula_mass` in `pipeline/build/sqlite.py`. `round` is
 * ties-to-even in Kotlin as in Python, so the rounding matches.
 */
private fun formulaMass(formula: String?): Double? {
    val parsed = parseFormula(formula) ?: return null
    var total = 0.0
    for ((element, count) in parsed) {
        val weight = ATOMIC_WEIGHT[element] ?: return null
        total += weight * count
    }
    return kotlin.math.round(total * 100) / 100
}
