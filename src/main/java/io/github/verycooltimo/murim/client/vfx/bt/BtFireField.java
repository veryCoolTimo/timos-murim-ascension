package io.github.verycooltimo.murim.client.vfx.bt;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.vfx.VfxDraw;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.EffectInstance;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

/**
 * The lab's fire look (lab-bt_third.js bt3Fire), not tongue cards. Author 31.10: «огонь выглядит ужасно совсем не так как мы делали ауру
 * мобам и не так как мы делали в 2д лабе». Every live particle of the simulation is a tongue along its trail (tail, middle, head; narrow at
 * the tail, widest a fifth of the way, pointed at the head); the tongues add up to one field, so neighbours merge into one fire, and the field
 * gets the lab's flame noise and colour ramp (dark rim, blue, light blue, white core).
 * <ol>
 *   <li>Each tongue is a camera-facing strip drawn into an off-screen target: RGB holds a quarter of the summed field (four full tongues on a
 *       pixel still fit 8 bits), A the strongest tongue (GL_MAX), which lights the cores. As in the lab there are two fields, the tongues
 *       behind the body and the ones in front, each summed on its own (one field of both runs hotter and paler than the lab). The targets
 *       carry the scene's depth, so the body hides the fire behind it and the tongues in front stay in front.</li>
 *   <li>{@code murim:bt_fire} lays the back field, then the front one (alpha .95 and .8 as the lab's passes) over the main target,
 *       alpha-blended, depth untouched (a PostChain pass would clear it).</li>
 * </ol>
 * Positions are the lab's plate (360 px a block round the body's axis, {@link BtBody#plate}, scaled by {@link #SPREAD} and {@link #RISE}):
 * the lab's spread, from the floor at the knees to above the head, not squeezed onto the narrower seated block body.
 * API: RenderStateShard.TransparencyStateShard / OutputStateShard (public constructors), RenderTarget#copyDepthFrom, EffectInstance (as
 * PostPass#process draws it), GL20#glBlendEquationSeparate. reference/minecraft-src/net/minecraft/client/renderer/PostPass.java.
 */
