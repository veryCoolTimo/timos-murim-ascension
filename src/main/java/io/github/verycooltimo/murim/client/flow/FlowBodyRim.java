package io.github.verycooltimo.murim.client.flow;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.client.vfx.MurimRenderTypes;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.RenderLayerParent;
import net.minecraft.client.renderer.entity.layers.RenderLayer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The body lamp of the high pill game (director's brief, section A; refs «absorbing pill» and «demonic essence 5.49.29»): the player's model is a
 * dark-toned LIT silhouette.
 * <ol>
 * <li><b>tint</b>: the model drawn once more, alpha-blended and textured, in the DEEP tone of the pill colour (value {@link FlowLook#DEEP_VALUE}): the
 * model's own colours are pulled toward it by {@code tint} (55 % at the start); no pale wash. Head and hat are left alone (the face is never touched).</li>
 * <li><b>rim</b>: an inflated hull of the model (every vertex pushed along the smoothed normal of the faces that meet there) of which only the faces
 * turned AWAY from the camera are drawn, additively, in the scene's pass ({@link #drawHull}) with the scene's depth: the model hides them except where
 * they stick out past its silhouette, so a crisp outline of 1 and 2.4 cm lies on every outer edge of torso, arms, the top of the legs and the crown.
 * Two layers: a hot inner line (pill colour to white) and a wider outer line in the pill colour. The head is masked below the chin.</li>
 * <li><b>surface</b>: the old Fresnel glow, now weak, plus a small inner glow on torso and arms.</li>
 * </ol>
 * API: ModelPart#render(PoseStack, VertexConsumer, int, int, int colour), RenderType#entityTranslucent / #eyes, VertexConsumer#addVertex(FFFIFFIIFFF) as
 * ModelPart.Cube#compile calls it (camera-relative positions, unit world normals); the hull replay follows BtBodyRim (branch fix/breakthrough-vfx-1114).
 */
public final class FlowBodyRim extends RenderLayer<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> {
    /** The lamp this frame: lamp strength 0..1 (ease in/out, dips), tint share, rim gain, inner glow, pill colour. */
    public record State(float tint, float rim, float inner, float r, float g, float b) {
        public static final State NONE = new State(0, 0, 0, 0, 0, 0);
        boolean off() { return tint <= .002F && rim <= .002F && inner <= .002F; }
    }

    /** Recorded hull quads of this frame: 4 camera-relative corners pushed (outer layer), 4 corners pushed (inner layer), weight. */
    private static final List<float[]> HULL = new ArrayList<>();
    private static State hullState = State.NONE;
    private static final int FULL_BRIGHT = 15728880;
    private static int dbg;
    private static long hullFirst;
    private static String lastMask = "";

    /** Which parts take a rim: h head, t torso, a arms, l legs. Dev: MURIM_FLOWFX_HULL=cycle walks through them every 3 s (finding the part that shows through). */
    private static String hullMask() {
        String e = System.getenv("MURIM_FLOWFX_HULL");
        if (e == null) return "ta";   // the head part records its edges one head lower than the head is drawn (seen in the film): no head rim; the legs read as a wire cage: none
        if (e.equals("cycle2")) { if (hullFirst == 0) hullFirst = System.nanoTime(); String[] sq = {"h", "t", "a", "ta", "hta"}; return sq[(int) (((System.nanoTime() - hullFirst) / 2_500_000_000L) % sq.length)]; }
        if (!e.equals("cycle")) return e;
        if (hullFirst == 0) hullFirst = System.nanoTime();
        String[] seq = {"h", "t", "a", "l", "hta"};
        return seq[(int) (((System.nanoTime() - hullFirst) / 3_000_000_000L) % seq.length)];
    }

    public FlowBodyRim(RenderLayerParent<AbstractClientPlayer, PlayerModel<AbstractClientPlayer>> parent) {
        super(parent);
    }

    @Override
    public void render(PoseStack pose, MultiBufferSource buffers, int light, AbstractClientPlayer player, float limbSwing,
                       float limbSwingAmount, float partialTick, float ageInTicks, float yaw, float pitch) {
        HULL.clear();
        if (player != Minecraft.getInstance().player || player.isInvisible()) return;
        State st = FlowMeridianFx.lamp();
        hullState = st;
        if (st.off() && lampK <= .002F) return;
        float bodyYaw = Mth.rotLerp(partialTick, player.yBodyRotO, player.yBodyRot) * Mth.DEG_TO_RAD;
        float fx = -Mth.sin(bodyYaw), fz = Mth.cos(bodyYaw);
        PlayerModel<AbstractClientPlayer> model = getParentModel();
        var tex = getTextureLocation(player);
        // 1. the tint: the model's colours pulled toward the deep tone (alpha = the share), full bright so it is not darkened twice
        if (st.tint() * (1 - lampK) > .002F) {
            int a = Math.max(0, Math.min(255, Math.round(st.tint() * (1 - lampK) * 255)));
            int rgb = to255(st.r() * FlowLook.DEEP_VALUE) << 16 | to255(st.g() * FlowLook.DEEP_VALUE) << 8 | to255(st.b() * FlowLook.DEEP_VALUE);
            VertexConsumer t = buffers.getBuffer(RenderType.entityTranslucent(tex));
            for (ModelPart part : new ModelPart[] {model.body, model.rightArm, model.leftArm, model.jacket, model.rightSleeve, model.leftSleeve}) {
                part.render(pose, t, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, a << 24 | rgb);
            }
            int al = Math.round(a * FlowLook.TINT_LEG_SHARE);
            for (ModelPart part : new ModelPart[] {model.rightLeg, model.leftLeg, model.rightPants, model.leftPants}) {
                part.render(pose, t, FULL_BRIGHT, OverlayTexture.NO_OVERLAY, al << 24 | rgb);
            }
        }
        // 2. the surface glow (weak Fresnel + the small inner glow)
        VertexConsumer out = buffers.getBuffer(RenderType.eyes(tex));
        model.head.render(pose, new Wrap(out, st, fx, fz, true), light, OverlayTexture.NO_OVERLAY);
        Wrap body = new Wrap(out, st, fx, fz, false);
        for (ModelPart part : new ModelPart[] {model.body, model.rightArm, model.leftArm}) part.render(pose, body, light, OverlayTexture.NO_OVERLAY);
        // 2b. the body as a lamp (end of the game): light spreads out of the dantian over the whole model, edges brightest, face and legs too
        if (lampK > .002F && lampAt != null) {
            Vec3 cam = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
            // a flat pale glow over the whole silhouette first (the model's dark cloth would not light through its own texture), then the textured lamp
            LampWrap flat = new LampWrap(buffers.getBuffer(RenderType.eyes(WHITE)), (float) (lampAt.x - cam.x), (float) (lampAt.y - cam.y), (float) (lampAt.z - cam.z), lampR, lampK * .4F, lampTone);
            for (ModelPart part : new ModelPart[] {model.body, model.jacket, model.rightArm, model.leftArm, model.rightSleeve, model.leftSleeve, model.rightLeg, model.leftLeg, model.rightPants, model.leftPants})
                part.render(pose, flat, light, OverlayTexture.NO_OVERLAY);
            LampWrap lw = new LampWrap(buffers.getBuffer(RenderType.eyes(tex)), (float) (lampAt.x - cam.x), (float) (lampAt.y - cam.y), (float) (lampAt.z - cam.z), lampR, lampK, lampTone);
            for (ModelPart part : new ModelPart[] {model.head, model.hat, model.body, model.jacket, model.rightArm, model.leftArm, model.rightSleeve, model.leftSleeve,
                    model.rightLeg, model.leftLeg, model.rightPants, model.leftPants}) {
                if (!lampFace && (part == model.head || part == model.hat)) continue;
                part.render(pose, lw, light, OverlayTexture.NO_OVERLAY);
            }
        }
        // 3. the hull quads, recorded here and replayed in the scene's pass
        if (st.rim() > .002F) {
            Rec rec = new Rec(fx, fz);
            String m = hullMask();
            if (!m.equals(lastMask)) { lastMask = m; io.github.verycooltimo.murim.MurimMod.LOGGER.info("Flow rim mask {}", m); }
            if (m.indexOf('h') >= 0) rec.part(model.head, pose, light, true);
            // the base parts and the outer layers (jacket, sleeves, pants): the outer layer is the outermost surface, its strips are the ones that show
            if (m.indexOf('t') >= 0) { rec.verticalOnly = true; rec.part(model.body, pose, light, false); rec.part(model.jacket, pose, light, false); rec.verticalOnly = false; }
            if (m.indexOf('a') >= 0) { for (ModelPart q : new ModelPart[] {model.rightArm, model.leftArm, model.rightSleeve, model.leftSleeve}) rec.part(q, pose, light, false); }
            if (m.indexOf('l') >= 0) { rec.weight = FlowLook.RIM_LEG_SHARE; for (ModelPart q : new ModelPart[] {model.rightLeg, model.leftLeg, model.rightPants, model.leftPants}) rec.part(q, pose, light, false); rec.weight = 1; }
        }
    }

    private static int to255(float v) { return Math.max(0, Math.min(255, Math.round(v * 255))); }

    /** Fresnel edge term 0..1: 0 facing the camera, 1 turned away. */
    private static float edge(float x, float y, float z, float nx, float ny, float nz) {
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        float facing = len < 1e-5F ? 1 : Math.abs(nx * x + ny * y + nz * z) / len;
        float e = 1 - facing;
        return e * e * e;
    }

    /**
     * Collects the quads of one part and turns its SILHOUETTE EDGES into rim strips: an edge is a silhouette edge when exactly one of the two faces that
     * share it looks at the camera. A strip lies in the plane of the camera-facing face (a hair nearer to the camera), from a little outside the edge to a
     * little inside it, so it needs no depth trick: it sits on the model's own surface and beyond the outline. Two nested strips per edge (wide pill colour,
     * narrow hot white). The camera-facing face of the head (the face) takes none.
     */
    private static final class Rec implements VertexConsumer {
        float weight = 1;
        /** The torso takes only its two side edges: no bar over the neck line, none across the lap. */
        boolean verticalOnly;
        int bottom = -1;
        final float fx, fz;
        final List<float[]> quads = new ArrayList<>();
        final float[] cur = new float[15];
        int corner;

        Rec(float fx, float fz) { this.fx = fx; this.fz = fz; }

        private static float facing(float[] q) {
            float cx = (q[0] + q[3] + q[6] + q[9]) / 4, cy = (q[1] + q[4] + q[7] + q[10]) / 4, cz = (q[2] + q[5] + q[8] + q[11]) / 4;
            float len = (float) Math.sqrt(cx * cx + cy * cy + cz * cz);
            return len < 1e-5F ? 0 : (q[12] * cx + q[13] * cy + q[14] * cz) / len;   // < 0: looks at the camera
        }

        void part(ModelPart part, PoseStack pose, int light, boolean head) {
            quads.clear();
            corner = 0;
            part.render(pose, this, light, OverlayTexture.NO_OVERLAY);
            if (dbg % 100 == 1) io.github.verycooltimo.murim.MurimMod.LOGGER.info("Flow rim part quads={} head={} strips so far={}", quads.size(), head, HULL.size());
            // the quad lowest in the world is the underside (the chin of the head): found by its place, not by its normal (the model's normals are flipped in y)
            bottom = -1;
            float lowest = 1e9F;
            for (int i = 0; i < quads.size(); i++) {
                float[] q = quads.get(i);
                float cy = (q[1] + q[4] + q[7] + q[10]) / 4;
                if (cy < lowest) { lowest = cy; bottom = i; }
            }
            Map<Long, List<Integer>> edges = new HashMap<>();
            for (int i = 0; i < quads.size(); i++) {
                float[] q = quads.get(i);
                for (int j = 0; j < 4; j++) edges.computeIfAbsent(edgeKey(q, j, (j + 1) % 4), k -> new ArrayList<>()).add(i);
            }
            for (Map.Entry<Long, List<Integer>> en : edges.entrySet()) {
                List<Integer> adj = en.getValue();
                if (adj.size() != 2) continue;
                float[] q1 = quads.get(adj.get(0)), q2 = quads.get(adj.get(1));
                // a silhouette edge: one of its two faces looks at the camera, the other turns away
                float c1 = facing(q1), c2 = facing(q2);
                boolean f1 = c1 < 0, f2 = c2 < 0;
                // also the edge between a face-on face and a grazing TOP face (the lit upper edge of shoulders, arms and knees seen from above)
                boolean top = FlowLook.RIM_TOP_EDGES && f1 && f2 && ((q1[13] > .7F && c1 > -.3F && c2 < -.5F) || (q2[13] > .7F && c2 > -.3F && c1 < -.5F));
                if (f1 == f2 && !top) continue;
                float w = (head && (adj.get(0) == bottom || adj.get(1) == bottom) ? 0 : 1) * weight;
                if (w <= .01F) continue;
                float[] q = top ? (q1[13] > .7F ? q2 : q1) : (f1 ? q1 : q2);
                int j = -1;
                for (int e = 0; e < 4 && j < 0; e++) if (edgeKey(q, e, (e + 1) % 4) == en.getKey()) j = e;
                if (j < 0) continue;
                int k = (j + 1) % 4;
                float ax = q[3 * j], ay = q[3 * j + 1], az = q[3 * j + 2], bx = q[3 * k], by = q[3 * k + 1], bz = q[3 * k + 2];
                float ex = bx - ax, ey = by - ay, ez = bz - az, el = (float) Math.sqrt(ex * ex + ey * ey + ez * ez);
                if (el < 1e-5F) continue;
                ex /= el; ey /= el; ez /= el;
                if (verticalOnly && Math.abs(ey) < .8F) continue;
                // a ribbon facing the camera, centred on the edge: half of it lies on the model, half outside the outline (no foreshortening on grazing faces)
                float mx = (ax + bx) / 2, my = (ay + by) / 2, mz = (az + bz) / 2, ml = (float) Math.sqrt(mx * mx + my * my + mz * mz);
                if (ml < 1e-5F) continue;
                float vx = mx / ml, vy = my / ml, vz = mz / ml;
                float sx = ey * vz - ez * vy, sy = ez * vx - ex * vz, sz = ex * vy - ey * vx, sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (sl < 1e-4F) continue;
                sx /= sl; sy /= sl; sz /= sl;
                float[] h = new float[25];
                ribbon(h, 0, ax, ay, az, bx, by, bz, sx, sy, sz, -vx * FlowLook.RIM_LIFT, -vy * FlowLook.RIM_LIFT, -vz * FlowLook.RIM_LIFT, FlowLook.RIM_OUTER_HALF);
                ribbon(h, 12, ax, ay, az, bx, by, bz, sx, sy, sz, -vx * FlowLook.RIM_LIFT, -vy * FlowLook.RIM_LIFT, -vz * FlowLook.RIM_LIFT, FlowLook.RIM_INNER_HALF);
                h[24] = w;
                HULL.add(h);
            }
        }

        /** The head's face and chin take no rim. */
        private float mask(float[] q, boolean head) {
            if (!head) return 1;
            return q[13] < -.3F ? 0 : 1;   // under the chin: nothing; the sides of the head's outline may carry the rim
        }

        private static void ribbon(float[] h, int o, float ax, float ay, float az, float bx, float by, float bz, float sx, float sy, float sz,
                                   float lx, float ly, float lz, float half) {
            float[][] c = {{ax - sx * half, ay - sy * half, az - sz * half}, {bx - sx * half, by - sy * half, bz - sz * half},
                    {bx + sx * half, by + sy * half, bz + sz * half}, {ax + sx * half, ay + sy * half, az + sz * half}};
            for (int j = 0; j < 4; j++) { h[o + 3 * j] = c[j][0] + lx; h[o + 3 * j + 1] = c[j][1] + ly; h[o + 3 * j + 2] = c[j][2] + lz; }
        }

        private static long edgeKey(float[] q, int j, int k) {
            long a = vkey(q[3 * j], q[3 * j + 1], q[3 * j + 2]), b = vkey(q[3 * k], q[3 * k + 1], q[3 * k + 2]);
            return a < b ? a * 1_000_003L + b : b * 1_000_003L + a;
        }

        private static long vkey(float x, float y, float z) {
            return ((long) Math.round(x * 1024) & 0x7FFL) << 22 | ((long) Math.round(y * 1024) & 0x7FFL) << 11 | ((long) Math.round(z * 1024) & 0x7FFL);
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            cur[3 * corner] = x; cur[3 * corner + 1] = y; cur[3 * corner + 2] = z;
            cur[12] = nx; cur[13] = ny; cur[14] = nz;
            if (++corner == 4) { corner = 0; quads.add(cur.clone()); }
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { return this; }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return this; }
        @Override public VertexConsumer setUv(float u, float v) { return this; }
        @Override public VertexConsumer setUv1(int u, int v) { return this; }
        @Override public VertexConsumer setUv2(int u, int v) { return this; }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return this; }
    }

    /**
     * The crisp rim, replayed in the scene's pass with the scene's depth: the silhouette-edge strips, two additive layers: the outer one in the pill colour,
     * the inner one hot (pill colour to white).
     */
    static void drawHull(PoseStack.Pose pose, Vec3 cam, MultiBufferSource.BufferSource buffers) {
        State st = hullState;
        if (++dbg % 120 == 0) io.github.verycooltimo.murim.MurimMod.LOGGER.info("Flow rim strips {} rim {}", HULL.size(), st.rim());
        if (HULL.isEmpty() || st.rim() <= .002F) return;
        var type = MurimRenderTypes.flowGlow();
        VertexConsumer vc = buffers.getBuffer(type);
        Vec3 n = new Vec3(0, 1, 0);
        float k = FlowLook.RIM_INNER_WHITE;
        float ir = st.r() + (1 - st.r()) * k, ig = st.g() + (1 - st.g()) * k, ib = st.b() + (1 - st.b()) * k;
        for (float[] h : HULL) {
            float ao = Math.min(1, FlowLook.RIM_OUTER_ALPHA * st.rim() * h[24]), ai = Math.min(1, FlowLook.RIM_INNER_ALPHA * st.rim() * h[24]);
            for (int j = 0; j < 4; j++) VfxDraw.vertex(vc, pose, new Vec3(h[3 * j] + cam.x, h[3 * j + 1] + cam.y, h[3 * j + 2] + cam.z), n, 0, 0, ao, st.r(), st.g(), st.b());
            for (int j = 0; j < 4; j++) VfxDraw.vertex(vc, pose, new Vec3(h[12 + 3 * j] + cam.x, h[12 + 3 * j + 1] + cam.y, h[12 + 3 * j + 2] + cam.z), n, 0, 0, ai, ir, ig, ib);
        }
        buffers.endBatch(type);
    }

    /** The body lamp of the finale (set once a frame by FlowMeridianFx): strength 0..1, reach from the dantian in blocks, the dantian in the world, the pale tone. */
    static volatile float lampK, lampR;
    /** The lamp also lights the head (the finale of the high pill); the calm absorption keeps the face untouched. */
    static volatile boolean lampFace = true;
    private static final net.minecraft.resources.ResourceLocation WHITE = net.minecraft.resources.ResourceLocation.withDefaultNamespace("textures/misc/white.png");
    static volatile Vec3 lampAt;
    static volatile float[] lampTone = {1F, 1F, 1F};

    /** Light by distance from the dantian: inside the reach the model's own texture is brightened toward the pale tone, the edges most. */
    private static final class LampWrap implements VertexConsumer {
        final VertexConsumer out;
        final float cx, cy, cz, reach, k;
        final float[] tone;

        LampWrap(VertexConsumer out, float cx, float cy, float cz, float reach, float k, float[] tone) {
            this.out = out; this.cx = cx; this.cy = cy; this.cz = cz; this.reach = reach; this.k = k; this.tone = tone;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            float d = (float) Math.sqrt((x - cx) * (x - cx) + (y - cy) * (y - cy) + (z - cz) * (z - cz));
            float spread = 1 - Mth.clamp((d - (reach - .45F)) / .45F, 0, 1);
            float e = edge(x, y, z, nx, ny, nz);
            float w = k * spread * (.4F + .6F * e);
            float pr = tone[0] * .45F + .55F, pg = tone[1] * .45F + .55F, pb = tone[2] * .45F + .55F;
            int lit = 0xFF000000 | to255(pr * w) << 16 | to255(pg * w) << 8 | to255(pb * w);
            out.addVertex(x, y, z, lit, u, v, overlay, light, nx, ny, nz);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { return out.addVertex(x, y, z); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return out.setColor(r, g, b, a); }
        @Override public VertexConsumer setUv(float u, float v) { return out.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return out.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return out.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return out.setNormal(x, y, z); }
    }

    private static final class Wrap implements VertexConsumer {
        final VertexConsumer out;
        final State st;
        final float fx, fz;
        final boolean head;

        Wrap(VertexConsumer out, State st, float fx, float fz, boolean head) {
            this.out = out; this.st = st; this.fx = fx; this.fz = fz; this.head = head;
        }

        @Override
        public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
            float e = edge(x, y, z, nx, ny, nz);
            float mask = 1;
            if (head) {
                float face = nx * fx + nz * fz;
                mask = 1 - Mth.clamp((face - .1F) / .3F, 0, 1);
                if (ny < -.3F) mask = 0;
                if (ny > .6F) mask *= .6F;
            }
            if (!head && ny > .6F) mask = 0;
            float w = st.rim() * e * FlowLook.SURFACE_RIM * mask + (head ? 0 : st.inner());
            int lit = 0xFF000000 | to255(st.r() * w) << 16 | to255(st.g() * w) << 8 | to255(st.b() * w);
            out.addVertex(x, y, z, lit, u, v, overlay, light, nx, ny, nz);
        }

        @Override public VertexConsumer addVertex(float x, float y, float z) { return out.addVertex(x, y, z); }
        @Override public VertexConsumer setColor(int r, int g, int b, int a) { return out.setColor(r, g, b, a); }
        @Override public VertexConsumer setUv(float u, float v) { return out.setUv(u, v); }
        @Override public VertexConsumer setUv1(int u, int v) { return out.setUv1(u, v); }
        @Override public VertexConsumer setUv2(int u, int v) { return out.setUv2(u, v); }
        @Override public VertexConsumer setNormal(float x, float y, float z) { return out.setNormal(x, y, z); }
    }
}
