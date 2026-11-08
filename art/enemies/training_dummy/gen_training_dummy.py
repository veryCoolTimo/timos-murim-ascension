#!/usr/bin/env python3
"""Wooden martial-arts training dummy (mu ren zhuang): cube list, box-UV packing, textures, bbmodel operations.

Units: model pixels, 16 per block, y up, the model's front is -Z (Blockbench / Bedrock). One texel per model pixel.
Round parts are octagons made of four slabs (width W, thickness a, a/W = 0.41 = tan 22.5 degrees) or two crossed squares;
no stair-stepped small cubes. Sizes are integers (box UV).
"""
import json
import math
import random
import sys

from PIL import Image

OUT = sys.argv[1] if len(sys.argv) > 1 else '/tmp/c_scratch/dummy/out'
TEX_W, TEX_H = 128, 64

# ------------------------------------------------------------------ palette (the decor palette: int_workshop_wood, dark_timber, old_rope)
W0, W1, W2, W3, W4, W5 = '#7a4535', '#86503e', '#6a3a28', '#57301f', '#3e2116', '#9a6048'
WORN, WORN2 = '#4d2a1d', '#2c170f'
R0, R1, R2, R3 = '#a39373', '#85765a', '#665a46', '#4a4034'
S0, S1, S2, S3, S4 = '#c9a85a', '#a88a3e', '#8a6f2f', '#e0c477', '#6b5424'


def rgb(h):
    h = h.lstrip('#')
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4)) + (255,)


# ------------------------------------------------------------------ cubes
cubes = []   # dicts: name, group, frm, to, rot, origin, kind, long ('x'|'y'|'z'), wear


def box(name, group, frm, to, kind, rot=(0, 0, 0), origin=None, wear=0.0):
    size = [round(to[i] - frm[i], 3) for i in range(3)]
    assert all(abs(s - round(s)) < 1e-6 for s in size), (name, size)
    size = [int(round(s)) for s in size]
    long_axis = 'xyz'[size.index(max(size))]
    cubes.append(dict(name=name, group=group, frm=list(frm), to=list(to), rot=list(rot), origin=origin, kind=kind,
                      size=size, long=long_axis, wear=wear))


def octagon(prefix, group, y0, y1, w, a, kind, wear=0.0, stagger=0.0, front_wear=0.0):
    """Four slabs at 0/45/90/135 degrees about the vertical axis: a regular octagon prism, flat to flat width w."""
    for k, ang in enumerate((0, 45, 90, 135)):
        ya = y0 + stagger * k
        yb = y1 + stagger * k
        box(f'{prefix}_{k}', group, (-w / 2, ya, -a / 2), (w / 2, yb, a / 2), kind, rot=(0, ang, 0), origin=(0, (ya + yb) / 2, 0),
            wear=front_wear if ang == 90 else wear)


# base: a low octagonal platform
octagon('base', 'dummy', 0, 3, 20, 8, 'base', stagger=0.02)
# four leaning braces against the trunk foot
# the trunk: octagon 12 flat to flat, y 3..28; the front side (the 90 degree slab) is the worn one
octagon('trunk', 'trunk', 3, 32, 8, 3, 'trunk', wear=0.35, front_wear=1.0)
# rope bindings
octagon('rope_neck', 'trunk', 29, 31, 10, 4, 'rope')
octagon('rope_waist', 'trunk', 17, 19, 10, 4, 'rope')
octagon('rope_foot', 'trunk', 3, 5, 10, 4, 'rope')
# the head: a capped top
octagon('cap', 'trunk', 32, 34, 6, 3, 'cap')
box('knob_0', 'trunk', (-2, 34, -2), (2, 35, 2), 'cap')
box('knob_1', 'trunk', (-2, 34, -2), (2, 35, 2), 'cap', rot=(0, 45, 0), origin=(0, 34.5, 0))


