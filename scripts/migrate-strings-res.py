#!/usr/bin/env python3
"""Rewrite Android `R.string.*` references to Compose Multiplatform resources.

Android resource access compiles to `Int` ids (`R.string.label_title`) and is
resolved with `stringResource(id)`. Desktop resources are generated objects:
`Res.string.label_title` is a `StringResource` value, imported per name from
`com.lonx.lyrico.resources`. This script does that mechanical rewrite:

  * `R.string.foo`            -> `Res.string.foo`
  * `R.drawable.foo`          -> `Res.drawable.foo`
  * `import com.lonx.lyrico.R` -> `import com.lonx.lyrico.resources.Res`
  * adds `import com.lonx.lyrico.resources.foo` for every name used,
  * `androidx.compose.ui.res.painterResource` -> `org.jetbrains.compose.resources.painterResource`,
  * reports `@StringRes ... : Int` declarations (they need a type change to
    `StringResource`, which is a semantic edit, not a textual one) and any other
    `R.<type>.<name>` references it does not handle.

Usage:
    python scripts/migrate-strings-res.py [--write] <files...>
"""
from __future__ import annotations

import argparse
import re
import sys

RES_RE = re.compile(r"\bR\.(string|plurals|array|drawable|raw|color|dimen|style|font|id)\.(\w+)")
ANDROID_R_IMPORT_RE = re.compile(r"^import com\.lonx\.lyrico\.R$", re.M)
STRING_RES_DECL_RE = re.compile(
    r"@(?:\w+:)?StringRes\s+(?:val|var)\s+(\w+)\s*:\s*Int"
)
STRING_RESOURCE_IMPORT = "import org.jetbrains.compose.resources.StringResource"
# `stringResource` is `androidx.compose.ui.res` on Android and `org.jetbrains.compose.resources`
# on desktop. Only the call site's spelling carries over, so the import has to be rewritten too --
# otherwise every migrated file fails with "Unresolved reference 'stringResource'".
STRING_RESOURCE_USAGE_IMPORT_RE = re.compile(r"^import androidx\.compose\.ui\.res\.stringResource$", re.M)
PAINTER_RESOURCE_USAGE_IMPORT_RE = re.compile(r"^import androidx\.compose\.ui\.res\.painterResource$", re.M)
# The resource *kinds* whose Android `R` entry maps 1:1 onto a generated Compose resource object.
# Everything else (`color`, `dimen`, `style`, ...) has no such object and is reported for a human.
MAPPED_KINDS = {"string", "drawable"}
IMPORT_RE = re.compile(r"^(import .*)$", re.M)


def rewrite(text: str) -> tuple[str, list[str], list[str]]:
    names: set[str] = set()
    unhandled: list[str] = []

    def sub(m: re.Match[str]) -> str:
        kind, name = m.group(1), m.group(2)
        if kind not in MAPPED_KINDS:
            unhandled.append(f"R.{kind}.{name}")
            return m.group(0)
        names.add(name)
        return f"Res.{kind}.{name}"

    text = RES_RE.sub(sub, text)
    if any(f"Res.{kind}." in text for kind in MAPPED_KINDS):
        text = STRING_RESOURCE_USAGE_IMPORT_RE.sub(
            "import org.jetbrains.compose.resources.stringResource", text
        )
        text = PAINTER_RESOURCE_USAGE_IMPORT_RE.sub(
            "import org.jetbrains.compose.resources.painterResource", text
        )
        text = ANDROID_R_IMPORT_RE.sub("import com.lonx.lyrico.resources.Res", text)
        # Drop names that may already be imported, then insert the new ones in
        # sorted order right after the Res import.
        existing = set(re.findall(r"^import com\.lonx\.lyrico\.resources\.(\w+)$", text, re.M))
        needed = sorted(names - existing)
        if needed:
            anchor = "import com.lonx.lyrico.resources.Res"
            block = "\n".join(f"import com.lonx.lyrico.resources.{n}" for n in needed)
            if anchor in text:
                text = text.replace(anchor, f"{anchor}\n{block}", 1)
            else:
                imports = list(IMPORT_RE.finditer(text))
                if imports:
                    last = imports[-1]
                    text = text[: last.end()] + "\n" + block + text[last.end():]
                else:
                    # No imports at all: place the block after the package line.
                    text = re.sub(
                        r"(^package .*$)",
                        lambda m: f"{m.group(1)}\n\n{anchor}\n{block}",
                        text,
                        count=1,
                        flags=re.M,
                    )
    decls = [m.group(1) for m in STRING_RES_DECL_RE.finditer(text)]
    if decls:
        # An Int resource id has no desktop equivalent: the value itself is a
        # StringResource, so the declaration changes type and loses the annotation.
        text = STRING_RES_DECL_RE.sub(r"val \1: StringResource", text)
        if STRING_RESOURCE_IMPORT not in text:
            text = _insert_imports(text, [STRING_RESOURCE_IMPORT])
    return text, sorted(names), unhandled + [
        f"@StringRes {d}: Int -> StringResource (review call sites)" for d in decls
    ]


def _insert_imports(text: str, lines: list[str]) -> str:
    imports = list(IMPORT_RE.finditer(text))
    if imports:
        last = imports[-1]
        return text[: last.end()] + "\n" + "\n".join(lines) + text[last.end():]
    return text


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="+")
    ap.add_argument("--write", action="store_true")
    args = ap.parse_args(argv)

    for path in args.files:
        with open(path, encoding="utf-8") as fh:
            original = fh.read()
        updated, names, notes = rewrite(original)
        changed = updated != original
        if args.write and changed:
            with open(path, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(updated)
        status = "written" if (args.write and changed) else ("would change" if changed else "unchanged")
        print(f"{status}: {path}  ({len(names)} strings)")
        for note in sorted(set(notes)):
            print(f"    manual: {note}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
