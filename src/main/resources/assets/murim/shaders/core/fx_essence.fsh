#version 150

// "Essence": the flat violet-blue flame of panels 13 and 14 of the author's references (docs/design/reference/vfx-author-1113,
// layers in plates/13-violet-layer.png and 14-violet-layer.png). It is not a stroke: it is the web of flat patches that lies between
// rounded, drifting dark discs: concave scalloped bites, sharp cusps where three discs nearly touch, thin pointed wisps where two do.
// The patches are flat tone bands (shadow, blue, violet, light violet, a pink accent with bubbles), partly transparent, no gradients.
// Everything moves: the discs drift on moving jitter and rise with the field, the field is domain-warped, tones and accents are thresholds
// of moving noise. Patches thin out by closing (the discs of a quiet region grow until they touch), they never fade or pop.
// A quad carries the field in its own plane; its size in blocks comes in EsSize. tools/vfx-lab/essence_lab.py runs this same file.
uniform float EsTime;
uniform vec4 EsSize;   // x,y = half width, half height of the quad in blocks; z = seed; w = growth 0..1 (0 = every disc closed, 1 = fully open)
uniform vec4 EsField;  // x = cell size in blocks, y = disc radius in cells (coverage: bigger = narrower webs), z = angular 0..1, w = stretch of the cells upward (<1 = taller)
uniform vec4 EsFlow;   // x = rise in blocks per second, y = outward flare per block of height, z = disc drift speed, w = domain warp in blocks
uniform vec4 EsMask;   // x = scale of the envelope noise (1/blocks), y = how much of the quad is left open (0..1), z = favour for the bottom, w = soft border of the quad (blocks)
uniform vec4 EsTone;   // x = tone band scale (1/blocks), y = share of the dark violet shadow tone, z = share of light violet, w = pink accent amount (0..1)
uniform vec4 EsRim;    // x = unused, y = frequency of the torn-edge noise (per cell), z = bubble size scale, w = how long the discs are (0 = round, 1 = up to twice as tall)
uniform vec4 EsShape;  // x = how much the discs grow with height (webs thin to wisps near the top), y = width of the blue edge band (cells), z = spread of disc sizes (0..1), w = jaggedness of the edges (cells)
uniform vec4 EsTongue; // x = place of the tall tongue across the quad (-1..1), y = its reach up the quad (0..1), z,w = the same for a second, lower tongue (reach 0 = none)
uniform vec4 EsWisp;   // x = how much the discs are bent into crescents, y = how much they lean one way with the flow (0 = random), z = length of a pink drop in blocks, w = how much the blue is a one-sided shadow edge (0 = all round)
uniform vec4 EsThorn; // x = share of thorn cells that carry a thorn (0 = none), y = thorn length in blocks, z = half width of its base in blocks, w = curl (how far it bends sideways)
uniform vec4 EsFan;    // half widths (blocks, across the quad's centre line) of a fan-shaped silhouette at 0, 1/3, 2/3 and the top of the quad; x = 0 means no fan. Holds the webs open inside it, so the field follows a given silhouette (the ink tree's)
uniform vec4 EsLod;    // x = 1: a cheap field (one octave of noise for each disc's envelope and lean instead of three); the shapes keep their kind, the cost drops by about half
uniform vec4 EsClear;  // x,y = centre, z,w = half axes (blocks, quad plane) of a place that is always closed: the head and body of the figure stay clean
uniform vec4 EsC0;     // shadow tone rgb, a = its alpha
uniform vec4 EsC1;     // blue tone
uniform vec4 EsC2;     // violet tone
uniform vec4 EsC3;     // light violet tone
uniform vec4 EsC4;     // pink accent

in vec2 texCoord0;
in vec4 vertexColor;

out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
vec2 hash2(vec2 p) { return vec2(hash(p), hash(p + vec2(17.3, 91.7))); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + 1.0), f.x), f.y);
}
float fbm(vec2 p) { return 0.55 * noise(p) + 0.3 * noise(p * 2.07 + 3.1) + 0.15 * noise(p * 4.3 + 7.7); }

