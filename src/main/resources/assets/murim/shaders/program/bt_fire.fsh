#version 150

// The lab's fire field (docs/vfx-stage/bt-port/lab/lab-bt_third.js, bt3Fire), over the frame. DiffuseSampler is the density target of
// BtFireField: RGB a quarter of the summed tongue field, A the strongest tongue. Coordinates are the lab's plate pixels (y down) round
// the body's screen position, so the noise keeps its size on the body at any distance and moves with it, not with the screen.
// Game tuning against the lab (codex 31.10): the fine warp is a third weaker and coarser (less wiry fringe on the bigger tongues).
uniform sampler2D DiffuseSampler;
uniform vec2 OutSize;
uniform float Clock;
uniform float PlateScale;
uniform vec2 Anchor;
uniform float SumScale;
uniform float AlphaK;
uniform vec3 Core;
uniform vec3 Body;
uniform vec3 Edge;
uniform vec3 Mid;

in vec2 texCoord;

out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7)) + 5.0) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + 1.0), f.x), f.y);
}
float fbm(vec2 p) { return 0.57 * noise(p) + 0.28 * noise(p * 2.03) + 0.15 * noise(p * 4.11); }

void main() {
    vec2 p = (texCoord * OutSize - Anchor) * PlateScale;
    p.y = -p.y;
    float clock = Clock;
    float warp = (noise(vec2(p.x * 0.04, p.y * 0.033 + clock * 2.3)) - 0.5) * 14.0
            + (noise(vec2(p.x * 0.072 + clock * 0.7, p.y * 0.048 + clock * 3.3)) - 0.5) * 4.5;
    // the lab warps the tongue lookup sideways
    vec4 d = texture(DiffuseSampler, texCoord + vec2(warp / PlateScale / OutSize.x, 0.0));
    if (d.r <= 0.0 && d.a <= 0.0) {
        discard;
    }
    // The sum shapes the fire (neighbours merge), each tongue's own peak lights its core.
    float F = 1.0 - exp(-1.5 * d.r * SumScale), Fm = d.a;
    float n = fbm(vec2(p.x * 0.011, p.y * 0.007 + clock * 0.5));
    float detail = fbm(vec2(p.x * 0.05 + warp * 0.035, p.y * 0.026 + clock * 1.9));
    // The noise only breaks up the edge of the tongues; where no tongue is there is no fire.
    float f = F * 0.75 - 0.12 + ((detail - 0.5) * 0.55 + 0.25 * (n - 0.5)) * smoothstep(0.02, 0.3, F);
    float alpha = smoothstep(0.04, 0.15, f) * AlphaK;
    float streak = fbm(vec2((p.x + p.y * 0.4) * 0.028 + warp * 0.012, p.y * 0.02 + clock * 1.2));
    float heat = min(f, Fm * 0.9 - 0.05 + (detail - 0.5) * 0.3);
    vec3 col = mix(Edge, Body, smoothstep(0.06, 0.2, f) * (0.6 + 0.4 * streak));
    // In game the wider tongues sum higher than the lab's: the light blue starts a little earlier, the white core later (codex 31.10).
    col = mix(col, Mid, smoothstep(0.23, 0.5, heat) * 0.85);
    col = mix(col, Core, smoothstep(0.62, 0.9, heat + 0.25 * (streak - 0.5)));
    fragColor = vec4(col, clamp(alpha, 0.0, 0.95));
}
