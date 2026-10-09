#!/usr/bin/env python3
"""Convert Android vector drawables into SVG files for Compose Multiplatform resources.

Why this exists: the Android module referenced six `R.drawable.*` icons. Desktop builds do not
compile `src/main/res` at all, and Compose Multiplatform's `painterResource` cannot read Android's
`<vector>` XML on non-Android targets -- it decodes raster formats and SVG instead
(`androidx.compose.ui.res.SVGPainter` is present in the desktop artifact, and the SVG support comes
from Skiko's `org.jetbrains.skia.svg`). So every Android vector icon used by a ported screen has to
exist as an `.svg` under `src/main/composeResources/drawable/`.

The translation is deliberately literal: viewport becomes the SVG `viewBox`, and each `<path>` keeps
its `pathData` verbatim. Android's `#AARRGGBB` becomes `#RRGGBB` plus `fill-opacity`/`stroke-opacity`,
because SVG has no alpha channel in a colour literal.

Usage:
    python scripts/migrate-vector-drawables.py --write lyrico-app/src/main/res/drawable/ic_album_24dp.xml
    python scripts/migrate-vector-drawables.py lyrico-app/src/main/res/drawable/ic_arrow_up_24dp.xml   # dry run

Only `<vector>`/`<group>`/`<path>` are handled; a `<group>` with a transform, a `<clip-path>`, a
gradient, or a colour reference (`@color/...`) is reported as unsupported rather than silently
emitted wrong.
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"

DEFAULT_OUT = Path("lyrico-app/src/main/composeResources/drawable")


def attr(node: ET.Element, name: str) -> str | None:
    return node.get(f"{ANDROID_NS}{name}")


def dimension_to_number(value: str | None, what: str) -> float:
    if value is None:
        raise ValueError(f"missing {what}")
    match = re.fullmatch(r"([0-9.]+)(dp|dip|px)?", value.strip())
    if not match:
        raise ValueError(f"unsupported {what}: {value!r}")
    return float(match.group(1))


def convert_color(value: str, what: str, path: ET.Element) -> tuple[str, str | None]:
    """Return (rgb colour, opacity or None) for an Android colour literal."""
    text = value.strip()
    if not text.startswith("#"):
        raise ValueError(f"unsupported {what} {text!r} (colour references need a manual value)")
    digits = text[1:]
    if len(digits) == 8:
        alpha = int(digits[0:2], 16)
        rgb = f"#{digits[2:].upper()}"
        opacity = None if alpha == 0xFF else f"{alpha / 255:.3f}".rstrip("0").rstrip(".")
        return rgb, opacity
    if len(digits) == 6:
        return f"#{digits.upper()}", None
    if len(digits) == 3:
        expanded = "".join(c * 2 for c in digits)
        return f"#{expanded.upper()}", None
    raise ValueError(f"unsupported {what} {text!r}")


def path_to_svg(node: ET.Element) -> str:
    data = attr(node, "pathData")
    if not data:
        raise ValueError("<path> without android:pathData")
    pieces: list[str] = []

    fill = attr(node, "fillColor")
    stroke = attr(node, "strokeColor")
    if fill is None and stroke is None:
        raise ValueError("<path> with neither fillColor nor strokeColor")
    if fill is not None:
        if fill.strip().lower() == "@android:color/transparent":
            pieces.append('fill="none"')
        else:
            rgb, opacity = convert_color(fill, "fillColor", node)
            pieces.append(f'fill="{rgb}"')
            if opacity:
                pieces.append(f'fill-opacity="{opacity}"')
    else:
        pieces.append('fill="none"')

    if stroke is not None:
        rgb, opacity = convert_color(stroke, "strokeColor", node)
        pieces.append(f'stroke="{rgb}"')
        if opacity:
            pieces.append(f'stroke-opacity="{opacity}"')
        pieces.append(f'stroke-width="{attr(node, "strokeWidth") or "1"}"')
        if attr(node, "strokeLineCap"):
            pieces.append(f'stroke-linecap="{attr(node, "strokeLineCap")}"')
        if attr(node, "strokeLineJoin"):
            pieces.append(f'stroke-linejoin="{attr(node, "strokeLineJoin")}"')

    # Android's default fillType is nonZero, which is also SVG's default.
    if (attr(node, "fillType") or "nonZero") == "evenOdd":
        pieces.append('fill-rule="evenodd"')

    for name, keep in (("fillAlpha", "fill-opacity"), ("strokeAlpha", "stroke-opacity")):
        value = attr(node, name)
        if value is not None:
            pieces = [p for p in pieces if not p.startswith(f'{keep}="')]
            pieces.append(f'{keep}="{value}"')

    return f'  <path {" ".join(pieces)} d="{data}"/>'


def walk(node: ET.Element, out: list[str]) -> None:
    for child in node:
        tag = child.tag.split("}")[-1]
        if tag == "path":
            out.append(path_to_svg(child))
        elif tag == "group":
            if child.attrib:
                unsupported = ", ".join(sorted(k.split("}")[-1] for k in child.attrib))
                raise ValueError(f"<group> attributes are not translated: {unsupported}")
            walk(child, out)
        else:
            raise ValueError(f"unsupported element <{tag}>")


def convert(source: Path) -> str:
    root = ET.parse(source).getroot()
    if root.tag.split("}")[-1] != "vector":
        raise ValueError("root element is not <vector>")
    viewport_width = dimension_to_number(attr(root, "viewportWidth"), "viewportWidth")
    viewport_height = dimension_to_number(attr(root, "viewportHeight"), "viewportHeight")
    width = dimension_to_number(attr(root, "width"), "width")
    height = dimension_to_number(attr(root, "height"), "height")

    body: list[str] = []
    walk(root, body)
    if not body:
        raise ValueError("no drawable content found")

    header = (
        f'<svg xmlns="http://www.w3.org/2000/svg" width="{width:g}" height="{height:g}"'
        f' viewBox="0 0 {viewport_width:g} {viewport_height:g}">'
    )
    return "\n".join([header, *body, "</svg>", ""])


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("sources", nargs="+", type=Path, help="Android vector drawable XML files")
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT, help=f"output directory (default {DEFAULT_OUT})")
    parser.add_argument("--write", action="store_true", help="write the files (default is a dry run)")
    args = parser.parse_args()

    failures = 0
    for source in args.sources:
        target = args.out / f"{source.stem}.svg"
        try:
            svg = convert(source)
        except (ValueError, ET.ParseError) as error:
            print(f"FAIL {source}: {error}", file=sys.stderr)
            failures += 1
            continue
        print(f"{'write' if args.write else 'dry-run'} {source} -> {target}")
        if args.write:
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(svg, encoding="utf-8")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
