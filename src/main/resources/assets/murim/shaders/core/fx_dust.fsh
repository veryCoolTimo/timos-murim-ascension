#version 150

// One puff of dust or smoke of the effect engine (block B): a camera-facing quad that gets a cel-drawn shape. Kinds (fxParams.w):
//   0 round clump: a union of a few big round lobes laid out from the puff's seed; the tones come from a pseudo sphere height of the lobes
//     (the light-facing cap is the lit patch, the far side the shade), so a clump shows a few big horn-shaped patches, as the reference does;
//     a short ink dash hugs the lit edges. Age tears big chunks out of it, back and below first (no petals).
//   1 hard chip, 3 ink arc, 4 streak, 5 droplet: flat thrown matter in the ink colour, with a pale rim so that it reads on a dark scene.
//   2 torn tongue of dust: wide where it leaves the body, thrown out to a swept-back point, edges torn into thorns of three sizes; white at the
//     tip, grey at the base so that it grows out of the dark body; no outline. Age shortens it and rounds the thorns off gradually.
// Targets: docs/design/reference/vfx-author-1113/plates dust-B1 (11.png), dust-B2 (8.png), dust-B3 (15.png), dust-A4 (2.png).
// The same file runs in tools/vfx-lab/dust_lab.py.
uniform vec4 DuLit;    // rgb of the lit tone, a = how much of the shape is lit (0..1)
uniform vec4 DuMid;    // rgb of the body tone, a = number of lobes of a clump (3..8)
uniform vec4 DuShade;  // rgb of the shade tone, a = how much of the shape is shade (0..1)
uniform vec4 DuInk;    // rgb of the contour and of thrown matter, a = contour opacity
uniform vec4 DuLight;  // xy = direction towards the light on screen (y up), z = contour breaks (0 whole .. 1 mostly missing), w = edge roughness
uniform vec4 DuExtra;  // x = time, y = ink dashes on the lit edges, z = pale rim of thrown matter (0..1), w = ragged bite of age

in vec2 texCoord0;
in vec4 vertexColor;
in vec4 fxParams;      // x seed, y erosion (0..1), z roll (a clump's layout turn; for every other kind the quad's angle on screen), w kind
in float fxTaper;

out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + 1.0), f.x), f.y);
}

const int MAXLOBES = 16;

