package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.technique.TangDagger;
import io.github.verycooltimo.murim.technique.TangRules;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * Гранёный метательный кинжал клана Тан (рефы d2-04: прямой клинок с гранями, обмотанная рукоять, шнур;
 * d1-02: длинное лезвие с продольными гранями, тёмная рукоять с поперечными полосами).
 *
 * <p>Модель — своя геометрия из нескольких четырёхугольников: два скрещённых ромба клинка (виден с любой
 * стороны), гарда, рукоять с полосами обмотки, кольцо навершия, два шнура. Слой 0 — только этот голый кинжал;
 * оболочка, след и венцы рисует {@link TangVfx}. RenderType — ванильный полупрозрачный тип сущностей
 * по белой текстуре ({@link MurimRenderTypes#solid()}), полная яркость.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public class TangDaggerRenderer extends EntityRenderer<TangDagger> {

    /** Длина кинжала в блоках: читаемый на 10–15 блоках, но не больше предплечья. */
    public static final double LENGTH = 0.72D;

    private static final ResourceLocation WHITE = ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public TangDaggerRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @SubscribeEvent
    static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.TANG_DAGGER.get(), TangDaggerRenderer::new);
    }

    @Override
    public ResourceLocation getTextureLocation(TangDagger entity) {
        return WHITE;
    }

    @Override
    public void render(TangDagger e, float yaw, float partial, PoseStack ps, MultiBufferSource buffers, int light) {
        if (TangVfx.hidden(e)) {
            return;
        }
        Vec3 f = forward(e, partial);
        float age = e.tickCount + partial;
        // Крен: у карпа — S-изгиб корпуса (рыскание вокруг хода), у зависшего — медленное вращение.
        double roll = e.mode() == TangDagger.STUCK ? e.getId() * 0.7D : e.mode() == TangDagger.HANG ? age * 0.25D
                : e.mode() == TangDagger.CARP ? Math.sin(age * 0.9D) * 0.6D : age * 0.15D;
        Vec3 up = perpendicular(f, roll);
        if (e.mode() == TangDagger.HANG) {
            // Висит боком и вращается (spec §4): ось — вертикаль.
            double a = age * 0.22D;
            f = new Vec3(Math.cos(a), 0.15D * Math.sin(age * 0.1D), Math.sin(a)).normalize();
            up = new Vec3(0.0D, 1.0D, 0.0D);
        }
        if (e.mode() == TangDagger.FALL) {
            double a = age * 0.6D;
            f = new Vec3(Math.cos(a), Math.sin(a), 0.3D).normalize();
        }
        // Модель автора (art/items/tang_dagger, 03.10): гарда чуть позади центра сущности, остриё по ходу.
        drawModel(ps, buffers.getBuffer(TangDaggerMesh.renderType()), f.scale(-LENGTH * 0.1D), f, roll, e.sky() ? 1.35F : 1.0F, 1.0F);
    }

    /** Модель кинжала автора: гарда в {@code at} (координаты текущей позы), остриё по {@code f}. */
    public static void drawModel(PoseStack ps, VertexConsumer v, Vec3 at, Vec3 f, double roll, float scale, float alpha) {
        ps.pushPose();
        try {
            ps.translate(at.x, at.y, at.z);
            TangDaggerMesh.orient(ps, f.x, f.y, f.z, (float) roll);
            TangDaggerMesh.render(ps, v, 0x00F000F0, scale, 1.0F, 1.0F, 1.0F, alpha);
        } finally {
            ps.popPose();
        }
    }

    /** Направление острия: по повороту сущности (сервер ставит его по ходу или к цели). */
    static Vec3 forward(TangDagger e, float partial) {
        float yRot = Mth.rotLerp(partial, e.yRotO, e.getYRot());
        float xRot = Mth.lerp(partial, e.xRotO, e.getXRot());
        double y = Math.toRadians(yRot);
        double x = Math.toRadians(xRot);
        return new Vec3(Math.sin(y) * Math.cos(x), Math.sin(x), Math.cos(y) * Math.cos(x));
    }

    static Vec3 perpendicular(Vec3 f, double roll) {
        Vec3 ref = Math.abs(f.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 s = f.cross(ref).normalize();
        Vec3 u = s.cross(f).normalize();
        return u.scale(Math.cos(roll)).add(s.scale(Math.sin(roll)));
    }

    /**
     * Кинжал в точке {@code at} (центр рукояти — у гарды) остриём по {@code f}. {@code dark} — чёрно-белый
     * (Тёмный Взрыв), {@code sky} — двенадцатый (крупнее).
     */
    public static void draw(VertexConsumer v, PoseStack.Pose pose, Vec3 at, Vec3 f, Vec3 up, double scale, float alpha,
                            boolean dark, boolean sky) {
        double k = LENGTH * scale * (sky ? 1.35D : 1.0D);
        Vec3 s = f.cross(up).normalize();
        Vec3 u = up.normalize();
        // Клинок-ромб «лист ивы» (гл. 194): основание у гарды, шире всего на 0,22, остриё на 0,62 длины.
        Vec3 base = at;
        Vec3 wide = at.add(f.scale(0.18D * k));
        Vec3 tip = at.add(f.scale(0.62D * k));
        double w = 0.075D * k;
        float[] light = dark ? new float[] {0.96F, 0.96F, 0.96F} : new float[] {0.95F, 1.0F, 1.0F};
        float[] face = dark ? new float[] {0.62F, 0.64F, 0.66F} : new float[] {0.61F, 0.86F, 0.95F};
        float[] shade = dark ? new float[] {0.2F, 0.2F, 0.22F} : new float[] {0.36F, 0.54F, 0.75F};
        for (Vec3 ax : new Vec3[] {s, u}) {
            // Две половины ромба разным тоном: грань на свету и грань в тени — читается объём.
            quad(v, pose, base, wide.add(ax.scale(w)), tip, wide, alpha, light, face);
            quad(v, pose, base, wide, tip, wide.subtract(ax.scale(w)), alpha, face, shade);
        }
        // Гарда.
        Vec3 g = at.subtract(f.scale(0.01D * k));
        quad(v, pose, g.add(s.scale(0.06D * k)).add(u.scale(0.012D * k)), g.subtract(s.scale(0.06D * k)).add(u.scale(0.012D * k)),
                g.subtract(s.scale(0.06D * k)).subtract(u.scale(0.012D * k)), g.add(s.scale(0.06D * k)).subtract(u.scale(0.012D * k)),
                alpha, face, shade);
        // Рукоять с обмоткой: тёмная (d1-02) или голубая (d2-04), полосы обмотки светлее.
        float[] grip = dark ? new float[] {0.06F, 0.06F, 0.06F} : new float[] {0.13F, 0.2F, 0.26F};
        float[] wrap = dark ? new float[] {0.5F, 0.5F, 0.5F} : new float[] {0.44F, 0.78F, 0.95F};
        Vec3 h0 = at.subtract(f.scale(0.02D * k));
        Vec3 h1 = at.subtract(f.scale(0.24D * k));
        double hw = 0.022D * k;
        for (Vec3 ax : new Vec3[] {s, u}) {
            quad(v, pose, h0.add(ax.scale(hw)), h1.add(ax.scale(hw)), h1.subtract(ax.scale(hw)), h0.subtract(ax.scale(hw)), alpha, grip, grip);
            for (int i = 0; i < 4; i++) {
                Vec3 b0 = h0.lerp(h1, 0.12D + i * 0.22D);
                Vec3 b1 = h0.lerp(h1, 0.2D + i * 0.22D);
                double bw = hw * 1.25D;
                quad(v, pose, b0.add(ax.scale(bw)), b1.add(ax.scale(bw)), b1.subtract(ax.scale(bw)), b0.subtract(ax.scale(bw)), alpha, wrap, wrap);
            }
        }
        // Кольцо навершия.
        Vec3 ring = at.subtract(f.scale(0.28D * k));
        double rr = 0.035D * k;
        for (int i = 0; i < 4; i++) {
            double a0 = i * Math.PI / 2.0D;
            double a1 = a0 + Math.PI / 2.0D;
            Vec3 p0 = ring.add(s.scale(Math.cos(a0) * rr)).add(f.scale(Math.sin(a0) * rr));
            Vec3 p1 = ring.add(s.scale(Math.cos(a1) * rr)).add(f.scale(Math.sin(a1) * rr));
            quad(v, pose, p0.add(u.scale(0.008D * k)), p1.add(u.scale(0.008D * k)), p1.subtract(u.scale(0.008D * k)),
                    p0.subtract(u.scale(0.008D * k)), alpha, face, face);
        }
    }

    private static void quad(VertexConsumer v, PoseStack.Pose pose, Vec3 a, Vec3 b, Vec3 c, Vec3 d, float alpha, float[] c0, float[] c1) {
        Vec3 n = b.subtract(a).cross(d.subtract(a));
        n = n.lengthSqr() < 1.0E-10D ? new Vec3(0.0D, 1.0D, 0.0D) : n.normalize();
        VfxDraw.vertex(v, pose, a, n, 0.0F, 0.0F, alpha, c0[0], c0[1], c0[2]);
        VfxDraw.vertex(v, pose, b, n, 1.0F, 0.0F, alpha, c0[0], c0[1], c0[2]);
        VfxDraw.vertex(v, pose, c, n, 1.0F, 1.0F, alpha, c1[0], c1[1], c1[2]);
        VfxDraw.vertex(v, pose, d, n, 0.0F, 1.0F, alpha, c1[0], c1[1], c1[2]);
    }
}
