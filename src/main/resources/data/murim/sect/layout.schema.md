# layout.json schema (version 1)

`layout.json` describes the hand-built Mount Hua sect (world `int5`) for the NPC daily-routine code: buildings, rooms,
doors, stairs, beds, work spots and a waypoint graph. It is GENERATED, do not edit by hand:

```
cd ~
~
    --out <repo>/src/main/resources/data/murim/sect/layout.json
~
~
python3 <repo>/tools/build/sect/audit_plan_zones.py                                  # plan zones vs built sect
```

## Conventions

* All coordinates are ABSOLUTE world block coordinates of the built sect. Position = `[x, y, z]`, `y` = the
  **feet cell** (the air block an NPC stands in; the floor is the block at `y-1`). Add `0.5` to `x` and `z` for the
  block centre. Slab and stair floors are lower by 0.5 / 0.25 inside the same cell; nothing here stores that, use the
  block below.
* `yaw` follows Minecraft: `0` = south (+z), `90` = west (-x), `180` = north (-z), `-90` = east (+x). `facing` is the same
  thing as a word.
* Mountain local frame (MountHuaPlan, see top-level `frame`): `x = -1072 + u`, `z = 4216 + v` (floor), `y = 70 + plan_y`
  (site centre (-1072, 4216), base y 70, rotation 0). Valid for this world only; stage 2 must anchor the file to the
  site (see docs/design/38 section 5.1).
* Ids are stable only for one extraction run (they are numbered after sorting). Do not store them in saves; store
  `owner_key` (roster key) or a role plus a slot.
* `reach`: `ok` = walkable on foot from the trail gate with steps of at most one block; `one_way_drop` = only by dropping
  (up to 3 blocks), you cannot come back; `unreachable` = no route; `enclosed` (spots) = the room is reachable but
  furniture leaves no free neighbour cell; `no_cell` = no standing cell found.

## Top level

| key | meaning |
|---|---|
| `schema` | 1 |
| `source` | world name, extractor, notes, `site_frame` text |
| `frame` | the `MountHuaSite` of the world: `center_x, center_z, base_y, rotation, scale` |
| `stats` | counts (buildings, rooms, doors by kind, stairs, beds by role, spots by kind, waypoints, edges) |
| `roster_plan` | `bed_role_counts` (how many beds per role), `roster_to_layout_role` (roster key -> layout role), `elder_houses` (house -> plaque, owner key, code role) |
| `buildings[]` | see below |
| `rooms[]` | see below |
| `doors[]` | passages between two rooms or a room and the outdoors |
| `stairs[]` | flights of stair blocks with landings |
| `beds[]` | every bed with its owner role |
| `spots[]` | work / rest / guard / training places |
| `zones[]` | outdoor zones (rectangles) |
| `waypoints[]`, `edges[]` | the navigation graph |
| `plaques[]` | plaque blocks that name the buildings (`block`, `pos`, `facing`) |
| `ignored_door_leaves[]` | door blocks that are decoration beside a gap (elder yard gates), not passages |
| `tour` | fixed camera shots of the newcomer tour by id (`main_hall, dining, training, board, dorms`): `camera` (eye cell), `aim` (cell the frame centre looks at), `fov` (vertical lens, degrees); cells of the file frame, anchored with everything else. Defined in `extract_layout.TOUR`, checked by `verify_tour.py`. The `bed` shot is not here: the server finds it around the player's own bed |
| `boards[]` | cells of the contract boards (`murim:sect_mission_board`) standing in the built sect |
| `landmarks` | named places the code needs exactly, found in the world by the extractor (`extract_layout.penance()`): `penance` = `{cell, gate, mouth}` — the penance seal the sentenced stands on, the lower half of the cold iron door that shuts the cave, the standing cell outside that door. `SectLayoutFile#landmark`, `PenanceService#landmarkCell`; the carved volume itself is the zone `penance` below. Absent in older files (the plan zone is the fallback) |
| `issues[]` | problems found in the built world (`id`, `severity` blocker/warn/info, `text`, `where`) |

## buildings[]

`id` (`sect_gate, bell_tower, mentor_dais, east_pavilion, treasury, main_hall, ancestors, scriptures, elder_1..3,
dorm_2nd, dorm_3rd, dining_hall, kitchen, alchemy`), `label`, `function`, `wall_boxes[]` (`box` = `[x0, x1, z0, z1]`
inclusive wall ring, `y` = `[min, max]` feet levels covered), `floor_ys` (feet level of each floor that has rooms),
`rooms` (ids), `doors` (ids touching the building). Elder houses: `elder_1` = violet plaque (west), `elder_2` = plum,
`elder_3` = pine (east).

## rooms[]

`id` = `<building>.f<floor>.r<n>`; `floor` = 0 for the lowest floor with rooms; `y` = feet level; `bounds` =
`[x0, z0, x1, z1]` of the room's cells; `area` cells including furniture-occupied floor; `walkable_cells` cells an NPC can
stand on; `function` (`single_room, sleeping_room, corridor, stair_hall, storeroom, washroom, study_hall,
meditation_lounge, lecture_hall, audience_hall, dining_hall, kitchen, pharmacy, furnace_room, shrine, library, study,
counting_room, reception, open_pavilion, upper_gallery, hall, room`); `furniture` counts by kind; `reach`,
`reach_fraction`; `portals` = waypoint ids standing in the room (door sides, stair landings); `doors` = door ids.
A room is the set of floor cells inside the wall ring that are covered by a roof, connected on the same level and cut by
doors and stairs. The sect gate has no rooms (it is a passage).

