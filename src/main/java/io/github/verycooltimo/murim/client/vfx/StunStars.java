package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Признак оглушения (combat/Stun): три чётких золотых четырёхлучевых звезды кружат над головой,
 * пока цель оглушена. Не эффект техники, а читаемый индикатор состояния — правило «слой 0 без
 * VFX» к нему не относится. Плоские непрозрачные звёзды без свечения (манхва-стиль, без блюра).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class StunStars {

    /** id сущности → игровое время конца оглушения. */
    private static final Map<Integer, Long> UNTIL = new ConcurrentHashMap<>();

    private static final int STARS = 3;
    private static final float R = 1.0F, G = 0.84F, B = 0.25F;

    public static void onStun(int entityId, int ticks) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        if (ticks <= 0) {
            UNTIL.remove(entityId);
        } else {
            UNTIL.put(entityId, mc.level.getGameTime() + ticks);
        }
    }

    @SubscribeEvent
    static void onRenderStage(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || UNTIL.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            UNTIL.clear();
            return;
        }
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        long now = mc.level.getGameTime();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();
        VertexConsumer out = buffers.getBuffer(MurimRenderTypes.solid());
        PoseStack.Pose pose = event.getPoseStack().last();
        UNTIL.entrySet().removeIf(e -> {
            Entity entity = mc.level.getEntity(e.getKey());
            if (entity == null || !entity.isAlive() || now >= e.getValue()) {
                return true;
            }
            if (entity == mc.player && mc.options.getCameraType().isFirstPerson()) {
                return false;
            }
            Vec3 head = new Vec3(Mth.lerp(partial, entity.xOld, entity.getX()),
                    Mth.lerp(partial, entity.yOld, entity.getY()) + entity.getBbHeight() + 0.3D,
                    Mth.lerp(partial, entity.zOld, entity.getZ())).subtract(cam);
            double radius = Math.max(0.4D, entity.getBbWidth() * 0.7D);
            double t = (now + partial) * 0.22D;
            for (int i = 0; i < STARS; i++) {
                double a = t + i * Math.PI * 2.0D / STARS;
                Vec3 c = head.add(Math.cos(a) * radius, Math.sin(a * 2.0D) * 0.05D, Math.sin(a) * radius);
                star(out, pose, c, 0.17D, (float) (a * 1.5D));
            }
            return false;
        });
        buffers.endBatch(MurimRenderTypes.solid());
    }

    /** Четырёхлучевая звезда к камере: два узких ромба крестом, повёрнутые на {@code spin}. */
    private static void star(VertexConsumer out, PoseStack.Pose pose, Vec3 c, double size, float spin) {
        Vec3 forward = c.scale(-1.0D);
        if (forward.lengthSqr() < 1.0E-6D) {
            return;
        }
        forward = forward.normalize();
        Vec3 reference = Math.abs(forward.y) > 0.95D ? new Vec3(1.0D, 0.0D, 0.0D) : new Vec3(0.0D, 1.0D, 0.0D);
        Vec3 right = forward.cross(reference).normalize();
        Vec3 up = right.cross(forward).normalize();
        double cs = Math.cos(spin), sn = Math.sin(spin);
        Vec3 u = right.scale(cs).add(up.scale(sn));
        Vec3 v = up.scale(cs).subtract(right.scale(sn));
        double thin = size * 0.28D;
        diamond(out, pose, c, u.scale(size), v.scale(thin), forward);
        diamond(out, pose, c, v.scale(size), u.scale(thin), forward);
    }

    private static void diamond(VertexConsumer out, PoseStack.Pose pose, Vec3 c, Vec3 longAxis, Vec3 shortAxis, Vec3 n) {
        VfxDraw.vertex(out, pose, c.add(longAxis), n, 0.0F, 0.0F, 1.0F, R, G, B);
        VfxDraw.vertex(out, pose, c.add(shortAxis), n, 0.0F, 1.0F, 1.0F, R, G, B);
        VfxDraw.vertex(out, pose, c.subtract(longAxis), n, 1.0F, 1.0F, 1.0F, R, G, B);
        VfxDraw.vertex(out, pose, c.subtract(shortAxis), n, 1.0F, 0.0F, 1.0F, R, G, B);
    }

    private StunStars() {
    }
}
