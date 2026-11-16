#version 150

// One brush stroke of the effect engine (block A): a ribbon mesh gives u along the stroke (0 tail .. 1 head) and v across (0..1),
// this shader paints the stroke inside it: a taper with uneven thickness, a ragged dry-brush edge, layered colour from the core out
// to a dark contour, thin companion strands beside the main line, a drawing head and an erosion fade.
// Targets: docs/design/reference/vfx-author-1113/atlas (A1, A2, A5 light strokes; A6, A8 ink strokes).
// The same file runs in tools/vfx-lab/stroke_lab.py, which feeds the same inputs.
uniform float FxTime;
uniform vec4 FxCore;   // rgb, a = outer edge of the core band in stroke radii (0..1)
uniform vec4 FxBody;   // rgb, a = outer edge of the body band
uniform vec4 FxRim;    // rgb, a = outer edge of the rim band; the rest up to 1 is the contour
uniform vec4 FxInk;    // rgb of the contour, a = its opacity
uniform vec4 FxShape;  // x ragged edge, y dry ends, z companion strands, w thickness wobble
uniform vec4 FxExtra;  // x contour breaks (0 whole .. 1 mostly missing), y belly: extra width in the middle, z soft bands (0 crisp steps .. 1 one gradient),
                       // w boil: the thickness pattern jumps with FxTime (drawn animation redraws the line each step)
uniform vec4 FxMove;   // x clots: thick drops that travel along the stroke, y their speed, z their spacing (in half-widths), w shimmer of the centre line
uniform vec4 FxSoft;   // x halo: the stroke fades from its centre line to nothing at its edge (a wide soft light under a crisp stroke),
                       // y strand gap: 1 = companion strands stand off the stroke, 0 = they hug its edge and read as its torn fringe,
                       // z slide: extra tail taper (share of the stroke) once the stroke slides off along itself (tail > 0); 0 = none,
                       // w thorn: sharp triangular spikes pulled out of the edge (0 = none)
uniform vec4 FxFlow;   // x noise flow along the stroke per second, y tail length, z head length, w main width share of the mesh

in vec2 texCoord0;
in vec4 vertexColor;
in vec4 fxParams;      // x seed, y drawing head (0..1), z erosion (0..1), w stroke length in mesh half-widths
in float fxTail;       // where the stroke begins now (0..1)

out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + 1.0), f.x), f.y);
}
float fbm(vec2 p) { return 0.55 * noise(p) + 0.3 * noise(p * 2.07 + 3.1) + 0.15 * noise(p * 4.3 + 7.7); }

