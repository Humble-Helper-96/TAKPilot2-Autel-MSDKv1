#!/usr/bin/env python3
"""
Generate the printable Field Guide markdown from the in-app guide.

Ported from the DJI MSDKv5 tree's generator of the same name (2026-08-15). Same parser, same
strictness; the differences are this tree's package path, this tree's section functions, and
the `keyEntry` builder, which the DJI guide does not have.

The source of truth stays FieldGuideActivity.kt. This only re-prints it.

Usage:
    python3 tools/generate_field_guide_md.py [output.md]

Default output is ../TAKPilot2-FieldGuide.md, beside the tree, where the handout lives.

The parser reads the builder calls (title/lede/section/sub/body/bullet/note/warn/entry/
keyEntry) in source order. It is deliberately strict: an unknown string template or an
unparsable argument raises instead of writing a plausible-looking file with a hole in it. A
silent hole is how the DJI copy became wrong.
"""

import os
import re
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.dirname(HERE)
GUIDE = os.path.join(REPO, "app/src/main/java/com/autel/sdksample/tak/FieldGuideActivity.kt")
TAK_PKG = os.path.join(REPO, "app/src/main/java/com/autel/sdksample/tak")
BUILD_GRADLE = os.path.join(REPO, "app/build.gradle")

CALLS = ("title", "lede", "section", "sub", "body", "bullet", "note", "warn", "entry",
         "keyEntry", "divider")

# onCreate calls these, then adds its own closing text. Walking the file top-to-bottom would
# put that closing text BEFORE the sections, because the functions are defined further down —
# so onCreate drives the order and these are spliced in where it calls them.
SECTION_FUNS = ("sectionOne", "sectionTwo", "sectionControllerButtons", "sectionThree",
                "sectionFour", "sectionFlightRecords")

# `TakDropMarkers.QUICK_NAME` is NOT a const — it is a `val` with a getter that draws one
# callsign per install from a pool of 24. There is no single correct value to print, so the
# handout prints a representative one and the header says so. Resolving it to the literal
# text "QUICK" (an earlier mistake in a hand-made preview) would be simply wrong: no pilot
# ever sees that string.
QUICK_POOL_SAMPLE = "E419"
EXTRA_CONSTS = {"QUICK_NAME": QUICK_POOL_SAMPLE}


def extract_fun(src, name):
    """The body of `private fun name(...) { ... }`, by brace matching."""
    m = re.search(r"\bfun %s\(" % re.escape(name), src)
    if not m:
        raise ValueError("no function %s" % name)
    open_brace = src.index("{", balanced(src, src.index("(", m.start())) - 1)
    depth, i = 0, open_brace
    while i < len(src):
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == "{":
            depth += 1
        elif c == "}":
            depth -= 1
            if depth == 0:
                return src[open_brace + 1:i]
        i += 1
    raise ValueError("unbalanced braces in %s" % name)


def scan_constants():
    """`const val NAME = "value"` across the tak package, for resolving ${...} templates."""
    consts = {}
    for name in os.listdir(TAK_PKG):
        if not name.endswith(".kt"):
            continue
        with open(os.path.join(TAK_PKG, name), encoding="utf-8") as fh:
            for m in re.finditer(r'const val (\w+)\s*=\s*"([^"]*)"', fh.read()):
                consts[m.group(1)] = m.group(2)
    consts.update(EXTRA_CONSTS)
    return consts


def skip_string(src, i):
    """Index just past the string literal starting at src[i] == '"'."""
    i += 1
    while i < len(src):
        if src[i] == "\\":
            i += 2
            continue
        if src[i] == '"':
            return i + 1
        i += 1
    raise ValueError("unterminated string literal")


def balanced(src, open_idx):
    """Index just past the ')' matching the '(' at open_idx, ignoring parens inside strings."""
    depth = 0
    i = open_idx
    while i < len(src):
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c == "(":
            depth += 1
        elif c == ")":
            depth -= 1
            if depth == 0:
                return i + 1
        i += 1
    raise ValueError("unbalanced parentheses")


def split_args(src):
    """Split an argument list on top-level commas, respecting strings and nesting."""
    args, depth, start, i = [], 0, 0, 0
    while i < len(src):
        c = src[i]
        if c == '"':
            i = skip_string(src, i)
            continue
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        elif c == "," and depth == 0:
            args.append(src[start:i])
            start = i + 1
        i += 1
    tail = src[start:]
    if tail.strip():
        args.append(tail)
    return args


