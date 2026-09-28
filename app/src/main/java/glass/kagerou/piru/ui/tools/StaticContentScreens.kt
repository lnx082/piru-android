package glass.kagerou.piru.ui.tools

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import glass.kagerou.piru.PiruApplication
import glass.kagerou.piru.data.entity.DoseEntryEntity
import glass.kagerou.piru.engine.ActiveSubstance
import glass.kagerou.piru.engine.ActiveSubstanceCalculator
import glass.kagerou.piru.engine.DoseRecord
import glass.kagerou.piru.engine.SubstanceCatalog
import glass.kagerou.piru.model.P3Color
import glass.kagerou.piru.model.SubstanceCategory
import glass.kagerou.piru.model.SubstanceColorGenerator
import glass.kagerou.piru.model.doseFormatted
import glass.kagerou.piru.model.unitDisplay
import glass.kagerou.piru.ui.components.FAB_CLEARANCE
import glass.kagerou.piru.ui.components.PiruCard
import glass.kagerou.piru.ui.nav.AppNavigator
import glass.kagerou.piru.ui.nav.PushRoute
import glass.kagerou.piru.ui.theme.PiruTheme
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.delay
import glass.kagerou.piru.ui.theme.toComposeColor

/**
 * The three static-content screens: the comedown guide, Get Help, and the
 * education index.
 *
 * Ported from `Views/Tools/ComedownGuideView.swift` (420 lines),
 * `Views/Tools/HelpView.swift` (721 lines) and `Views/Tools/EducationCard.swift`
 * (100 lines).
 *
 * ## The copy is the product here, and it is verbatim
 * Every sentence in the guide tables and the emergency list is the original's,
 * carried across unchanged. These screens do not model anything — they are the
 * app telling someone what sources report about a class, and which number to
 * call. Rewriting a line to read better is how a warning quietly loses its
 * qualification; the two screens that hold a sentence like *"a dose you handled
 * before is the documented cause of many overdoses"* are exactly the ones where
 * that matters.
 *
 * ## What the port could not carry
 * The originals lead each row with an SF Symbol. This build ships the small
 * Material icon set, which has no equivalent for `drop.fill`, `figure.walk` or
 * `heart.text.clipboard`, so a row carries its sentence and, where the original
 * colour-coded it, a bullet in the same secondary label the guide's own `•`
 * lists use. Nothing is claimed by the omission — the mapping belongs with the
 * rest of the icon set — and no row is left looking tappable that is not.
 */

// MARK: - Comedown guide

/**
 * What sources report about the hours after each class wears off.
 *
 * ## Why the recent classes come first
 * The guide is ordered by *your* last 48 hours when there is anything to order:
 * the class somebody is coming down from right now is the one they opened this
 * for, and making them find it in an alphabetical list is the failure mode. The
 * per-class lookup runs once in the effect, not per frame — the original says so
 * explicitly, because it resolves a substance per dose through the catalog.
 */
