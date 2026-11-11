"""Calcule le rectangle englobant de chaque pays (Natural Earth admin 0, domaine public), pour
écarter du globe les stations dont la position déclarée ne correspond pas à leur pays.

Usage : python3 tools/make_countries.py ne_110m_admin_0_countries.geojson app/src/main/assets/countries.txt

Une ligne par pays : CODE latMin latMax lonMin lonMax
"""
import json
import sys


def points(geometry):
    kind, coords = geometry["type"], geometry["coordinates"]
    polygons = [coords] if kind == "Polygon" else coords if kind == "MultiPolygon" else []
    for polygon in polygons:
        for ring in polygon:
            for lon, lat, *_ in ring:
                yield lon, lat


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        features = json.load(f)["features"]
    lines = []
    for feat in features:
        props = feat.get("properties") or {}
        code = None
        for key in ("ISO_A2_EH", "ISO_A2", "iso_a2"):
            value = str(props.get(key) or "")
            if len(value) == 2 and value.isalpha():
                code = value.upper()
                break
        geometry = feat.get("geometry")
        if not code or not geometry:
            continue
        pts = list(points(geometry))
        if not pts:
            continue
        lons = [p[0] for p in pts]
        lats = [p[1] for p in pts]
        lines.append(f"{code} {min(lats):.2f} {max(lats):.2f} {min(lons):.2f} {max(lons):.2f}")
    with open(dst, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"{len(lines)} pays -> {dst}")
    if len(lines) < 100:
        sys.exit("Trop peu de pays : le fichier téléchargé n'est sans doute pas le bon.")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
