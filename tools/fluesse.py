"""Erzeugt app/src/main/assets/fluesse.json mit den Flussläufen rund um Vorarlberg.

Quelle: OpenStreetMap über die Overpass-API (© OpenStreetMap-Mitwirkende, ODbL).
Genommen werden alle Flüsse und Kanäle sowie Bäche, an denen ein vowis-Pegel liegt.
Stollen, Druckleitungen und verrohrte Abschnitte bleiben weg, die sieht man ja nicht.
Die Linien werden vereinfacht, damit die Datei klein bleibt.
"""
import json
import math
import os
import re
import sys
import urllib.parse
import urllib.request

BBOX = (46.80, 9.48, 47.62, 10.30)  # süd, west, nord, ost
OVERPASS = "https://overpass-api.de/api/interpreter"
WFS = ("https://vowis.vorarlberg.at/geoserver/owf/ows?service=WFS&request=GetFeature"
       "&typename=owf:Pegel&srsname=EPSG:4326&outputFormat=application/json")
TOLERANCE_M = 12


def get(url, data=None):
    req = urllib.request.Request(url, data=data, headers={"User-Agent": "Fluessle-Datenaufbereitung"})
    with urllib.request.urlopen(req, timeout=300) as r:
        return json.load(r)


def norm(name):
    name = name.lower().replace("ß", "ss")
    name = re.sub(r"\(.*?\)", "", name)
    return re.sub(r"[^a-zäöü]", "", name)


def gauges():
    """Lage der vowis-Pegel als (lat, lon)."""
    result = []
    for f in get(WFS)["features"]:
        c = (f.get("geometry") or {}).get("coordinates")
        if c:
            result.append((c[1], c[0]))
    return result


def near_gauge(pts, gauges, metres=250):
    for lat, lon in pts:
        for glat, glon in gauges:
            if abs(lat - glat) * 110540 < metres and abs(lon - glon) * 75500 < metres:
                return True
    return False


HIDDEN_USAGE = {"headrace", "tailrace", "penstock", "spillway"}


def visible(tags):
    if tags.get("tunnel") or tags.get("location") in ("underground", "underwater"):
        return False
    if tags.get("usage") in HIDDEN_USAGE:
        return False
    return not re.search(r"stollen|leitung|überleitung|zuleitung", tags.get("name", ""), re.I)


def simplify(pts, tol):
    """Douglas-Peucker in Metern (lokal flach gerechnet)."""
    if len(pts) < 3:
        return pts
    k = math.cos(math.radians(pts[0][0]))

    def xy(p):
        return (p[1] * 111320 * k, p[0] * 110540)

    def dist(p, a, b):
        (px, py), (ax, ay), (bx, by) = xy(p), xy(a), xy(b)
        dx, dy = bx - ax, by - ay
        if dx == dy == 0:
            return math.hypot(px - ax, py - ay)
        t = max(0, min(1, ((px - ax) * dx + (py - ay) * dy) / (dx * dx + dy * dy)))
        return math.hypot(px - ax - t * dx, py - ay - t * dy)

    keep = [False] * len(pts)
    keep[0] = keep[-1] = True
    stack = [(0, len(pts) - 1)]
    while stack:
        i, j = stack.pop()
        best, idx = 0, None
        for m in range(i + 1, j):
            d = dist(pts[m], pts[i], pts[j])
            if d > best:
                best, idx = d, m
        if idx is not None and best > tol:
            keep[idx] = True
            stack += [(i, idx), (idx, j)]
    return [p for p, k_ in zip(pts, keep) if k_]


def merge(lines):
    """Hängt Wege mit gemeinsamen Endpunkten aneinander, damit die Namen schön entlang laufen."""
    lines = [list(l) for l in lines]
    changed = True
    while changed:
        changed = False
        for i in range(len(lines)):
            for j in range(len(lines)):
                if i == j or not lines[i] or not lines[j]:
                    continue
                a, b = lines[i], lines[j]
                if a[-1] == b[0]:
                    lines[i] = a + b[1:]
                elif a[-1] == b[-1]:
                    lines[i] = a + b[::-1][1:]
                elif a[0] == b[-1]:
                    lines[i] = b + a[1:]
                elif a[0] == b[0]:
                    lines[i] = b[::-1] + a[1:]
                else:
                    continue
                lines[j] = []
                changed = True
    return [l for l in lines if len(l) > 1]


def main(out):
    pegel = gauges()
    print(len(pegel), "Pegel", file=sys.stderr)
    s, w, n, e = BBOX
    query = f"""[out:json][timeout:240];
(
  way["waterway"~"^(river|canal)$"]({s},{w},{n},{e});
  way["waterway"="stream"]["name"]({s},{w},{n},{e});
);
out geom;"""
    data = get(OVERPASS, urllib.parse.urlencode({"data": query}).encode())
    by_name = {}
    kinds = {}
    for el in data["elements"]:
        tags = el.get("tags", {})
        name = tags.get("name")
        if not name or "geometry" not in el or not visible(tags):
            continue
        pts = [(round(g["lat"], 5), round(g["lon"], 5)) for g in el["geometry"]]
        by_name.setdefault(name, []).append(pts)
        kind = tags["waterway"]
        if kinds.get(name) != "river":
            kinds[name] = kind
    # Bäche nur, wenn irgendwo an ihnen ein Pegel steht
    for name in [n for n, k in kinds.items() if k == "stream"]:
        if not any(near_gauge(l, pegel) for l in by_name[name]):
            del by_name[name], kinds[name]

    rivers = []
    for name, lines in sorted(by_name.items()):
        merged = [simplify(l, TOLERANCE_M) for l in merge(lines)]
        rivers.append({
            "name": name,
            "major": kinds[name] == "river",
            "kind": kinds[name],
            "lines": [[c for p in l for c in p] for l in merged],
        })
    print("Bäche mit Pegel:", [r["name"] for r in rivers if r["kind"] == "stream"], file=sys.stderr)
    print(len(rivers), "Gewässer,", sum(len(l) // 2 for r in rivers for l in r["lines"]), "Punkte", file=sys.stderr)
    os.makedirs(os.path.dirname(out) or ".", exist_ok=True)
    with open(out, "w", encoding="utf-8") as f:
        json.dump({"source": "© OpenStreetMap-Mitwirkende (ODbL)", "rivers": rivers}, f,
                  ensure_ascii=False, separators=(",", ":"))


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "app/src/main/assets/fluesse.json")
