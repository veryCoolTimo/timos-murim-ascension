#version 150

// Давление ауры (docs/design/19 §3ж, разбор astra 01.10).
// Не жаркое марево, а направленное смещение: при удержании — слабое поле от противника
// наружу (0,6–2,2 пикселя на 1080), на фронте — одна волна от него к краям за 0,6 с
// (до 5 пикселей). Внутри окна противника и у прицела смещение почти гаснет. Цвет уходит
// умеренно, периферия чуть темнеет; центр не трогаем — его держит светлое поле ауры.
// Демоническая — красный оттенок и 20 % касательного «втягивания».

uniform sampler2D DiffuseSampler;
uniform vec2 InSize;
uniform float Pressure;
uniform float Clock;
uniform float CenterX;
uniform float CenterY;
uniform float Demonic;
uniform float Front;
uniform float Window;
uniform float Visible;

in vec2 texCoord;
out vec4 fragColor;

void main() {
    float p = clamp(Pressure, 0.0, 1.0);
    vec2 uv = texCoord;
    float aspect = InSize.x / InSize.y;
    vec2 center = mix(vec2(0.5), vec2(CenterX, CenterY), Visible);
    vec2 d = uv - center;
    d.x *= aspect;
    float r = length(d);
    vec2 dir = r > 1e-4 ? d / r : vec2(0.0);
    vec2 tangent = vec2(-dir.y, dir.x);

    // Направленное поле: наружу, с небольшой неровностью по углу; демоническая — внутрь и вбок.
    float angle = atan(dir.y, dir.x);
    float irregular = 0.7 + 0.3 * sin(angle * 5.0 + Clock * 0.6);
    vec2 field = mix(dir, -dir * 0.8 + tangent * 0.2, Demonic) * irregular;
    float pixel = 1.0 / InSize.y;
    float hold = mix(0.0, 2.2, p) * smoothstep(0.0, 0.5, r);

    // Фронт: кольцо уходит от противника к краям за 0,6 с.
    float frontR = Front / 0.6 * 1.2;
    float frontBand = exp(-pow((r - frontR) / 0.08, 2.0)) * step(Front, 0.8) * (1.0 - Front / 0.8);
    float front = mix(0.0, 5.0, p) * frontBand;

    // Окно противника и прицела: смещение до 15 %.
    float protect = mix(0.15, 1.0, smoothstep(Window * 0.8, Window * 1.2, r));
    vec2 toCross = (uv - vec2(0.5)) * vec2(aspect, 1.0);
    protect *= mix(0.15, 1.0, smoothstep(0.02, 0.06, length(toCross)));

    vec2 offset = field * (hold + front) * pixel * protect;
    offset.x /= aspect;
    vec3 col = texture(DiffuseSampler, uv - offset).rgb;

    float lum = dot(col, vec3(0.299, 0.587, 0.114));
    col = mix(col, vec3(lum), 0.35 * p);
    col *= 1.0 - 0.15 * p * smoothstep(0.45, 1.0, r);
    col = mix(col, col * vec3(1.3, 0.6, 0.55), 0.3 * p * Demonic);

    fragColor = vec4(col, 1.0);
}
