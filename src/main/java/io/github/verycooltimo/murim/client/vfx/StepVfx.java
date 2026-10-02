package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.StepPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.player.PlayerRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/**
 * Шаг Невидимого Аромата — эффекты по слоям (референсы «huashan footwork», «senior 1st
 * disciple footwork»; Хуашань гл. 134).
 *
 * <ol start="0">
 *   <li>ничего — только рывок;</li>
 *   <li>тонкие бело-голубые линии из-под подошвы, текущие по земле назад, и завиток
 *       на прежнем месте;</li>
 *   <li>+ полупрозрачный двойник на старом месте тает; бело-голубые струи догоняют тело
 *       от старой позиции к новой;</li>
 *   <li>+ мягкое розовое свечение в струях и несколько лепестков сливы, медленно опадающих;</li>
 *   <li>три рывка подряд — струи переплетаются по всему пути.</li>
 * </ol>
 *
 * <p>Визуальный язык — течение воды: плавные ленты, завитки, прозрачные струи, без плотной
 * массы. Цвета — белый и холодный голубой, розовое — только у мастера.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class StepVfx {

    private static final VfxColour WHITE = hex(0xF4F8FF);
    private static final VfxColour BLUE = hex(0x9CC8FF);
    private static final VfxColour PINK = hex(0xF3A6BE);

    private static final List<Step> ACTIVE = new ArrayList<>();
    private static int clientTicks;

    private static final class Petal {
        Vec3 pos;
        Vec3 prev;
        Vec3 vel;
        int age;
        final int life;
        final int cell;
        final float spin;

        Petal(Vec3 pos, Vec3 vel, int life, int cell, float spin) {
            this.pos = pos;
            this.prev = pos;
            this.vel = vel;
            this.life = life;
            this.cell = cell;
            this.spin = spin;
        }
    }

    private static final class Step {
        final int entityId;
        final Vec3 from;
        final Vec3 to;
        final float yaw;
        final int layer;
        final int startTick;
        final Random random;
        final List<Petal> petals = new ArrayList<>();

        Step(StepPayload p) {
            this.entityId = p.entityId();
            this.from = p.from();
            this.to = p.to();
            this.yaw = p.yaw();
            this.layer = p.layer();
            this.startTick = clientTicks;
            this.random = new Random(p.entityId() * 31L + clientTicks);
        }

        float age(float partial) {
            return clientTicks - startTick + partial;
        }

        Vec3 dir() {
            Vec3 d = to.subtract(from);
            d = new Vec3(d.x, 0.0D, d.z);
            return d.lengthSqr() < 1.0E-6D ? Vec3.directionFromRotation(0.0F, yaw) : d.normalize();
        }
    }

    public static void start(StepPayload payload) {
        if (payload.layer() <= 0) {
            return;
        }
        Step s = new Step(payload);
        if (s.layer >= 3) {
            Random r = s.random;
            int n = 6 + r.nextInt(4);
            for (int i = 0; i < n; i++) {
                double t = r.nextDouble();
                Vec3 at = s.from.lerp(s.to, t).add((r.nextDouble() - 0.5D) * 0.8D, 0.4D + 1.2D * r.nextDouble(),
                        (r.nextDouble() - 0.5D) * 0.8D);
                s.petals.add(new Petal(at, s.dir().scale(0.04D + 0.04D * r.nextDouble()).add(0.0D, -0.01D, 0.0D),
                        30 + r.nextInt(20), r.nextInt(4), (r.nextFloat() - 0.5F) * 0.4F));
            }
        }
        ACTIVE.add(s);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            ACTIVE.clear();
            return;
        }
        if (minecraft.isPaused()) {
            return;
        }
        clientTicks++;
        Iterator<Step> it = ACTIVE.iterator();
        while (it.hasNext()) {
            Step s = it.next();
            Iterator<Petal> pi = s.petals.iterator();
            while (pi.hasNext()) {
                Petal p = pi.next();
                p.prev = p.pos;
                if (++p.age >= p.life) {
                    pi.remove();
                    continue;
                }
                // Лепесток: тормозит, покачивается и медленно опускается.
                double sway = Math.sin(p.age * 0.3D + p.spin * 10.0D) * 0.01D;
                p.vel = new Vec3(p.vel.x * 0.94D + sway, Math.max(-0.02D, p.vel.y - 0.001D), p.vel.z * 0.94D - sway);
                p.pos = p.pos.add(p.vel);
            }
            if (s.age(0) > 50.0F && s.petals.isEmpty()) {
                it.remove();
            }
        }
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || ACTIVE.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 camera = event.getCamera().getPosition();
        PoseStack poseStack = event.getPoseStack();
        MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        poseStack.pushPose();
        try {
            poseStack.translate(-camera.x, -camera.y, -camera.z);
            for (Step s : ACTIVE) {
                float age = s.age(partial);
                if (s.layer >= 2) {
                    ghost(minecraft, s, poseStack, buffers, age);
                }
                PoseStack.Pose pose = poseStack.last();
                VertexConsumer glow = buffers.getBuffer(MurimRenderTypes.ribbon());
                soleLines(s, pose, glow, age);
                swirl(s, pose, camera, glow, age);
                if (s.layer >= 2) {
                    streams(s, pose, camera, glow, age);
                }
                buffers.endBatch(MurimRenderTypes.ribbon());
                if (!s.petals.isEmpty()) {
                    RenderType type = MurimRenderTypes.plumPetals();
                    VertexConsumer c = buffers.getBuffer(type);
                    for (Petal p : s.petals) {
                        float t = (p.age + partial) / p.life;
                        float a = Mth.clamp((1.0F - t) * 3.0F, 0.0F, 1.0F);
                        petal(c, pose, camera, p.prev.lerp(p.pos, partial), 0.13D, p.cell, (p.age + partial) * p.spin, a);
                    }
                    buffers.endBatch(type);
                }
            }
        } finally {
            poseStack.popPose();
        }
    }

    /** Тонкие линии из-под подошвы: 3–4 плоские извилистые струйки по земле назад от точки толчка. */
    private static void soleLines(Step s, PoseStack.Pose pose, VertexConsumer c, float age) {
        if (age > 14.0F) {
            return;
        }
        float k = age / 14.0F;
        float alpha = (1.0F - k) * (1.0F - k);
        Vec3 dir = s.dir();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        Random r = new Random(s.entityId * 13L + s.startTick);
        for (int line = 0; line < 4; line++) {
            double off = (line - 1.5D) * 0.12D;
            double wob = r.nextDouble() * 6.0D;
            double len = (0.6D + 0.5D * r.nextDouble()) * (0.4D + 0.6D * Math.min(1.0D, age / 4.0D));
            Vec3 prev = null;
            for (int i = 0; i <= 8; i++) {
                double u = i / 8.0D;
                Vec3 p = s.from.add(dir.scale(-u * len)).add(side.scale(off + Math.sin(u * 5.0D + wob + age * 0.3D) * 0.06D))
                        .add(0.0D, 0.03D, 0.0D);
                if (prev != null) {
                    flat(c, pose, prev, p, 0.025D * (1.0D - u * 0.7D), alpha * (float) (1.0D - u), i % 2 == 0 ? WHITE : BLUE);
                }
                prev = p;
            }
        }
    }

    /** Завиток на прежнем месте: лента одним витком поднимается и тает. */
    private static void swirl(Step s, PoseStack.Pose pose, Vec3 camera, VertexConsumer c, float age) {
        if (age > 16.0F) {
            return;
        }
        float k = age / 16.0F;
        float alpha = 0.7F * (1.0F - k);
        double baseAngle = Math.toRadians(-s.yaw);
        Vec3 prev = null;
        for (int i = 0; i <= 16; i++) {
            double u = i / 16.0D;
            double a = baseAngle + u * Math.PI * 1.6D + age * 0.15D;
            double r = 0.35D + 0.1D * u + 0.2D * k;
            Vec3 p = s.from.add(Math.cos(a) * r, 0.15D + u * (0.9D + 0.6D * k), Math.sin(a) * r);
            if (prev != null) {
                VfxDraw.segment(c, pose, prev, p, camera, 0.035D * (1.0D - u * 0.6D), alpha * (float) Math.sin(Math.PI * u),
                        WHITE.red(), WHITE.green(), WHITE.blue());
            }
            prev = p;
        }
    }

    /**
     * Струи: 3–5 извилистых лент от старой позиции к новой, бегут вслед за телом за 6 тиков
     * и гаснут; на третьем слое — розовая подсветка шире основной ленты.
     */
    private static void streams(Step s, PoseStack.Pose pose, Vec3 camera, VertexConsumer c, float age) {
        if (age > 18.0F) {
            return;
        }
        float head = Mth.clamp(age / 6.0F, 0.0F, 1.0F);
        float fade = age < 6.0F ? 1.0F : Mth.clamp(1.0F - (age - 6.0F) / 12.0F, 0.0F, 1.0F);
        float tail = Mth.clamp((age - 3.0F) / 10.0F, 0.0F, 1.0F);
        Vec3 dir = s.dir();
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
        Random r = new Random(s.entityId * 17L + s.startTick);
        int n = 3 + r.nextInt(3);
        for (int k = 0; k < n; k++) {
            double h = 0.4D + 1.1D * r.nextDouble();
            double amp = 0.15D + 0.25D * r.nextDouble();
            double ph = r.nextDouble() * 6.0D;
            Vec3 prev = null;
            for (int i = 0; i <= 20; i++) {
                double u = tail + (head - tail) * i / 20.0D;
                Vec3 p = s.from.lerp(s.to, u).add(side.scale(Math.sin(u * Math.PI * 2.0D + ph) * amp * Math.sin(Math.PI * u)))
                        .add(0.0D, h + Math.cos(u * Math.PI * 2.0D + ph) * 0.15D, 0.0D);
                if (prev != null) {
                    float a = fade * (float) Math.sin(Math.PI * i / 20.0D);
                    if (s.layer >= 3) {
                        VfxDraw.segment(c, pose, prev, p, camera, 0.12D, 0.22F * a, PINK.red(), PINK.green(), PINK.blue());
                    }
                    VfxDraw.segment(c, pose, prev, p, camera, 0.05D, 0.4F * a, BLUE.red(), BLUE.green(), BLUE.blue());
                    VfxDraw.segment(c, pose, prev, p, camera, 0.018D, 0.75F * a, WHITE.red(), WHITE.green(), WHITE.blue());
                }
                prev = p;
            }
        }
    }

    /** Полупрозрачный двойник на прежнем месте, холодно-голубой, тает за полсекунды. */
    private static void ghost(Minecraft minecraft, Step s, PoseStack poseStack, MultiBufferSource.BufferSource buffers, float age) {
        if (age > 10.0F || !(minecraft.level.getEntity(s.entityId) instanceof AbstractClientPlayer player)) {
            return;
        }
        EntityRenderer<? super AbstractClientPlayer> renderer = minecraft.getEntityRenderDispatcher().getRenderer(player);
        if (!(renderer instanceof PlayerRenderer playerRenderer)) {
            return;
        }
        float alpha = 0.55F * (1.0F - age / 10.0F);
        PlayerModel<AbstractClientPlayer> model = playerRenderer.getModel();
        poseStack.pushPose();
        try {
            // Как LivingEntityRenderer: поворот корпуса, отражение осей, масштаб игрока, подъём на 1,501.
            // API: reference/minecraft-src/net/minecraft/client/renderer/entity/LivingEntityRenderer.java#render
            poseStack.translate(s.from.x, s.from.y, s.from.z);
            poseStack.mulPose(Axis.YP.rotationDegrees(180.0F - s.yaw));
            poseStack.scale(-1.0F, -1.0F, 1.0F);
            poseStack.scale(0.9375F, 0.9375F, 0.9375F);
            poseStack.translate(0.0F, -1.501F, 0.0F);
            RenderType type = RenderType.entityTranslucent(player.getSkin().texture());
            int colour = ((int) (alpha * 255.0F) << 24) | 0xC8E0FF;
            model.renderToBuffer(poseStack, buffers.getBuffer(type), 0x00F000F0, OverlayTexture.NO_OVERLAY, colour);
            buffers.endBatch(type);
        } finally {
            poseStack.popPose();
        }
    }

    private static void petal(VertexConsumer c, PoseStack.Pose pose, Vec3 camera, Vec3 centre, double size, int cell, float spin, float alpha) {
        Vec3 forward = camera.subtract(centre);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right0 = forward.cross(reference).normalize();
        Vec3 up0 = right0.cross(forward).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 right = right0.scale(cs).add(up0.scale(sn)).scale(size);
        Vec3 up = up0.scale(cs).subtract(right0.scale(sn)).scale(size);
        float u0 = (cell % 2) / 2.0F, u1 = u0 + 0.5F;
        float v0 = (cell / 2) / 2.0F, v1 = v0 + 0.5F;
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, centre.subtract(right).subtract(up), n, u0, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).subtract(up), n, u1, v1, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.add(right).add(up), n, u1, v0, alpha, 1.0F, 1.0F, 1.0F);
        VfxDraw.vertex(c, pose, centre.subtract(right).add(up), n, u0, v0, alpha, 1.0F, 1.0F, 1.0F);
    }

    private static void flat(VertexConsumer c, PoseStack.Pose pose, Vec3 a, Vec3 z, double width, float alpha, VfxColour col) {
        Vec3 d = z.subtract(a);
        Vec3 s = new Vec3(-d.z, 0.0D, d.x);
        if (s.lengthSqr() < 1.0E-9D) {
            return;
        }
        s = s.normalize().scale(width);
        Vec3 n = new Vec3(0.0D, 1.0D, 0.0D);
        VfxDraw.vertex(c, pose, a.subtract(s), n, 0.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, z.subtract(s), n, 1.0F, 0.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, z.add(s), n, 1.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
        VfxDraw.vertex(c, pose, a.add(s), n, 0.0F, 1.0F, alpha, col.red(), col.green(), col.blue());
    }

    private static VfxColour hex(int c) {
        return new VfxColour((c >> 16 & 255) / 255.0F, (c >> 8 & 255) / 255.0F, (c & 255) / 255.0F);
    }

    private StepVfx() {
    }
}