float fbmL(vec2 p) { return EsLod.x > 0.5 ? 0.5 + (noise(p) - 0.5) * 0.5 : fbm(p); }   // one octave, squeezed to the contrast of three

// the field in the quad's own plane, in blocks, origin at the quad's centre, y up
vec2 flowCoord(vec2 P, float t) {
    float height = max(P.y + EsSize.y, 0.0);
    vec2 q = vec2(P.x / (1.0 + EsFlow.y * height), P.y - t * EsFlow.x);
    vec2 w = vec2(fbm(q * 0.9 + vec2(EsSize.z, -t * 0.13)), fbm(q * 0.9 + vec2(5.3 + EsSize.z, t * 0.11)));
    return q + (w - 0.5) * 2.0 * EsFlow.w;
}

// a hard step with an edge one pixel wide, whatever the scale of the value
float step2(float x, float edge) {
    float w = max(fwidth(x), 0.0001) * 0.75;
    return smoothstep(edge - w, edge + w, x);
}

// A disc's distance, between a round disc and an octagon turned a random way: the octagon is what gives the angular look of panel 14.
float shape(vec2 v, float turn, float angular, float elong, float bend) {
    float c = cos(turn), s = sin(turn);
    vec2 r = vec2(c * v.x - s * v.y, s * v.x + c * v.y);
    r.x -= bend * r.y * abs(r.y);                 // a crescent: the two sides of a gap between crescents curve the same way, and the gap ends in a long curved point
    r.y /= elong;                                 // a tilted ellipse: the dark tendrils of the reference are long, not round
    vec2 a = abs(r);
    float oct = max(max(a.x, a.y), (a.x + a.y) * 0.7071) * 0.93;
    return mix(length(r), oct, angular) * min(elong, 1.0 + 0.5 * (elong - 1.0));
}

// half width of the fan silhouette (EsFan) at height hq (0 bottom .. 1 top)
float fanWidth(float hq) {
    float h3 = hq * 3.0;
    return h3 < 1.0 ? mix(EsFan.x, EsFan.y, h3) : (h3 < 2.0 ? mix(EsFan.y, EsFan.z, h3 - 1.0) : mix(EsFan.z, EsFan.w, h3 - 2.0));
}

// a fan silhouette (EsFan): open inside, closing at its edge and at the top
float fanLift(float x, float hq) {
    if (EsFan.x <= 0.0) return 0.0;
    return 1.7 * (1.0 - smoothstep(fanWidth(hq) - 0.45, fanWidth(hq) + 0.1, abs(x))) * (1.0 - smoothstep(0.86, 1.0, hq));
}

// the flame silhouette: wide and dense at the bottom, a tall tongue and a lower one. Added to the envelope: it holds the webs open up to the tongue's reach.
float tongueLift(float xq, float hq, float t) {
    float lift = 0.0;
    for (int i = 0; i < 2; i++) {
        float x0 = i == 0 ? EsTongue.x : EsTongue.z, reach = i == 0 ? EsTongue.y : EsTongue.w;
        if (reach <= 0.0) continue;
        reach += 0.07 * (noise(vec2(t * 0.35, float(i) * 9.0 + EsSize.z)) - 0.5);                 // the tip licks up and down, it does not sway
        float wid = mix(0.34, 0.2, hq);                                                           // a tongue narrows to its tip
        float g = exp(-pow((xq - x0 - 0.06 * hq * (fbm(vec2(hq * 2.0, EsSize.z + float(i))) - 0.5) * 4.0) / wid, 2.0));
        lift = max(lift, g * (1.0 - smoothstep(reach - 0.3, reach, hq)) * 1.7);
    }
    return lift;
}

