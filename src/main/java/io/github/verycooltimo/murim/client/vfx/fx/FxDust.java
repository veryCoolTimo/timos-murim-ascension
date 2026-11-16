package io.github.verycooltimo.murim.client.vfx.fx;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Block B of the effect engine: cel dust. {@link FxDustSim} gives the puffs (position, velocity, size, age); this class draws each as a
 * camera-facing quad and fx_dust.fsh paints the clump inside it (lobed silhouette unique to the puff's seed, flat tones from a light, a
 * contour that comes and goes, erosion with age; kind 2 is a torn tongue, kind 1 a hard chip). Neighbours overlap and merge into a mass.
 * API: RegisterShadersEvent#registerShader, ShaderInstance(ResourceProvider,ResourceLocation,VertexFormat), #getUniform, Uniform#set;
 * RenderType.create — the same calls as {@link FxStroke}. Under an Iris shader pack core shaders are ignored: {@link #available()} is
 * false there and a caller keeps its plain path (docs/03-vfx/06-shader-effects.md).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FxDust {
    /**
     * The palette of one use, from the reference (colours 0xRRGGBB). {@code litShare} and {@code shadeShare} (0..1) say how much of a shape is lit
     * and how much is shade; {@code rim} (0..1) is the pale rim of thrown matter; {@code lobes} is the number of lobes of a clump.
     */
    public record Look(int lit, int mid, int shade, int ink, float inkAlpha, float litShare, float shadeShare, float lobes, float lightX, float lightY,
                       float contourBreak, float innerLine, float rim) {}

    /** Panel 11: warm pale dust with a rose body and brown ink dashes (colours counted from the plate: F8F8F0, E8B0A0, C4837A). */
    public static final Look WARM = new Look(0xF8F4EC, 0xE8B0A0, 0xC4837A, 0x3A1F1C, .9F, .66F, .42F, 4, -.55F, .8F, .75F, .5F, 0F);
    /** Panel 8: grey landing dust (plate colours E8E8E8, B0B0B0, 686868). */
    public static final Look GREY = new Look(0xEAEAEA, 0xB0B0B0, 0x6C6E72, 0x202226, 0F, .6F, .65F, 5, -.4F, .9F, .5F, 0F, 0F);
    /** Panel 2: the pale wake on a night floor (plate colours C0C0C0, A0A0A0). */
    public static final Look NIGHT = new Look(0xD2D4D8, 0xA8AAB0, 0x7A7C82, 0x1B1D21, .9F, .55F, .45F, 5, -.2F, .9F, .6F, .2F, 0F);
    /** Panel 15: black spatter with a pale rim so that it reads on a dark scene. */
    public static final Look DARK = new Look(0x9A8E96, 0x2F2529, 0x130E11, 0x0A080C, 1F, .4F, .55F, 5, -.5F, .85F, .2F, .3F, .75F);
    /** Panel 15: the pale dust round the black spatter (plate colours F8E8E8, E8B0B8, A07884). */
    public static final Look PALE = new Look(0xF6E8E8, 0xE0B0B8, 0xA67C88, 0x3A1F28, 0F, .6F, .45F, 5, -.5F, .85F, .3F, 0F, 0F);

    // The same five looks for a night scene (round 3): the lit tone is dimmed so that the dust does not glow like a lamp, the shade stays well under it,
    // the ink stays darker than the body so that the contour still reads. Used by the bench, which is filmed at night; the lab renders both.
    public static final Look WARM_NIGHT = new Look(0xC8BDB8, 0x93706C, 0x5A4146, 0x1C1014, .9F, .66F, .42F, 4, -.55F, .8F, .75F, .5F, 0F);
    public static final Look GREY_NIGHT = new Look(0xA9ACB4, 0x7C808A, 0x444852, 0x14161C, 0F, .6F, .65F, 5, -.4F, .9F, .5F, 0F, 0F);
    public static final Look WAKE_NIGHT = new Look(0xB4B8C2, 0x868A96, 0x4E525E, 0x14161C, .9F, .55F, .45F, 5, -.2F, .9F, .6F, .2F, 0F);
    public static final Look PALE_NIGHT = new Look(0xC8B8BE, 0x9A7482, 0x5A4250, 0x1C1014, 0F, .6F, .45F, 5, -.5F, .85F, .3F, 0F, 0F);

    private static final String[] UNIFORMS = {"DuLit", "DuMid", "DuShade", "DuInk", "DuLight", "DuExtra"};
    private static ShaderInstance program;
    private static boolean failed;

    private static final RenderType MAIN = RenderType.create(MurimMod.MODID + ":fx_dust", DefaultVertexFormat.NEW_ENTITY,
            VertexFormat.Mode.QUADS, 4096, RenderType.CompositeState.builder()
                    .setShaderState(new RenderStateShard.ShaderStateShard(() -> program))
                    .setTransparencyState(RenderStateShard.TRANSLUCENT_TRANSPARENCY)
                    .setCullState(RenderStateShard.NO_CULL)
                    .setDepthTestState(RenderStateShard.LEQUAL_DEPTH_TEST)
                    .setWriteMaskState(RenderStateShard.COLOR_WRITE)
                    .createCompositeState(false));

    private FxDust() {}

    public static boolean available() { return program != null && !failed; }

    @SubscribeEvent
    static void shaders(RegisterShadersEvent event) {
        try {
            event.registerShader(new ShaderInstance(event.getResourceProvider(),
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fx_dust"), DefaultVertexFormat.NEW_ENTITY), loaded -> {
                for (String name : UNIFORMS) {
                    if (loaded.getUniform(name) == null) {
                        failed = true;
                        MurimMod.LOGGER.warn("Effect dust disabled: fx_dust has no uniform {}", name);
                        return;
                    }
                }
                failed = false;
                program = loaded;
            });
        } catch (Exception error) {
            failed = true;
            MurimMod.LOGGER.warn("Effect dust disabled: fx_dust did not load", error);
        }
    }

    /**
     * Where the simulation's axes stand in the world: sim x along {@code right}, sim y up, sim z along {@code near} (towards the viewer),
     * and the billboard axes of the camera ({@code camRight}, {@code camUp}) the quads are turned to.
     */
    public record Frame(Vec3 origin, Vec3 right, Vec3 near, Vec3 camRight, Vec3 camUp) {
        Vec3 at(double x, double y, double z) { return origin.add(right.scale(x)).add(near.scale(z)).add(0, y, 0); }
        /** A world velocity (sim axes) as a vector in the world. */
        Vec3 dir(double x, double y, double z) { return right.scale(x).add(near.scale(z)).add(0, y, 0); }
    }

    /** Draws the puffs of one look and flushes them (the uniforms belong to this batch). Chips and tongues come after clumps, far before near. */
    public static void draw(Look look, List<FxDustSim.Puff> puffs, Frame frame, PoseStack.Pose pose, MultiBufferSource.BufferSource buffers, double seconds) {
        if (!available() || puffs.isEmpty()) return;
        uniforms(look, seconds);
        List<FxDustSim.Puff> order = new ArrayList<>(puffs);
        order.sort(Comparator.comparingInt(FxDustSim.Puff::layer).thenComparingDouble(FxDustSim.Puff::z));
        VertexConsumer out = buffers.getBuffer(MAIN);
        for (FxDustSim.Puff p : order) emit(p, frame, pose, out);
        buffers.endBatch(MAIN);
    }

    private static void uniforms(Look l, double seconds) {
        set("DuLit", l.lit(), l.litShare());
        set("DuMid", l.mid(), l.lobes());
        set("DuShade", l.shade(), l.shadeShare());
        set("DuInk", l.ink(), l.inkAlpha());
        program.getUniform("DuLight").set(l.lightX(), l.lightY(), l.contourBreak(), 1F);
        program.getUniform("DuExtra").set((float) (seconds % 3600.0), l.innerLine(), l.rim(), 1F);
    }

    private static void set(String name, int rgb, float a) {
        program.getUniform(name).set(((rgb >> 16) & 255) / 255F, ((rgb >> 8) & 255) / 255F, (rgb & 255) / 255F, a);
    }

    private static void emit(FxDustSim.Puff p, Frame f, PoseStack.Pose pose, VertexConsumer out) {
        if (p.size() <= 0) return;
        Vec3 c = f.at(p.x(), p.y(), p.z());
        // everything but a round clump is turned along its heading, or along its flow when it has none, as it looks from the camera
        double angle = 0;
        if (p.kind() != FxDustSim.CLUMP) {
            Vec3 v = Double.isNaN(p.heading()) ? f.dir(p.vx(), p.vy(), p.vz()) : f.dir(Math.cos(p.heading()), Math.sin(p.heading()), 0);
            angle = Math.atan2(v.dot(f.camUp()), v.dot(f.camRight()));
        }
        double ca = Math.cos(angle), sa = Math.sin(angle);
        Vec3 ex = f.camRight().scale(ca).add(f.camUp().scale(sa)).scale(p.size() * p.aspect());
        Vec3 ey = f.camRight().scale(-sa).add(f.camUp().scale(ca)).scale(p.size());
        int seed = (int) Math.round(Math.clamp(p.seed(), 0, 1) * 10000), erode = (int) Math.round(Math.clamp(p.erode(), 0, 1) * 10000);
        // the roll slot carries a clump's layout turn; for every other kind it carries the quad's angle (the shader turns the light into it)
        int roll = (int) Math.round((p.kind() == FxDustSim.CLUMP ? p.roll() : angle) * 1000), kind = p.kind(), alpha = Math.clamp(Math.round(p.alpha() * 255), 0, 255);
        int tint = Math.clamp(Math.round(p.tint() * 255), 0, 255);
        float taper = (float) p.taper();
        int[][] corners = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};
        for (int[] k : corners) {
            Vec3 at = c.add(ex.scale(k[0])).add(ey.scale(k[1]));
            out.addVertex(pose, (float) at.x, (float) at.y, (float) at.z).setColor(tint, tint, tint, alpha).setUv((k[0] + 1) / 2F, (k[1] + 1) / 2F)
                    // the normal is free in this format: its x carries the tongue's taper, untransformed
                    .setUv1(seed, erode).setUv2(roll, kind).setNormal(taper, 0, 0);
        }
    }
}