## doors[]

`id`, `kind` = `hall_door` (wooden `DoorBlock`, vanilla pathfinding opens it), `gate_door` / `grand_door` (multi-block
doors that are NOT `DoorBlock`: an NPC must toggle them), `opening` (doorless gap in a wall). `cells` = `[min, max]`
`[x,y,z]` of the door blocks (lower half for `hall_door`); `facing`; `width` in cells; `passage` = `wooden_door | gate_toggle |
free`; `open` = built open (true) or closed; `side_a` / `side_b` = standing cells on either side (`side_a` is on the
opposite side of `facing`, `side_b` on the `facing` side); `room_a` / `room_b` = room on that side or null (outdoors);
`buildings`; `reach`; `ajar` / `passage_axis` only for leaves swung parallel to the passage.

## stairs[]

One flight (connected stair blocks). `bbox` `[x0, z0, x1, z1]`, `y_bottom` / `y_top` (feet level of the lowest / highest
tread), `cells`, `width` (cells at the widest level), `bottom[]` / `top[]` = standing cells at the foot / head of the
flight (empty = dangling end, see `issues`), `room_bottom` / `room_top`, `indoor`, `building`.

## beds[]

`id`, `pos_head`, `pos_foot`, `facing` (the way the head points), `yaw`, `blue` (variant), `room`, `building`,
`approach` = standing cell beside the bed, `approach_side`, `role`, `owner_key` (roster key when the bed is for one named
person), `slot` (n-th bed of that role), `reach`.
Roles and counts: `leader` 1, `elder` 3, `instructor` 3 (single rooms: `gyeong_pil`, `gyeong_tak`, `gyeong_cho`),
`steward` 1, `disciple_2nd` 12 (6 double rooms), `disciple_3rd` 17 and `servant` 6 (quad rooms), `spare` 1.

## spots[]

`id`, `kind`, `posture` (`stand | sit | kneel`), `pos` (where the NPC stands, or the seat block for `sit`/`kneel`
together with `approach` = the cell to walk to), `target` (the furniture block a standing spot works on), `facing`,
`yaw`, `room`, `building`, `roles` (who uses it; `all`, layout roles, `elder:<building>`, `guard_day`...), `reach`,
and for outdoor spots `zone`, `row/col` (formation), `radius` (spar rings), `door_group` (guard posts).
Kinds: `seat_chair, seat_stool, seat_bench, seat_armchair, cushion, kneel_mat, work_desk, low_desk, counter, table, stove,
furnace, mortar, herb_jars, pharmacy_cabinet, water_vat, altar, shelf, sword_rack, drum, coffer, formation, spar_ring,
pole_stand, mentor_post, watch_post, guard_post`.
`facing` of furniture-derived spots is derived from the nearest table / the block's own `facing` property: check it in game.

## zones[]

`id`, `rect` `[x0, z0, x1, z1]`, `y`, `note`. Outdoor areas used by the schedule (training square, ceremony platform,
poles area, hall court, treasury yard) and, since 18.10, the built penance cave (`penance`: the footprint of the carved volume behind
the gate, `y` = feet level; `SectLayout.Hua` takes this zone from the file instead of the plan, so the access rule, the guards' warning
and the elders' sentence all point at the real cave).

## waypoints[] and edges[]

`waypoints[]`: `id`, `pos`, `kind` = `anchor` (named outdoor point) | `door_in` / `door_out` (standing cell inside /
outside a door) | `stair_bottom` / `stair_top`, `ref` (door / stair / anchor id), `room`.
`edges[]`: `a`, `b`, `length` (cells), `via` = corner points between a and b (every straight segment
`a -> via... -> b` is verified walkable), `crosses` = door ids on the way (a `gate_toggle` door on the way means the walker
must open it), `stairs` = the way climbs, `one_way` (drop link, only `a -> b`), `note`.
The graph is a relative-neighbourhood graph over walking distance, so it is sparse and stays connected. Edges are
undirected unless `one_way`.

## Verification

`verify_layout.py` re-reads the world and checks: every waypoint standable; every straight segment of every edge walkable
(step <= 1 block; slabs and stairs allowed; doors passable); one component reachable from `anchor.trail_approach` except
documented blockers; every door side standable; every bed approach and every spot reachable from a portal of its room by
walking inside the room (enclosed spots are counted, not failed); stair landings standable; bed counts equal the roster plan.
Known limits: the model does not check the entity's width (0.6) against corners, treats every unknown block as an obstacle, and
does not model fences/gates other than the listed ones.

## Round 2 lesson stations

`lecture_teacher`, `lecture_audience`, `lecture_visitor` are standing floor cells grouped as
`lecture:<main|poles>:<teacher|audience|visitor>`. The audience sits on the floor after walking to
its cell; a missing or obstructed cell reduces audience size. These groups are separate from NPC
formation and spar spots, and are emitted by `outdoor_spots` in the extractor. `formation:visitor`
(`formation_visitor`) reserves the invited player's place; it is never assigned to an NPC.
Stations use the file frame and rotate/translate with the rest of the layout. The lesson executor
checks loaded, standable cells and never teleports to them. Route tags reuse `mentor_post`,
`pole_stand`, `spar_ring`; the ring approach is offset to the edge rather than its combat center.
