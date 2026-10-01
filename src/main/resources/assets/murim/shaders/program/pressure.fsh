#version 150

// Давление ауры (docs/design/19 §3ж): воздух вокруг сильного дрожит, как над огнём.
// Кольца марева расходятся от противника, мелкая рябь по всему кадру, цвет уходит,
// края темнеют; при сильном давлении — двоение каналов, как от удара по глазам.

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Pressure;
uniform float Clock;
uniform float CenterX;
uniform float CenterY;
uniform float Demonic;
uniform float Gust;
uniform float GustStrength;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float p = clamp(Pressure, 0.0, 1.0);
    vec2 uv = texCoord;
    float aspect = InSize.x / InSize.y;
    vec2 d = uv - vec2(CenterX, CenterY);
    d.x *= aspect;
    float r = length(d);
    vec2 dir = r > 1e-4 ? d / r : vec2(0.0);

    // Кольца марева: бегут наружу, гаснут с расстоянием.
    float wave = sin(r * 34.0 - Clock * 5.0) * exp(-r * 1.6);
    // Мелкая рябь горячего воздуха.
    vec2 haze = vec2(sin(uv.y * 85.0 + Clock * 6.3) * sin(uv.x * 23.0 - Clock * 2.1),
                     sin(uv.x * 71.0 - Clock * 5.1) * sin(uv.y * 19.0 + Clock * 1.7));
    // Смещение 0,0005–0,0025 высоты кадра, сильнее к краям (разбор codex 01.10).
    float edge = 0.4 + 0.6 * smoothstep(0.1, 0.8, r);
    vec2 offset = (dir * wave * 0.0025 + haze * 0.0012) * p * edge;
    offset.x /= aspect;

    vec2 ca = dir * (0.0045 * p * p + 0.006 * band * GustStrength);
    ca.x /= aspect;
    vec3 col;
    col.r = texture(DiffuseSampler, uv + offset + ca).r;
    col.g = texture(DiffuseSampler, uv + offset).g;
    col.b = texture(DiffuseSampler, uv + offset - ca).b;

    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(lum), 0.15 + 0.4 * p);
    // Мир за пределами противника темнеет: взгляд прилипает к нему.
    // Экспозицию не роняем: иначе вместе со сценой гаснет и белое основание ауры.
    col *= 1.0 - 0.18 * p * smoothstep(0.35, 0.95, r);
    // Демоническая ци красит воздух в кровь.
    col = mix(col, col * vec3(1.35, 0.55, 0.5), 0.35 * p * Demonic);

    fragColor = vec4(col, 1.0);
}
