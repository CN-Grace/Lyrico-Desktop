#!/usr/bin/env python3
"""Dependency closure for Android -> desktop port triage.

Given one or more source files from the Android (uncompiled) tree, walk their
`com.lonx.lyrico.*` imports transitively and report the closure, flagging:

  * files that already exist in the Kotlin (compiled) tree,
  * files carrying Android-only coupling (imports outside the desktop surface),
  * external (non-project) dependencies pulled in by the closure.

This keeps port planning evidence-based: it answers "what exactly has to move
for this unit to compile" without guessing.

Usage:
    python scripts/port-closure.py lyrico-app/src/main/java/com/lonx/lyrico/data/repository/SettingsRepository.kt
    python scripts/port-closure.py <files...> --root lyrico-app/src/main
"""
from __future__ import annotations

import argparse
import os
import re
import sys
from collections import deque

IMPORT_RE = re.compile(r"^\s*import\s+(?:static\s+)?([\w.]+)(?:\s+as\s+\w+)?\s*$")

# Android-only prefixes: any of these means the file needs an edit before it can
# live in the desktop (kotlin) source set.
ANDROID_PREFIXES = (
    "android.",
    "androidx.activity.",
    "androidx.work.",
    "androidx.documentfile.",
    "androidx.core.",
    "androidx.datastore.preferences.preferencesDataStore",
    "android.net.Uri",
    "android.os.Parcelable",
)

# Android-ish prefixes that are fine on desktop JVM (Compose Multiplatform,
# DataStore core, Room JVM, coroutines).
DESKTOP_OK_PREFIXES = (
    "androidx.compose.",
    "androidx.datastore.preferences.core.",
    "androidx.room.",
    "androidx.sqlite.",
    "kotlinx.",
    "java.",
    "javax.",
    "kotlin.",
)


def index_tree(root: str) -> dict[str, str]:
    """Map fully-qualified class name -> file path for every .kt/.java file."""
    out: dict[str, str] = {}
    for dirpath, _dirnames, filenames in os.walk(root):
        for name in filenames:
            if not name.endswith((".kt", ".java")):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8", errors="replace") as fh:
                text = fh.read()
            pkg = re.search(r"^\s*package\s+([\w.]+)", text, re.M)
            if not pkg:
                continue
            pkg_name = pkg.group(1)
            stem = name.rsplit(".", 1)[0]
            out[f"{pkg_name}.{stem}"] = path
            # Kotlin files can declare several top-level classes; index the ones
            # we can see so the closure does not stop at a multi-class file.
            for cls in re.findall(r"^\s*(?:open |abstract |sealed |data |enum |value )*class\s+(\w+)", text, re.M):
                out.setdefault(f"{pkg_name}.{cls}", path)
            for obj in re.findall(r"^\s*(?:open |abstract |sealed |data |enum |value )*object\s+(\w+)", text, re.M):
                out.setdefault(f"{pkg_name}.{obj}", path)
    return out


def imports_of(path: str) -> list[str]:
    with open(path, encoding="utf-8", errors="replace") as fh:
        return [m.group(1) for m in (IMPORT_RE.match(ln) for ln in fh) if m]


def resolve(fqcn: str, index: dict[str, str]) -> str | None:
    """Resolve an import to a file, walking up outer-class segments."""
    parts = fqcn.split(".")
    while len(parts) > 1:
        candidate = ".".join(parts)
        if candidate in index:
            return index[candidate]
        parts.pop()
    return None


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("roots", nargs="+", help="entry-point source files")
    ap.add_argument("--src", default="lyrico-app/src/main", help="source root containing both trees")
    args = ap.parse_args(argv)

    src = args.src
    java_root = os.path.join(src, "java")
    kotlin_root = os.path.join(src, "kotlin")

    index: dict[str, str] = {}
    index.update(index_tree(java_root))
    kotlin_index = index_tree(kotlin_root)

    seen: set[str] = set()
    order: list[str] = []
    queue: deque[str] = deque(os.path.abspath(p) for p in args.roots)
    external: set[str] = set()
    android: dict[str, list[str]] = {}

    while queue:
        path = queue.popleft()
        if path in seen or not os.path.exists(path):
            continue
        seen.add(path)
        order.append(path)
        for imp in imports_of(path):
            if imp.startswith("com.lonx.lyrico"):
                target = resolve(imp, index)
                if target is None:
                    # Might already live in the Kotlin tree (ported earlier).
                    target = resolve(imp, kotlin_index)
                if target is None:
                    external.add(f"{imp}  <UNRESOLVED>")
                elif target not in seen:
                    queue.append(target)
            elif imp.split(".")[0] == "kotlin" or imp.split(".")[0] == "java":
                continue
            elif imp.startswith(DESKTOP_OK_PREFIXES):
                continue
            elif imp.startswith(ANDROID_PREFIXES):
                android.setdefault(path, []).append(imp)
            else:
                external.add(imp)

    def rel(p: str) -> str:
        return os.path.relpath(p, src).replace("\\", "/")

    def in_kotlin(p: str) -> bool:
        jp = rel(p)
        return os.path.exists(os.path.join(kotlin_root, jp[5:])) if jp.startswith("java/") else False

    print(f"closure: {len(order)} files\n")
    print("== already in kotlin tree ==")
    for p in order:
        if in_kotlin(p):
            print(f"  {rel(p)}")
    print("\n== needs moving ==")
    for p in order:
        if not in_kotlin(p):
            flag = " ANDROID" if p in android else ""
            print(f"  {rel(p)}{flag}")
    if android:
        print("\n== android coupling (needs edit) ==")
        for p, imps in android.items():
            print(f"  {rel(p)}")
            for imp in sorted(set(imps)):
                print(f"      {imp}")
    if external:
        print("\n== external imports (check availability) ==")
        for imp in sorted(external):
            print(f"  {imp}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