@Composable
fun ComedownGuideScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    var recent by remember { mutableStateOf<List<SubstanceCategory>>(emptyList()) }

    LaunchedEffect(navigator.dataVersion) {
        val catalog = app.catalog()
        val entries = app.database.doseEntryDao().all()
        recent = recentGuidedCategories(entries, Instant.now(), catalog)
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            // No subtitle: the card immediately below says what the screen is,
            // and saying it twice puts two identical paragraphs a swipe apart.
            Text(
                "Recovery guide",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.padding(top = 16.dp),
            )
        }

        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("What is this?", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "What sources report about the hours after each class wears off. " +
                            "It describes the class, never your condition.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                    Text(
                        "If someone is hard to wake, breathing slowly, overheating or " +
                            "having a seizure, call emergency services.",
                        style = MaterialTheme.typography.bodySmall,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        if (recent.isNotEmpty()) {
            item { SectionLabel("From your last 48 hours", Modifier.padding(top = 6.dp)) }
            items(recent, key = { it.wireValue }) { category ->
                CategoryDisclosure(category)
            }
        }

        item { SectionLabel("All categories", Modifier.padding(top = 6.dp)) }
        items(GUIDED_CATEGORIES.filter { it !in recent }, key = { it.wireValue }) { category ->
            CategoryDisclosure(category)
        }

        item { SectionLabel("Universal recovery basics", Modifier.padding(top = 6.dp)) }
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (tip in UNIVERSAL_BASICS) {
                        BulletText(tip)
                    }
                }
            }
        }

        item {
            Text(
                "The recovery tips describe what sources report for a class. " +
                    "Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * The eight classes the guide covers, in the order they are shown.
 *
 * Declaration order *is* the display order and is upstream's; it runs from the
 * classes with the most written about them to the ones with a shorter note.
 */
private val GUIDED_CATEGORIES: List<SubstanceCategory> = listOf(
    SubstanceCategory.STIMULANT,
    SubstanceCategory.EMPATHOGEN,
    SubstanceCategory.PSYCHEDELIC,
    SubstanceCategory.DISSOCIATIVE,
    SubstanceCategory.OPIOID,
    SubstanceCategory.BENZODIAZEPINE,
    SubstanceCategory.DEPRESSANT,
    SubstanceCategory.CANNABINOID,
)

/** The seven general tips, in the original's fixed order. */
private val UNIVERSAL_BASICS: List<String> = listOf(
    "Hydrate — water or electrolyte drinks, sip steadily",
    "Eat something nutritious — protein, carbs, and fruit",
    "Rest when your body asks for it",
    "Fresh air and gentle light",
    "Light movement or stretching — nothing intense",
    "Put the phone down — screens can amplify restlessness",
    "Reach out to someone you trust if you feel overwhelmed",
)

/**
 * The guided classes present in [entries] since 48 hours before [now],
 * newest-first and de-duplicated by first appearance.
 *
 * The cutoff is applied here rather than in a query so this is a pure function of
 * its inputs, which is what makes it testable and what the session detail's
 * recovery section shares. Running it over the log directly is why it belongs in
 * an effect: it resolves one substance per dose through the catalog.
 */
private fun recentGuidedCategories(
    entries: List<DoseEntryEntity>,
    now: Instant,
    catalog: SubstanceCatalog,
): List<SubstanceCategory> {
    val cutoff = now.minus(Duration.ofHours(48))
    val guided = GUIDED_CATEGORIES.toSet()
    val seen = mutableSetOf<SubstanceCategory>()
    val out = mutableListOf<SubstanceCategory>()
    // Newest first: the caller passes the log in the order a query would return
    // it, and the first class seen is the most recent one.
    for (entry in entries.sortedByDescending { it.timestamp.toInstant() }) {
        val stamp = entry.timestamp.toInstant()
        if (stamp.isBefore(cutoff)) continue
        val category = catalog.lookup(entry.substance)?.category ?: continue
        if (category in guided && seen.add(category)) out += category
    }
    return out
}

/** One class's four tip groups. */
private data class CategoryGuide(
    val whatsHappening: List<String>,
    val rightNow: List<String>,
    val nextHours: List<String>,
    val avoid: List<String>,
)

/**
 * The guide for one class.
 *
 * Verbatim from `ComedownGuideView.guide(for:)`. The default case is upstream's
 * and is reached by every class the guide does not name — including the
 * categories a reader reached this screen from a substance page with. The
 * "Piru doesn't measure any of this" line is present on every class the source
 * gives it to, and is absent on the two where the source omits it; that
 * inconsistency is the original's and is kept.
 */
@Suppress("CyclomaticComplexMethod")
private fun guide(category: SubstanceCategory): CategoryGuide = when (category) {
    SubstanceCategory.STIMULANT -> CategoryGuide(
        whatsHappening = listOf(
            "After a stimulant wears off, fatigue, irritability and low mood are commonly reported.",
            "You may need food and rest after several hours without them.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "Eat something, even without hunger. Protein and complex carbs are the usual suggestion.",
            "Drink water or an electrolyte drink, in sips.",
            "Something to chew eases a tight jaw.",
            "Chest pain, a pounding heart that won't settle, or a severe headache needs medical help.",
        ),
        nextHours = listOf(
            "Lie down even if sleep doesn't come immediately.",
            "Dark room, comfortable temperature, no screens.",
            "A warm shower or light stretching helps tight muscles.",
            "Low mood after a stimulant is commonly reported. If it turns into thoughts of harming yourself, use the numbers in Get Help.",
        ),
        avoid = listOf(
            "Taking more to put off the crash delays it.",
            "Caffeine adds to the load on the heart.",
            "You may think differently about important decisions and emotionally charged messages tomorrow.",
            "Alcohol disrupts the sleep you need.",
        ),
    )

    SubstanceCategory.EMPATHOGEN -> CategoryGuide(
        whatsHappening = listOf(
            "Low mood, fatigue and emotional sensitivity in the days after are commonly reported.",
            "How long that lasts varies between people, and the kinetics in humans aren't well measured.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "This class raises body temperature. Feeling very hot, confused or rigid is an emergency — cool down and call for help.",
            "Sip rather than gulp, and favor electrolytes. Over-drinking water is its own danger with this class — more is not safer.",
            "Eat light foods: fruit, toast, soup.",
            "Gentle massage eases a sore jaw.",
        ),
        nextHours = listOf(
            "Rest in a comfortable, calm space. Soft music or silence both work.",
            "Be patient with yourself over the next few days.",
            "A walk outside helps when you're ready.",
            "Talk to someone you trust — connection helps more than isolation.",
        ),
        avoid = listOf(
            "Taking more to put off the low mood delays it.",
            "Piru doesn't establish a safe interval for adding medicines or supplements. MAOIs are the documented danger with this class.",
            "Skip intense social situations — you may feel emotionally raw.",
            "Don't judge your baseline mood by how you feel right now.",
        ),
    )

    SubstanceCategory.PSYCHEDELIC -> CategoryGuide(
        whatsHappening = listOf(
            "Feeling emotionally open, contemplative, or just tired afterwards is commonly reported.",
            "Lingering visual or thought patterns are reported too, and usually fade over hours.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "If the experience was intense: the acute effects of this class are time-limited, and company helps.",
            "Eat something grounding — warm food, fruit, or anything that sounds appealing.",
            "Drink water. Wrap up in something comfortable.",
            "Write down anything meaningful before the details fade.",
        ),
        nextHours = listOf(
            "Rest when you can.",
            "Don't try to 'figure it all out' right now. Integration takes days.",
            "Nature, art, or quiet music can help you process gently.",
            "Distress or perceptual changes that persist for days are worth taking to a professional.",
        ),
        avoid = listOf(
            "Don't make big life decisions based on acute revelations — wait a week.",
            "Avoid screens and doom-scrolling while you're this impressionable.",
            "Cannabis is widely reported to bring the effects back, sometimes unpleasantly.",
            "Skip intense or crowded environments until you feel grounded.",
        ),
    )

    SubstanceCategory.DISSOCIATIVE -> CategoryGuide(
        whatsHappening = listOf(
            "Feeling foggy or unreal for a while afterwards is commonly reported.",
            "Motor coordination and spatial awareness may still be impaired.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "Stay seated or lying down. Your balance may not be what you think it is.",
            "Drink water. Eat something simple when your stomach allows.",
            "Stay somewhere safe with someone you trust if possible.",
            "Avoid stairs, sharp objects, and anything requiring fine motor skills.",
        ),
        nextHours = listOf(
            "Rest with someone nearby if you can.",
            "The fog is reported to clear over hours. If it doesn't, seek medical help.",
            "Gentle sensory input (music, soft textures) can help you reconnect.",
            "Things feeling 'weird' for a while is commonly reported.",
        ),
        avoid = listOf(
            "Do not drive or operate machinery. Feeling normal does not establish that you can drive safely.",
            "Alcohol, benzodiazepines and opioids on top of a dissociative raise the risk of stopped breathing.",
            "Avoid hot baths or showers alone — you may not feel temperature accurately.",
            "Your own read of how affected you are is unreliable while dissociated.",
        ),
    )

    SubstanceCategory.OPIOID -> CategoryGuide(
        whatsHappening = listOf(
            "As an opioid fades, increased pain sensitivity, restlessness and mild nausea are commonly reported.",
            "How strong that is tracks how much and how often you've been using.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "If someone is hard to wake, breathing slowly, or has blue lips, call emergency services. Give naloxone if you have it, following its instructions.",
            "Drink water, in sips. Eat something light.",
            "If you feel nauseous, lie on your side.",
            "Fresh air can help with the foggy, closed-in feeling.",
        ),
        nextHours = listOf(
            "Stay with someone, or let someone know to check on you. Heavy snoring or gurgling in sleep is a warning sign, not rest.",
            "Light movement helps — even a short walk.",
            "A warm bath can ease the achy, restless feeling — with someone in earshot.",
            "Help is available through the numbers in Get Help.",
        ),
        avoid = listOf(
            "Tolerance drops quickly after a break. A dose you handled before is the documented cause of many overdoses.",
            "Alcohol, benzodiazepines and other depressants on top of an opioid raise the risk of stopped breathing.",
            "Don't isolate yourself. Let someone know where you are.",
            "Do not drive. Feeling normal does not establish that you can drive safely.",
        ),
    )

    SubstanceCategory.BENZODIAZEPINE -> CategoryGuide(
        whatsHappening = listOf(
            "As a benzodiazepine wears off, rebound anxiety and restlessness are commonly reported.",
            "Memory and coordination can stay impaired after the sedation lifts.",
            "After regular use, stopping abruptly can be dangerous.",
        ),
        rightNow = listOf(
            "Stay somewhere calm and safe.",
            "Drink water and eat something.",
            "Breathing exercises: 4 seconds in, 7 seconds hold, 8 seconds out.",
            "Caffeine amplifies rebound anxiety.",
        ),
        nextHours = listOf(
            "Sleep may be disrupted tonight.",
            "Light activity like walking helps burn off anxious energy.",
            "If someone is hard to wake or breathing slowly, call emergency services.",
            "If this is frequent for you, consider talking to a professional about alternatives.",
        ),
        avoid = listOf(
            "Taking more in reaction to the rebound reinforces the cycle.",
            "Alcohol acts on the same receptors, and the combination can stop breathing.",
            "Do not drive. Feeling less sedated does not establish that memory or coordination are unimpaired.",
            "After regular use, a seizure or severe confusion on stopping is an emergency.",
        ),
    )

    SubstanceCategory.DEPRESSANT -> CategoryGuide(
        whatsHappening = listOf(
            "As a depressant wears off, feeling shaky, anxious or nauseous is commonly reported.",
            "Headaches and fatigue are common.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "If someone is hard to wake, breathing slowly, or vomiting while drowsy, put them on their side and call emergency services.",
            "Drink water or an electrolyte drink, in sips.",
            "Eat something with salt, protein, and carbs.",
            "If nauseous, small sips of water and lying on your side help.",
        ),
        nextHours = listOf(
            "Rest with someone nearby. A person who can't be woken needs help, not sleep.",
            "A cool, dark room helps with headaches and overstimulation.",
            "Light food every few hours, even if you don't feel hungry.",
            "Fresh air and gentle movement when you're ready.",
        ),
        avoid = listOf(
            "Taking more of a depressant to ease the morning symptoms delays recovery.",
            "If you have been drinking heavily and daily for weeks, stopping abruptly can be dangerous — seizures and delirium tremens peak 2–4 days after the last drink.",
            "With daily phenibut or F-phenibut, dependence develops within weeks and withdrawal can be protracted.",
            "Acetaminophen (paracetamol) after heavy alcohol use adds stress to the liver.",
            "Do not drive. Feeling normal does not establish that you can drive safely.",
            "Avoid greasy, heavy food — it sounds good but often makes nausea worse.",
        ),
    )

    SubstanceCategory.CANNABINOID -> CategoryGuide(
        whatsHappening = listOf(
            "Feeling foggy, lethargic or mildly irritable afterwards is commonly reported.",
            "Appetite changes and sleep disruption are common after heavy sessions.",
            "Piru doesn't measure any of this. It describes what sources report for the class.",
        ),
        rightNow = listOf(
            "Drink water. A dry mouth is an effect of cannabis itself and doesn't by itself mean dehydration.",
            "Eat something balanced.",
            "If you feel anxious, slow your breathing. Anxiety is a listed effect of this class.",
            "A change of scenery — even moving to a different room — can shift your headspace.",
        ),
        nextHours = listOf(
            "Physical activity helps with the fog.",
            "Sleep quality may be off tonight.",
            "If you feel spacey, grounding exercises: name 5 things you can see, 4 you can touch.",
            "Repeated vomiting that only hot showers relieve is a recognized syndrome.",
        ),
        avoid = listOf(
            "Do not drive. Impairment outlasts the feeling of being high.",
            "Taking more cannabis to ease the comedown delays it.",
            "Short-term memory gaps are commonly reported. Piru can't tell what caused one.",
            "Skip intense social obligations if you're not feeling up to it.",
        ),
    )

    else -> CategoryGuide(
        whatsHappening = listOf(
            "How you feel depends on what you took, how much, and your own body.",
            "Piru doesn't measure any of this. It describes what sources report.",
        ),
        rightNow = listOf(
            "Drink water and eat something nutritious.",
            "Rest in a comfortable, safe environment.",
            "If you feel unwell, don't hesitate to call for help.",
        ),
        nextHours = listOf(
            "Rest with someone nearby if you can.",
            "Light food and fluids every few hours.",
            "Give yourself time.",
        ),
        avoid = listOf(
            "Taking more within the same session adds to what is still active.",
            "Mixing adds risk.",
            "Do not drive. Feeling normal does not establish that you can drive safely.",
        ),
    )
}

/**
 * One class's fold-open row: the class, then its four groups of tips.
 *
 * Collapsed on arrival, including in the recent list — the original's
 * `DisclosureGroup` owns its own state and starts closed in both places, and
 * eight open groups is a screen nobody can scan.
 */
@Composable
private fun CategoryDisclosure(category: SubstanceCategory) {
    var expanded by remember(category.wireValue) { mutableStateOf(false) }
    val tint = SubstanceColorGenerator
        .displayP3(category, category.wireValue)
        .toComposeColor()

    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        onClick = { expanded = !expanded },
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(tint, CircleShape),
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(categoryLabel(category), style = MaterialTheme.typography.bodyLarge)
            }
            if (expanded) {
                val guidance = guide(category)
                TipGroup("What's happening", guidance.whatsHappening)
                TipGroup("Right now", guidance.rightNow)
                TipGroup("Over the next hours", guidance.nextHours)
                TipGroup("What to avoid", guidance.avoid)
            }
        }
    }
}