def beam_x(name, group, sign, y, length=12, inset=3, w=5, a=2):
    """A round arm along +x (sign 1) or -x (sign -1): an octagon of four slabs about its axis."""
    x0, x1 = (inset, inset + length) if sign > 0 else (-inset - length, -inset)
    for k, ang in enumerate((0, 45, 90, 135)):
        box(f'{name}_{k}', group, (x0, y - w / 2, -a / 2), (x1, y + w / 2, a / 2), 'arm', rot=(ang, 0, 0),
            origin=((x0 + x1) / 2, y, 0), wear=0.5)


def beam_z(name, group, y, length=12, inset=3, w=5, a=2):
    """A round arm along -z (to the front)."""
    z0, z1 = -inset - length, -inset
    for k, ang in enumerate((0, 45, 90, 135)):
        box(f'{name}_{k}', group, (-a / 2, y - w / 2, z0), (a / 2, y + w / 2, z1), 'arm', rot=(0, 0, ang),
            origin=(0, y, (z0 + z1) / 2), wear=0.5)


beam_x('arm_a', 'arm_a', +1, 26, inset=2, w=4)
beam_x('arm_b', 'arm_b', -1, 21, inset=2, w=4)
beam_z('arm_f', 'arm_f', 16, inset=2, w=4)
beam_z('leg', 'leg', 10, length=9, inset=2, w=4)


def beam_back(name, group, y, length=12, inset=2, w=4, a=2):
    """A round arm along +z (to the back): pegs on every side, wing-chun style (author 22.10)."""
    z0, z1 = inset, inset + length
    for k, ang in enumerate((0, 45, 90, 135)):
        box(f'{name}_{k}', group, (-a / 2, y - w / 2, z0), (a / 2, y + w / 2, z1), 'arm', rot=(0, 0, ang),
            origin=(0, y, (z0 + z1) / 2), wear=0.5)


beam_back('arm_r', 'arm_r', 24)
beam_back('arm_s', 'arm_s', 12)

# bone pivots and rest rotations (the arms point a little forward and tilt; the leg goes forward and down)
GROUPS = [
    dict(name='dummy', origin=(0, 0, 0), rotation=(0, 0, 0), parent=None),
    dict(name='trunk', origin=(0, 3, 0), rotation=(0, 0, 0), parent='dummy'),
    dict(name='arm_a', origin=(2, 26, 0), rotation=(0, 0, 0), parent='trunk'),
    dict(name='arm_b', origin=(-2, 21, 0), rotation=(0, 0, 0), parent='trunk'),
    dict(name='arm_f', origin=(0, 16, -2), rotation=(0, 0, 0), parent='trunk'),
    dict(name='leg', origin=(0, 10, -2), rotation=(0, 0, 0), parent='trunk'),
    dict(name='arm_r', origin=(0, 24, 2), rotation=(0, 0, 0), parent='trunk'),
    dict(name='arm_s', origin=(0, 12, 2), rotation=(0, 0, 0), parent='trunk'),
]

# ------------------------------------------------------------------ box UV packing (shelf)


def region_size(c):
    sx, sy, sz = c['size']
    return 2 * (sz + sx), sz + sy


# cubes of one kind and size share a UV box (equal looks), except where wear differs
def share_key(c):
    return (c['kind'], tuple(c['size']), c['wear'], c['long'])


boxes = {}
for c in cubes:
    boxes.setdefault(share_key(c), []).append(c)
rects = []
for key, cs in boxes.items():
    w, h = region_size(cs[0])
    rects.append((h, w, key))
rects.sort(reverse=True)
x = y = shelf_h = 0
placed = {}
for h, w, key in rects:
    if x + w > TEX_W:
        x = 0
        y += shelf_h
        shelf_h = 0
    placed[key] = (x, y)
    x += w
    shelf_h = max(shelf_h, h)
    if y + shelf_h > TEX_H:
        raise SystemExit(f'texture {TEX_W}x{TEX_H} too small: needs {y + shelf_h}')
for c in cubes:
    c['uv'] = placed[share_key(c)]
