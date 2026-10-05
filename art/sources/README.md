# Исходники моделей и текстур для Blockbench

Здесь всё, что мы сгенерировали, в виде, который открывается в Blockbench и правится руками.
Текстуры **внутри** `.bbmodel` (base64), отдельно таскать PNG не нужно. Открывать двойным щелчком
или File → Open Model.

## Что где

| Файл | Формат Blockbench | Что пишет в игру (`src/main/resources/assets/murim/`) |
|---|---|---|
| `entities/bandit.bbmodel` | Bedrock Entity | `bedrock/bandit.geo.json`, `bedrock/bandit.animation.json`, `textures/entity/bandit.png`, `bandit_elite.png`, `bandit_chief.png` |
| `entities/bandit_archer.bbmodel` | Bedrock Entity | `bedrock/bandit_archer.geo.json`, `.animation.json`, `textures/entity/bandit_archer.png`, `peddler.png` (торговец — модель лучника) |
| `entities/sect_people.bbmodel` | Bedrock Entity | **только текстуры** секты: `textures/entity/sect_disciple|leader|mentor.png` и все облики `textures/entity/sect/*.png`. Геометрия — модель бандита: её правь в `bandit.bbmodel` |
| `entities/fortress_master.bbmodel` | Bedrock Entity | `bedrock/fortress_master.geo.json`, `.animation.json`, `textures/entity/fortress_master.png`, `fortress_master_boil.png` |
| `entities/sect_props.bbmodel` | Bedrock Entity | `bedrock/sect_props.geo.json`, `textures/entity/sect_props.png` (метла, чашка, коромысло…) |
| `entities/huashan_sword.bbmodel`, `tang_dagger`, `tang_coin` | Bedrock Entity | `bedrock/<имя>.geo.json`, `textures/item/<имя>.png` |
| `blocks/<имя>.bbmodel` | Java Block | `models/block/<имя>.json` + её текстуры. Шатёр: в каждой модели три цвета ткани (crimson, linen, white) — модель одна, цвет задают `tent_canvas_*_<цвет>.json` |
| `blocks/textures/*.png` | — | `textures/block/*.png` (гранит, слива, полки… — простые кубы, у них только PNG) |
| `blocks/models/*.json` | — | `models/block/*.json` (простые кубы, ступени, плиты, стены — ссылки на ванильные формы) |
| `items/textures/*.png`, `items/models/*.json` | — | `textures/item/*.png`, `models/item/*.json` (положение в руке и в инвентаре — блок `display`) |
| `animations/` | Free / GeckoLib | анимации игрока и позы секты — копия `art/animations/`, см. `animations/README.txt` |
| `manifest.json` | — | карта «исходник → игровой файл» для скрипта импорта, руками не трогать |

Ванильные текстуры в `propped_shelf.bbmodel` (полка, доски) — только для вида, обратно не пишутся.

## Как вернуть правки в игру

1. Правишь в Blockbench и сохраняешь `.bbmodel` **на том же месте** (Ctrl+S). PNG — просто перерисовать и сохранить поверх.
2. Присылаешь папку (или изменённые файлы) — я кладу их в `art/sources/` и запускаю:

```
python3 tools/art/import_sources.py --dry-run   # что изменится
python3 tools/art/import_sources.py             # записать в src/main/resources
```

Импорт пишет только то, что правда изменилось (числа сравниваются по смыслу, картинки — по пикселям).
Если сменился размер UV модели или размер PNG — откажет: старые UV поедут; осознанно — `--force`.
Анимации из `animations/` возвращает `tools/art/bbmodel_pal.py`.

Пересобрать эту папку из игры: `python3 tools/art/export_sources.py` (откажет, если здесь есть невнесённые правки).
