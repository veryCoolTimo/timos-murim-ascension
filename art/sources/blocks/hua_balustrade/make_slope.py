#!/usr/bin/env python3
"""Builds hua_balustrade_side_up.json and hua_balustrade_side_down.json: the sloped arms of the Hua balustrade down a
1:1 run of full blocks.

Author 06.10: a fence that runs straight and then goes down must not look like another fence — the slope is the same
fence: the flat post, the flat rail and the flat panel with its carved field, the flat brackets and feet. Parts may
sink into the step blocks («сквозь блок пройдёт» is fine).

Built pointing north. Rising arm: x = distance from this post centre, a line y = c + x; falling arm (upper block):
y = c - x from its own post, the same line in the lower block's frame (c + 16 - (16 - x)), so every tilted part runs
straight through the shared edge, where all of them end on one plane.
- rail: the flat rail's section (6.5..9.5 wide, 1.4 tall vertically) turned 45 degrees about X, centre 12.7 at the post
  centre: it meets every post at the flat rail's height above that post's foot (12..13.4).
- panel: the flat panel (2.5..11, 8.5 tall, 1 px under the rail as on the flat) as two stacked turned marble slabs
  whose end faces sit wholly inside the post: no gap at either post face, no corner out of the post's back. One darker
  field slab (the flat field's grey, 0.05 proud of the faces) lies over both, 1.5 px inside the top and bottom edges
  and 0.75 px clear of each post, so the marble around it reads as one continuous frame.
- brackets: the flat brackets turned with the rail, right under it at each post (same sizes along the rail).
- no feet: the panel reaches the post on its own.
- no bottom rail (the flat fence has none), no tall post, no drum.
The blockstate turns each model with y 90/180/270.
"""
import copy
import json
import math
import pathlib

ROOT = pathlib.Path(__file__).resolve().parents[4]
MODELS = ROOT / "src/main/resources/assets/murim/models/block"
side = json.loads((MODELS / "hua_balustrade_side.json").read_text())
by = {e["name"]: e for e in side["elements"]}
R2 = math.sqrt(2)
TEX = "#0"
OUTER = 8 - 8 * R2          # the shared edge, 8*sqrt2 along the slope
POST_FACE = 1.75
MARBLE = [10.25, 10.25, 10.5, 10.5]     # single-colour texels of hua_balustrade.png (16-space UV)
FIELD = [8.75, 9.0, 9.0, 9.25]          # the flat panel's carved-field grey
RAIL_C, RAIL_H = 12.7, 1.4
PANEL_LO, PANEL_HI = 2.5, 11.0


def r(v):
    return round(v, 4)


def span(src, name, yc, h, x_in, x_out, angle, uv=None):
    """src's section turned 45 degrees about X around (8, yc, 8), its end faces centred x_in and x_out across from the
    post centre (x_out 8 = the shared edge). h is the vertical section, kept; uv overrides the long faces' u range."""
    e = copy.deepcopy(src)
    hp = h / R2
    z_in, z_out = 8 - x_in * R2, 8 - x_out * R2
    e["name"] = name
    e["from"] = [src["from"][0], r(yc - hp / 2), r(z_out)]
    e["to"] = [src["to"][0], r(yc + hp / 2), r(z_in)]
    k = (z_in - z_out) / (src["to"][2] - src["from"][2])
    for face_name, f in e["faces"].items():
        f.pop("cullface", None)
        u0, v0, u1, v1 = f["uv"]
        if face_name in ("east", "west") and uv is not None:
            f["uv"] = list(uv[face_name])
        elif face_name in ("east", "west"):
            f["uv"] = [u0, v0, r(u0 + (u1 - u0) * k), v1]
        elif face_name in ("up", "down"):
            f["uv"] = [u0, v0, u1, r(v0 + (v1 - v0) * k)]
    e["rotation"] = {"angle": angle, "axis": "x", "origin": [8, r(yc), 8]}
    return e


def build(rising):
    angle = 45 if rising else -45
    els = [span(by["rail"], "rail", RAIL_C, RAIL_H, 0.35, 8.0, angle)]
    # Panel: the flat panel in two stacked halves (each 4.25 tall vertically), so each end face is short enough to sit
    # wholly inside the post: centred 0.6 from the post centre its corners are at -0.46 and 1.66, inside both post
    # faces (+-1.75). Nothing opens at either post face and nothing pokes out of the post's back.
    # Texture: plain marble; the flat panel's carved texture cut over two turned halves shows as chopped corner marks.
    mid = (PANEL_LO + PANEL_HI) / 2
    plain = {"east": MARBLE, "west": MARBLE}
    for name, y0, y1 in (("panel_low", PANEL_LO, mid), ("panel_high", mid, PANEL_HI)):
        els.append(span(by["panel"], name, (y0 + y1) / 2, y1 - y0, 0.6, 8.0, angle, uv=plain))
    # Inset field: one darker slab (the flat panel's field grey) over both halves, 1.5 px inside the panel's top and
    # bottom edges and 0.75 px clear of each post face, so the marble around it reads as one continuous frame.
    f0, f1 = PANEL_LO + 1.5, PANEL_HI - 1.5
    clear = POST_FACE + 0.75 + (f1 - f0) / 4      # end face centre: its near corner 0.75 from the post face
    els.append(span(by["panel"], "field", (f0 + f1) / 2, f1 - f0, clear, 8.0, angle,
                    uv={"east": FIELD, "west": FIELD}))
    for e in els[-3:]:
        for fname in ("north", "south", "up", "down"):
            e["faces"][fname]["uv"] = list(MARBLE if e["name"] != "field" else FIELD)
    els[-1]["from"][0], els[-1]["to"][0] = 6.95, 9.05
    # Brackets: the flat ones, turned with the rail, right under it: the post bracket (1 tall) from inside the post to
    # 1.25 past its face, the step (0.5 tall) 1 further.
    for src, x_in, x_out, h in ((by["bracket_post"], 0.9, POST_FACE + 1.25, 1.0),
                                (by["bracket_step"], POST_FACE + 1.25, POST_FACE + 2.25, 0.5)):
        yc = RAIL_C - RAIL_H / 2 - h / 2
        els.append(span(src, src["name"], yc, h, x_in, x_out, angle))
    out = {k: v for k, v in side.items() if k != "elements"}
    out["elements"] = els
    return out


for suffix, rising in (("up", True), ("down", False)):
    path = MODELS / f"hua_balustrade_side_{suffix}.json"
    path.write_text(json.dumps(build(rising), indent=1) + "\n")
    print("wrote", path)
