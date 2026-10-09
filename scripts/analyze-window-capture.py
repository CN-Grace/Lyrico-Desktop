#!/usr/bin/env python3
"""Turns a window screenshot into numbers, because a screenshot is only evidence if it can be checked.

`scripts/capture-window.ps1` proves a window with a given title existed on screen; a PNG on its own
does not say whether it was painted, blank, or showing a different application. This script answers
the questions that matter mechanically:

* is the window actually rendered, or a uniform block of one colour (a window captured before its
  first frame paints, which `CopyFromScreen` then fills with whatever is behind it),
* does the frame contain text-like detail at all, and in which rows (an ASCII density map, so a list
  can be told apart from a centred empty state without a human looking at the image),
* are the theme's accent colours present (Miuix's primary blue, and the red/amber used by the app log
  level chips), which a blank or occluded window cannot have,
* how two captures of the same window differ, which is how "the screen renders the rows in the
  database" is demonstrated rather than asserted.

Usage:
    python scripts/analyze-window-capture.py docs/port-evidence/p4-applog-rows.png \
        [--compare docs/port-evidence/p4-applog-empty.png] [--write]
"""

from __future__ import annotations

import argparse
import sys
from collections import Counter
from pathlib import Path

try:
    from PIL import Image
except ImportError:  # pragma: no cover - environment problem, not a code path
    sys.exit("Pillow is required: python -m pip install pillow")

# A pixel is "text-like" when it is dark; everything the window draws on its light surface is either
# near-white or ink.
TEXT_LUMA = 400

# Coarse grid for the density map. Wide enough to show a top bar and a list, small enough to read.
MAP_COLUMNS = 96
MAP_ROWS = 28


def load(path: Path) -> Image.Image:
    return Image.open(path).convert("RGB")


def colour_stats(image: Image.Image) -> dict:
    pixels = list(image.getdata())
    counts = Counter(pixels)
    return {
        "size": image.size,
        "distinct_colours": len(counts),
        "top_colours": counts.most_common(4),
        "text_pixels": sum(1 for p in pixels if sum(p) < TEXT_LUMA),
        # Flatness: a uniform window (one colour) is the signature of a capture that beat the first
        # frame. Real UI always has several surface tones.
        "most_common_share": counts.most_common(1)[0][1] / len(pixels),
        "accent_blue": sum(1 for p in pixels if p[2] > 180 and p[2] - p[0] > 60),
        "accent_warm": sum(1 for p in pixels if p[0] > 150 and p[0] - p[2] > 50),
    }


def density_map(image: Image.Image) -> tuple[list[str], int]:
    width, height = image.size
    cell_w, cell_h = width / MAP_COLUMNS, height / MAP_ROWS
    lines: list[str] = []
    rows_with_text = 0
    for row in range(MAP_ROWS):
        line = ""
        row_has_detail = False
        for column in range(MAP_COLUMNS):
            x0, y0 = int(column * cell_w), int(row * cell_h)
            dark = total = 0
            for y in range(y0, min(int((row + 1) * cell_h), height), 2):
                for x in range(x0, min(int((column + 1) * cell_w), width), 2):
                    if sum(image.getpixel((x, y))) < TEXT_LUMA:
                        dark += 1
                    total += 1
            share = dark / max(total, 1)
            line += "#" if share > 0.15 else ("+" if share > 0.05 else ("." if share > 0.01 else " "))
            if share > 0.05:
                row_has_detail = True
        lines.append(line)
        if row_has_detail:
            rows_with_text += 1
    return lines, rows_with_text


def compare(before: Image.Image, after: Image.Image) -> dict:
    if before.size != after.size:
        return {"comparable": False, "reason": f"{before.size} vs {after.size}"}
    left, right = before.load(), after.load()
    width, height = before.size
    changed = 0
    for y in range(height):
        for x in range(width):
            a, b = left[x, y], right[x, y]
            # A small tolerance keeps font antialiasing from counting as a change.
            if abs(a[0] - b[0]) + abs(a[1] - b[1]) + abs(a[2] - b[2]) > 30:
                changed += 1
    return {
        "comparable": True,
        "changed_pixels": changed,
        "changed_share": changed / (width * height),
    }


def report(path: Path, compare_path: Path | None) -> str:
    image = load(path)
    stats = colour_stats(image)
    lines, rows_with_text = density_map(image)

    out = [f"file: {path}", f"size: {stats['size'][0]}x{stats['size'][1]}"]
    out.append(
        f"distinct_colours: {stats['distinct_colours']}  "
        f"most_common_share: {stats['most_common_share']:.3f}  text_pixels: {stats['text_pixels']}"
    )
    out.append(f"accent_blue_pixels: {stats['accent_blue']}  accent_warm_pixels: {stats['accent_warm']}")
    out.append("top_colours: " + ", ".join(f"{c}={n}" for c, n in stats["top_colours"]))
    out.append(f"grid_rows_with_detail: {rows_with_text}/{MAP_ROWS}")
    out.append("density map (# ink, + detail, . faint):")
    out.extend("  " + line for line in lines)

    if stats["most_common_share"] > 0.9:
        out.append(
            "VERDICT: SUSPECT - the window is nearly one flat colour, which is what a capture taken "
            "before the first frame paints looks like. Re-run with a longer -SettleMs."
        )
    elif stats["text_pixels"] < 200:
        out.append("VERDICT: SUSPECT - almost no ink; the window may not have drawn its content.")
    else:
        out.append("VERDICT: OK - the window painted real content.")

    if compare_path is not None:
        diff = compare(load(compare_path), image)
        out.append(f"compare_with: {compare_path}")
        if diff["comparable"]:
            out.append(
                f"changed_pixels: {diff['changed_pixels']} "
                f"({diff['changed_share'] * 100:.2f}% of the window)"
            )
        else:
            out.append(f"changed_pixels: not comparable ({diff['reason']})")
    return "\n".join(out)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("image", type=Path, help="PNG produced by scripts/capture-window.ps1")
    parser.add_argument("--compare", type=Path, help="an earlier capture of the same window to diff against")
    parser.add_argument("--write", action="store_true", help="also write the report next to the image")
    args = parser.parse_args()

    text = report(args.image, args.compare)
    print(text)
    if args.write:
        target = args.image.with_suffix(".analysis.txt")
        target.write_text(text + "\n", encoding="utf-8")
        print(f"\nwrote {target}")


if __name__ == "__main__":
    main()
