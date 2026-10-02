Анимации игрока для Blockbench (агент, 02.10).

Как открыть: двойной щелчок по .bbmodel (или File → Open Model). Вкладка Animate — анимация
уже внутри. Кости — как в моде: head, torso, right_arm, left_arm, right_leg, left_leg.
_sword_preview — только превью меча в правой руке, в игру не идёт.

Easing (easeOutCubic и т. п.) сохраняется, если в Blockbench стоит плагин GeckoLib
(File → Plugins → GeckoLib Models & Animations). Без него ключи останутся, кривые — линейные.

Как вернуть: Animation → Export Animations (или просто сохрани .bbmodel и пришли файл) —
я переложу в src/main/resources/assets/murim/player_animations/<имя>.json.

Главные сейчас:
  seven_plum_blossoms — Меч Семи Цветков Сливы «Разрез»: стойка/заряд 0–0,6 с, удар вверх 0,6,
                        боковые взмахи 0,66–0,96, рука вперёд 1,45, удержание до 1,65, возврат 2,0.
                        (Тайминг будет растянут до ~5 с — см. план на странице.)
  dark_fragrance_step — Шаг Невидимого Аромата
  wind_god_steps      — Шаги Бога Ветров (короткий толчок)
  six_form_1..6       — формы основы меча (Шесть Равновесий)
