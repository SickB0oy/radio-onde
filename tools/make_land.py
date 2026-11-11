"""Convertit les contours des terres de Natural Earth (GeoJSON, domaine public) en un petit
fichier binaire lu par le globe de Radio Onde.

Usage : python3 tools/make_land.py ne_110m_land.geojson app/src/main/assets/land.bin

Format (petit-boutiste) : int32 nombre de contours, puis pour chaque contour :
int32 nombre de points, puis les points en float32 (longitude, latitude).
"""
import json
import struct
import sys


def rings(geometry):
    if geometry is None:
        return
    kind, coords = geometry["type"], geometry["coordinates"]
    if kind == "Polygon":
        yield from coords
    elif kind == "MultiPolygon":
        for polygon in coords:
            yield from polygon
    elif kind == "GeometryCollection":
        for g in geometry["geometries"]:
            yield from rings(g)


def main(src, dst):
    with open(src, encoding="utf-8") as f:
        data = json.load(f)
    features = data["features"] if data.get("type") == "FeatureCollection" else [data]
    out = [r for feat in features for r in rings(feat.get("geometry")) if len(r) >= 3]
    points = 0
    with open(dst, "wb") as f:
        f.write(struct.pack("<i", len(out)))
        for ring in out:
            f.write(struct.pack("<i", len(ring)))
            for lon, lat, *_ in ring:
                f.write(struct.pack("<ff", lon, lat))
            points += len(ring)
    print(f"{len(out)} contours, {points} points -> {dst}")
    if points < 1000:
        sys.exit("Trop peu de points : le fichier téléchargé n'est sans doute pas la bonne carte.")


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
