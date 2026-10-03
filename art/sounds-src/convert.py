#!/usr/bin/env python3
"""mp3 из ElevenLabs → ogg vorbis mono 44.1k для assets/murim/sounds/.

Использование: convert.py [имя_варианта …]   (без аргументов — все из PICK)
Громкость: двухпроходный loudnorm к цели категории, но не выше пикового потолка; клипы короче,
чем нужно loudnorm для гейтинга (I = -inf), нормализуются по пику. Цели подобраны по ванили
(sweep/strong: пик −2 дБ; crit −14.9 LUFS; bow −21.5 LUFS; heartbeat −20.9 LUFS) — наши не громче.
Одноразовые звуки: срез тишины в начале (атака ровно на тике события) и затухание хвоста 40 мс.
Петли (--loop у генератора) не режутся — иначе шов станет слышен.
"""
import json, os, re, subprocess, sys

HERE = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(HERE, '..', '..', 'src', 'main', 'resources', 'assets', 'murim', 'sounds')

# Цель (LUFS, пиковый потолок дБ) по категориям.
# 03.10 автор дважды: «тише» — всё, кроме интерфейса, опущено на 5 дБ.
LOUD = (-24.0, -8.0)
SOFT = (-28.0, -11.0)
UI = (-26.0, -9.0)
LOOP = (-33.0, -15.0)
CAT = {
    # Ци/прорыв/давление — тише (автор 03.10: «чтобы уши не взрывать»).
    'qi_charge': SOFT, 'breakthrough': SOFT, 'aura_gust': SOFT,
    'step_soft': SOFT, 'qi_chime': SOFT, 'meditation_cycle': SOFT, 'blossom_open': SOFT, 'sword_draw': SOFT,
    'lock_on': UI, 'wheel_open': UI, 'wheel_select': UI,
    'run_wind': LOOP, 'aura_charge': LOOP, 'meditation_loop': LOOP, 'qi_sword_hum': LOOP,
}


def loops():
    s = set()
    for line in open(os.path.join(HERE, 'prompts.tsv')):
        p = line.rstrip('\n').split('\t')
        if len(p) >= 4 and p[3] == 'loop':
            s.add(p[0])
    return s


def measure(src, pre):
    r = subprocess.run(['ffmpeg', '-hide_banner', '-i', src, '-af', pre + 'loudnorm=print_format=json', '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
    j = json.loads(r[r.rindex('{'):r.rindex('}') + 1])
    return j


def peak(src, pre):
    r = subprocess.run(['ffmpeg', '-hide_banner', '-i', src, '-af', pre + 'volumedetect', '-f', 'null', '-'],
                       capture_output=True, text=True).stderr
    return float(re.search(r'max_volume: ([-\d.]+)', r).group(1))


def convert(variant):
    name = variant.rsplit('_', 1)[0]
    src = os.path.join(HERE, variant + '.mp3')
    is_loop = name in loops()
    target, ceil = CAT.get(name, LOOP if is_loop else LOUD)
    pre = 'aformat=channel_layouts=mono,'
    if not is_loop:
        pre += 'silenceremove=start_periods=1:start_threshold=-45dB,areverse,silenceremove=start_periods=1:start_threshold=-55dB,areverse,'
    m = measure(src, pre)
    pk = peak(src, pre)
    if m['input_i'] in ('-inf', 'inf'):
        gain = ceil - pk
    else:
        gain = min(target - float(m['input_i']), ceil - pk)
    af = pre + f'volume={gain:.2f}dB'
    if not is_loop:
        # Затухание хвоста: разворот → нарастание в начале → разворот обратно.
        af += ',areverse,afade=t=in:d=0.04,areverse'
    os.makedirs(OUT, exist_ok=True)
    dst = os.path.join(OUT, variant + '.ogg')
    subprocess.run(['ffmpeg', '-y', '-hide_banner', '-loglevel', 'error', '-i', src, '-af', af, '-ar', '44100', '-ac', '1',
                    '-c:a', 'libvorbis', '-q:a', '5', dst], check=True)
    print(f'{variant:24s} I={m["input_i"]:>7s} peak={pk:6.1f} gain={gain:+6.1f} dB → {os.path.relpath(dst, HERE)}')


if __name__ == '__main__':
    names = sys.argv[1:] or sorted(f[:-4] for f in os.listdir(HERE) if f.endswith('.mp3'))
    for v in names:
        convert(v)