// distance to the nearest disc surface (negative inside a disc), in the field's own coordinates; the web of flat tone is where it is positive
float web(vec2 q, float t) {
    // over the 3x3 cells round this point, in two grids: big discs, and
    // a finer grid of small discs (only some cells have one) that bite the wide gaps into narrow wisps and cusps
    float d1 = 9.0, d2 = 9.0;
    for (int k = 0; k < 2; k++) {
        float sc = k == 0 ? 1.0 : 0.5;                      // size of this grid's cell relative to the main cell
        float seedK = EsSize.z + float(k) * 41.0;
        vec2 g = vec2(q.x, q.y * EsField.w) / (EsField.x * sc) + float(k) * 0.37;
        vec2 cell = floor(g), local = fract(g);
        for (int j = -1; j <= 1; j++) {
            for (int i = -1; i <= 1; i++) {
                vec2 o = vec2(float(i), float(j)), id = cell + o + seedK;
                vec2 h = hash2(id);
                float speed = EsFlow.z * (k == 0 ? 1.0 : 1.6);
                vec2 centre = o + 0.5 + 0.45 * vec2(sin(t * speed * (0.4 + h.x) + 6.283 * h.y), cos(t * speed * (0.4 + h.y) + 6.283 * h.x));
                // the envelope is read at the disc's own place: a quiet region has all its discs grown shut, so webs end in a point
                vec2 at = (cell + centre - float(k) * 0.37) * EsField.x * sc;
                at.y /= EsField.w;
                float hq = clamp((at.y + t * EsFlow.x + EsSize.y) / (2.0 * EsSize.y), 0.0, 1.0);   // height in the quad, 0 bottom .. 1 top
                float xq = at.x * (1.0 + EsFlow.y * hq * 2.0 * EsSize.y) / EsSize.x;                  // place across the quad, -1..1
                float env = fbmL(at * EsMask.x + vec2(EsSize.z * 3.0, -t * 0.05)) + EsMask.z * (0.5 - hq) * 0.6;
                float tl = max(tongueLift(xq, hq, t), fanLift(xq * EsSize.x, hq));
                env += tl;
                float open = smoothstep(0.0, 0.18, env - (1.0 - EsMask.y) * 0.9);
                float hf = hq;
                float r = (EsField.y * (1.0 - EsShape.z + 2.0 * EsShape.z * hash(id + 4.1)) + EsShape.x * hf * hf * (1.0 - 0.85 * min(tl, 1.0))) * mix(1.25, 1.0, open) - 0.03 * min(tl, 1.0);
                if (k == 1) r = hash(id + 6.6) < 0.68 ? 0.0 : r * 0.62;
                float turn = (hash(id + 9.9) - 0.5) * 2.3 * mix(1.0, 2.0, EsField.z);
                // the discs lean with the flow (outward with height, and with a slow noise): their gaps become long slivers that all point one way
                float lean = 0.55 * xq * (0.4 + hq) + 0.9 * (fbmL(at * 0.8 + vec2(EsSize.z, 7.0)) - 0.5) + 0.3 * (hash(id + 3.3) - 0.5);
                turn = mix(turn, lean, EsWisp.y);
                float bend = EsWisp.x * (hash(id + 5.5) < 0.5 ? -1.0 : 1.0) * (0.6 + 0.8 * hash(id + 8.8));
                float d = (shape(local - centre, turn, EsField.z, 1.0 + EsRim.w * hash(id + 2.2), bend) - r) * sc;
                if (d < d1) { d2 = d1; d1 = d; } else if (d < d2) { d2 = d; }
            }
        }
    }
    return d1;
}

