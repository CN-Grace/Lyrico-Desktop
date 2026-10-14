#!/usr/bin/env python3
"""Detects a scrollbar thumb in the right edge of a window capture.

Compose Desktop draws no scrollbar unless the screen adds one, so "this list has a visible
scrollbar" is a claim about pixels, not about code. The thumb is a rounded bar a few pixels wide
inside the right edge whose colour differs from the surface behind it, spanning a contiguous run of
rows proportional to the visible fraction of the list.

A row counts as a hit only when at least MIN_COLS columns in the band differ from the surface
colour: the window's own 1 px edge line would otherwise make every row look like a hit.

**The verdict is not trustworthy on its own — read the numbers, not the word.** Two failure modes were
observed on the songs page, in opposite directions: when the list does not overflow the viewport the
thumb either spans the whole track or is hidden entirely, and the window's own edge line and the page's
content reaching the right edge both count as "not the surface colour". A "THUMB PRESENT" that is 100%
of the height and a "NO scrollbar thumb" that is 4% are both artifacts. It is kept as the record of an
attempt, not as a usable oracle; the scrollbar coverage claim in PLAN.md 6g rests on the source, not on
this script.

Usage: python scripts/probe-scrollbar.py <capture.png> [more.png ...]
"""
import sys
from PIL import Image

INNER_SKIP = 2        # outermost pixels are the window edge, not content
BAND = 16             # band width measured from the right edge, minus the skip
MIN_COLS = 3          # a thumb is several pixels wide; a 1 px edge line is not
DIFF = 20             # per-channel sum difference that counts as "not the surface colour"


def probe(path):
    img = Image.open(path).convert("RGB")
    w, h = img.size
    px = img.load()
    x0, x1 = w - BAND, w - INNER_SKIP
    counts = {}
    for y in range(h):
        for x in range(x0, x1):
            c = px[x, y]
            counts[c] = counts.get(c, 0) + 1
    surface = max(counts, key=counts.get)
    hits, cols = [], set()
    for y in range(h):
        row = 0
        for x in range(x0, x1):
            c = px[x, y]
            if sum(abs(c[i] - surface[i]) for i in range(3)) > DIFF:
                row += 1
                cols.add(x)
        hits.append(row >= MIN_COLS)
    best = cur = 0
    best_start = cur_start = 0
    for y, hit in enumerate(hits):
        if hit:
            if cur == 0:
                cur_start = y
            cur += 1
            if cur > best:
                best, best_start = cur, cur_start
        else:
            cur = 0
    print(f"{path}")
    print(f"  size           : {w} x {h}   surface {surface}   band x[{x0}..{x1})")
    print(f"  longest run    : {best} px tall (y {best_start}..{best_start + best - 1})"
          f" = {best / h:.0%} of height")
    print(f"  columns hit    : {sorted(cols)}")
    print(f"  verdict        : {'THUMB PRESENT' if best > h * 0.08 else 'NO scrollbar thumb'}")


for p in sys.argv[1:]:
    probe(p)
