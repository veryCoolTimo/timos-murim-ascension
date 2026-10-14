#!/usr/bin/env python3
"""Composes preview_diagonal.json: the in-game models laid out as the blockstate would place them.

A south-east diagonal line of 4, a north-east diagonal line of 3 and a straight east-west line of 3. Each arm is the
blockstate's model turned by its y rotation (multiples of 90 about the block centre, which commute with the arm's own
-45 element rotation), then shifted to its block. Import the result with bbmodel_import_java_block to render it.
"""
import copy
import json
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[4]
MODELS = ROOT / "src/main/resources/assets/murim/models/block"
HERE = pathlib.Path(__file__).resolve().parent


def load(name):
    return json.loads((MODELS / f"{name}.json").read_text())


POST, SIDE, DIAG = load("hua_balustrade_post"), load("hua_balustrade_side"), load("hua_balustrade_side_diagonal")
UP, DOWN = load("hua_balustrade_side_up"), load("hua_balustrade_side_down")
ARMS = {  # property -> (model, y)
    "north": (SIDE, 0), "east": (SIDE, 90), "south": (SIDE, 180), "west": (SIDE, 270),
    "up_north": (UP, 0), "up_east": (UP, 90), "up_south": (UP, 180), "up_west": (UP, 270),
    "down_north": (DOWN, 0), "down_east": (DOWN, 90), "down_south": (DOWN, 180), "down_west": (DOWN, 270),
    "north_east": (DIAG, 0), "south_east": (DIAG, 90), "south_west": (DIAG, 180), "north_west": (DIAG, 270),
}
CW = {"north": "east", "east": "south", "south": "west", "west": "north", "up": "up", "down": "down"}


def turn(e, y):
    """Turn an element clockwise (seen from above) by y degrees about the block centre."""
    e = copy.deepcopy(e)
    for _ in range(y // 90):
        (x0, y0, z0), (x1, y1, z1) = e["from"], e["to"]
        e["from"], e["to"] = [16 - z1, y0, x0], [16 - z0, y1, x1]
        e["faces"] = {CW[k]: v for k, v in e["faces"].items()}
        for f in ("up", "down"):
            if f in e["faces"]:
                e["faces"][f]["rotation"] = (e["faces"][f].get("rotation", 0) + 90) % 360
        if "rotation" in e:
            ox, oy, oz = e["rotation"]["origin"]
            e["rotation"]["origin"] = [16 - oz, oy, ox]
            # A clockwise quarter turn about Y carries an X-axis tilt to Z and a Z-axis tilt to -X.
            if e["rotation"]["axis"] == "x":
                e["rotation"]["axis"] = "z"
            elif e["rotation"]["axis"] == "z":
                e["rotation"]["axis"], e["rotation"]["angle"] = "x", -e["rotation"]["angle"]
    return e


def shift(e, bx, bz, by=0):
    e = copy.deepcopy(e)
    e["from"] = [e["from"][0] + 16 * bx, e["from"][1] + 16 * by, e["from"][2] + 16 * bz]
    e["to"] = [e["to"][0] + 16 * bx, e["to"][1] + 16 * by, e["to"][2] + 16 * bz]
    if "rotation" in e:
        o = e["rotation"]["origin"]
        e["rotation"]["origin"] = [o[0] + 16 * bx, o[1] + 16 * by, o[2] + 16 * bz]
    for f in e["faces"].values():
        f.pop("cullface", None)
    return e


def block(bx, bz, arms, by=0):
    out = [shift(e, bx, bz, by) for e in POST["elements"]]
    for a in arms:
        model, y = ARMS[a]
        out += [shift(turn(e, y), bx, bz, by) for e in model["elements"]]
    return out


def cube(bx, by, bz):
    """A plain full block (wall or stair step) in the post's marble, to stand the stair runs on."""
    uv = {"uv": [8, 0, 9.75, 1.75], "texture": "#0"}
    return {"name": "step", "from": [16 * bx, 16 * by, 16 * bz], "to": [16 * bx + 16, 16 * by + 16, 16 * bz + 16],
            "faces": {f: dict(uv) for f in ("north", "east", "south", "west", "up", "down")}}


def stair_scene():
    """One fence: two flat blocks, four steps down, one flat block (run A falls to the south along x=0, run B the same
    turned to fall east along z=8). Each balustrade stands on a column of full blocks."""
    el = []
    heights = [4, 4, 3, 2, 1, 0, 0]
    n = len(heights)
    for i, y in enumerate(heights):
        arms = []
        if i > 0:
            arms.append("north" if heights[i - 1] == y else "up_north")
        if i < n - 1:
            arms.append("south" if heights[i + 1] == y else "down_south")
        el += block(0, i, arms, y)
        el += [cube(0, yy, i) for yy in range(-1, y)]
        turned = [{"north": "west", "south": "east", "up_north": "up_west", "down_south": "down_east"}[a] for a in arms]
        el += block(3 + i, 9, turned, y)
        el += [cube(3 + i, yy, 9) for yy in range(-1, y)]
    return el


import sys
if len(sys.argv) > 1 and sys.argv[1] == "stairs":
    out = {k: v for k, v in POST.items() if k != "elements"}
    out["elements"] = stair_scene()
    (HERE / "preview_slope.json").write_text(json.dumps(out, indent=1) + "\n")
    print(len(out["elements"]), "elements")
    sys.exit(0)


elements = []
se = [(0, 0), (1, 1), (2, 2), (3, 3)]
for i, (x, z) in enumerate(se):
    elements += block(x, z, (["north_west"] if i else []) + (["south_east"] if i < len(se) - 1 else []))
ne = [(4, 3), (5, 2), (6, 1)]
for i, (x, z) in enumerate(ne):
    elements += block(x, z, (["south_west"] if i else []) + (["north_east"] if i < len(ne) - 1 else []))
line = [(0, 3), (1, 3), (2, 3)]
for i, (x, z) in enumerate(line):
    elements += block(x, z, (["west"] if i else []) + (["east"] if i < len(line) - 1 else []))

out = {k: v for k, v in POST.items() if k != "elements"}
out["elements"] = elements
(HERE / "preview_diagonal.json").write_text(json.dumps(out, indent=1) + "\n")
print(len(elements), "elements")