print('texture height used', y + shelf_h, 'of', TEX_H, '; uv boxes', len(placed), '; cubes', len(cubes))

# ------------------------------------------------------------------ painting


def faces_of(c):
    u, v = c['uv']
    sx, sy, sz = c['size']
    return {
        'up': (u + sz, v, sx, sz), 'down': (u + sz + sx, v, sx, sz),
        'east': (u, v + sz, sz, sy), 'north': (u + sz, v + sz, sx, sy),
        'west': (u + sz + sx, v + sz, sz, sy), 'south': (u + 2 * sz + sx, v + sz, sx, sy),
    }


def face_axis(face, c):
    """Which way the grain of a face runs: 'h' along the face width, 'v' along its height; or 'ring' for an end face."""
    sx, sy, sz = c['size']
    long_axis = c['long']
    if face in ('north', 'south'):          # width x, height y
        return {'x': 'h', 'y': 'v', 'z': 'ring'}[long_axis]
    if face in ('east', 'west'):            # width z, height y
        return {'x': 'ring', 'y': 'v', 'z': 'h'}[long_axis]
    return {'x': 'h', 'y': 'ring', 'z': 'v'}[long_axis]   # up / down: width x, height z


def paint_wood(img, rect, rnd, axis, base=(W0, W1, W2), dark=W3):
    x0, y0, w, h = rect
    px = img.load()
    if axis == 'ring':
        cx, cy = (w - 1) / 2, (h - 1) / 2
        for i in range(w):
            for j in range(h):
                d = math.hypot((i - cx) / max(1, w / 2), (j - cy) / max(1, h / 2)) * 3.2
                tone = (W2, W0, W3)[int(d + 0.5) % 3]
                px[x0 + i, y0 + j] = rgb(tone)
        return
    length, cross = (h, w) if axis == 'v' else (w, h)
    for ci in range(cross):
        tone = rnd.choices(base, weights=(5, 2, 3))[0]
        t = 0
        while t < length:
            seg = rnd.randint(4, 14)
            if rnd.random() < 0.18:
                tone = dark if rnd.random() < 0.5 else W1
            elif rnd.random() < 0.35:
                tone = rnd.choices(base, weights=(5, 2, 3))[0]
            for k in range(t, min(length, t + seg)):
                i, j = (ci, k) if axis == 'v' else (k, ci)
                px[x0 + i, y0 + j] = rgb(tone)
            t += seg
    # a knot or two on big faces
    if w * h >= 120 and rnd.random() < 0.7:
        kx, ky = rnd.randint(2, max(2, w - 3)), rnd.randint(2, max(2, h - 3))
        for i in range(-2, 3):
            for j in range(-1, 2):
                if 0 <= kx + i < w and 0 <= ky + j < h:
                    d = abs(i) / 2 + abs(j)
                    px[x0 + kx + i, y0 + ky + j] = rgb(W4 if d < 0.8 else W3)


