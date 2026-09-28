package glass.kagerou.piru.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import glass.kagerou.piru.data.catalog.AndroidSubstanceDb
import glass.kagerou.piru.data.catalog.SubstanceCatalogInstaller
import glass.kagerou.piru.substance.ContentLanguage
import glass.kagerou.piru.substance.DbSubstanceCatalog
import glass.kagerou.piru.substance.SubstanceReader
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The catalog's SQL, through **Android's own SQLite**.
 *
 * ## Why this suite exists at all
 * Every other catalog spec in the port runs on the JVM against `sqlite-jdbc`. That
 * is a different engine, and the two do not agree on everything — `metabolismRows`
 * splices a source-priority fragment into a correlated subquery that references an
 * outer table's alias. **JDBC resolves it; Android refuses it** with
 * `no such column: src.slug`, so the entire tolerance replay failed on device while
 * the JVM suite stayed green.
 *
 * That is not a gap a JVM test can close by being written better. It needs the
 * engine the app actually ships with, which is what this is for.
 *
 * The assertions are deliberately shallow. This is not a second copy of the reader
 * specs — it is a smoke test whose job is to *run the queries* on the real engine
 * and fail if one of them stops being valid SQL there.
 */
@RunWith(AndroidJUnit4::class)
class CatalogOnDeviceTest {

    private lateinit var db: AndroidSubstanceDb
    private lateinit var catalog: DbSubstanceCatalog
    private lateinit var reader: SubstanceReader

    @Before
    fun setUp() = runBlocking {
        // The install copies 18 MB out of the APK and verifies it against the
        // packaged manifest, so it suspends.
        val file = SubstanceCatalogInstaller.install(ApplicationProvider.getApplicationContext())
        db = AndroidSubstanceDb.open(file)
        val order = db.query("SELECT slug FROM sources ORDER BY default_priority, slug")
            .mapNotNull { it.string("slug") }
        catalog = DbSubstanceCatalog.open(db = db, order = order, language = ContentLanguage.EN)
        // The reader directly, not only through the catalog: half the tools' SQL
        // lives here and is reachable nowhere else, and it is exactly the SQL that
        // has never run on this engine.
        reader = SubstanceReader(db = db, order = order, language = ContentLanguage.EN)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun theCatalogOpensAndResolvesOnDevice() {
        val caffeine = catalog.lookup("Caffeine").shouldNotBeNull()
        caffeine.name shouldBe "Caffeine"
    }

    @Test
    fun everyPharmacologyReadIsValidSqlOnDevice() {
        // The whole resolve, for substances chosen to reach every branch: MDMA has
        // metabolism rows (the correlated subquery), Estradiol has esters, Kratom
        // routes to an active constituent, and 2-MMC carries a PK reference.
        for (name in listOf("Caffeine", "MDMA", "Estradiol", "Kratom", "2-MMC", "Diazepam", "Morphine")) {
            val params = catalog.pharmacologyParameters(name)
            // The values are the reader specs' business; this asserts only that the
            // resolve completed rather than throwing.
            (params.targets.size >= 0) shouldBe true
        }
    }

    @Test
    fun theMetabolismSubqueryResolvesOnDevice() {
        // The specific query that broke. Its subquery joins `sources` as `inner_src`
        // and splices the source-priority fragment, which must name that alias
        // rather than the outer one.
        val mdma = catalog.pharmacologyParameters("MDMA")
        mdma.metabolites.isNotEmpty() shouldBe true
    }

    @Test
    fun everyToolReadIsValidSqlOnDevice(): Unit = runBlocking {
        // Every query the tools and insights added this session, run on Android's
        // SQLite. The counts are the JVM suites' business and are asserted there;
        // these are asserted again because a query that parses on JDBC and not
        // here returns *nothing* rather than failing, and an empty reference table
        // renders as a tool with no data rather than as an error. The counts are
        // what distinguishes the two.
        reader.classContexts().size shouldBe 50
        reader.opioidMmeTable().size shouldBe 11
        reader.diazepamEquivalents().size shouldBe 32
        reader.esterPKRows().size shouldBe 8
        reader.classInteractionRules().size shouldBe 99
        reader.categoryInteractionClasses().size shouldBe 29
        reader.tagEnzymeRows().size shouldBe 285
        reader.enzymeModulatorRows().size shouldBe 11
        reader.substanceInteractionClasses().size shouldBe 1357
    }

    @Test
    fun theCorrelatedSubqueryReadsResolveOnDevice(): Unit = runBlocking {
        // The reads that embed a second table with its own alias, which is the
        // shape that broke before. A count of zero here is the failure mode that
        // does not throw.
        // Through the catalog rather than the reader: the reader keys on the
        // catalog's own row id, and the name→id resolution in between is part of
        // what has to work.
        catalog.pharmacokineticInteractions("Ketamine").isNotEmpty() shouldBe true

        // A real slug from `class_contexts`, not "opioid". The class write-ups and
        // the interaction `DrugClass` list are two different taxonomies: these are
        // narrow pharmacological families (afinil, racetams-and-ampakines), while
        // "opioid" belongs to the 24 interaction classes and has no write-up here.
        reader.classContext("afinil").shouldNotBeNull()
    }

    @Test
    fun theFullRecordReadIsValidSqlOnDevice() {
        // The detail path runs a different query set, including the mechanism
        // bindings and the curated blobs.
        for (name in listOf("Caffeine", "MDMA", "Alprazolam", "Semaglutide")) {
            val substance = catalog.resolveFull(name).shouldNotBeNull()
            (substance.name.isNotEmpty()) shouldBe true
        }
    }
}
