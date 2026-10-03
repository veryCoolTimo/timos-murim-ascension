#!/usr/bin/env python3
"""Собирает assets/murim/sounds.json из ogg-файлов и дописывает субтитры в lang (ru_ru, en_us).

Событие = имя без суффикса варианта (_a/_b). Каждое событие — все его варианты с равным весом.
Субтитры дописываются строками в конец lang-файла (без переформатирования: другие агенты
правят те же файлы параллельно); уже существующие ключи не трогаются.
"""
import json, os, re

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'murim')

SUB = {  # id: (ru, en)
    'sword_swing': ('Свист клинка', 'Blade whooshes'),
    'sword_swing_heavy': ('Тяжёлый взмах меча', 'Heavy sword slash'),
    'sword_hit': ('Клинок рассекает', 'Blade cuts'),
    'sword_draw': ('Меч выходит из ножен', 'Sword drawn'),
    'impact_heavy': ('Сокрушительный удар', 'Crushing impact'),
    'petal_burst': ('Взрыв лепестков', 'Petals burst'),
    'blossom_open': ('Распускаются цветы сливы', 'Plum blossoms open'),
    'whirlwind': ('Ревёт ураган клинков', 'Blade whirlwind roars'),
    'dive_whoosh': ('Пикирование с неба', 'Diving from the sky'),
    'ground_slam': ('Удар в землю', 'Ground slam'),
    'barrier_hit': ('Удар о барьер ци', 'Qi barrier struck'),
    'barrier_break': ('Барьер ци разбит', 'Qi barrier shatters'),
    'river_flow': ('Поток лепестков', 'Stream of petals'),
    'qi_chime': ('Звон ци', 'Qi chimes'),
    'palm_charge': ('Тёмная ци собирается в ладони', 'Dark qi gathers in a palm'),
    'palm_release': ('Удар ладонью', 'Palm strike'),
    'dash': ('Рывок', 'Dash'),
    'step_soft': ('Тихий шаг', 'Soft step'),
    'blink': ('Шаг тени', 'Shadow step'),
    'burst_step': ('Толчок прыжка', 'Burst step'),
    'run_wind': ('Ветер бега', 'Running wind'),
    'qi_charge': ('Ци нарастает', 'Qi builds up'),
    'aura_charge': ('Гул ауры', 'Aura hums'),
    'meditation_loop': ('Дыхание медитации', 'Meditation breath'),
    'meditation_cycle': ('Цикл ци завершён', 'Qi cycle completes'),
    'breakthrough': ('Прорыв на новый ранг', 'Realm breakthrough'),
    'aura_gust': ('Давление ауры', 'Aura pressure'),
    'lock_on': ('Цель захвачена', 'Target locked'),
    'wheel_select': ('Техника выбрана', 'Technique selected'),
    'bandit_windup': ('Бандит замахивается', 'Bandit winds up'),
    'bow_shot': ('Выстрел из лука', 'Bow fires'),
    'qi_sword_summon': ('Появляется ци-меч', 'Qi sword forms'),
    'qi_sword_hum': ('Гул ци-меча', 'Qi sword hums'),
}
# Дальность слышимости: тихие и интерфейсные — ближе, удары и прорыв — дальше (дефолт 16).
ATTEN = {'step_soft': 10, 'lock_on': 8, 'wheel_open': 8, 'wheel_select': 8, 'meditation_loop': 10,
         'qi_sword_hum': 10, 'run_wind': 12, 'impact_heavy': 24, 'breakthrough': 32, 'ground_slam': 24,
         'whirlwind': 24, 'barrier_break': 24}


def main():
    files = sorted(f[:-4] for f in os.listdir(os.path.join(ASSETS, 'sounds')) if f.endswith('.ogg'))
    events = {}
    for v in files:
        events.setdefault(re.sub(r'_[a-z]$', '', v), []).append(v)
    out = {}
    for ev in SUB:
        if ev not in events:
            raise SystemExit('нет файлов для ' + ev)
        sounds = []
        for v in events[ev]:
            s = {'name': 'murim:' + v}
            if ev in ATTEN:
                s['attenuation_distance'] = ATTEN[ev]
            sounds.append(s)
        out[ev] = {'subtitle': 'subtitles.murim.' + ev, 'sounds': sounds}
    with open(os.path.join(ASSETS, 'sounds.json'), 'w') as f:
        json.dump(out, f, ensure_ascii=False, indent=2)
        f.write('\n')
    for lang, i in (('ru_ru', 0), ('en_us', 1)):
        path = os.path.join(ASSETS, 'lang', lang + '.json')
        text = open(path, encoding='utf-8').read()
        have = json.loads(text)
        add = [(f'subtitles.murim.{ev}', SUB[ev][i]) for ev in SUB if f'subtitles.murim.{ev}' not in have]
        if not add:
            continue
        body = text.rstrip()
        assert body.endswith('}')
        body = body[:-1].rstrip() + ',\n' + ',\n'.join(f'  {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}' for k, v in add) + '\n}\n'
        json.loads(body)
        open(path, 'w', encoding='utf-8').write(body)
    print('sounds.json:', len(out), 'событий,', sum(len(v['sounds']) for v in out.values()), 'файлов')


main()
