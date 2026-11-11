"""Génère le logo de Radio Onde : une petite radio et le texte « RADIO ONDE ».
Sorties : icon.svg (aperçu) et ic_launcher_foreground.xml (vector drawable Android)."""
import sys
from fontTools.ttLib import TTFont
from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen

AMBER, INK, LIGHT = "#FFB547", "#0E1116", "#F3F4F6"
FONT = "/usr/share/fonts/opentype/inter/Inter-Bold.otf"
TEXT, BASELINE, MAX_W, TRACK = "RADIO ONDE", 72.5, 46.0, 0.10

font = TTFont(FONT)
gs, cmap, hmtx = font.getGlyphSet(), font.getBestCmap(), font["hmtx"]
upm = font["head"].unitsPerEm
names = [cmap[ord(c)] for c in TEXT]
advance = sum(hmtx[n][0] for n in names) + TRACK * upm * (len(names) - 1)
scale = MAX_W / advance
x = 54 - MAX_W / 2
pen = SVGPathPen(gs)
for n in names:
    tp = TransformPen(pen, (scale, 0, 0, -scale, x, BASELINE))
    gs[n].draw(tp)
    x += (hmtx[n][0] + TRACK * upm) * scale
text_path = pen.getCommands()

shapes = [
    # corps de la radio
    ("fill", AMBER, "M38,36h32a6,6 0 0 1 6,6v16a6,6 0 0 1 -6,6h-32a6,6 0 0 1 -6,-6v-16a6,6 0 0 1 6,-6z"),
    # haut-parleur
    ("fill", INK, "M45,43.5a6.5,6.5 0 1 1 0,13a6.5,6.5 0 1 1 0,-13z"),
    ("fill", AMBER, "M45,47.5a2.5,2.5 0 1 1 0,5a2.5,2.5 0 1 1 0,-5z"),
    # cadran et boutons
    ("fill", INK, "M56.5,43h12a1.5,1.5 0 0 1 1.5,1.5v2a1.5,1.5 0 0 1 -1.5,1.5h-12a1.5,1.5 0 0 1 -1.5,-1.5v-2a1.5,1.5 0 0 1 1.5,-1.5z"),
    ("fill", AMBER, "M63,43.8h1.4v3.4h-1.4z"),
    ("fill", INK, "M59,51.5a2,2 0 1 1 0,4a2,2 0 1 1 0,-4z"),
    ("fill", INK, "M66.5,51.5a2,2 0 1 1 0,4a2,2 0 1 1 0,-4z"),
    # antenne et ondes
    ("stroke", AMBER, "M64,36L69,28"),
    ("fill", AMBER, "M69,26.2a1.8,1.8 0 1 1 0,3.6a1.8,1.8 0 1 1 0,-3.6z"),
    ("stroke", AMBER, "M71.8,24.6A4.2,4.2 0 0 1 72.9,31.2"),
    ("stroke", AMBER, "M74,21.4A7.6,7.6 0 0 1 76,33.2"),
    # texte
    ("fill", LIGHT, text_path),
]

svg = ['<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">',
       f'<rect width="108" height="108" fill="{INK}"/>']
xml = ['<?xml version="1.0" encoding="utf-8"?>',
       '<!-- Logo Radio Onde : petite radio ambre, ondes et texte « RADIO ONDE ». Généré par make_icon.py -->',
       '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
       '    android:width="108dp"', '    android:height="108dp"',
       '    android:viewportWidth="108"', '    android:viewportHeight="108">']
for kind, color, d in shapes:
    if kind == "fill":
        svg.append(f'<path d="{d}" fill="{color}"/>')
        xml.append(f'    <path android:fillColor="{color}" android:pathData="{d}" />')
    else:
        svg.append(f'<path d="{d}" fill="none" stroke="{color}" stroke-width="2.2" stroke-linecap="round"/>')
        xml.append(f'    <path android:strokeColor="{color}" android:strokeWidth="2.2" android:strokeLineCap="round" android:pathData="{d}" />')
svg.append("</svg>")
xml.append("</vector>")
out = sys.argv[1]
open(f"{out}/icon.svg", "w").write("\n".join(svg))
open(f"{out}/ic_launcher_foreground.xml", "w").write("\n".join(xml) + "\n")
print("ok, largeur texte", MAX_W, "échelle", round(scale, 5))