// Thorns: thin curved points that leave the edge of the web and point into the dark discs, up and outward. Each cell of a coarse grid may carry
// one needle: a tapering curved triangle that starts at a root point and runs along a mostly upward, outward axis. A needle counts only if its root
// lies inside the web (so it is always joined to a patch) and it grows with the depth of the root, so it never pops. Only the part that leaves
// the web is seen, a thin point. Result: a distance-like value in blocks, positive inside a needle, added by max to d1.
float thornLift(vec2 q, float t) {
    float cs = EsThorn.y * 0.8;
    vec2 g = q / cs, cell = floor(g);
    float best = -9.0;
    for (int j = -1; j <= 1; j++) {
        for (int i = -1; i <= 1; i++) {
            vec2 id = cell + vec2(float(i), float(j)) + EsSize.z * 13.0;
            if (hash(id + 2.7) > EsThorn.x) continue;
            vec2 root = (cell + vec2(float(i), float(j)) + 0.15 + 0.7 * hash2(id)) * cs;
            float outward = clamp(q.x / EsSize.x, -1.0, 1.0) * 0.55;
            float ang = outward + (hash(id + 5.0) - 0.5) * 1.5 + 0.12 * sin(t * (0.6 + hash(id + 8.0)) + 6.283 * hash(id + 9.0));
            vec2 ax = vec2(-sin(ang), cos(ang)), pr = vec2(ax.y, -ax.x);
            float Lg = EsThorn.y * (0.55 + 0.75 * hash(id + 3.0));
            vec2 v = q - root;
            float a = dot(v, ax);
            if (a < -0.02 || a > Lg) continue;
            float curl = EsThorn.w * (hash(id + 6.0) < 0.5 ? -1.0 : 1.0);
            float c = dot(v, pr) - curl * a * a / EsThorn.y;
            if (EsThorn.z * (1.0 - a / Lg) - abs(c) < -0.01) continue;
            float depth = web(root, t) * EsField.x;                  // how far inside the web the root lies, in blocks
            float grow = smoothstep(0.0, 0.05, depth);
            float wd = EsThorn.z * grow * (1.0 - a / (Lg * (0.3 + 0.7 * grow))) - abs(c);
            best = max(best, wd * 0.8);
        }
    }
    return best;
}

