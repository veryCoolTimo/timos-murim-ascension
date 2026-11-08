# Provenance

This inventory describes the 0.1.0 source tree. Paths below are relative to
`src/main/resources/assets/murim/` unless stated otherwise. It records production
methods, not a claim that every file has an individual source receipt. Mixed families
retain explicit uncertainties. The author's release brief of 2 November 2026 identifies
gpt-image-2 through Codex and ElevenLabs Sound Effects on a paid plan as production tools.
[ASSETS_LICENSE.md](ASSETS_LICENSE.md) states the author's asset terms; third-party
licences below remain applicable to their respective files.

## Textures

| Folder (including descendants listed) | Origin and evidence |
| --- | --- |
| `textures/block/` | Mixed procedural art and hand edits; generators include `tools/art/hua_blocks.py`, `hall_joinery.py` and `roof_v7_art.py`. Some scripts reuse vanilla textures; exact vanilla-derived file inventory and any image-generated inputs: to confirm with the author. |
| `textures/entity/`, `textures/entity/sect/` | Author's student models and skins, with scripted UV fixes (`tools/art/fix_entity_uv.py`); history `3d33c0db1`, `da223936a`. Origins of remaining entity skins: to confirm with the author. |
| `textures/item/`, `textures/item/achievement/` | Mixed generated illustrations and scripted pixel art. gpt-image-2 through Codex, then pixel-snapped/cut/quantized by `tools/art/pill_art/gen.py`, `finalize.py`, `tools/art/items/` and `cut_scroll_sheet.py`. Complete per-file split, including achievement icons: to confirm with the author. |
| `textures/particle/`, `textures/vfx/` | Scripted effect textures and generated inputs; `tools/art/bt_flame_atlas.py` is one generator. Original tool/source of each older mask: to confirm with the author. |
| `textures/gui/`, `textures/gui/technique/`, `textures/gui/technique/method/` | Mixed generated and scripted UI art. Method illustrations: gpt-image-2 through Codex, pixel-snapped with `tools/art/cut_breathing_sheet.py`; prompts in `tools/art/breathing_final/`, history `041cf49aa`. Remaining GUI art split: to confirm with the author. |
| `textures/gui/caption/`, `textures/gui/sprites/`, `textures/gui/sprites/tablet/` | Scripted UI art; `tools/art/sect_tablet_cuts.py` records tablet processing. Original inputs for all sprites and captions: to confirm with the author. |
| `textures/gui/book/`, `textures/gui/book/pages/`, `textures/gui/book/spreads/` | Generated book illustrations, prompts/generation in `tools/art/book_spreads/`, with scripted preparation and hand edits. Exact model/version for every older spread: to confirm with the author. |
| `textures/gui/scroll/`, `textures/gui/scroll/art/` | Generated hanging-scroll illustrations and scripted cutting; history `63bb8ba99`, `48b7ffab4` and `tools/art/cut_scroll_sheet.py`. Exact source/tool mapping for each hanging-scroll plate: to confirm with the author. |

## Models and structures

| Folder / family | Origin and evidence |
| --- | --- |
| `models/`, `models/block/`, `models/block/thin_wall/`, `models/item/` | Author's designs and hand edits mixed with models written by AI coding agents and Python generators under his direction. See `tools/art/items/gen_cover_models.py`, `tools/art/hall_joinery.py`. Exact per-file authoring split: to confirm with the author. |
| `models/block/obj/`, `models/block/obj/building_kit/`, `models/block/obj/hall_joinery/` | Procedural OBJ meshes from `tools/art/*.py`, including joinery and roof generators, under the author's direction. No external mesh library is established by this inventory. |
| `bedrock/` | Author's Blockbench models and animation edits, with agent-created blockouts and conversion scripts. Student model updates recorded in `3d33c0db1` and `da223936a`; clip conversion in `tools/art/sect_people_anims.py`. Per-file split of older bandit/boss/dummy material: to confirm with the author. |
| Buildings and cave (outside assets: baked world data) | Made by the author in his worlds, then baked by `tools/hua_bake/`; see [ADR 0004](docs/adr/0004-baked-hua-sect.md). Procedural additions and hand edits coexist with the bake. |

## Animation and camera data

| Folder | Origin and evidence |
| --- | --- |
| `player_animations/`, `npc_animations/` | Author-made animations and hand edits mixed with agent-written/generated blockouts, e.g. `tools/art/anim_six_harmonies_forms.py`, `sect_poses_anims.py`, `sect_people_anims.py`. These clips are project assets, distinct from PlayerAnimationLib. Exact per-clip split: to confirm with the author. |
| `camera/` | Camera data authored as part of the project's scenes; exact human/agent split: to confirm with the author. |
| `face/` | NPC expression data; original authoring method and any third-party inputs: to confirm with the author. |