// ---- round clump -------------------------------------------------------------------------------------------------------------------------------
// x = signed distance of the union (negative inside), y = distance to the lit patch, z = to the shade patch, w = distance to the nearest lobe outline.
// q is in puff radii; the layout turns with the roll, the light does not.
vec4 lobed(vec2 q, float seed, float erode, float roll, float lobes, vec2 L, float litK, float shadeK, float mass) {
    float d = 1e3, arc = 1e3, hTop = -1.0, nzTop = 0.0, rTop = 0.3;
    vec2 nTop = vec2(0.0);
    float c = cos(roll), s = sin(roll);
    for (int i = 0; i < MAXLOBES; i++) {
        float fi = float(i);
        if (fi >= lobes) break;
        float h1 = hash(vec2(seed * 17.0 + fi, 1.3)), h2 = hash(vec2(seed * 29.0 + fi, 5.1)), h3 = hash(vec2(seed * 41.0 + fi, 9.7));
        // lobe 0 sits in the middle and is the biggest; the others stand round it at their own distance and size.
        // A mass (big billowing clump, mass > 0) has many lobes of very different sizes, spread wider and piled up and to the light side.
        float ang = 6.2831853 * (fi / lobes + 0.3 * (h1 - 0.5)), dist = i == 0 ? 0.0 : mix(0.3 + 0.3 * h2, 0.1 + 0.5 * h2, mass);
        float r = i == 0 ? mix(0.5, 0.36, mass) : mix(0.28 + 0.16 * h3, 0.17 + 0.22 * h3 * h3 + 0.06 * h2, mass);
        // age takes the outer lobes a little (the tearing below does the real work)
        r *= 1.0 - smoothstep(0.5, 1.0, erode) * 0.25 * (i == 0 ? 0.5 : 1.0);
        vec2 ctr = vec2(cos(ang), sin(ang)) * dist;
        ctr = vec2(c * ctr.x - s * ctr.y, s * ctr.x + c * ctr.y);
        ctr.y += mass * 0.18 * (h1 - 0.35);   // piled up: more lobes stand above than below
        vec2 dv = q - ctr;
        // the edge of each lobe is scalloped: small bumps with cusps between them (cauliflower)
        float bumps = 7.0 + floor(h1 * 5.0), ph = h3 * 6.0;
        float aa0 = atan(dv.y, dv.x);
        float scallop = 1.0 - mix(0.11, 0.2, mass) * (1.0 - abs(sin(aa0 * bumps * 0.5 + ph))) * mix(1.0, 0.55 + 0.9 * noise(vec2(aa0 * 1.7 + ph, fi)), mass);
        float re = r * scallop;
        float dl = length(dv);
        float di = dl - re;
        d = min(d, di);
        arc = min(arc, abs(di));
        // pseudo sphere: the lobe with the highest surface here decides the normal
        float nz = sqrt(max(1.0 - (dl * dl) / (re * re), 0.0));
        float hgt = nz * re;
        if (hgt > hTop && di < 0.0) { hTop = hgt; nTop = dv / re; nzTop = nz; rTop = re; }
    }
    if (hTop < 0.0) return vec4(d, 1e3, 1e3, arc);
    // the light of this clump turns a little, so neighbours do not share one light
    float jit = (hash(vec2(seed, 31.0)) - 0.5) * 0.9, cj = cos(jit), sj = sin(jit);
    vec2 Lj = vec2(cj * L.x - sj * L.y, sj * L.x + cj * L.y);
    vec3 L3 = normalize(vec3(Lj * 0.78, 0.62));
    float sh = dot(vec3(nTop, nzTop), L3);
    // a mass is lit as ONE body: a large connected lit area on the light side, the shade on the far side; the lobes only bite into its edge
    float glob = dot(q, Lj) * 0.95 + 0.22 + (noise(q * 1.3 + seed * 2.0) - 0.5) * 0.7 + (noise(q * 3.4 + seed * 6.0) - 0.5) * 0.55 + (noise(q * 8.0 + seed * 4.0) - 0.5) * 0.2;
    sh = mix(sh, glob, mass * 0.75);
    // warped, so that the patch edges are horn-shaped and not circles
    sh += (noise(q * 1.7 + seed * 5.0) - 0.5) * 0.6 + (noise(q * 5.0 + seed * 3.0) - 0.5) * 0.16;
    float share = 0.7 + 0.6 * hash(vec2(seed, 21.0)) + mass * 0.35;
    float thrL = mix(0.95, 0.12, clamp(litK * share, 0.0, 1.0));
    float thrS = mix(-0.55, 0.45, clamp(shadeK, 0.0, 1.0));
    return vec4(d, (thrL - sh) * rTop * 0.7, (sh - thrS) * rTop * 0.7, arc);
}