public final class BtFireField {
    /** The lab's cap (bt3FireMax: at most 200 tongues in the uniform arrays); the strongest go first. */
    static final int MAX = 200;
    static final float SUM_SCALE = .25F;
    /**
     * The block body is wider and taller on screen than the lab's painted hero at the same 360 px a block, and it hides more of the fire
     * behind it, so the same simulation read as separate thin ribbons round a big body (codex 31.10 on the first film: «separated flame
     * tongues with large empty gaps; the lab has broad connected sheets»). The tongues are drawn 1.6 times wider, the spread round the
     * axis 1.25 times wider and the rise above each spawn 1.3 times higher; the simulation itself stays the lab's.
     */
    static final double WIDTH_GAIN = 1.6, SPREAD = 1.25, RISE = 1.3;
    /** BT3_FIRE_COLS: core, body, edge, mid. */
    public static final int[] BLUE = {0xe8fdff, 0x2276ff, 0x0a1d6e, 0x78d9ff};
    private static final ResourceLocation PROFILE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "dynamic/bt_fire_profile");
    private static final int PROFILE_WIDTH = 64;
    private static DynamicTexture profile;
    private static final TextureTarget[] density = new TextureTarget[2];
    private static EffectInstance effect;
    private static boolean failed;
    private static int frame, prepared = -1;
    private static final boolean[] pending = new boolean[2];
    private static Matrix4f view, projection;
    private static Vec3 camera, anchor, anchorUp;
    private static double clock;
    private static int[] colours = BLUE;

    /** Field 0 is behind the body, 1 in front. */
    private static final RenderType[] DENSITY = {densityType(0), densityType(1)};

    private static RenderType densityType(int field) {
        return RenderType.create(MurimMod.MODID + ":bt_fire_density_" + field, DefaultVertexFormat.NEW_ENTITY,
                VertexFormat.Mode.QUADS, 1 << 18, RenderType.CompositeState.builder()
                        .setShaderState(RenderStateShard.RENDERTYPE_EYES_SHADER)
                        .setTextureState(new RenderStateShard.TextureStateShard(PROFILE, true, false))
                        .setTransparencyState(new RenderStateShard.TransparencyStateShard(MurimMod.MODID + "_fire_sum", () -> {
                            RenderSystem.enableBlend();
                            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE,
                                    GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ONE);
                            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_MAX);
                        }, () -> {
                            GL20.glBlendEquationSeparate(GL14.GL_FUNC_ADD, GL14.GL_FUNC_ADD);
                            RenderSystem.disableBlend();
                            RenderSystem.defaultBlendFunc();
                        }))
                        .setOutputState(new RenderStateShard.OutputStateShard(MurimMod.MODID + "_fire_density_" + field, () -> {
                            if (density[field] != null) {
                                density[field].bindWrite(false);
                            }
                        }, () -> Minecraft.getInstance().getMainRenderTarget().bindWrite(false)))
                        .setCullState(RenderStateShard.NO_CULL)
                        .setLightmapState(RenderStateShard.NO_LIGHTMAP)
                        .setOverlayState(RenderStateShard.NO_OVERLAY)
                        .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                        .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                        .createCompositeState(false));
    }

    private record Tongue(Vec3 tail, Vec3 mid, Vec3 head, double width, double fade) { }

    private BtFireField() {
    }

    /** The frame's camera, from the stage event: the composite measures the body's size on screen with it. */
    public static void camera(Matrix4f modelView, Matrix4f proj, Vec3 position) {
        view = new Matrix4f(modelView);
        projection = new Matrix4f(proj);
        camera = position;
    }

    /**
     * The simulation's tongues into the density targets, as bt3Fire picks them: behind the body all, in front only the low ones at the sides
     * (the ones over the chest and face read as blue smudges); width and fade from age, the front a quarter narrower.
     */
    public static void render(BtCtx c, BtFireSim sim, int[] cols) {
        double quality = BtScenes.quality(c.mc());
        if (failed || quality <= 0 || view == null) {
            return;
        }
        List<List<Tongue>> live = List.of(new ArrayList<>(), new ArrayList<>());
        for (var p : sim.particles()) {
            if (p.trail.size() < 3) {
                continue;
            }
            double facing = p.z / Math.max(1e-12, Math.hypot(p.x, p.z));
            boolean front = p.z > 0;
            if (front && (facing > .6 || p.f > .45)) {
                continue;
            }
            double u = Math.min(1, p.age / p.life);
            double env = Math.pow(Math.sin(Math.PI * Math.min(1, u * 1.3 + .05)), .6);
            double width = p.size * env * .4 * WIDTH_GAIN * (1 - .55 * u * u) * (front ? .75 : 1);
            double fade = Math.pow(1 - u, .6) * (facing > 0 ? 1 - .6 * facing : 1) * (1 - .5 * u * u);
            if (width <= 0 || fade <= 0) {
                continue;
            }
            int n = p.trail.size();
            Vec3[] at = new Vec3[3];
            int[] pick = {n - 1, n - 1 - (n >> 1), 0};   // oldest (tail), the middle sample, newest (head)
            for (int j = 0; j < 3; j++) {
                var q = p.trail.get(pick[j]);
                at[j] = c.body().plate(400 + q.x() * SPREAD, 594 + q.z() * SPREAD * .22, p.spawn.y() + (q.y() - p.spawn.y()) * RISE);
            }
            live.get(front ? 1 : 0).add(new Tongue(at[0], at[1], at[2], width, fade));
        }
        if (live.get(0).isEmpty() && live.get(1).isEmpty()) {
            return;
        }
        if (prepared != frame && !prepare(c.mc())) {
            return;
        }
        for (int field = 0; field < 2; field++) {
            List<Tongue> list = live.get(field);
            if (list.isEmpty()) {
                continue;
            }
            list.sort(Comparator.comparingDouble((Tongue t) -> t.width() * t.fade()).reversed());
            VertexConsumer vc = c.buffers().getBuffer(DENSITY[field]);
            int count = Math.min(list.size(), (int) Math.ceil(MAX * quality));
            for (int i = 0; i < count; i++) {
                strip(c, vc, list.get(i));
            }
            pending[field] = true;
        }
        clock = c.labSeconds() * 1.5;
        colours = cols;
        anchor = c.body().axis();
        anchorUp = anchor.add(0, 1, 0);
    }

    /** lab width(u, W): narrow at the tail, full a fifth of the way, pointed at the head; plus the lab's one plate pixel. */
    static double width(double u, double w) {
        return w * Math.min(1, .25 + u * 4) * Math.pow(Math.max(0, 1 - u), .8) + 1 / 360.0;
    }

    private static void strip(BtCtx c, VertexConsumer vc, Tongue t) {
        // tail -> middle -> head in eight steps; u is the lab's width parameter (0 tail, .5 middle, 1 head). A short cap behind the tail
        // stands in for the round end of the lab's segment distance.
        int n = 9;
        Vec3[] p = new Vec3[n + 1];
        double[] w = new double[n + 1];
        for (int i = 0; i < n; i++) {
            double s = i / (n - 1.0);
            p[i + 1] = s <= .5 ? lerp(t.tail(), t.mid(), s * 2) : lerp(t.mid(), t.head(), (s - .5) * 2);
            w[i + 1] = width(s, t.width());
        }
        Vec3 back = p[1].subtract(p[2]);
        p[0] = back.lengthSqr() < 1e-12 ? p[1] : p[1].add(back.normalize().scale(w[1]));
        w[0] = 0;
        Vec3[] side = new Vec3[n + 1];
        for (int i = 0; i <= n; i++) {
            Vec3 tangent = p[Math.min(n, i + 1)].subtract(p[Math.max(0, i - 1)]);
            Vec3 cross = tangent.cross(c.camera().subtract(p[i]));
            side[i] = cross.lengthSqr() < 1e-14 ? null : cross.normalize();
        }
        float r = (float) (t.fade() * SUM_SCALE), a = (float) t.fade();
        Vec3 normal = c.body().near();
        for (int i = 0; i < n; i++) {
            if (side[i] == null || side[i + 1] == null) {
                continue;
            }
            VfxDraw.vertex(vc, c.pose(), p[i].subtract(side[i].scale(w[i])), normal, 0, 0, a, r, r, r);
            VfxDraw.vertex(vc, c.pose(), p[i + 1].subtract(side[i + 1].scale(w[i + 1])), normal, 0, 1, a, r, r, r);
            VfxDraw.vertex(vc, c.pose(), p[i + 1].add(side[i + 1].scale(w[i + 1])), normal, 1, 1, a, r, r, r);
            VfxDraw.vertex(vc, c.pose(), p[i].add(side[i].scale(w[i])), normal, 1, 0, a, r, r, r);
        }
    }

    private static Vec3 lerp(Vec3 a, Vec3 b, double k) {
        return a.add(b.subtract(a).scale(k));
    }

    /** Cleared density target of the window's size with the scene's depth; main stays bound for the rest of the scene. */
    private static boolean prepare(Minecraft mc) {
        if (profile == null) {
            // across the strip: d = |2s - 1|, the lab's tongue field k^2 (3 - 2k), k = 1 - d
            profile = new DynamicTexture(PROFILE_WIDTH, 4, false);
            NativeImage image = profile.getPixels();
            for (int x = 0; x < PROFILE_WIDTH; x++) {
                double d = Math.abs((x + .5) / PROFILE_WIDTH * 2 - 1), k = 1 - d;
                int g = (int) Math.round(k * k * (3 - 2 * k) * 255);
                for (int y = 0; y < 4; y++) {
                    image.setPixelRGBA(x, y, g << 24 | g << 16 | g << 8 | g);
                }
            }
            profile.upload();
            mc.getTextureManager().register(PROFILE, profile);
        }
        RenderTarget main = mc.getMainRenderTarget();
        for (int field = 0; field < 2; field++) {
            TextureTarget target = density[field];
            if (target == null) {
                target = density[field] = new TextureTarget(main.width, main.height, true, Minecraft.ON_OSX);
                if (main.isStencilEnabled()) {
                    target.enableStencil();
                }
            } else if (target.width != main.width || target.height != main.height) {
                target.resize(main.width, main.height, Minecraft.ON_OSX);
            }
            target.setClearColor(0, 0, 0, 0);
            target.clear(Minecraft.ON_OSX);
            target.copyDepthFrom(main);
        }
        main.bindWrite(false);
        prepared = frame;
        return true;
    }

    /** After the scene's flush: the back field, then the front one, over the frame. Draws nothing in a frame without tongues. */
    public static void composite(Minecraft mc) {
        frame++;
        boolean any = pending[0] || pending[1];
        boolean[] draw = pending.clone();
        pending[0] = pending[1] = false;
        if (!any || failed || view == null) {
            return;
        }
        if (effect == null) {
            try {
                effect = new EffectInstance(mc.getResourceManager(), MurimMod.MODID + ":bt_fire");
            } catch (Exception e) {
                failed = true;
                MurimMod.LOGGER.warn("Breakthrough fire shader failed to load", e);
                return;
            }
        }
        RenderTarget main = mc.getMainRenderTarget();
        float w = main.width, h = main.height;
        float[] a = BtProjection.project(anchor, camera, view, projection);
        float[] b = BtProjection.project(anchorUp, camera, view, projection);
        if (a == null || b == null) {
            return;
        }
        // 360 plate px make a block: the lab's noise keeps its size on the body at any distance
        double perBlock = Math.hypot((b[0] - a[0]) * w, (b[1] - a[1]) * h);
        if (perBlock < 1) {
            return;
        }
        main.bindWrite(true);
        effect.safeGetUniform("ProjMat").set(new Matrix4f().setOrtho(0, w, 0, h, .1F, 1000F));
        effect.safeGetUniform("InSize").set(w, h);
        effect.safeGetUniform("OutSize").set(w, h);
        effect.safeGetUniform("Clock").set((float) clock);
        effect.safeGetUniform("PlateScale").set((float) (360 / perBlock));
        effect.safeGetUniform("Anchor").set(a[0] * w, (1 - a[1]) * h);
        effect.safeGetUniform("SumScale").set(1 / SUM_SCALE);
        String[] names = {"Core", "Body", "Edge", "Mid"};
        for (int i = 0; i < 4; i++) {
            int rgb = colours[i];
            effect.safeGetUniform(names[i]).set((rgb >> 16 & 255) / 255F, (rgb >> 8 & 255) / 255F, (rgb & 255) / 255F);
        }
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        for (int field = 0; field < 2; field++) {
            if (!draw[field] || density[field] == null) {
                continue;
            }
            TextureTarget target = density[field];
            effect.setSampler("DiffuseSampler", target::getColorTextureId);
            // the lab's passes: alpha .95 behind, .8 in front
            effect.safeGetUniform("AlphaK").set(field == 0 ? .95F : .8F);
            effect.apply();
            // EffectInstance's blend mode skips itself when it was the last one applied; the state may have changed since.
            RenderSystem.enableBlend();
            RenderSystem.blendEquation(GL14.GL_FUNC_ADD);
            RenderSystem.blendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA,
                    GlStateManager.SourceFactor.ZERO, GlStateManager.DestFactor.ONE);
            BufferBuilder quad = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION);
            quad.addVertex(0, 0, 500);
            quad.addVertex(w, 0, 500);
            quad.addVertex(w, h, 500);
            quad.addVertex(0, h, 500);
            BufferUploader.draw(quad.buildOrThrow());
            effect.clear();
        }
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        main.bindWrite(false);
    }
}