/** One titled group of bullets. */
@Composable
private fun TipGroup(title: String, items: List<String>) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge)
        for (item in items) BulletText(item)
    }
}

/** A bulleted paragraph, the form both guides' bodies use. */
@Composable
private fun BulletText(text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "•",
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
        Text(
            text,
            style = MaterialTheme.typography.bodySmall,
            color = PiruTheme.colors.secondaryLabel,
        )
    }
}

@Composable
private fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = modifier)
}

/**
 * A category's name.
 *
 * The catalog stores the human-readable spelling in the wire value, which is
 * what the library's browse grid already titles its cards with — one category
 * reads as "GABAergic" there because that is the name the field carries.
 */
private fun categoryLabel(category: SubstanceCategory): String = category.wireValue

// MARK: - Get Help

/**
 * The emergency screen: the numbers, what is still active, and a plain-text
 * summary to hand to someone else.
 *
 * ## The region table is the screen
 * Everything above and below it is a courtesy; the table is why somebody opened
 * this. It carries a row per country for ~60 regions and is deliberately
 * over-long — a person reading it is not in a state to look up which of two
 * numbers applies to them, and the wrong one is worse than an extra row. The
 * region is the device's, so a traveller gets the country they are standing in.
 *
 * ## Two things are computed once, not per frame
 * `ActiveSubstanceCalculator` does a catalog lookup per dose, so it runs in the
 * effect and not in composition — the original says so in a comment, and the
 * `refreshToken` it keys on is this screen's [AppNavigator.dataVersion].
 */
