#!/usr/bin/env python3
"""Convert the mascot SVGs into Android VectorDrawables.

Only handles the subset these files use — <path> with fill, opacity and a flat
d attribute — which is deliberate: VectorDrawable does not support filter, mask,
clipPath or gradients, and silently drops them, so anything else is an error
rather than something to pass through.
"""
import re
import sys

HEADER = '''<?xml version="1.0" encoding="utf-8"?>
<!--
  信鸽「咕咕」 — generated from docs/mascot/{src} by tools/svg_to_vectordrawable.py
  Do not edit by hand: edit the SVG and regenerate, so the two never drift.
-->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="{w}dp"
    android:height="{h}dp"
    android:viewportWidth="{vw}"
    android:viewportHeight="{vh}">
'''

FORBIDDEN = ("filter", "mask", "clipPath", "linearGradient", "radialGradient", "<style")


def convert(src_path: str, dst_path: str, size_dp: int) -> None:
    svg = open(src_path).read()

    for bad in FORBIDDEN:
        if bad in svg:
            sys.exit(f"error: {src_path} uses {bad}, which VectorDrawable cannot render")

    vb = re.search(r'viewBox="([\d.\- ]+)"', svg)
    if not vb:
        sys.exit(f"error: {src_path} has no viewBox")
    vw, vh = vb.group(1).split()[2:4]

    out = [HEADER.format(
        src=src_path.split("/")[-1], w=size_dp, h=size_dp, vw=vw, vh=vh,
    )]
    for m in re.finditer(r'<path\s+([^>]*?)/>', svg, re.S):
        attrs = m.group(1)
        d = re.search(r'\sd="([^"]+)"', attrs)
        fill = re.search(r'fill="([^"]+)"', attrs)
        opacity = re.search(r'opacity="([^"]+)"', attrs)
        if not d:
            continue
        fill = fill.group(1) if fill else "#000000"
        # VectorDrawable wants ARGB
        argb = "#" + fill.lstrip("#") if len(fill) == 7 else fill
        line = f'    <path\n        android:fillColor="{argb}"'
        if opacity:
            alpha = float(opacity.group(1))
            line += f'\n        android:fillAlpha="{alpha}"'
        line += f'\n        android:pathData="{d.group(1).strip()}" />'
        out.append(line + "\n")
    out.append("</vector>\n")
    open(dst_path, "w").write("".join(out))
    print(f"{src_path} -> {dst_path}")


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2], size_dp=int(sys.argv[3]) if len(sys.argv) > 3 else 108)