// ---- billowing mass ----------------------------------------------------------------------------------------------------------------------------
// A big cumulus clump (panel 11): a few big lobes make the silhouette, many small lobes sit on them and along the rim. Every lobe is a little dome:
// its light-facing cap is cream, its far side pink, so cream and pink interleave all through the mass. All masses share the light L (no turn per clump),
// so neighbours blend without a seam. Returns the same as lobed(); w = distance to the visible, dashed outline of a small lobe (the ink squiggles).
const int NBIG = 6, NSMALL = 46;
void bigLobe(int i, float seed, float roll, out vec2 c, out float r) {
    float fi = float(i);
    float h1 = hash(vec2(seed * 17.0 + fi, 1.3)), h2 = hash(vec2(seed * 29.0 + fi, 5.1)), h3 = hash(vec2(seed * 41.0 + fi, 9.7));
    float ang = 6.2831853 * (fi / float(NBIG) + 0.3 * (h1 - 0.5)), dist = i == 0 ? 0.0 : 0.22 + 0.36 * h2;
    r = i == 0 ? 0.42 : 0.24 + 0.17 * h3;
    c = vec2(cos(ang), sin(ang)) * dist;
    c = vec2(cos(roll) * c.x - sin(roll) * c.y, sin(roll) * c.x + cos(roll) * c.y);
    c.y += 0.16 * (h1 - 0.35);
}
vec4 massed(vec2 q, float seed, float erode, float roll, vec2 L, float litK, float shadeK) {
    float d = 1e3, hTop = -1.0, rTop = 0.2, nzTop = 0.0, arc = 1e3, arcBase = -1.0;
    vec2 nTop = vec2(0.0);
    float shrink = 1.0 - smoothstep(0.5, 1.0, erode) * 0.22;
    vec2 cs[NBIG]; float rs[NBIG];
    for (int i = 0; i < NBIG; i++) {
        vec2 c; float r;
        bigLobe(i, seed, roll, c, r);
        r *= shrink; cs[i] = c; rs[i] = r;
        vec2 dv = q - c;
        float aa0 = atan(dv.y, dv.x);
        float re = r * (1.0 - 0.07 * (1.0 - abs(sin(aa0 * 4.0 + hash(vec2(seed, float(i))) * 6.0))));
        float dl = length(dv), di = dl - re;
        d = min(d, di);
        if (di < 0.0) {
            float nz = sqrt(max(1.0 - dl * dl / (re * re), 0.0)), hgt = nz * re;
            if (hgt > hTop) { hTop = hgt; nTop = dv / re; nzTop = nz; rTop = re; }
        }
    }
    for (int j = 0; j < NSMALL; j++) {
        float fj = float(j) + 3.0;
        float k1 = hash(vec2(seed * 13.0 + fj, 2.7)), k2 = hash(vec2(seed * 19.0 + fj, 6.1)), k3 = hash(vec2(seed * 23.0 + fj, 8.3)), k4 = hash(vec2(seed * 31.0 + fj, 3.9));
        int kb = int(floor(k1 * float(NBIG)));
        // most small lobes stand on the rim of a big one (they make the cauliflower edge), some inside it (they only shade)
        float ang = 6.2831853 * k2, fr = j < 20 ? mix(0.8, 1.02, k3) : mix(0.15, 0.7, k3);
        float r = (0.085 + 0.13 * k4 * k4 + 0.03 * k3) * shrink;
        vec2 c = cs[kb] + vec2(cos(ang), sin(ang)) * rs[kb] * fr;
        vec2 dv = q - c;
        float aa0 = atan(dv.y, dv.x);
        float re = r * (1.0 - 0.09 * (1.0 - abs(sin(aa0 * (3.0 + floor(k4 * 3.0)) + k2 * 6.0))));
        float dl = length(dv), di = dl - re;
        d = min(d, di);
        float base = 0.45 + 0.4 * k3;   // small lobes sit on the big ones, in an order of their own
        if (di < 0.0) {
            float nz = sqrt(max(1.0 - dl * dl / (re * re), 0.0)), hgt = base + nz * re;
            if (hgt > hTop) { hTop = hgt; nTop = dv / re; nzTop = nz; rTop = re; }
        }
        // an outline squiggle: a dash on the light side of the lobe, drawn only where no other lobe covers it
        float dash = smoothstep(0.3, 0.42, noise(vec2(aa0 * 1.1 + fj * 5.0, seed * 3.0 + fj)));
        float lightSide = smoothstep(-0.35, 0.3, dot(dv / max(dl, 1e-3), L));
        float ad = abs(di) + (1.0 - dash * lightSide) * 1.0;
        if (ad < arc) { arc = ad; arcBase = base; }
    }
    if (hTop < 0.0) return vec4(d, 1e3, 1e3, 1e3);
    if (arcBase < hTop - 0.09) arc = 1e3;
    vec3 L3 = normalize(vec3(L * 0.78, 0.62));
    float sh = dot(vec3(nTop, nzTop), L3);
    // a gentle shared bias: the top of the whole mass is lit a little more, its foot a little less; the noise is slow and shared
    float glob = dot(q, L) * 0.5 + (noise(q * 2.1 + seed * 2.0) - 0.5) * 0.35;
    sh += glob;
    sh += (noise(q * 3.6 + seed * 5.0) - 0.5) * 0.9 + (noise(q * 6.0 + seed * 3.0) - 0.5) * 0.7;
    float thrL = mix(1.3, 0.55, clamp(litK * 1.25, 0.0, 1.0));
    float thrS = mix(-0.55, 0.3, clamp(shadeK, 0.0, 1.0));
    return vec4(d, (thrL - sh) * rTop * 1.4, (sh - thrS) * rTop * 1.4, arc);
}