def paint_worn(img, rect, rnd, strength, axis):
    """Hit-polished dark patches with a few pale nicks: where students strike the post."""
    x0, y0, w, h = rect
    if strength <= 0 or w * h < 20:
        return
    px = img.load()
    blobs = max(1, int(strength * w * h / 90))
    for _ in range(blobs):
        bx, by = rnd.randint(0, w - 1), rnd.randint(0, h - 1)
        rx, ry = rnd.randint(2, max(2, w // 2)), rnd.randint(2, max(2, h // 3))
        for i in range(w):
            for j in range(h):
                d = ((i - bx) / rx) ** 2 + ((j - by) / ry) ** 2
                if d < 1.0:
                    px[x0 + i, y0 + j] = rgb(WORN if d < 0.55 else W3)
                    if d < 0.2 and rnd.random() < 0.5:
                        px[x0 + i, y0 + j] = rgb(WORN2)
    for _ in range(int(strength * 4)):
        nx, ny = rnd.randint(0, w - 2), rnd.randint(0, h - 1)
        for k in range(rnd.randint(2, 5)):
            i, j = (nx + k, ny) if axis == 'h' else (ny % w, (nx + k) % h)
            if 0 <= i < w and 0 <= j < h:
                px[x0 + i, y0 + j] = rgb(W5)


def paint_rope(img, rect, rnd, axis):
    x0, y0, w, h = rect
    px = img.load()
    for i in range(w):
        for j in range(h):
            s = (i + j) % 4
            px[x0 + i, y0 + j] = rgb((R0, R0, R1, R2)[s])
    for i in range(w):
        if rnd.random() < 0.15:
            px[x0 + i, y0 + rnd.randint(0, h - 1)] = rgb(R3)


def paint_all(variant):
    img = Image.new('RGBA', (TEX_W, TEX_H), (0, 0, 0, 0))
    rnd = random.Random(7)
    done = set()
    for c in cubes:
        key = share_key(c)
        if key in done:
            continue
        done.add(key)
        for face, rect in faces_of(c).items():
            axis = face_axis(face, c)
            if c['kind'] == 'rope':
                paint_rope(img, rect, rnd, axis)
            elif c['kind'] in ('base', 'brace'):
                paint_wood(img, rect, rnd, axis, base=(W2, W3, W0), dark=W4)
            elif c['kind'] == 'cap':
                paint_wood(img, rect, rnd, axis, base=(W2, W0, W1))
            else:
                paint_wood(img, rect, rnd, axis)
                paint_worn(img, rect, rnd, c['wear'] * (1.0 if face in ('east', 'west', 'north', 'south') else 0.3), axis)
    if variant == 'straw':
        # the middle of the trunk is wrapped in straw: bundles of stalks, bound with rope bands
        for c in cubes:
            if c['kind'] != 'trunk' or c['wear'] == 0.0 and False:
                continue
        for key, (u, v) in placed.items():
            if key[0] != 'trunk':
                continue
            sx, sy, sz = key[1]
            for face, rect in {'east': (u, v + sz, sz, sy), 'west': (u + sz + sx, v + sz, sz, sy),
                               'north': (u + sz, v + sz, sx, sy), 'south': (u + 2 * sz + sx, v + sz, sx, sy)}.items():
                x0, y0, w, h = rect
                px = img.load()
                top = int(h * 0.20)       # texture row 0 is the top of the face (y = 28)
                bottom = int(h * 0.82)
                for i in range(w):
                    tone = rnd.choice((S0, S0, S1, S3, S2))
                    for j in range(top, bottom):
                        if rnd.random() < 0.12:
                            tone = rnd.choice((S0, S1, S3, S2))
                        px[x0 + i, y0 + j] = rgb(tone)
                for band in (top + 1, (top + bottom) // 2, bottom - 2):
                    for i in range(w):
                        px[x0 + i, y0 + band] = rgb((R1, R0, R2)[i % 3])
                        if band + 1 < bottom:
                            px[x0 + i, y0 + band + 1] = rgb((R2, R1, R0)[i % 3])
    return img


import os
os.makedirs(OUT, exist_ok=True)
paint_all('wood').save(f'{OUT}/training_dummy.png')
paint_all('straw').save(f'{OUT}/training_dummy_straw.png')

# ------------------------------------------------------------------ bbmodel operations
ops = []
for g in GROUPS:
    op = dict(op='add_group', name=g['name'], origin=list(g['origin']), rotation=list(g['rotation']))
    if g['parent']:
        op['parent'] = g['parent']
    ops.append(op)
for c in cubes:
    op = dict(op='add_cube', name=c['name'], parent=c['group'], **{'from': c['frm'], 'to': c['to']}, box_uv=True,
              uv_offset=list(c['uv']), texture=0)
    if any(c['rot']):
        op['rotation'] = c['rot']
        op['origin'] = list(c['origin'])
    ops.append(op)
json.dump(ops, open(f'{OUT}/ops.json', 'w'), separators=(',', ':'))
print('ops', len(ops))
