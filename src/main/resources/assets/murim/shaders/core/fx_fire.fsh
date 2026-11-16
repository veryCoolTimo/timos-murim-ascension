#version 150

// "Fire": big soft sheets of flame (the pill-game aura, refs pill-sequence 3, 3.5, 4 and «cultivating it»). One sheet is a strip of quads bent round the
// figure; this shader paints it from a baked tiling noise texture (FlowFireSheets): three lookups, no loops. The noise is stretched along the rise
// (tall streaks) and sheared (diagonal drift), scrolls upward, and is cut by a density threshold that grows with height, so the top is torn.
// Soft body in the pill tone, a darker deep-tone band along every torn edge, dark patches inside, hot streaks low in the sheet (more with heat).
uniform sampler2D Sampler0;
uniform float FiTime;
uniform vec4 FiSize;   // x,y = half width, half height of the sheet in blocks; z = seed; w = growth 0..1
uniform vec4 FiMap;    // x = noise tiles per block across, y = tiles per block up, z = rise in blocks per second, w = lean (sideways shear per block of height)
uniform vec4 FiShape;  // x = density contrast, y = threshold at the top, z = softness of the body edge, w = width of the dark torn band
uniform vec4 FiHeat;   // x = heat 0..1, y = hot streak amount, z = dark patch amount, w = side softness (0 = wide body .. 1 = narrow)
uniform vec4 FiC0;     // deep torn-edge tone rgb, a = alpha of the band
uniform vec4 FiC1;     // body tone, a = body alpha
uniform vec4 FiC2;     // hot tone

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

void main() {
    float hq = 1.0 - texCoord0.y;
    float x = (texCoord0.x - 0.5) * 2.0;
    float hb = hq * 2.0 * FiSize.y;
    float wb = x * FiSize.x;
    float t = FiTime;
    float sx = wb + FiMap.w * hb;
    vec2 w = texture(Sampler0, vec2(sx * 0.09 + FiSize.z * 0.37, hb * 0.05 - t * 0.012 * FiMap.z)).ra - 0.5;
    vec2 q1 = vec2(sx * FiMap.x + w.x * 0.9 + FiSize.z * 0.13, hb * FiMap.y - t * FiMap.z * FiMap.y);
    vec2 q2 = vec2(sx * FiMap.x * 2.4 - w.y * 0.7 + 0.31 + FiSize.z * 0.07, hb * FiMap.y * 2.0 - t * FiMap.z * FiMap.y * 1.6);
    vec4 a = texture(Sampler0, q1);
    vec4 b = texture(Sampler0, q2);
    float dens = a.g * 0.5 + b.g * 0.3 + a.r * 0.2;
    dens = (dens - 0.5) * FiShape.x + 0.5;
    float side = 1.0 - smoothstep(0.1 + 0.3 * FiHeat.w, 1.0, abs(x) + (a.g - 0.5) * 0.9 + (b.r - 0.5) * 0.4);
    float foot = smoothstep(0.0, 0.16, hq);
    float thr = FiShape.y * pow(hq, 1.3) + (1.0 - FiSize.w) * 0.7;
    float c = dens * side - thr - 0.12;
    float soft = max(FiShape.z, 0.02);
    float body = smoothstep(0.0, soft, c);
    if (body < 0.004) discard;
    float band = 1.0 - smoothstep(0.0, FiShape.w, c);
    float hot = smoothstep(0.1, 0.4, c) * smoothstep(0.55, 0.8, b.b) * (1.0 - hq) * FiHeat.y * (0.4 + 0.6 * FiHeat.x);
    float darkp = smoothstep(0.5, 0.74, b.a) * FiHeat.z;
    vec3 col = mix(FiC1.rgb, FiC2.rgb, hot);
    col = mix(col, FiC0.rgb, clamp(darkp + hq * 0.35 * (1.0 - hot), 0.0, 1.0));
    col = mix(col, FiC0.rgb, band * 0.9);
    float topFade = 1.0 - smoothstep(0.62, 1.0, hq);
    float al = mix(FiC1.a, FiC0.a, band) * body * foot * topFade * vertexColor.a;
    al = mix(al, min(1.0, al * 1.25), hot);
    fragColor = vec4(col, al);
}
