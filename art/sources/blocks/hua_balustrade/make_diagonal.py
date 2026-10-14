#!/usr/bin/env python3
"""Builds hua_balustrade_side_diagonal.json from hua_balustrade_side.json.

The side arm points north (z 0..6.25 from the post face at 6.25). The diagonal arm keeps the same parts, runs from the
post centre to the block corner (8*sqrt(2) from the centre, so two diagonal neighbours meet exactly at the shared
corner) and is turned -45 degrees about Y around the block centre, which points it north-east. The blockstate turns
it with y 90/180/270 for the other three diagonals. Parts next to the post move out by the post's half-diagonal
minus its half-width, so they sit where the post's corner is; long faces keep their UV region, the carved panel is
fitted to the visible length.
"""
import copy
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[4]
MODELS = ROOT / "src/main/resources/assets/murim/models/block"
side = json.loads((MODELS / "hua_balustrade_side.json").read_text())

CORNER = 8 - 8 * math.sqrt(2)          # outer end, local z (-3.3137)
SHIFT = 1.75 * math.sqrt(2) - 1.75     # post vertex vs post face (0.7249)
INNER = 8.0                             # inner ends hide in the post


def r(v):
    return round(v, 4)


def span_for(name, z0, z1):
    if name in ("rail", "panel"):
        return CORNER, INNER
    return z0 - SHIFT, (INNER if z1 >= 6.25 else z1 - SHIFT)


out = {k: v for k, v in side.items() if k != "elements"}
out["elements"] = []
for e in side["elements"]:
    e = copy.deepcopy(e)
    z0, z1 = e["from"][2], e["to"][2]
    n0, n1 = span_for(e["name"], z0, z1)
    k = (n1 - n0) / (z1 - z0)
    e["from"][2], e["to"][2] = r(n0), r(n1)
    for face_name, f in e["faces"].items():
        f.pop("cullface", None)
        u0, v0, u1, v1 = f["uv"]
        if e["name"] == "panel" and face_name in ("east", "west"):
            # The carved field (u 8..11.125) spans the visible part: outer end .. post surface at the panel edge.
            visible = (8 - (1.75 * math.sqrt(2) - 0.9)) - CORNER
            width = 3.125 * (n1 - n0) / visible
            f["uv"] = [r(8 + width), v0, 8, v1] if face_name == "east" else [8, v0, r(8 + width), v1]
        elif face_name in ("east", "west"):
            f["uv"] = [u0, v0, r(u0 + (u1 - u0) * k), v1]
        elif face_name in ("up", "down"):
            f["uv"] = [u0, v0, u1, r(v0 + (v1 - v0) * k)]
    e["rotation"] = {"angle": -45, "axis": "y", "origin": [8, 8, 8]}
    out["elements"].append(e)

(MODELS / "hua_balustrade_side_diagonal.json").write_text(json.dumps(out, indent=1) + "\n")
print("wrote", MODELS / "hua_balustrade_side_diagonal.json")
