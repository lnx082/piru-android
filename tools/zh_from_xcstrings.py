#!/usr/bin/env python3
"""Generate a values-zh-rCN strings file from an English one.

Upstream ships 3,212 strings with `zh-Hans`, and most of this port's English came
across unchanged — so for those the translation already exists, and this looks it
up rather than inventing a second one. A hand-written Chinese for an upstream
sentence is strictly worse than upstream's own: it drifts.

Four things make the lookup harder than a dictionary hit. All four are handled
here, because skipping any of them loses translations silently — the build stays
green and the string quietly falls back to English.

1. **Format specifiers.** Upstream's Swift is `%@` / `%lld`; Android needs
   `%1$s` / `%1$d`. So the English in a resource file *never* equals the upstream
   key for a string carrying an argument. This normalises the Android form back
   to Swift's to search with, and rewrites the found translation into positional
   form: the Nth specifier in the Chinese becomes `%N$s` or `%N$d` according to
   the Nth in the English. Swift's are positional by order too, and Chinese
   reorders them freely, so pairing by the English and numbering by the Chinese
   is what keeps `%1$s 的 %2$s` pointing at the right arguments.
2. **XML escaping on the way in.** `\\'` and `\\"` are required by aapt2 but are
   not part of the sentence, so they are unescaped before the lookup.
3. **XML escaping on the way out.** aapt2 decodes entities *first* and then
   rejects a bare `'` — so `&#39;` does not work and only `\\'` does.
   ElementTree will not do this, because `'` needs no escaping in XML.
4. **Plurals.** `<plurals>` carries one translation per quantity, and each is
   looked up separately. Marking the whole element on the first miss loses the
   half that was found.

Anything still not found is reported and marked `translatable="false"`. That
marker means "nothing upstream had this", not "this is done" — every one of them
is copy this port wrote and someone has to translate it.
"""
import json
import re
import sys
import xml.etree.ElementTree as ET

XCSTRINGS = r"E:\software\piru\piru\Piru\Localizable.xcstrings"

SPEC_ANDROID = re.compile(r"%(?:(\d+)\$)?([sdf])")

# Swift's specifiers, **including the positional form**. `%1$@` is valid Swift —
# it is what a .xcstrings translation carries when the translator reordered the
# arguments — and a pattern that only matched the bare `%@` walked straight past
# it. The result was `%1$@` and `%2$lld` written into an Android resource, which
# throws at format time rather than at build time: three strings in this port's
# tools screens were on their way to a runtime crash.
SPEC_SWIFT = re.compile(r"%(?:(\d+)\$)?(?:@|lld|d|f)")


def swift_is_integer(specifier):
    """`%lld` and `%d` are integers; `%@` and `%f` are not."""
    return specifier.endswith("lld") or specifier.endswith("d")


def load():
    with open(XCSTRINGS, encoding="utf-8") as f:
        return json.load(f)["strings"]


def unescape(text):
    """Strip the escapes aapt2 requires, which are not part of the sentence."""
    return (text
            .replace("\\'", "'")
            .replace('\\"', '"')
            .replace("\\n", "\n"))


def escape(text):
    """Put back the escapes aapt2 requires on the way out.

    `&#39;` is not an alternative: aapt2 decodes the entity and then rejects the
    resulting bare apostrophe with "unescaped apostrophe in string". A value
    containing `haven't` — or a Chinese one containing a straight quote —
    produces a resource file that does not compile.
    """
    return re.sub(r"(?<!\\)(['\"])", r"\\\1", text)


def to_swift(english):
    """`Time to log %1$s — %2$s.` -> `Time to log %@ — %lld.` (order preserved)."""
    return SPEC_ANDROID.sub(lambda m: "%lld" if m.group(2) == "d" else "%@", english)


