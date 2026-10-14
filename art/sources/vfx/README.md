# Ударная волна Ци — пилот AAA

`qi_shockwave.efkproj` — собственный XML-проект murim: два чётких горизонтальных кольца и
16 клубков пыли. Срок жизни 54 кадра Effekseer (0,9 с / 18 тиков Minecraft).
Официальные samples не использованы. Цвета взяты из существующих VFX, пыль — из атласа
`assets/murim/textures/vfx/dust_puffs.png`, уменьшенного до 16×16 nearest-neighbour.

В Minecraft используется **бинарный** `assets/murim/effeks/qi_shockwave.efkefc`, а не XML.
Его экспортирует официальная managed-библиотека Effekseer 1.80.7 без GUI и OpenGL.
Формат EFKE, runtime BIN_ version 1810 (`Ver1800`). Библиотека нужна только для экспорта;
поставляемый murim не зависит от .NET. DLL и SDK в репозиторий не включены.

Повторная генерация текстур и XML:

```sh
python3 tools/art/qi_shockwave.py
```

Экспорт требует .NET SDK 9 и `EffekseerCore.dll` из Tool/bin официального
[Effekseer 1.80.7](https://github.com/effekseer/Effekseer/releases/tag/1807):

```sh
python3 tools/art/qi_shockwave.py --export --core /absolute/path/Tool/bin/EffekseerCore.dll
```

`--dotnet /absolute/path/dotnet` задаёт SDK вне PATH. C#-экспортёр проверяет число узлов,
срок жизни, ориентацию и непрозрачные кромки колец, ZTest и отсутствие ZWrite.
Изменили XML/генератор — обязательно повторите экспорт перед сборкой мода.

```sh
python3 tools/art/qi_shockwave.py --preview
```

Превью `previews/qi_shockwave/qi_shockwave.mp4` использует `obj_mesh.Scene`.
Это проверка силуэта своего варианта; renderer работает с cutout и не моделирует fade,
Iris, Effekseer или сортировку прозрачности Minecraft. Нативный AAA проверяется в клиенте.
Новые блоки, blockstates, loot, теги и creative-tab items в задании не нужны.

Зависимость: `curse.maven:aaa-particles-979809:9101011` (NeoForge 1.21.1 / AAA 2.3.3),
[jar](https://cursemaven.com/curse/maven/aaa-particles-979809/9101011/aaa-particles-979809-9101011.jar),
SHA-256 `948b3fc4d38d33a817596ad0b2d14c28976f8cfde801041b1c58cf1fef505f98`.
MIT, copyright 2024-present ChloePrime. Mod id `aaa_particles`, NeoForge ≥21.1.169.
В jar проверены отдельные Mach-O arm64 и x86_64 библиотеки macOS.
Effekseer — [MIT, copyright 2011 Effekseer Project](https://github.com/effekseer/Effekseer/blob/1807/LICENSE).
Ни одна сторонняя библиотека здесь не скопирована или встроена в jar murim.

В полном репозитории подключить Gradle-фрагмент `tools/aaa-pilot.gradle` в конце build.gradle:

```groovy
apply from: 'tools/aaa-pilot.gradle'
```

Он использует compileOnly и отдельный client localRuntime (`murimAaaLocalRuntime`),
чтобы AAA не попал в server/GameTest. `-PmurimAaaAbsent` оставляет dev client без AAA.
В этом снимке отсутствуют build.gradle, wrapper, CLAUDE.md и docs/adr; Gradle и клиент
не запускались. Полный отчёт и Mac-проверки — в out.md; исследование — §Q-05
`docs/design/research/vfx-libraries.md`.