@Composable
fun HelpScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val app = context.applicationContext as PiruApplication
    val uriHandler = LocalUriHandler.current

    var catalog by remember { mutableStateOf<SubstanceCatalog?>(null) }
    var recent by remember { mutableStateOf<List<DoseEntryEntity>>(emptyList()) }
    var active by remember { mutableStateOf<List<ActiveSubstance>>(emptyList()) }
    var activeCategories by remember { mutableStateOf<List<SubstanceCategory>>(emptyList()) }
    var tints by remember { mutableStateOf<Map<String, P3Color>>(emptyMap()) }
    var hasShareable by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }

    LaunchedEffect(navigator.dataVersion) {
        val opened = app.catalog()
        val all = app.database.doseEntryDao().all().sortedByDescending { it.timestamp.toInstant() }
        val records = all.mapNotNull { it.toDoseRecord() }
        val palette = app.palette().tintsFor(records.map { it.substance }.toSet())
        catalog = opened
        recent = all
        tints = palette
        active = ActiveSubstanceCalculator.compute(
            entries = records,
            colorMap = palette,
            catalog = opened,
            fallbackTint = P3Color.NEUTRAL,
        )
        activeCategories = recentGuidedCategories(all, Instant.now(), opened)
        // Only a dose the checker still counts as active is worth handing to
        // someone else; a summary of everything ever logged is not what the
        // share sheet is for.
        hasShareable = opened.interactionChecker.activeEntries(records).isNotEmpty()
    }

    // The "Copied" flash, cleared on a timer rather than left set: a button that
    // says Copied forever stops telling the user anything.
    LaunchedEffect(copied) {
        if (copied) {
            delay(2_000)
            copied = false
        }
    }

    val last24h = recent.filter {
        it.timestamp.toInstant().isAfter(Instant.now().minus(Duration.ofHours(24)))
    }

    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Get Help", style = MaterialTheme.typography.headlineSmall)
            }
        }

        // Reassurance
        item {
            PiruCard(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "You don't have to do this alone",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Piru can't assess how you are. The people at the numbers below can.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        "Take a deep breath.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        item {
            // The detail tracks whether a psychedelic is active, because the
            // advice is different while one is: familiar music is a grounding
            // tool rather than a comfort.
            GroundingTip(
                title = "Put on familiar music",
                detail = if (SubstanceCategory.PSYCHEDELIC in activeCategories) {
                    "Music you know well is one of the most powerful grounding tools — " +
                        "especially during a psychedelic experience."
                } else {
                    "Familiar songs can ground you and bring comfort. Pick something you know well."
                },
            )
        }
        item {
            GroundingTip(
                title = "Call a friend or family member",
                detail = "Someone who knows you can help more than you'd expect. You don't " +
                    "have to explain everything — just hearing a familiar voice helps.",
            )
        }

        // Emergency services
        item {
            val region = Locale.getDefault().country.ifEmpty { "US" }
            // The `Locale(String, String)` constructor is deprecated; the builder
            // is the supported way to ask for a country's display name.
            val regionName = Locale.Builder().setRegion(region).build().getDisplayCountry()
            SectionLabel(
                if (regionName.isNotEmpty()) "Emergency Services — $regionName" else "Emergency Services",
                Modifier.padding(top = 8.dp),
            )
        }
        items(servicesFor(Locale.getDefault().country), key = { it.url + it.title }) { service ->
            val uri = service.url
            PiruCard(
                modifier = Modifier.fillMaxWidth(),
                onClick = { runCatching { uriHandler.openUri(uri) } },
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        service.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        service.detail,
                        style = MaterialTheme.typography.bodyMedium,
                        color = PiruTheme.colors.secondaryLabel,
                    )
                }
            }
        }

        // Modelled as active
        if (active.isNotEmpty()) {
            item { SectionLabel("Modeled as Active", Modifier.padding(top = 8.dp)) }
            items(active, key = { it.id }) { substance ->
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(substance.tint.toComposeColor(), CircleShape),
                        )
                        Column {
                            Text(substance.name, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "${doseFormatted(substance.totalDosed)} ${substance.unit} total · " +
                                    "est. ~${((1 - substance.eliminatedFraction) * 100).toInt()}% remaining",
                                style = MaterialTheme.typography.bodySmall,
                                color = PiruTheme.colors.secondaryLabel,
                            )
                        }
                    }
                }
            }
            item {
                Text(
                    "Estimates from pharmacokinetic modeling.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Recovery, right now
        if (activeCategories.isNotEmpty()) {
            item { SectionLabel("Recovery — Right Now", Modifier.padding(top = 8.dp)) }
            items(activeCategories, key = { "recovery-" + it.wireValue }) { category ->
                PiruCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        SubstanceColorGenerator
                                            .displayP3(category, category.wireValue)
                                            .toComposeColor(),
                                        CircleShape,
                                    ),
                            )
                            Text(categoryLabel(category), style = MaterialTheme.typography.titleSmall)
                        }
                        for (tip in guide(category).rightNow) BulletText(tip)
                    }
                }
            }
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(
                        onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.COMEDOWN)) },
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("View Full Recovery Guide")
                    }
                }
            }
            item {
                Text(
                    "Showing the guide for the classes you logged in the last 48 hours. " +
                        "Tap above for the full guide.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        // Recent entries
        if (last24h.isNotEmpty()) {
            item { SectionLabel("Recent Entries (24h)", Modifier.padding(top = 8.dp)) }
            items(last24h, key = { it.rowId }) { entry ->
                RecentEntryRow(entry, tints[entry.substance.lowercase()] ?: P3Color.NEUTRAL)
            }
        }

        // Copy / share
        if (active.isNotEmpty() || last24h.isNotEmpty()) {
            item {
                Button(
                    onClick = {
                        copyToClipboard(context, summaryText(active, last24h, catalog))
                        copied = true
                    },
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                ) {
                    Text(if (copied) "Copied" else "Copy Summary for Emergency Services")
                }
            }
            if (hasShareable) {
                item {
                    OutlinedButton(
                        onClick = { shareText(context, summaryText(active, last24h, catalog)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Share Current State…")
                    }
                }
            }
            item {
                Text(
                    "Copies a plain-text summary of substances and recent doses to share " +
                        "with emergency responders. Not medical advice.",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun GroundingTip(title: String, detail: String) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            Text(
                detail,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
        }
    }
}

@Composable
private fun RecentEntryRow(entry: DoseEntryEntity, tint: P3Color) {
    PiruCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(40.dp)
                    .background(tint.toComposeColor(), RoundedCornerShape(2.dp)),
            )
            Column {
                Text(
                    entry.displayNameSnapshot ?: entry.substance,
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "${entry.amountDisplay} ${unitDisplay(entry.unit, entry.amount)} · ${entry.route.displayName}",
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    TIME_FORMATTER.format(entry.timestamp.toInstant()),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    relativeTime(entry.timestamp.toInstant()),
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.tertiaryLabel,
                )
            }
        }
    }
}

/**
 * The emergency numbers for a region, falling back to the single generic row.
 *
 * The device's region code, as upstream reads it. A region the table does not
 * carry gets the generic 112 row rather than nothing — the one outcome that
 * must not happen here is an empty screen.
 */
private fun servicesFor(regionCode: String): List<EmergencyService> =
    EMERGENCY_SERVICES[regionCode.uppercase()] ?: EMERGENCY_SERVICES.getValue("__default")

/** One emergency contact row. */
private data class EmergencyService(
    val title: String,
    val detail: String,
    val url: String,
)

/**
 * The emergency table, region code to rows.
 *
 * Ported whole from `HelpView.services`. The URLs are what the platform dials,
 * with one deliberate change: the source writes its text-message links as
 * `sms:741741&body=HOME`, and Android's `SmsUri` reads the body after a `?`
 * rather than an `&`. The numbers and the bodies are unchanged; only the
 * separator is, and without it the link would dial a phone number of
 * "741741&body=HOME".
 */
private val EMERGENCY_SERVICES: Map<String, List<EmergencyService>> = buildMap {
    fun region(codes: String, vararg services: EmergencyService) {
        val rows = services.toList()
        for (code in codes.split(",")) put(code.trim(), rows)
    }

    // Americas
    region(
        "US",
        EmergencyService("Emergency Services", "911", "tel:911"),
        EmergencyService("Suicide & Crisis Lifeline", "988", "tel:988"),
        EmergencyService("Poison Control", "1-800-222-1222", "tel:18002221222"),
        EmergencyService("SAMHSA Helpline", "1-800-662-4357", "tel:18006624357"),
        EmergencyService("Crisis Text Line", "Text HOME to 741741", "sms:741741?body=HOME"),
    )
    region(
        "CA",
        EmergencyService("Emergency Services", "911", "tel:911"),
        EmergencyService("Suicide Crisis Helpline", "988", "tel:988"),
        EmergencyService("Poison Centre", "1-844-767-8187", "tel:18447678187"),
        EmergencyService("Crisis Text Line", "Text HOME to 686868", "sms:686868?body=HOME"),
    )
    region(
        "CO",
        EmergencyService("Linea de Emergencias", "123", "tel:123"),
        EmergencyService("Linea de Crisis", "106", "tel:106"),
    )
    region(
        "MX",
        EmergencyService("Servicios de Emergencia", "911", "tel:911"),
        EmergencyService("Linea de la Vida", "800-911-2000", "tel:8009112000"),
    )
    region(
        "BR",
        EmergencyService("SAMU", "192", "tel:192"),
        EmergencyService("CVV (Centro de Valorização da Vida)", "188", "tel:188"),
    )
    region(
        "AR",
        EmergencyService("Emergencias", "107", "tel:107"),
        EmergencyService("Centro de Asistencia al Suicida", "135", "tel:135"),
    )
    region(
        "CL",
        EmergencyService("Ambulancia", "131", "tel:131"),
        EmergencyService("Salud Responde", "600 360 7777", "tel:6003607777"),
    )
    region(
        "PE",
        EmergencyService("SAMU", "106", "tel:106"),
        EmergencyService("Linea 113 Salud", "113", "tel:113"),
    )
    region("EC", EmergencyService("Emergencias (ECU 911)", "911", "tel:911"))
    region("VE", EmergencyService("Emergencias", "171", "tel:171"))
    region(
        "UY",
        EmergencyService("Emergencias", "911", "tel:911"),
        EmergencyService("Linea de Prevencion del Suicidio", "0800 8483", "tel:08008483"),
    )
    region("CR", EmergencyService("Emergencias", "911", "tel:911"))
    region("PA,HN,SV,DO", EmergencyService("Emergencias", "911", "tel:911"))

    // Europe
    region(
        "GB",
        EmergencyService("Emergency Services", "999", "tel:999"),
        EmergencyService("Samaritans", "116 123", "tel:116123"),
        EmergencyService("FRANK Drug Helpline", "0300 123 6600", "tel:03001236600"),
    )
    region(
        "IE",
        EmergencyService("Emergency Services", "112 / 999", "tel:112"),
        EmergencyService("Samaritans", "116 123", "tel:116123"),
        EmergencyService("Pieta House", "1800 247 247", "tel:1800247247"),
    )
    region(
        "DE",
        EmergencyService("Notruf", "112", "tel:112"),
        EmergencyService("Telefonseelsorge", "0800 111 0 111", "tel:08001110111"),
        EmergencyService("Giftnotruf", "030 19240", "tel:03019240"),
    )
    region(
        "AT",
        EmergencyService("Notruf", "144", "tel:144"),
        EmergencyService("Telefonseelsorge", "142", "tel:142"),
    )
    region(
        "CH",
        EmergencyService("Sanitatsnotruf", "144", "tel:144"),
        EmergencyService("Die Dargebotene Hand", "143", "tel:143"),
        EmergencyService("Tox Info Suisse", "145", "tel:145"),
    )
    region(
        "FR",
        EmergencyService("SAMU", "15", "tel:15"),
        EmergencyService("SOS Amitie", "09 72 39 40 50", "tel:0972394050"),
        EmergencyService("Centre Antipoison", "01 40 05 48 48", "tel:0140054848"),
    )
    region(
        "ES",
        EmergencyService("Emergencias", "112", "tel:112"),
        EmergencyService("Telefono de la Esperanza", "717 003 717", "tel:717003717"),
    )
    region(
        "PT",
        EmergencyService("Emergencias", "112", "tel:112"),
        EmergencyService("SOS Voz Amiga", "213 544 545", "tel:213544545"),
    )
    region(
        "IT",
        EmergencyService("Emergenze", "112", "tel:112"),
        EmergencyService("Telefono Amico", "02 2327 2327", "tel:0223272327"),
        EmergencyService("Centro Antiveleni", "02 6610 1029", "tel:0266101029"),
    )
    region(
        "NL",
        EmergencyService("Alarmnummer", "112", "tel:112"),
        EmergencyService("113 Zelfmoordpreventie", "0900 0113", "tel:09000113"),
    )
    region(
        "BE",
        EmergencyService("Urgences", "112", "tel:112"),
        EmergencyService("Centre Antipoisons", "070 245 245", "tel:070245245"),
    )
    region(
        "SE",
        EmergencyService("Nodnummer", "112", "tel:112"),
        EmergencyService("Mind Sjalvmordslinjen", "90101", "tel:90101"),
    )
    region(
        "NO",
        EmergencyService("Nodnummer", "113", "tel:113"),
        EmergencyService("Mental Helse", "116 123", "tel:116123"),
        EmergencyService("Giftinformasjonen", "22 59 13 00", "tel:22591300"),
    )
    region(
        "DK",
        EmergencyService("Nodnummer", "112", "tel:112"),
        EmergencyService("Livslinien", "70 201 201", "tel:70201201"),
    )
    region(
        "FI",
        EmergencyService("Hatanumero", "112", "tel:112"),
        EmergencyService("Kriisipuhelin", "09 2525 0111", "tel:0925250111"),
    )
    region(
        "PL",
        EmergencyService("Numer alarmowy", "112", "tel:112"),
        EmergencyService("Telefon Zaufania", "116 123", "tel:116123"),
    )
    region(
        "CZ",
        EmergencyService("Tisnovka", "112", "tel:112"),
        EmergencyService("Linka bezpeci", "116 111", "tel:116111"),
    )
    region(
        "GR",
        EmergencyService("EKAB", "166", "tel:166"),
        EmergencyService("Klimaka Crisis Line", "1018", "tel:1018"),
    )
    region("RO,HU,HR,BG,SK,SI,LT,LV,EE,CY,LU,MT", EmergencyService("Emergency", "112", "tel:112"))

    // Asia and Oceania
    region(
        "AU",
        EmergencyService("Emergency Services", "000", "tel:000"),
        EmergencyService("Lifeline", "13 11 14", "tel:131114"),
        EmergencyService("Poisons Information", "13 11 26", "tel:131126"),
    )
    region(
        "NZ",
        EmergencyService("Emergency Services", "111", "tel:111"),
        EmergencyService("Lifeline", "0800 543 354", "tel:0800543354"),
        EmergencyService("Poisons Centre", "0800 764 766", "tel:0800764766"),
    )
    region(
        "JP",
        EmergencyService("Emergency (Ambulance)", "119", "tel:119"),
        EmergencyService("Yorisoi Hotline", "0120-279-338", "tel:0120279338"),
    )
    region(
        "KR",
        EmergencyService("Emergency (Ambulance)", "119", "tel:119"),
        EmergencyService("Suicide Prevention Hotline", "1393", "tel:1393"),
    )
    region(
        "CN",
        EmergencyService("Emergency (Ambulance)", "120", "tel:120"),
        EmergencyService("Crisis Hotline", "010-8295-1332", "tel:01082951332"),
    )
    region(
        "IN",
        EmergencyService("Emergency Services", "112", "tel:112"),
        EmergencyService("Vandrevala Foundation", "9999 666 555", "tel:9999666555"),
    )
    region(
        "PH",
        EmergencyService("Emergency Services", "911", "tel:911"),
        EmergencyService("Crisis Line", "0917-899-8727", "tel:09178998727"),
    )
    region(
        "SG",
        EmergencyService("Emergency (Ambulance)", "995", "tel:995"),
        EmergencyService("Samaritans of Singapore", "1-767", "tel:1767"),
    )
    region(
        "MY",
        EmergencyService("Emergency Services", "999", "tel:999"),
        EmergencyService("Befrienders", "03-7956 8145", "tel:0379568145"),
    )
    region(
        "TH",
        EmergencyService("Emergency (Ambulance)", "1669", "tel:1669"),
        EmergencyService("Samaritans of Thailand", "02-713-6793", "tel:027136793"),
    )
    region("ID", EmergencyService("Emergency (Ambulance)", "118", "tel:118"))
    region(
        "TW",
        EmergencyService("Emergency (Ambulance)", "119", "tel:119"),
        EmergencyService("Suicide Prevention", "1925", "tel:1925"),
    )
    region(
        "HK",
        EmergencyService("Emergency Services", "999", "tel:999"),
        EmergencyService("Samaritans", "2389 2222", "tel:23892222"),
    )

    // Middle East and Africa
    region(
        "IL",
        EmergencyService("Emergency (Ambulance)", "101", "tel:101"),
        EmergencyService("ERAN Crisis Line", "1201", "tel:1201"),
    )
    region(
        "TR",
        EmergencyService("Acil Yardim", "112", "tel:112"),
        EmergencyService("Intihar Onleme Hatti", "182", "tel:182"),
    )
    region("AE", EmergencyService("Emergency (Ambulance)", "998", "tel:998"))
    region("SA", EmergencyService("Emergency (Ambulance)", "997", "tel:997"))
    region(
        "ZA",
        EmergencyService("Emergency (Ambulance)", "10177", "tel:10177"),
        EmergencyService("SADAG Crisis Line", "0800 567 567", "tel:0800567567"),
    )
    region(
        "KE",
        EmergencyService("Emergency Services", "999", "tel:999"),
        EmergencyService("Befrienders Kenya", "0722 178 177", "tel:0722178177"),
    )
    region("NG", EmergencyService("Emergency Services", "112", "tel:112"))
    region("EG", EmergencyService("Emergency (Ambulance)", "123", "tel:123"))
    region(
        "RU",
        EmergencyService("Emergency Services", "112", "tel:112"),
        EmergencyService("Psychological Help", "8-800-2000-122", "tel:88002000122"),
    )
    region(
        "UA",
        EmergencyService("Emergency (Ambulance)", "103", "tel:103"),
        EmergencyService("Lifeline Ukraine", "7333", "tel:7333"),
    )

    put("__default", listOf(EmergencyService("Emergency Services", "112", "tel:112")))
}

/**
 * The plain-text hand-off, verbatim in shape from `HelpView.generateSummaryText`.
 *
 * A responder reads this, so the canonical name leads and the user's own word
 * follows in brackets when the two differ — a brand name or a non-English alias
 * is not something anyone can act on, and the user's spelling is what they will
 * recognise in their own log.
 */
private fun summaryText(
    active: List<ActiveSubstance>,
    last24h: List<DoseEntryEntity>,
    catalog: SubstanceCatalog?,
): String {
    val lines = mutableListOf<String>()
    lines += "SUBSTANCE SUMMARY"
    lines += "Generated: ${STAMP_FORMATTER.format(Instant.now())}"
    lines += ""

    if (active.isNotEmpty()) {
        lines += "CURRENTLY ACTIVE:"
        for (substance in active) {
            val remaining = ((1 - substance.eliminatedFraction) * 100).toInt()
            lines += "- ${substance.name} — ${doseFormatted(substance.totalDosed)} " +
                "${substance.unit} total (est. ~$remaining% remaining)"
            for (dose in substance.doses) {
                lines += "  ${doseFormatted(dose.amount)} ${substance.unit} at " +
                    STAMP_FORMATTER.format(dose.timestamp)
            }
        }
        lines += ""
    }

    if (last24h.isNotEmpty()) {
        lines += "RECENT DOSES (LAST 24 HOURS):"
        for (entry in last24h) {
            val canonical = catalog?.lookup(entry.substance)?.displayTitle ?: entry.substance
            val logged = entry.displayNameSnapshot ?: entry.substance
            val name = if (logged.equals(canonical, ignoreCase = true)) {
                canonical
            } else {
                "$canonical (logged as $logged)"
            }
            var line = "- $name ${entry.amountDisplay} ${entry.unit} " +
                "${entry.route.displayName.lowercase()} — ${STAMP_FORMATTER.format(entry.timestamp.toInstant())}"
            entry.notes?.takeIf { it.isNotEmpty() }?.let { line += " ($it)" }
            lines += line
        }
    }

    if (active.isEmpty() && last24h.isEmpty()) {
        lines += "No active substances or recent doses recorded."
    }

    lines += ""
    lines += "Generated by Piru. Estimates are approximate."
    return lines.joinToString("\n")
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Piru summary", text))
}

private fun shareText(context: Context, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(send, "Share current state"))
}

/** A dose entry as the engine reads one, or null for a record with no number. */
private fun DoseEntryEntity.toDoseRecord(): DoseRecord? {
    if (isUnknownDose) return null
    return DoseRecord(
        substance = substance,
        amount = amount,
        unit = unit,
        route = route,
        timestamp = timestamp.toInstant(),
        isUnknownDose = false,
        releaseForm = releaseForm,
        productName = productName,
        saltForm = saltForm,
        isomer = isomer,
        substanceUID = substanceUID,
    )
}

/** Clock time, and the absolute stamp the summary is dated with. */
private val TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withZone(ZoneId.systemDefault())

private val STAMP_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())