def literals(src):
    """Every string literal in source order, unescaped."""
    out, i = [], 0
    while i < len(src):
        if src[i] == '"':
            end = skip_string(src, i)
            out.append(src[i + 1:end - 1])
            i = end
            continue
        i += 1
    return out


def unescape(text, consts):
    text = text.replace("\\n", "\n").replace('\\"', '"').replace("\\\\", "\\")

    # ${a.b.CONST} -> its value. Unknown templates are a hard error on purpose.
    def sub(m):
        key = m.group(1).split(".")[-1]
        if key not in consts:
            raise ValueError("cannot resolve template ${%s}" % m.group(1))
        return consts[key]
    return re.sub(r"\$\{([\w.]+)\}", sub, text)


def joined(arg, consts):
    """A Kotlin "a" + "b" concatenation, flattened."""
    return unescape("".join(literals(arg)), consts)


def parse(src, consts, full=None):
    """The builder calls from `src`, in the order onCreate reaches them, as (kind, payload)."""
    full = src if full is None else full
    items, i = [], 0
    pattern = re.compile(r"\b(%s)\(" % "|".join(CALLS + SECTION_FUNS))
    while i < len(src):
        m = pattern.search(src, i)
        if not m:
            break
        # Skip the declarations themselves ("private fun body(") and KDoc references.
        line_start = src.rfind("\n", 0, m.start()) + 1
        line = src[line_start:m.start()]
        if "fun " in line or line.strip().startswith("*"):
            i = m.end()
            continue
        open_idx = m.end() - 1
        end = balanced(src, open_idx)
        inner = src[open_idx + 1:end - 1]
        kind = m.group(1)
        if kind in SECTION_FUNS:
            items += parse(extract_fun(full, kind), consts, full)
        elif kind == "divider":
            items.append((kind, ""))
        elif kind == "entry":
            args = split_args(inner)
            if len(args) < 3:
                raise ValueError("entry() with %d args near: %s" % (len(args), inner[:60]))
            captions = [literals(a)[-1] for a in split_args(_strip_list(args[0])) if literals(a)]
            caveats = ([joined(a, consts) for a in split_args(_strip_list(args[3]))]
                       if len(args) > 3 else [])
            items.append((kind, {
                "captions": [unescape(c, consts) for c in captions],
                "name": joined(args[1], consts),
                "what": joined(args[2], consts),
                "caveats": [c for c in caveats if c],
            }))
        elif kind == "keyEntry":
            # keyEntry(name, short, long?, note? = null). `long` is the literal `null` for a
            # control with no long press — printed as no row at all, never as "nothing",
            # which would read as a documented behaviour.
            args = split_args(inner)
            if len(args) < 3:
                raise ValueError("keyEntry() with %d args near: %s" % (len(args), inner[:60]))
            long_arg = args[2].strip()
            items.append((kind, {
                "name": joined(args[0], consts),
                "short": joined(args[1], consts),
                "long": None if long_arg == "null" else joined(args[2], consts),
                "note": joined(args[3], consts) if len(args) > 3 else None,
            }))
        else:
            items.append((kind, joined(inner, consts)))
        i = end
    return items


def _strip_list(arg):
    """`listOf( ... )` -> its contents; `emptyList()` -> empty."""
    arg = arg.strip()
    if arg.startswith("emptyList"):
        return ""
    m = re.match(r"listOf\s*\(", arg)
    if not m:
        return ""
    return arg[m.end():arg.rindex(")")]


def render_prose(text):
    """Paragraphs on blank lines; single newlines kept as hard breaks; '- ' lines as lists."""
    out = []
    for block in text.split("\n\n"):
        lines = [ln for ln in block.split("\n") if ln.strip()]
        if not lines:
            continue
        rendered, in_list = [], False
        for ln in lines:
            is_item = ln.lstrip().startswith("- ")
            if is_item and not in_list:
                if rendered:
                    # A hard break before a blank line does nothing — drop the trailing spaces.
                    rendered[-1] = rendered[-1].rstrip()
                    rendered.append("")       # markdown needs a blank line before a list
                in_list = True
            elif not is_item and in_list:
                rendered.append("")
                in_list = False
            rendered.append(ln if is_item else ln + "  ")
        # Trailing hard-break spaces on the last line of a paragraph are noise.
        while rendered and not rendered[-1].strip():
            rendered.pop()
        if rendered:
            rendered[-1] = rendered[-1].rstrip()
        out.append("\n".join(rendered))
    return "\n\n".join(out)