// ---- torn tongue -------------------------------------------------------------------------------------------------------------------------------
// wide where it leaves the body (x = -1), thrown out to a swept-back point (x = +1). Distance is approximate (good enough for tones and edge).
float tongue(vec2 q, float seed, float erode, float hook) {
    float bend = (hash(vec2(seed, 3.0)) - 0.5) * 0.9;
    q.y -= bend * 0.8 * (q.x + 1.0) * (q.x + 1.0);
    // a hooked tip: the last third curls to one side (hook is signed, 0 = none)
    float th = smoothstep(0.4, 1.0, (q.x + 1.0) * 0.5);
    q.y -= hook * 1.2 * th * th;
    float t = (q.x + 1.0) * 0.5;
    float life = 1.0 - 0.45 * smoothstep(0.35, 1.0, erode);
    // age eats the tip first: the tongue shortens from the tip towards its base
    float reach = mix(1.0, 0.3, smoothstep(0.1, 0.95, erode));
    float profile = 0.7 * pow(clamp(1.0 - t / reach, 0.0, 1.0), 0.8) * (0.4 + 0.6 * smoothstep(0.0, 0.15, t));
    float side = q.y >= 0.0 ? 1.0 : -1.0;
    float tt = t - abs(q.y) * 0.5;                 // the thorns lean towards the tip
    // three levels of thorns: a few big, some medium, many small chips; each side its own
    float k1 = 1.6 + 1.5 * hash(vec2(seed, 4.0 + side));
    float u1 = tt * k1 + hash(vec2(seed, 11.0 + side)) * 7.0;
    float saw1 = fract(u1);
    float big = 0.25 + 1.3 * pow(hash(vec2(floor(u1), seed * 3.0 + side)), 1.6);
    float saw2 = fract(tt * (5.0 + 3.0 * hash(vec2(seed, 8.0 + side))) + hash(vec2(seed, 13.0 + side)) * 5.0);
    float saw3 = fract(tt * 13.0 + hash(vec2(seed, 17.0 + side)) * 9.0);
    float teeth = 0.16 + big * pow(saw1, 1.7) + 0.4 * saw2 * (0.4 + saw1) + 0.18 * saw3 + 0.25 * (noise(vec2(tt * 7.0, seed * 5.0 + side)) - 0.4);
    // age rounds the thorns off: the sharp tongue melts into a lump that goes on moving
    teeth = mix(teeth, 0.85, 0.8 * smoothstep(0.15, 0.85, erode));
    float w = profile * teeth * life;
    float d = (abs(q.y) - w) * 0.8;
    d = max(d, (q.x - 1.0) * 0.8);
    d = max(d, (-1.0 - q.x) * 0.8);
    return d;
}

// ---- low bank of dust (panel 2) --------------------------------------------------------------------------------------------------------------------
// A segment of a bank that lies along the ground and is swept outward (+x): a flat ragged bottom, a low swelling body, and a saw-tooth top edge of flame
// teeth in three sizes. Every tooth is a ramp that climbs slowly from the inner side to a sharp tip on the outer side, then drops: it leans outward.
// Age shrinks the teeth first and then lets the body sink. With taper = 1 the segment is the thin tail at the outer end: the bank melts into a sliver.
float sawTooth(float f) { return f < 0.8 ? pow(f / 0.8, 2.6) : pow(1.0 - (f - 0.8) / 0.2, 1.7); }
float bankBottom(float x, float seed, float erode) {
    float shelf = sawTooth(fract(x * 3.1 + hash(vec2(seed, 5.0)) * 9.0)) * 0.07 + sawTooth(fract(x * 7.3 + hash(vec2(seed, 6.0)) * 9.0)) * 0.04;
    return -0.8 + shelf + 0.35 * smoothstep(0.55, 1.0, erode) * (0.5 + noise(vec2(x * 2.0, seed * 3.0)));
}
float bankTop(float x, float xe, float seed, float erode, float tail) {
    float t = clamp(x * 0.5 + 0.5, 0.0, 1.0);
    float body = -0.4 + 0.26 * sin(3.1416 * t) + 0.12 * (noise(vec2(x * 2.2 + seed * 5.0, seed)) - 0.5);
    float shrink = 1.0 - 0.95 * smoothstep(0.12, 0.8, erode);
    float teeth = 0.0;
    for (int L = 0; L < 4; L++) {
        float fl = float(L);
        float k = L == 3 ? 14.0 + 4.0 * hash(vec2(seed, 9.0)) : L == 0 ? 0.36 + 0.26 * hash(vec2(seed, 2.0)) : (L == 1 ? 1.4 + 0.6 * hash(vec2(seed, 3.0)) : 4.5 + 1.4 * hash(vec2(seed, 4.0)));
        float amp = L == 0 ? 1.3 : (L == 1 ? 0.75 : (L == 2 ? 0.3 : 0.12));
        float thr = L == 0 ? 0.3 : (L == 1 ? 0.18 : (L == 2 ? 0.05 : 0.0));
        float u = x * k + hash(vec2(seed, 7.0 + fl)) * 9.0, i = floor(u);
        float on = step(thr, hash(vec2(i + fl * 17.0, seed * 3.0 + fl)));
        float hgt = 0.55 + 0.45 * hash(vec2(i * 1.7 + fl, seed * 5.0 + fl * 3.0));
        teeth = max(teeth, amp * hgt * on * sawTooth(fract(u)));
    }
    float top = body + teeth * shrink + (noise(vec2(x * 23.0 + seed * 11.0, seed)) - 0.5) * 0.08;
    float bottom = bankBottom(x, seed, erode);
    // the tail: the bank melts into a thin sliver that lies on the ground
    top = mix(top, bottom + 0.05 + 0.06 * sawTooth(fract(x * 4.0 + seed * 9.0)), tail * smoothstep(0.1, 0.9, t));
    // both ends run out into the ground; the outer end more gently
    top = mix(top, bottom + 0.02, smoothstep(0.7, 1.0, xe) * (1.0 - 0.5 * tail));
    top = mix(top, bottom + 0.02, smoothstep(0.86, 1.0, -xe));
    return top;
}

