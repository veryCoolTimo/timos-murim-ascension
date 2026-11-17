#version 150

// "Flame": the flat violet-blue flame of panels 13 and 14 as a CHEAP shader: three lookups of a baked tiling texture (FxFlameTexture: R = the
// distance field of the webs between discs, G and B = smooth noise, A = blobs) and about eighty arithmetic operations, no loops and no
// procedural noise. The pattern still moves: two scrolled layers (rising, opposite sideways drift) and a warp that is itself a scrolled
// lookup. Flat tone bands (shadow, violet, light violet), a blue edge band and blue areas, small pink accents, scalloped bites and pointed
// tips; the web thins to wisps towards the top by raising the threshold with height. fx_essence.fsh is the same look, computed per pixel.
uniform sampler2D Sampler0;
uniform float FlTime;
uniform vec4 FlSize;   // x,y = half width, half height of the sheet in blocks; z = seed; w = growth 0..1
uniform vec4 FlMap;    // x = texture tiles per block, y = rise in blocks per second, z = warp amount in tiles, w = vertical squash of the cells
uniform vec4 FlShape;  // x = threshold at the bottom, y = threshold at the top (a higher threshold = thinner webs), z = softness of the bites, w = how much the fine layer bites
uniform vec4 FlTone;   // x = share of the dark tone, y = share of the light tone, z = pink accent amount, w = blue area amount
uniform vec4 FlCloth;  // x = 2: flat flame with a soft foot (w = its height in blocks); x = 1: CLOTH mode (soft broad sheets: wide feathered edges, smooth tones, no bites, no flat bands); y = feather of the edge, z = where the side/top fade starts (0..1), w = share of the pale core low down
uniform vec4 FlFan;    // half widths (blocks) of the silhouette at 0, 1/3, 2/3 and the top; nothing is drawn outside
uniform vec4 FlC0;     // shadow tone rgb, a = its alpha
uniform vec4 FlC1;     // blue tone
uniform vec4 FlC2;     // violet tone
uniform vec4 FlC3;     // light violet tone
uniform vec4 FlC4;     // pink accent

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float fanWidth(float hq) {
    float h3 = hq * 3.0;
    return h3 < 1.0 ? mix(FlFan.x, FlFan.y, h3) : (h3 < 2.0 ? mix(FlFan.y, FlFan.z, h3 - 1.0) : mix(FlFan.z, FlFan.w, h3 - 2.0));
}