def version_name():
    with open(BUILD_GRADLE, encoding="utf-8") as fh:
        m = re.search(r'versionName\s+"([^"]+)"', fh.read())
    return m.group(1) if m else "unknown"


def main():
    dest = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        REPO, "TAKPilot2-FieldGuide.md")
    with open(GUIDE, encoding="utf-8") as fh:
        src = fh.read()

    items = parse(extract_fun(src, "onCreate"), scan_constants(), src)
    if not any(k == "entry" for k, _ in items):
        raise SystemExit("parsed no control entries — the guide's structure changed")
    if not any(k == "keyEntry" for k, _ in items):
        raise SystemExit("parsed no controller-button entries — section 3 changed")

    md = [
        "<!--",
        "Verbatim copy of the in-app Pilot Field Guide (Home > FIELD GUIDE).",
        "",
        "GENERATED FROM SOURCE. The source of truth is",
        "app/src/main/java/com/autel/sdksample/tak/FieldGuideActivity.kt.",
        "",
        "Regenerate with:",
        "    python3 tools/generate_field_guide_md.py",
        "",
        "TO EDIT THE WORDING: mark this file up freely, then have the edits applied back to",
        "FieldGuideActivity.kt and regenerate. Edits made here alone do NOT reach the",
        "aircraft - this file is a print of the app, not the app.",
        "",
        "The guide text is written in ASD-STE100 Simplified Technical English. See the",
        "KDoc on FieldGuideActivity for the rule set to hold edits to.",
        "",
        "The quick-marker name shows as %s here. The app draws one callsign per install"
        % QUICK_POOL_SAMPLE,
        "from a pool of 24, so each controller shows its own.",
        "",
        "App version: %s" % version_name(),
        "-->",
        "",
    ]

    for kind, payload in items:
        if kind == "title":
            md += ["# %s" % payload, ""]
        elif kind == "lede":
            md += ["*%s*" % payload, ""]
        elif kind == "section":
            md += ["## %s" % payload, ""]
        elif kind == "sub":
            md += ["### %s" % payload, ""]
        elif kind == "divider":
            md += ["---", ""]
        elif kind == "body":
            md += [render_prose(payload), ""]
        elif kind == "bullet":
            md += ["- %s" % payload, ""]
        elif kind == "note":
            md += ["> **NOTE** — %s" % payload.replace("\n", " "), ""]
        elif kind == "warn":
            md += ["> **⚠ WARNING** — %s" % payload.replace("\n", " "), ""]
        elif kind == "entry":
            md += ["#### %s" % payload["name"], ""]
            if payload["captions"]:
                md += ["*Icon states shown: %s*" % " · ".join(payload["captions"]), ""]
            md += [render_prose(payload["what"]), ""]
            for c in payload["caveats"]:
                md += ["> **!** %s" % c.replace("\n", " "), ""]
        elif kind == "keyEntry":
            md += ["#### %s" % payload["name"], ""]
            md += ["- **Press** — %s" % payload["short"]]
            if payload["long"]:
                md += ["- **Press and hold** — %s" % payload["long"]]
            md += [""]
            if payload["note"]:
                md += ["> **!** %s" % payload["note"].replace("\n", " "), ""]

    # Collapse the runs of blank lines that consecutive bullets leave behind.
    text = re.sub(r"\n{3,}", "\n\n", "\n".join(md)).rstrip() + "\n"
    text = re.sub(r"(?m)^- (.*)\n\n(?=- )", r"- \1\n", text)

    with open(dest, "w", encoding="utf-8") as fh:
        fh.write(text)
    print("wrote %s (%d bytes, %d entries, %d controller buttons)" % (
        dest, len(text),
        sum(1 for k, _ in items if k == "entry"),
        sum(1 for k, _ in items if k == "keyEntry")))


if __name__ == "__main__":
    main()