// ---- thrown matter -----------------------------------------------------------------------------------------------------------------------------
float matterD(vec2 p, float kind, float seed) {
    float n = noise(p * 7.0 + seed * 9.0);
    float d;
    if (kind < 1.5) {
        // a chip: a flat sliver, shaped by its seed
        float skew = (hash(vec2(seed, 2.0)) - 0.5) * 0.8;
        vec2 a = abs(p);
        d = ((a.x + a.y * (1.2 + skew * sign(p.x * p.y)) * 2.2) - 0.9) * 0.5;
    } else if (kind < 3.5) {
        // an ink arc: a band round a far centre, swelling in the middle and ending in two points; ragged like a dry brush
        vec2 v = p - vec2(0.0, -1.45);
        float r = length(v), a = atan(v.x, v.y), amax = 0.62;
        float u = clamp(a / amax, -1.0, 1.0);
        float w = 0.13 * pow(max(1.0 - u * u, 0.0), 0.7) * (0.65 + 0.7 * noise(vec2(a * 9.0, seed * 5.0)));
        d = max(abs(r - 1.75) - w, (abs(a) - amax) * 1.5);
    } else if (kind < 4.5) {
        // a streak: a long spindle, head forward (+x) a little fatter than the tail, bent, with a ragged edge
        float t = clamp(p.x * 0.5 + 0.5, 0.0, 1.0);
        float bend = (hash(vec2(seed, 6.0)) - 0.5) * 0.5;
        float yy = p.y - bend * (p.x * p.x - 0.3);
        float w = 0.6 * pow(max(sin(3.14159 * pow(t, 0.75)), 0.0), 0.9) * (0.7 + 0.6 * noise(vec2(p.x * 5.0, seed * 7.0)));
        d = max(abs(yy) - w, abs(p.x) - 1.0) * 0.7;
    } else {
        // a droplet or a blot: a lumpy ellipse
        float a = atan(p.y, p.x);
        float rad = 0.75 + 0.12 * sin(a * 3.0 + seed * 40.0) + 0.06 * sin(a * 5.0 + seed * 71.0);
        d = (length(p * vec2(0.95, 1.1)) - rad) * 0.9;
    }
    return d + (n - 0.5) * (kind < 1.5 ? 0.0 : 0.16);
}