void main() {
    vec2 P = (texCoord0 - 0.5) * 2.0 * EsSize.xy;
    P.y = -P.y;                                   // texCoord0.y runs down the quad
    float t = EsTime;
    // outside the fan silhouette (and its soft edge) nothing is drawn: no web is computed there
    if (EsFan.x > 0.0) {
        float hq0 = clamp((P.y + EsSize.y) / (2.0 * EsSize.y), 0.0, 1.0);
        if (abs(P.x) > fanWidth(hq0) + 0.9 || hq0 > 0.985) discard;
    }
    vec2 q = flowCoord(P, t);

    float d1 = web(q, t);
    // thorns live on the edge of the web: only pixels within a needle's length of it pay for them
    if (EsThorn.x > 0.0 && abs(d1) < EsThorn.y * 1.3 / EsField.x) d1 = max(d1, thornLift(q, t) / EsField.x);
    // torn edges: a fine sharpened noise moves the edge of the web in and out, which cuts small spikes and tongues
    if (EsShape.w > 0.0) {
        vec2 jq = q * (EsRim.y / EsField.x);
        if (EsWisp.y > 0.0) jq.y *= 0.3;              // streaks along the flow: the edge is pulled out into long thin tips that go the way the field goes
        float jn = noise(jq + vec2(EsSize.z * 7.0, -t * 0.15));
        jn = jn * jn * (jn + 0.35);
        d1 += (jn - 0.3) * EsShape.w;
    }
    // the figure's head and body stay clean: the clear place counts as one more disc
    if (EsClear.z > 0.0) {
        vec2 cv = (P - EsClear.xy) / EsClear.zw;
        d1 = min(d1, (length(cv) - 1.0) * min(EsClear.z, EsClear.w) / EsField.x);
    }
    float aa = max(fwidth(d1), 0.0005);
    float inside = smoothstep(-aa, aa, d1);                    // 1 on the web between the discs
    // the border of the quad closes the webs too: no hard cut at the mesh edge
    vec2 e = abs(P) / EsSize.xy;
    float border = 1.0 - smoothstep(1.0 - EsMask.w, 1.0, max(e.x, e.y));
    float cut = d1 - (1.0 - border) * 0.9;
    inside = min(inside, smoothstep(-aa, aa, cut));
    if (inside < 0.002) discard;

    // tone bands: two violets from a slow noise, blue only as a bright band along the edge of the gap (thin wisps are blue all through),
    // and a patch of blue low on the figure's right where the reference has it
    float tn = fbm(q * EsTone.x + vec2(EsSize.z * 5.0, -t * 0.07)) + (d1 - 0.12) * 0.9;
    float blueEdge = EsTone.y, lightEdge = 1.0 - EsTone.z;
    vec4 col = EsC2;
    col = mix(col, EsC3, step2(tn, lightEdge));
    col = mix(col, EsC0, 1.0 - step2(tn, blueEdge));            // the dark violet shadow tone in the low places of the noise
    // blue: the shadow side of a violet patch, not an even outline. Where the edge faces this region's own shadow direction the band is wide,
    // on the other edges it is absent; some regions have no blue at all; at the right edge and low right whole patches are blue.
    float bn = fbm(q * (EsWisp.w > 0.0 ? 1.5 : 2.3) + vec2(31.0 + EsSize.z, t * 0.06));
    vec2 gr = vec2(dFdx(d1), dFdy(d1));
    vec2 out2 = -gr / max(length(gr), 1e-6);                                   // points out of the patch
    float ang = 6.283 * fbm(q * 0.6 + vec2(13.0 + EsSize.z, 2.0));
    float facing = dot(out2, vec2(cos(ang), sin(ang)));
    float shadowSide = mix(1.0, smoothstep(0.1, 0.8, facing), EsWisp.w) * mix(1.0, step2(bn, 0.62), EsWisp.w);
    float band = EsShape.y * (0.3 + 1.4 * bn) * shadowSide * (EsWisp.w > 0.0 ? 1.0 : step2(bn, 0.38));
    float side = smoothstep(0.1, 0.9, 0.5 + 0.5 * (P.x / EsSize.x) - 0.8 * (P.y + EsSize.y) / (2.0 * EsSize.y)) * step2(bn, 0.5);
    float wholeBlue = EsWisp.w > 0.0 ? smoothstep(0.5, 0.75, 0.5 + 0.6 * (P.x / EsSize.x) - 0.7 * (P.y + EsSize.y) / (2.0 * EsSize.y)) : 0.0;
    // separate blue areas: whole flat patches of blue where a slow, big noise is high (a few of them across the field, none rimming the rest)
    float area = EsWisp.w > 0.0 ? step2(fbm(q * 0.42 + vec2(41.0 + EsSize.z, -t * 0.03)), 0.6) : 0.0;
    col = mix(col, EsC1, max(max(1.0 - step2(d1, band), max(side * (1.0 - step2(d1, band * 4.0)), step2(wholeBlue, 0.5))), area));
    // pink accent: pale drop-shaped highlights, a few, only deep inside the big violet patches; a drop has a round belly, a tail pointing along
    // the flow and a couple of small droplets behind it. One drop per cell of a coarse grid, drifting with the field; its size follows the
    // depth of the patch under it, so it grows and shrinks, it never pops.
    if (EsWisp.z > 0.0 && EsTone.w > 0.0) {
        float cellP = EsWisp.z * 3.0;
        vec2 pg = q / cellP; vec2 pc = floor(pg), pf = fract(pg);
        float hp = hash(pc + EsSize.z * 1.7);
        vec2 off = 0.3 + 0.4 * hash2(pc + 3.7 + EsSize.z);
        float fade = smoothstep(0.01, 0.075, d1);
        float live = step(hash(pc + 12.3 + EsSize.z), EsTone.w);
        float L = EsWisp.z * (0.7 + 0.45 * hp) * fade * live;
        if (L > 0.0) {
            vec2 v = (pf - off) * cellP;
            float ta = (hash(pc + 5.1) - 0.5) * 1.2 + 0.3;                             // the tail points up, a little aside
            vec2 ax = vec2(sin(ta), cos(ta)), pr = vec2(ax.y, -ax.x);
            float a = dot(v, ax) / L, bb = dot(v, pr);
            float w = 0.2 * L * (1.0 - a) * sqrt(clamp((a + 0.45) / 0.45, 0.0, 1.0));  // drop: round belly at a = -0.45, tail to a = 1
            float drop = (a > -0.45 && a < 1.0) ? step2(w - abs(bb), 0.0) : 0.0;
            vec2 s1 = v - ax * (-0.78 * L) - pr * 0.1 * L;
            float dots = step2(0.085 * L - length(s1), 0.0);
            vec2 s2 = v - ax * (-1.0 * L) + pr * 0.12 * L;
            dots = max(dots, step2(0.055 * L - length(s2), 0.0));
            col = mix(col, EsC4, max(drop, dots));
        }
    }
    fragColor = vec4(col.rgb, col.a * inside * vertexColor.a);
}