void main() {
    float u = texCoord0.x, v = texCoord0.y * 2.0 - 1.0;
    float seed = fxParams.x * 61.0, head = min(fxParams.y, 1.0), erode = fxParams.z, s = u * fxParams.w;
    if (u > head || u < fxTail) discard;
    float span = max(head - fxTail, 0.02);
    float tailLen = (FxFlow.y + FxSoft.z * smoothstep(0.0, 0.03, fxTail)) * span;
    float tailFade = smoothstep(0.0, tailLen, u - fxTail), headFade = smoothstep(0.0, FxFlow.z * span, head - u);
    float prof = pow(tailFade, 0.8) * pow(headFade, 0.55);
    // uneven thickness: slow swell along the stroke, never a constant-width band
    float taper = prof;
    prof *= mix(1.0, 0.35 + 1.25 * noise(vec2(s * 0.21 + seed + FxExtra.w * FxTime * 3.7, seed * 0.37)), FxShape.w);
    // belly: the stroke swells where the blow lands, between the two tips
    prof *= 1.0 + FxExtra.y * exp(-pow((u - fxTail - 0.5 * span) / (0.09 * span), 2.0));
    // clots: energy leaves in drops. Each cell along the stroke may carry one drop of its own size; the cells run from tail to head
    if (FxMove.x > 0.0) {
        float phase = s / max(FxMove.z, 0.5) - FxTime * FxMove.y + seed * 7.0;
        float size = hash(vec2(floor(phase), seed * 3.1)), along = fract(phase);
        float drop = smoothstep(0.0, 0.3, along) * (1.0 - smoothstep(0.5, 1.0, along)) * step(0.3, size) * size;
        prof *= 1.0 + FxMove.x * drop * headFade;
    }
    // never wider than the ribbon: a stroke that reaches the mesh edge is cut off in a straight line (the halo showed as a box)
    float main = min(FxFlow.w * prof, 0.97);
    float centre = (noise(vec2(s * 0.13 + seed + 7.0, 1.7)) - 0.5) * 0.16 * FxShape.w * FxFlow.w;
    // shimmer: the line never stands still, a slow wave runs along it
    centre += (noise(vec2(s * 0.19 - FxTime * FxMove.y * 0.45 + seed * 2.0, FxTime * 0.35 + seed)) - 0.5) * FxMove.w * min(FxFlow.w, 1.0 - FxFlow.w);
    // as it dies the stroke is pushed about by slow turbulence (it bends and drifts before it breaks)
    float turb = erode * erode;
    centre += (fbm(vec2(s * 0.085 + seed * 4.0, FxTime * 0.9 + seed)) - 0.5) * 1.5 * turb * min(FxFlow.w, 1.0 - FxFlow.w);
    float off = v - centre;
    float d = abs(off) / max(main, 0.0005);
    // brush fibres run along the stroke: stretched noise pushes the edge in and out
    float flow = FxTime * FxFlow.x;
    float fibre = fbm(vec2(s * 0.55 - flow, off * 9.0 / max(FxFlow.w, 0.05) + seed));
    float fine = noise(vec2(s * 2.3 - flow * 1.7, off * 26.0 / max(FxFlow.w, 0.05) + seed * 1.3));
    d += ((fibre - 0.5) * 1.1 + (fine - 0.5) * 0.35) * FxShape.x * (0.35 + 0.65 * min(d, 1.0));
    // thorns: sharp triangular spikes pulled out of the edge, each on one side only, each with its own length
    if (FxSoft.w > 0.0) {
        float ts = s * 0.35 + seed * 17.0, cell = floor(ts), f = fract(ts);
        float h = hash(vec2(cell, seed)), side = hash(vec2(cell + 0.5, seed * 1.7)) > 0.5 ? 1.0 : -1.0;
        float spike = max(0.0, 1.0 - abs(f - 0.3) * 9.0);
        d -= FxSoft.w * spike * h * step(0.0, side * off) * step(0.4, h) * 3.0;
    }
    // dry ends: towards both tips the stroke splits into separate hairs
    float endness = 1.0 - min(smoothstep(0.0, tailLen * 1.6, u - fxTail), smoothstep(0.0, FxFlow.z * 1.9 * span, head - u));
    float hair = noise(vec2(off * 34.0 / max(FxFlow.w, 0.05) + seed * 2.1, s * 0.35 - flow));
    float dry = smoothstep(endness * FxShape.y - 0.12, endness * FxShape.y + 0.04, hair + (1.0 - endness) * 0.6);
    // dissolve in fractal noise: big slow shapes break the stroke into long pieces, a middle octave tears their edges and the brush
    // fibres fray them; the pattern itself keeps moving, and the edge of the stroke goes before its middle
    // the big shapes are counted per stroke, not per block: any stroke, long or short, breaks into five or six pieces
    float big = noise(vec2(u * 5.5 + seed * 13.0 - flow * 0.12, FxTime * 0.45 + seed * 9.0));
    float mid = noise(vec2(u * 15.0 + seed * 5.0 - flow * 0.3, off * 2.2 / max(FxFlow.w, 0.05) + FxTime * 0.7));
    // a ramp along the stroke joins the noise: the oldest part, the tail, tends to go first, and no seed can
    // make the whole stroke vanish in one step
    float hole = 0.4 * big + 0.2 * mid + 0.1 * fibre + 0.3 * (u - fxTail) / span - 0.05 * min(d, 1.0);
    // the sum lives between about 0.1 and 0.9: the threshold crosses that range evenly, so pieces go one after another
    float edge = mix(0.06, 0.94, erode);
    float alive = smoothstep(edge - 0.03, edge + 0.008, hole) * step(erode, 0.999) + step(erode, 0.0005);
    alive = min(alive, 1.0);
    // the burning front: where the stroke is about to go it flares to its core colour
    float front = alive * (1.0 - smoothstep(edge + 0.008, edge + 0.05, hole)) * step(0.001, erode);
    float aa = max(fwidth(d) * 1.2, 0.01);
    float inside = (1.0 - smoothstep(1.0 - aa, 1.0, d)) * dry * alive;
    vec3 col = FxCore.rgb;
    // soft bands: the steps between the colours widen into a gradient, and the band edges drift along the stroke
    float soft = aa + FxExtra.z * 0.3, drift = (noise(vec2(s * 0.31 + seed * 2.0, 4.4)) - 0.5) * 0.22 * FxExtra.z;
    col = mix(col, FxBody.rgb, smoothstep(FxCore.a + drift - soft, FxCore.a + drift + soft, d));
    col = mix(col, FxRim.rgb, smoothstep(FxBody.a + drift - soft, FxBody.a + drift + soft, d));
    col = mix(col, FxCore.rgb, front * 0.55);
    float contour = smoothstep(FxRim.a - aa, FxRim.a + aa, d) * (1.0 - front);
    col = mix(col, FxInk.rgb, contour);
    // the contour is broken: it comes and goes along the stroke and is thinner in places, never an even outline
    float kept = smoothstep(FxExtra.x - 0.25, FxExtra.x + 0.1, noise(vec2(s * 0.17 + seed * 5.0, sign(off) * 3.0 + 9.0)));
    float alpha = inside * mix(1.0, FxInk.a * kept, contour);
    // companion strands: thin broken lines beside the main stroke, each with its own length and thickness
    if (FxShape.z > 0.0) {
        for (int k = 0; k < 4; k++) {
            float side = (k < 2) ? 1.0 : -1.0, fk = float(k);
            // at gap 0 the strands start just inside the ragged edge, so no clear air shows between stroke and fringe
            float gap = main * (0.9 + 0.1 * mod(fk, 2.0) + FxSoft.y * (0.35 + 0.45 * mod(fk, 2.0)))
                    + FxFlow.w * (0.03 * hash(vec2(seed, fk)) + FxSoft.y * (0.1 + 0.19 * hash(vec2(seed, fk))));
            float lane = centre + side * gap + (noise(vec2(s * 0.3 + fk * 9.0, seed)) - 0.5) * 0.05;
            float run = noise(vec2(s * 0.09 + seed + fk * 13.0, 3.3));
            float on = smoothstep(0.52, 0.62, run) * prof;
            float w = FxFlow.w * 0.07 * (0.35 + run) * FxShape.z;
            float sd = abs(v - lane) / max(w, 0.0005);
            float saa = max(fwidth(sd) * 1.2, 0.02);
            float strand = (1.0 - smoothstep(1.0 - saa, 1.0, sd)) * on * alive;
            vec3 sc = mix(FxBody.rgb, FxRim.rgb, smoothstep(0.3, 0.9, sd));
            col = mix(col, sc, strand * (1.0 - alpha));
            alpha = max(alpha, strand);
        }
    }
    alpha *= mix(1.0, pow(max(1.0 - d, 0.0), 2.0), FxSoft.x);
    // soft looks thin out towards their tips instead of ending in a solid bar
    alpha *= mix(1.0, smoothstep(0.0, 0.85, taper), FxExtra.z);
    alpha *= vertexColor.a;
    if (alpha < 0.004) discard;
    fragColor = vec4(col * vertexColor.rgb, alpha);
}