void main() {
    vec2 p = texCoord0 * 2.0 - 1.0;
    float seed = fxParams.x * 61.0, erode = fxParams.y, roll = fxParams.z;
    float kind = fxParams.w;
    bool matter = kind > 0.5 && kind < 1.5 || kind > 2.5 && kind < 5.5;
    if (matter) {
        float d = matterD(p, kind, seed);
        float aa = max(fwidth(d), 1e-4);
        float alpha = (1.0 - smoothstep(-aa, aa, d)) * vertexColor.a * (1.0 - smoothstep(0.7, 1.0, erode));
        if (alpha < 0.01) discard;
        // a pale rim some two pixels wide so that black matter reads on a dark night scene
        float rim = DuExtra.z * (1.0 - smoothstep(0.0, 1.0, -d / (aa * 2.2)));
        fragColor = vec4(mix(DuInk.rgb, DuLit.rgb, rim) * vertexColor.rgb, alpha);
        return;
    }
    float px = max(fwidth(p.x), 1e-4);
    if (kind > 5.5) {
        // a bank segment. The quad is turned along its heading; when that points to the left the quad is upside down, so turn it back
        vec2 b = p / 0.82;
        if (cos(roll) < 0.0) b.y = -b.y;
        // the teeth lean outward: the higher a point, the farther along the flow the profile is read from
        float xs = b.x - 0.6 * max(b.y + 0.8, 0.0);
        float top = bankTop(xs, b.x, seed, erode, fxTaper), bottom = bankBottom(b.x, seed, erode);
        float dB = max(b.y - top, bottom - b.y);
        float dpx = dB / max(fwidth(dB), 1e-5);
        float cover = 1.0 - smoothstep(-0.7, 0.7, dpx);
        // tones: pale body, a few chunky streaks of the middle grey slanting with the flow, small dark patches, a dark ledge along the bottom
        float s1 = noise(vec2((b.x - b.y * 1.7) * 2.2 + seed * 9.0, b.y * 2.0 + b.x * 0.5)) * 0.7 + noise(vec2((b.x - b.y * 1.7) * 5.0 + seed * 3.0, b.y * 4.0 + b.x)) * 0.3;
        float s2 = noise(vec2((b.x - b.y * 1.7) * 3.0 + seed * 5.0, b.y * 3.0 + b.x * 0.6 + 3.0));
        float tm = (s1 - mix(0.72, 0.5, 1.0 - DuLit.a)) / max(fwidth(s1), 1e-5);
        float ts = (s2 - mix(0.86, 0.66, DuShade.a)) / max(fwidth(s2), 1e-5);
        float ledge = (bottom + 0.1 + 0.05 * noise(vec2(b.x * 6.0, seed * 7.0)) - b.y) / max(fwidth(b.y), 1e-5);
        float mid = smoothstep(-0.7, 0.7, tm);
        float shd = smoothstep(-0.7, 0.7, ts) * mid;
        float low = smoothstep(-0.7, 0.7, ledge) * 0.55;
        vec3 c = DuLit.rgb;
        c = mix(c, DuMid.rgb, max(mid, low));
        c = mix(c, DuShade.rgb, max(shd, low * mid));
        float a = cover * vertexColor.a * (1.0 - smoothstep(0.9, 1.0, erode));
        if (a < 0.004) discard;
        fragColor = vec4(c * vertexColor.rgb, a);
        return;
    }
    // tail: the trailing side (negative x of the quad, the quad is turned along the flow) tapers into a point
    float tail = smoothstep(0.1, -1.0, p.x);
    
    // the puff is drawn into 0.82 of its quad so that the edge never touches the quad's edge
    vec2 q = p / 0.82;
    float mass = kind < 0.5 ? clamp(fxTaper, 0.0, 1.0) : 0.0;
    float lobes = clamp(floor(DuMid.a + 0.5 + (hash(vec2(seed, 7.0)) - 0.5) * 2.0 + mass * 8.0), 3.0, float(MAXLOBES));
    vec2 L = normalize(DuLight.xy);
    float rough = (noise(q * 5.0 + seed) - 0.5) * 0.08 * DuLight.w + (noise(q * 11.0 + seed * 2.0) - 0.5) * 0.03 * DuLight.w;
    float aa = px * 1.4 / 0.82;
    float d, litD, shadeD, arcD = 1e3;
    if (kind > 1.5) {
        // a tongue; its quad is turned by the roll, so the light is turned the other way into the tongue's own axes
        float ca = cos(roll), sa = sin(roll);
        vec2 Ll = vec2(ca * L.x + sa * L.y, -sa * L.x + ca * L.y);
        d = tongue(q, seed, erode, fxTaper);
        float t = (q.x + 1.0) * 0.5;
        float sT = (t - 0.5) * 1.1 + dot(Ll, q) * 0.3 + (noise(q * 2.4 + seed * 5.0) - 0.5) * 0.6 + (noise(q * 6.0 + seed) - 0.5) * 0.2;
        litD = (mix(0.15, -0.35, DuLit.a) - sT) * 0.3;
        shadeD = (sT - mix(-0.9, -0.3, DuShade.a)) * 0.3;
    } else {
        vec4 sh = mass > 0.05 ? massed(q, seed, erode, roll, L, DuLit.a, DuShade.a) : lobed(q, seed, erode, roll, lobes, L, DuLit.a, DuShade.a, mass);
        d = sh.x; litD = sh.y; shadeD = sh.z; arcD = sh.w;
    }
    // age tears big pieces out: the back and the bottom first; the cut edges are rough. (Tongues lose pieces too, not only width.)
    {
        float tearF = noise(q * 1.5 + seed * 3.0) * 0.55 + noise(q * 3.3 + seed * 7.0) * 0.25 + 0.2 * (0.5 + 0.5 * q.y)
                + (noise(q * 14.0 + seed) - 0.5) * 0.08 * DuExtra.w;
        float thr = erode * 1.15 - 0.12;
        if (mass > 0.05) {
            // a mass thins from its edge inwards: the bite never reaches far into the body, so no hole opens inside it
            float bite = clamp((thr - tearF) * 0.5, 0.0, 1.0);
            d += bite * 0.9;
        } else d = max(d, (thr - tearF) * 0.5);
    }
    d += rough + mass * ((noise(q * 6.5 + seed * 7.0) - 0.5) * 0.04 + (noise(q * 14.0 + seed * 3.0) - 0.5) * 0.025);   // a mass has a bumpy rim
    float inside = 1.0 - smoothstep(-aa, aa, d);
    if (inside < 0.003) discard;
    float litT = litD + rough, shadeT = shadeD + rough;
    if (mass > 0.05) {
        // a mass: tone edges keep a pixel's width wherever the tone changes slowly (the distance is divided by its own gradient), so they stay crisp
        litT = litT / max(fwidth(litT), 1e-5) * px / 0.82;
        shadeT = shadeT / max(fwidth(shadeT), 1e-5) * px / 0.82;
    }
    float lit = (1.0 - smoothstep(-aa, aa, litT)) * inside;
    float shade = (1.0 - smoothstep(-aa, aa, shadeT)) * inside * (1.0 - lit);
    vec3 col = DuMid.rgb;
    col = mix(col, DuShade.rgb, shade);
    col = mix(col, DuLit.rgb, lit);
    if (kind < 0.5) {
        // ink: a broken thin line along the outer edge, and short dashes hugging the lit patches and the lobe outlines (hand-drawn squiggles)
        float ang = atan(q.y, q.x);
        float kept = smoothstep(DuLight.z - 0.2, DuLight.z + 0.12, noise(vec2(ang * 2.3 + seed * 3.0, seed * 1.7 + length(q) * 2.0)));
        float cw = aa * 1.5;
        float outer = (1.0 - smoothstep(cw * 0.6, cw * 1.6, abs(d + cw * 0.9))) * kept;
        float arcs = (1.0 - smoothstep(cw * 0.3, cw * 0.9, arcD)) * smoothstep(0.0, aa * 3.0, -d)
                * (mass > 0.05 ? DuExtra.y * 2.0 : smoothstep(0.55, 0.62, noise(q * 9.0 + seed * 9.0)) * DuExtra.y * 0.6);
        // a mass: the squiggle keeps one pixel width wherever the tone changes slowly (a distance divided by its own gradient)
        float litBand = mass > 0.05 ? abs(litD + rough) / max(fwidth(litD), 1e-5) * px / aa * 1.4 : abs(litD + rough);
        float litEdge = (1.0 - smoothstep(cw * 0.3, cw * 1.0, litBand)) * smoothstep(0.0, aa * 3.0, -d)
                * smoothstep(0.28, 0.38, noise(q * (11.0 - mass * 2.0) + seed * 4.0)) * DuExtra.y * 2.0;
        float line = max(outer, max(arcs, min(litEdge, 1.0)));
        col = mix(col, DuInk.rgb, line * DuInk.a);
    }
    // the very end of life: what is left thins out
    float alpha = inside * vertexColor.a * (1.0 - smoothstep(0.9, 1.0, erode));
    if (alpha < 0.004) discard;
    fragColor = vec4(col * vertexColor.rgb, alpha);
}
