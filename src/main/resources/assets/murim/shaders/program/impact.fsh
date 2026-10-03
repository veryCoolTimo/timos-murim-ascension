#version 150

// Импакт-кадры по разбору rimuru.dev (docs/03-vfx/13-rimuru-impact-frames.md): удар —
// это смена СПОСОБА изображения, классический чёрно-белый, без цвета.
//   Mode 0 — белый разрыв (вспышка);
//   Mode 1 — почти чёрный кадр: только белые контуры сцены и самое светлое;
//   Mode 2 — графическая версия: белая бумага, свет удара — сплошная тушь, полутона —
//            растр ~5 px, контуры — чёрные, радиальные штрихи от точки удара;
//   Mode 3 — смена полярности кадра 2 (белое на чёрном).

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Mode;
uniform float CenterX;
uniform float CenterY;
uniform float Seed;
uniform float Exposure;

in vec2 texCoord;
in vec2 oneTexel;
out vec4 fragColor;

float lum(vec2 uv) {
    return clamp(dot(texture(DiffuseSampler, uv).rgb, vec3(0.299, 0.587, 0.114)) * Exposure, 0.0, 1.0);
}

float hash(float n) {
    return fract(sin(n * 12.9898 + Seed * 78.233) * 43758.5453);
}

void main() {
    vec2 uv = texCoord;
    // Шаг 2,5 px: мелкая фактура блоков (трава) не даёт «соли» в контурах.
    vec2 o = oneTexel * 2.5;
    float tl = lum(uv + o * vec2(-1.0, 1.0));
    float t = lum(uv + o * vec2(0.0, 1.0));
    float tr = lum(uv + o * vec2(1.0, 1.0));
    float ml = lum(uv + o * vec2(-1.0, 0.0));
    float l = lum(uv);
    float mr = lum(uv + o * vec2(1.0, 0.0));
    float bl = lum(uv + o * vec2(-1.0, -1.0));
    float b = lum(uv + o * vec2(0.0, -1.0));
    float br = lum(uv + o * vec2(1.0, -1.0));
    float gx = -tl - 2.0 * ml - bl + tr + 2.0 * mr + br;
    float gy = -tl - 2.0 * t - tr + bl + 2.0 * b + br;
    // Контур — резкий, без полутона: манхва, не размытие.
    float edge = step(0.22, sqrt(gx * gx + gy * gy));

    // Точка удара: вокруг неё — свободная белая зона с рваным краем (разрыв), штрихи к ней.
    vec2 d = uv - vec2(CenterX, CenterY);
    d.x *= InSize.x / InSize.y;
    float r = length(d);
    float ang = atan(d.y, d.x);
    float burstR = 0.07 + 0.035 * sin(ang * 9.0 + Seed) + 0.02 * sin(ang * 23.0 - Seed * 2.0);
    float burst = step(r, burstR);
    float burstRim = step(r, burstR + 0.012) - burst;

    if (Mode < 0.5) {
        // Почти белый разрыв: еле видные серые контуры сцены.
        fragColor = vec4(vec3(1.0 - 0.25 * edge * (1.0 - burst)), 1.0);
        return;
    }

    if (Mode < 1.5) {
        // Тёмный кадр: только крупные контуры (порог выше) и самое светлое.
        float strong = step(0.5, sqrt(gx * gx + gy * gy));
        // Белое — только узкие края и самые яркие участки эффекта; масса остаётся тёмной.
        // Порог — по исходной яркости, без поднятой экспозиции: белеет только свет удара.
        float white = max(strong, step(0.8, l / max(Exposure, 1.0)));
        fragColor = vec4(vec3(white), 1.0);
        return;
    }

    // Радиальные штрихи к точке удара: разной длины, с разрывами, свободная зона у центра.
    float a = (ang + 3.14159265) / 6.2831853 * 220.0;
    float sector = floor(a);
    float f = fract(a);
    float h = hash(sector);
    float start = burstR + 0.12 + 0.3 * hash(sector + 31.0);
    float lines = step(0.5, h) * step(start, r) * step(abs(f - 0.5), 0.08 + 0.32 * h * clamp((r - start) * 1.6, 0.0, 1.0));
    lines *= step(fract(r * (5.0 + 6.0 * hash(sector + 7.0)) + h * 3.0), 0.82);

    // Тон: самое тёмное (ночное небо) — бумага; свет удара — сплошная тушь; середина — растр.
    vec2 px = uv * InSize;
    vec2 cell = mod(px, 5.0) - 2.5;
    float tone = smoothstep(0.08, 0.6, l);
    // Растр — только в полутонах; самое светлое — тушь (свет удара), тёмное — бумага.
    float dotInk = step(length(cell) / 3.3, tone * 0.85) * step(0.14, l) * step(l, 0.62);
    float mass = step(0.7, l);
    float ink = max(max(mass, edge), max(dotInk, lines));
    // Зона разрыва белая, с чёрной рваной каймой.
    ink = mix(ink, 0.0, burst);
    ink = max(ink, burstRim);

    if (Mode < 2.5) {
        fragColor = vec4(mix(vec3(0.97, 0.96, 0.93), vec3(0.04), ink), 1.0);
    } else {
        // Выборочная инверсия: мир — белым по чёрному, но разрыв у точки удара остаётся белым.
        float inv = mix(1.0 - ink, 1.0, burst);
        fragColor = vec4(vec3(0.02 + 0.96 * inv), 1.0);
    }
}