void main() {
    vec2 P = (texCoord0 - 0.5) * 2.0 * FlSize.xy;
    P.y = -P.y;                                              // texCoord0.y runs down the sheet
    float hq = clamp((P.y + FlSize.y) / (2.0 * FlSize.y), 0.0, 1.0);
    float fw = max(fanWidth(hq), 0.05);
    float ex = abs(P.x) / fw;                                // 0 in the middle, 1 at the silhouette's side
    bool cloth = FlCloth.x > 0.5 && FlCloth.x < 1.5;   // 1 = cloth, 2 = the flat flame with a soft foot of FlCloth.w blocks (rises out of nothing at the floor)
    if (ex > 1.0 || hq > 0.995) discard;
    // no straight border anywhere: over the outer 15% (sides and top) the alpha fades out and the webs thin out, so the pattern's own bites cut the edge
    float rim = cloth ? 0.0 : max(smoothstep(0.62, 1.0, ex), smoothstep(0.80, 0.995, hq));
    float fade = (1.0 - smoothstep(0.85, 1.0, ex)) * (1.0 - smoothstep(0.85, 0.995, hq)) * smoothstep(0.0, 0.2, hq);
    if (FlCloth.x > 1.5) fade = (1.0 - smoothstep(0.5, 1.0, ex)) * (1.0 - smoothstep(0.55, 0.995, hq)) * smoothstep(0.0, clamp(FlCloth.w / (2.0 * FlSize.y), 0.03, 0.3), hq);
    if (cloth) {
        // cloth: the sides and the top melt away over a wide band, the foot rises out of nothing (never cut by the floor)
        float sd = 1.0 - smoothstep(FlCloth.z, 1.0, ex), tp = 1.0 - smoothstep(0.42, 0.995, hq);
        fade = sd * sd * (3.0 - 2.0 * sd) * tp * tp * smoothstep(0.0, clamp(0.35 / (2.0 * FlSize.y), 0.03, 0.3), hq);
    }   // round 6: the foot fades out softly, never a straight cut at the floor
    float t = FlTime;
    vec2 cell = vec2(1.0, FlMap.w);
    // warp: a slow lookup of the smooth fields moves the sampling point
    vec2 w = texture(Sampler0, P * (0.5 * FlMap.x) + vec2(FlSize.z * 0.37, -t * 0.03 * FlMap.y)).gb - 0.5;
    vec2 q1 = (P + w * FlMap.z / FlMap.x) * FlMap.x * cell + vec2(FlSize.z * 0.13, -t * FlMap.y * FlMap.x);
    vec2 q2 = (P - w.yx * FlMap.z / FlMap.x) * FlMap.x * 1.9 * cell + vec2(0.31 + FlSize.z * 0.07 + t * 0.045, -t * FlMap.y * FlMap.x * 1.6);
    vec4 s1 = texture(Sampler0, q1);
    vec4 s2 = texture(Sampler0, q2);
    // the web: the first layer's distance field, bitten by the finer layer; the threshold rises with height (thinner webs, then wisps), and with the sheet closing
    float d = min(s1.r, s2.r + 0.04 + (1.0 - FlShape.w) * 0.25) - 0.5;
    float thr = mix(FlShape.x, FlShape.y, smoothstep(0.0, 1.0, hq)) + (1.0 - FlSize.w) * 0.6 + rim * 0.2;
    float edge = cloth ? FlCloth.y : max(fwidth(d) * 0.75, 0.002) + FlShape.z * 0.01;
    float inside = smoothstep(thr - edge, thr + edge, d);
    if (inside < 0.003) discard;
    float depth = d - thr;                                    // how far inside the web this pixel is
    if (cloth) {
        // smooth tones: dark folds (FlC0) into the body tone (FlC2), crests towards the pale tone (FlC3), a pale core low down; alpha follows the tone
        float fold = smoothstep(0.2, 0.9, s1.g + depth * 0.8);
        vec3 c = mix(FlC0.rgb, FlC2.rgb, fold);
        c = mix(c, FlC3.rgb, smoothstep(0.55, 1.0, fold) * FlTone.y);
        c = mix(c, FlC3.rgb, (1.0 - smoothstep(0.0, 0.5, hq)) * FlCloth.w);
        c = mix(c, FlC0.rgb, smoothstep(0.4, 1.0, hq) * 0.6);   // the upper parts go to the deep, near-black tone
        float a = mix(FlC0.a, FlC2.a, fold) * smoothstep(0.0, FlCloth.y * 2.0 + 0.02, depth + FlCloth.y * 0.5);
        fragColor = vec4(c, a * inside * fade * vertexColor.a);
        return;
    }
    // flat tone bands from the smooth field
    float tn = s1.g + depth * 0.9;
    vec4 col = FlC2;
    col = mix(col, FlC3, step(1.0 - FlTone.y, tn));
    col = mix(col, FlC0, 1.0 - step(FlTone.x, tn));
    // blue: a band along the edge (wider where the second field is high) and whole blue areas
    float band = 0.035 * (0.3 + 1.4 * s2.b);
    float blueArea = step(1.0 - FlTone.w, s2.g);
    col = mix(col, FlC1, max(1.0 - step(band, depth), blueArea));
    // pink: small accents deep inside the web
    col = mix(col, FlC4, step(1.0 - FlTone.z, s1.a) * step(0.09, depth));
    fragColor = vec4(col.rgb, col.a * inside * fade * vertexColor.a);
}