## Audio

| Folder / file | Origin and evidence |
| --- | --- |
| `sounds/`, `sounds/ambient/` | ElevenLabs Sound Effects on a paid plan, per the author's release brief; edited/layered for game use (e.g. history `ee22917d3`, `0a165cfe3`). Per-file generation receipts and whether any older effects have other origins: to confirm with the author. |
| `sounds.json` | Project sound-event definitions written/edited by coding agents under the author's direction. |

## Font and language

| Folder | Origin and evidence |
| --- | --- |
| `font/` | `caption.ttf`: Reggae, Copyright 2020 The Reggae Project Authors, SIL Open Font License 1.1; bundled notice `font/caption-ofl.txt`. `caption.json` is project configuration. The font is a third-party exception to the general asset notice. |
| Glyphs rasterized into textures | `tools/art/shrine_set.py` and `eld_plaques.py` reference Noto Serif CJK (including TC). Third-party font glyphs are used in scripted plaque art; exact font files, retained glyphs and applicable licence notices: to confirm with the author. |
| `lang/` | English and Russian game text written/edited by AI coding agents under the author's direction, with the author's canon naming and corrections. Any older externally sourced passages: to confirm with the author. |

## Resource definitions and code

| Folder / family | Origin and evidence |
| --- | --- |
| `blockstates/`, `atlases/`, `particles/` | Project JSON definitions made with code/data generators and hand edits under the author's direction. |
| `shaders/`, `shaders/post/`, `shaders/program/`, `vfx_presets/` | Project effect code/configuration written by AI coding agents under the author's direction. External shader snippets, if any: to confirm with the author. |
| Java code, build helpers, scripts and documentation | Written by AI coding agents under the author's direction, with author decisions, review and hand edits. Code licence: LGPL-3.0-only. This document and the release READMEs are AI-written text. |
| NeoForge MDK template | Third-party NeoForged MDK scaffold, MIT; [TEMPLATE_LICENSE.txt](TEMPLATE_LICENSE.txt). |
| PlayerAnimationLib and nested dependencies | Third-party library, bundled unmodified using jar-in-jar. PlayerAnimationLib: Zigy and dima_dencep, MIT. Nested mochafloats and Javassist retain their own notices; see [THIRD_PARTY_NOTICES.txt](src/main/resources/META-INF/licenses/THIRD_PARTY_NOTICES.txt). |
| `src/main/resources/murim_logo.png`, `docs/media/banner.png` | Author-selected title logo A and its README banner, already supplied in commit `afa1894b2`; original creation tool and inputs: to confirm with the author. Neither image was regenerated for this release pack. |

## Names and setting

Arts, techniques and places use names from the novels the mod is inspired by:
Return of the Mount Hua Sect, Absolute Regression, Myst, Might, Mayhem, Nano Machine,
and The Breaker. This mod is unofficial fan work and is not affiliated with their
authors or publishers. The project licences do not grant rights to those works.

## AI use disclosure

**Assets:** Generative AI was used for images/textures and sound effects; scripts
prepare generated images for use in game. Author-made models, animations, buildings,
the cave and hand edits are also present. See the family inventory above.

**Code:** AI coding agents wrote Java code and supporting scripts under the author's
direction.

**Text:** AI coding agents wrote and edited in-game text, translations and documentation,
including the release READMEs and this disclosure, under the author's direction.

## Release policy findings — 2 November 2026

- Q-86: Paid-plan ElevenLabs sound effects may be incorporated into the game, but
  must not be redistributed as standalone audio or a separate sound library.
  See [ElevenCreative terms](https://elevenlabs.io/creative-terms).
- Q-127: CurseForge's AI disclaimer rule concerns AI-made/modified showcase images
  that may misrepresent actual mod content; it does not impose that disclaimer on
  assets inside the mod. See [Moderation Policies](https://support.curseforge.com/support/solutions/articles/9000197279).
- Q-143: Modrinth requires disclosure of AI use in assets, code and text, and disallows
  projects entirely or primarily comprised of generative AI output. Disclosure alone
  does not establish eligibility. See [Content Rules](https://modrinth.com/legal/rules)
  and [Disclosure and Usage of AI](https://support.modrinth.com/en/articles/16551575-disclosure-and-usage-of-ai).
  Those rules also restrict AI imagery on project pages. This inventory makes no
  claim that the project is eligible for Modrinth publication.