def to_android(chinese, english):
    """Rewrite a Swift translation's specifiers into Android positional form.

    The types come from the **English**, read with the Android pattern — it is an
    Android string, and reading it with Swift's pattern picks up `%2$d` while
    missing `%1$s`, which is how an earlier version labelled a string argument as
    an integer.

    The numbers come from the **Chinese** when it already carries them, and from
    order of appearance when it does not. Upstream's translations are often
    already positional — `%2$@ 中的 %1$@` is a translator having moved the
    arguments — and renumbering by position destroys exactly the mapping those
    numbers exist to record. The result is not a formatting oddity: it is the
    wrong value in the wrong slot.
    """
    kind_of = {
        index + 1: (m.group(2) or "s")
        for index, m in enumerate(SPEC_ANDROID.finditer(english))
    }
    pieces, last, seen = [], 0, 0
    for m in SPEC_SWIFT.finditer(chinese):
        seen += 1
        explicit = m.group(1)
        index = int(explicit) if explicit else seen
        kind = kind_of.get(index) or ("d" if swift_is_integer(m.group(0)) else "s")
        pieces.append(chinese[last:m.start()])
        pieces.append(f"%{index}${kind}")
        last = m.end()
    pieces.append(chinese[last:])
    return "".join(pieces)


def zh_for(strings, english, warned):
    probes = [english, to_swift(english)]
    # A case difference is worth a second look **only for a sentence**. A single
    # word is context-dependent by nature, and the fallback promptly proved it:
    # a severity field labelled "Note" matched upstream's unrelated "Note" and
    # came back 备注, which is a different word in this app. Sentences do not have
    # that problem — "Skip today" against upstream's "Skip Today" really is the
    # same sentence — so the net is narrowed to them and every hit is announced.
    if len(english.split()) >= 3:
        lowered = english.lower()
        probes += [k for k in strings if k.lower() == lowered][:1]

    # Two things a reader has to check by hand, announced rather than silent.
    #
    # A *case-folded* match is nearly always the same sentence, but it widened
    # the net to short strings and caught an unrelated one, so it now needs three
    # words and says so when it fires. A *single-word* match is the opposite
    # problem: it is exact, and it is still a guess, because one word is the unit
    # of language most likely to mean something else on another screen. This port
    # shipped a severity field labelled "Note" whose regeneration came back 备注 —
    # upstream's unrelated "Note", and a different word here.
    if len(english.split()) < 3 and strings.get(english):
        warned.append((english, english, "single word"))
    else:
        for probe in probes[2:]:
            if strings.get(probe):
                warned.append((english, probe, "case"))
                break

    for probe in probes:
        entry = strings.get(probe)
        if not entry:
            continue
        loc = (entry.get("localizations") or {}).get("zh-Hans")
        unit = (loc or {}).get("stringUnit")
        value = (unit or {}).get("value")
        if value is None:
            continue
        # Re-index only when the English carries an argument. The test has to be
        # on the Android form: `%1$s` does not match Swift's `%@`, and an earlier
        # version of this tested the wrong one and passed `%@` straight through
        # into an Android resource.
        result = to_android(value, english) if SPEC_ANDROID.search(english) else value
        # Belt and braces: a Swift specifier that survives into an Android
        # resource does not fail the build, it throws the first time the string
        # is formatted. Refusing is louder and cheaper.
        if SPEC_SWIFT.search(result):
            return None
        return result
    return None


def convert(el, strings, missing, warned):
    if el.get("translatable") == "false":
        return
    units = list(el) if el.tag == "plurals" else [el]
    translated = 0
    for unit in units:
        english = unescape("".join(unit.itertext()))
        zh = zh_for(strings, english, warned)
        if zh is None:
            missing.append((el.get("name"), english))
            continue
        unit.text = escape(zh)
        translated += 1
    if translated == 0:
        el.set("translatable", "false")


def main(src, dst):
    strings = load()
    # `insert_comments` because a header comment in the English file is the only
    # place several of these tables explain themselves, and the default parser
    # drops it on the way through. The Chinese file had been losing its headers.
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    tree = ET.parse(src, parser=parser)
    missing, warned, count = [], [], 0
    for el in tree.getroot():
        # Comments arrive as elements whose tag is a function, not a name.
        if not isinstance(el.tag, str) or el.tag not in ("string", "plurals"):
            continue
        count += 1
        convert(el, strings, missing, warned)
    tree.write(dst, encoding="utf-8", xml_declaration=True)
    print(f"{dst}: {count} entries, {len(missing)} with no upstream translation")
    for english, matched, why in warned:
        print(f"  CHECK ({why})  {english[:48]!r} -> {matched[:48]!r}")
    for name, english in missing:
        print(f"  MISSING  {name}: {english[:88]}")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
