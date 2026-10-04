package io.github.verycooltimo.murim.client.boss;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bedrock.BedrockGeo;
import io.github.verycooltimo.murim.entity.boss.BossMove;
import io.github.verycooltimo.murim.entity.boss.BossRegistry;
import io.github.verycooltimo.murim.entity.boss.BossRules;
import io.github.verycooltimo.murim.entity.boss.FortressMaster;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.MobRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Рендер хозяина крепости и его меток на земле (docs/design/26-boss.md §4, §6).
 *
 * <p>Метки — плоские красно-оранжевые полосы {@code #E0482B} поверх плит: кольцо прыжка (видно весь
 * полёт), полоса тарана и вихря, круг рыка, три линии раскола. Метка = зона урона: геометрия
 * берётся из тех же {@link BossRules}, что считает сервер. Слой аддитивный ({@link RenderType#lightning()}):
 * виден и на программном рендере стенда, и ночью. Барьер ци — низкая красная стена по краю плаца,
 * пока идёт бой.
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java
 * (#setupRotations, #getWhiteOverlayProgress), reference/minecraft-src/net/minecraft/client/renderer/RenderType.java#lightning.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public class BossRenderer extends MobRenderer<FortressMaster, BossModel> {

    public static final ModelLayerLocation LAYER =
            new ModelLayerLocation(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "fortress_master"), "main");

    private static final ResourceLocation TEXTURE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/fortress_master.png");

    /** Масштаб модели: в 1,35 раза больше игрока. */
    public static final float SCALE = 1.35F;

    /** Цвет меток: красно-оранжевая тушь. */
    private static final float MR = 0xE0 / 255.0F, MG = 0x48 / 255.0F, MB = 0x2B / 255.0F;
    /** Барьер ци — красный. */
    private static final float BR = 0xC8 / 255.0F, BG = 0x32 / 255.0F, BB = 0x2A / 255.0F;

    public BossRenderer(EntityRendererProvider.Context context) {
        super(context, new BossModel(context.bakeLayer(LAYER)), 0.9F);
    }

    @Override
    public ResourceLocation getTextureLocation(FortressMaster entity) {
        return TEXTURE;
    }

    @Override
    protected void scale(FortressMaster entity, PoseStack pose, float partialTick) {
        pose.scale(SCALE, SCALE, SCALE);
    }

    /** Без ванильного заваливания на бок при смерти: смерть играет клип {@code death}. */
    @Override
    protected void setupRotations(FortressMaster entity, PoseStack pose, float bob, float yBodyRot, float partialTick, float scale) {
        pose.mulPose(Axis.YP.rotationDegrees(180.0F - yBodyRot));
    }

    /** Последние 3 тика замаха — белая вспышка: «сейчас ударит». */
    @Override
    protected float getWhiteOverlayProgress(FortressMaster entity, float partialTick) {
        if (entity.state() == FortressMaster.WINDUP) {
            float left = entity.move().windup() - entity.stateAge(partialTick);
            if (left <= 3.0F) {
                return 0.3F * Math.max(0.0F, 1.0F - Math.abs(left - 1.5F) / 1.5F) + 0.1F;
            }
        }
        return 0.0F;
    }

    /** Метки и барьер выходят далеко за тело: рисовать, пока хозяин рядом, даже вне кадра. */
    @Override
    public boolean shouldRender(FortressMaster entity, Frustum camera, double camX, double camY, double camZ) {
        return entity.arena() && entity.distanceToSqr(camX, camY, camZ) < 64 * 64 || super.shouldRender(entity, camera, camX, camY, camZ);
    }

    @Override
    public void render(FortressMaster entity, float yaw, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {
        super.render(entity, yaw, partialTick, pose, buffers, light);
        if (entity.deathTime > 0) {
            return;
        }
        Vector3f yard = entity.yard();
        double ex = Mth.lerp(partialTick, entity.xo, entity.getX());
        double ey = Mth.lerp(partialTick, entity.yo, entity.getY());
        double ez = Mth.lerp(partialTick, entity.zo, entity.getZ());
        // Земля плаца в системе координат сущности.
        float floor = (float) (yard.y - ey) + 0.04F;
        VertexConsumer vc = buffers.getBuffer(RenderType.lightning());
        Matrix4f m = pose.last().pose();
        float age = entity.stateAge(partialTick);
        float pulse = 0.75F + 0.25F * Mth.sin((entity.tickCount + partialTick) * 0.6F);
        BossMove move = entity.move();
        int state = entity.state();
        Vector3f mk = entity.mark();
        if (state == FortressMaster.STRIKE && move == BossMove.POUNCE) {
            // Кольцо приземления: зафиксировано до отрыва, растёт яркость к моменту удара.
            float k = Mth.clamp(age / move.strike(), 0.0F, 1.0F);
            ring(vc, m, (float) (mk.x - ex), floor, (float) (mk.y - ez), (float) BossMove.POUNCE_RADIUS, 0.28F, (0.45F + 0.5F * k) * pulse);
            disc(vc, m, (float) (mk.x - ex), floor - 0.01F, (float) (mk.y - ez), (float) BossMove.POUNCE_RADIUS * k, 0.18F * pulse);
        }
        if ((state == FortressMaster.WINDUP || state == FortressMaster.STRIKE) && (move == BossMove.RAM || move == BossMove.WHIRL)) {
            double half = move == BossMove.RAM ? BossMove.RAM_HALF_WIDTH : BossMove.WHIRL_HALF_WIDTH;
            double len = BossRules.laneLength(mk.x, mk.y, mk.z, yard.x, yard.z, FortressMaster.YARD_HALF, 1.0D,
                    move == BossMove.RAM ? BossMove.RAM_LENGTH : BossMove.WHIRL_MAX_LENGTH) + half;
            float a = state == FortressMaster.WINDUP ? 0.35F + 0.5F * Mth.clamp(age / move.windup(), 0.0F, 1.0F) : 0.5F;
            lane(vc, m, mk.x - ex, floor, mk.y - ez, mk.z, len, half, a * pulse);
        }
        if (state == FortressMaster.WINDUP && move == BossMove.ROAR) {
            float k = Mth.clamp(age / move.windup(), 0.0F, 1.0F);
            ring(vc, m, 0.0F, floor, 0.0F, (float) BossMove.ROAR_RADIUS, 0.3F, (0.4F + 0.5F * k) * pulse);
            disc(vc, m, 0.0F, floor - 0.01F, 0.0F, (float) BossMove.ROAR_RADIUS * k, 0.12F * pulse);
        }
        if (move == BossMove.SPLIT && (state == FortressMaster.WINDUP && age >= BossMove.SPLIT_PLANT || state == FortressMaster.STRIKE)) {
            float k = state == FortressMaster.STRIKE ? 1.0F : Mth.clamp((age - BossMove.SPLIT_PLANT) / (move.windup() - BossMove.SPLIT_PLANT), 0.0F, 1.0F);
            for (double lineYaw : BossRules.splitYaws(mk.z)) {
                lane(vc, m, 0.0D, floor, 0.0D, (float) lineYaw, BossMove.SPLIT_LENGTH, BossMove.SPLIT_HALF_WIDTH, (0.35F + 0.55F * k) * pulse);
            }
            if (state == FortressMaster.STRIKE) {
                double front = BossMove.SPLIT_LENGTH * Math.min(1.0D, (age + 1.0D) / move.strike());
                for (double lineYaw : BossRules.splitYaws(mk.z)) {
                    double[] f = BossRules.forward(lineYaw);
                    lane(vc, m, f[0] * Math.max(0.0D, front - 3.0D), floor + 0.01F, f[1] * Math.max(0.0D, front - 3.0D), (float) lineYaw,
                            Math.min(3.0D, front), BossMove.SPLIT_HALF_WIDTH * 1.3D, 0.95F);
                }
            }
        }
        if (entity.arena()) {
            barrier(vc, m, (float) (yard.x - ex), floor, (float) (yard.z - ez), (float) FortressMaster.YARD_HALF, entity.tickCount + partialTick);
        }
    }

    // ------------------------------------------------------------------ геометрия меток

    private static void quad(VertexConsumer vc, Matrix4f m, float[] a, float[] b, float[] c, float[] d, float r, float g, float bl, float alpha) {
        if (alpha <= 0.0F) {
            return;
        }
        // Обе стороны: у lightning-слоя включено отсечение задних граней.
        vc.addVertex(m, a[0], a[1], a[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, b[0], b[1], b[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, c[0], c[1], c[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, d[0], d[1], d[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, d[0], d[1], d[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, c[0], c[1], c[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, b[0], b[1], b[2]).setColor(r, g, bl, alpha);
        vc.addVertex(m, a[0], a[1], a[2]).setColor(r, g, bl, alpha);
    }

    /** Кольцо на земле: центр (x, z), радиус, толщина линии. */
    private static void ring(VertexConsumer vc, Matrix4f m, float x, float y, float z, float radius, float width, float alpha) {
        int n = 40;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            float c0 = (float) Math.cos(a0), s0 = (float) Math.sin(a0), c1 = (float) Math.cos(a1), s1 = (float) Math.sin(a1);
            float ri = radius - width, ro = radius;
            quad(vc, m, new float[] {x + c0 * ri, y, z + s0 * ri}, new float[] {x + c0 * ro, y, z + s0 * ro},
                    new float[] {x + c1 * ro, y, z + s1 * ro}, new float[] {x + c1 * ri, y, z + s1 * ri}, MR, MG, MB, alpha);
        }
    }

    /** Заливка круга (растёт к удару). */
    private static void disc(VertexConsumer vc, Matrix4f m, float x, float y, float z, float radius, float alpha) {
        if (radius < 0.05F) {
            return;
        }
        int n = 24;
        for (int i = 0; i < n; i++) {
            double a0 = Math.PI * 2 * i / n, a1 = Math.PI * 2 * (i + 1) / n;
            quad(vc, m, new float[] {x, y, z}, new float[] {x + (float) Math.cos(a0) * radius, y, z + (float) Math.sin(a0) * radius},
                    new float[] {x + (float) Math.cos(a1) * radius, y, z + (float) Math.sin(a1) * radius}, new float[] {x, y, z}, MR, MG, MB, alpha);
        }
    }

    /** Полоса от точки (x, z) по yaw: две кромки, бледная заливка и шевроны направления. */
    private static void lane(VertexConsumer vc, Matrix4f m, double x, float y, double z, float yaw, double length, double half, float alpha) {
        double[] f = BossRules.forward(yaw);
        double sx = f[1], sz = -f[0];
        for (int side : new int[] {-1, 1}) {
            double ox = sx * half * side, oz = sz * half * side;
            double ix = sx * (half - 0.22D) * side, iz = sz * (half - 0.22D) * side;
            quad(vc, m, p(x + ox, y, z + oz), p(x + ox + f[0] * length, y, z + oz + f[1] * length),
                    p(x + ix + f[0] * length, y, z + iz + f[1] * length), p(x + ix, y, z + iz), MR, MG, MB, alpha);
        }
        quad(vc, m, p(x - sx * half, y - 0.005F, z - sz * half), p(x - sx * half + f[0] * length, y - 0.005F, z - sz * half + f[1] * length),
                p(x + sx * half + f[0] * length, y - 0.005F, z + sz * half + f[1] * length), p(x + sx * half, y - 0.005F, z + sz * half), MR, MG, MB, alpha * 0.18F);
        for (double d = 1.5D; d < length - 0.5D; d += 2.0D) {
            double cx = x + f[0] * d, cz = z + f[1] * d;
            double w = half * 0.55D;
            for (int side : new int[] {-1, 1}) {
                double tx = cx + sx * w * side - f[0] * 0.6D, tz = cz + sz * w * side - f[1] * 0.6D;
                quad(vc, m, p(tx, y, tz), p(cx, y, cz), p(cx + f[0] * 0.18D, y, cz + f[1] * 0.18D), p(tx + f[0] * 0.18D, y, tz + f[1] * 0.18D),
                        MR, MG, MB, alpha * 0.8F);
            }
        }
    }

    /** Барьер ци: низкая красная стена по краю плаца, мерцает бегущей волной. */
    private static void barrier(VertexConsumer vc, Matrix4f m, float cx, float y, float cz, float half, float time) {
        float h = 2.2F;
        int seg = 25;
        float[][] corners = {{-half, -half}, {half, -half}, {half, half}, {-half, half}};
        for (int c = 0; c < 4; c++) {
            float[] a = corners[c], b = corners[(c + 1) % 4];
            for (int i = 0; i < seg; i++) {
                float t0 = i / (float) seg, t1 = (i + 1) / (float) seg;
                float x0 = cx + Mth.lerp(t0, a[0], b[0]), z0 = cz + Mth.lerp(t0, a[1], b[1]);
                float x1 = cx + Mth.lerp(t1, a[0], b[0]), z1 = cz + Mth.lerp(t1, a[1], b[1]);
                float wave = 0.5F + 0.5F * Mth.sin(time * 0.25F + (c * seg + i) * 0.7F);
                float alpha = 0.10F + 0.10F * wave;
                quad(vc, m, new float[] {x0, y, z0}, new float[] {x1, y, z1}, new float[] {x1, y + h, z1}, new float[] {x0, y + h, z0},
                        BR, BG, BB, alpha);
                quad(vc, m, new float[] {x0, y + h - 0.12F, z0}, new float[] {x1, y + h - 0.12F, z1}, new float[] {x1, y + h, z1},
                        new float[] {x0, y + h, z0}, BR, BG, BB, 0.45F + 0.3F * wave);
            }
        }
    }

    private static float[] p(double x, float y, double z) {
        return new float[] {(float) x, y, (float) z};
    }

    @SubscribeEvent
    static void onLayers(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(LAYER, () -> BedrockGeo.load("/assets/murim/bedrock/fortress_master.geo.json"));
    }

    @SubscribeEvent
    static void onRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(BossRegistry.FORTRESS_MASTER.get(), BossRenderer::new);
    }
}