/** "3h ago" — the relative stamp the recent-entries list carries beside the clock time. */
private fun relativeTime(instant: Instant): String {
    val minutes = Duration.between(instant, Instant.now()).toMinutes()
    return when {
        minutes < 1 -> "just now"
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        else -> "${minutes / (60 * 24)}d ago"
    }
}

// MARK: - Education

/**
 * The education index: the four learn-oriented screens, as a list.
 *
 * Ported from `Views/Tools/EducationCard.swift` (100 lines), which upstream
 * renders as a fold-open card on the hub. Reached as a route it is the same four
 * rows without the fold — a card that expands inside a screen whose whole
 * content is that card would be a fold with nothing around it.
 *
 * ## The ceiling row has no destination in this build
 * Three of the four push a screen that exists. "Ceiling Effect" does not: this
 * build has no route for it and no screen behind one. It is rendered inert and
 * says so, because a row that looked tappable and did nothing is the failure the
 * port's rules name — and the tolerance tool it sits beside does not cover it.
 */
@Composable
fun EducationCardsScreen(navigator: AppNavigator, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(bottom = FAB_CLEARANCE),
    ) {
        item {
            Column(modifier = Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Education", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "How dosing, tolerance, and recovery work",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }

        item {
            EducationRow(
                title = "Ceiling Effect",
                subtitle = "When dose and exposure aren't proportional",
                enabled = false,
                detail = "Needs the ceiling explorer, which is not in this build.",
                onClick = {},
            )
        }
        item {
            EducationRow(
                title = "How Modeled Tolerance Works",
                subtitle = "Why effects fade and how receptors recover",
                enabled = true,
                detail = null,
                onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.TOLERANCE)) },
            )
        }
        item {
            EducationRow(
                title = "Recovery Guide",
                subtitle = "Comedown and aftercare tips",
                enabled = true,
                detail = null,
                onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.COMEDOWN)) },
            )
        }
        item {
            EducationRow(
                title = "Drug Classes",
                subtitle = "What the members of a family share",
                enabled = true,
                detail = null,
                onClick = { navigator.push(PushRoute.Tool(PushRoute.ToolKind.DRUG_CLASS)) },
            )
        }

        item {
            Text(
                "These screens describe what the catalog and the model say. " +
                    "Not medical advice.",
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * One education row.
 *
 * [enabled] false renders the row de-emphasised and inert with [detail] naming
 * what it would need — the same shape the tools hub uses for the entries it does
 * not carry.
 */
@Composable
private fun EducationRow(
    title: String,
    subtitle: String,
    enabled: Boolean,
    detail: String?,
    onClick: () -> Unit,
) {
    PiruCard(
        modifier = Modifier.fillMaxWidth(),
        // An inert row is not a button: `onClick` of null is what keeps it from
        // lifting on touch, which is the whole difference between "not built" and
        // "broken".
        onClick = if (enabled) onClick else null,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = if (enabled) PiruTheme.colors.accent else PiruTheme.colors.secondaryLabel,
            )
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = PiruTheme.colors.secondaryLabel,
            )
            if (detail != null) {
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = PiruTheme.colors.secondaryLabel,
                )
            }
        }
    }
}
