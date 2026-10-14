package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import io.github.verycooltimo.murim.technique.FunnyJunkTiming;
import io.github.verycooltimo.murim.technique.JunkArts;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

/** Tiny joke effects at every mastery layer: dragon hands during anticipation, dust on each tap. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FunnyJunkVfx {
    private static final Map<Integer, Cast> CASTS = new HashMap<>();
    private static final VfxColour GOLD = new VfxColour(1.0F, 0.78F, 0.42F);
    private static final VfxColour WHITE = new VfxColour(1.0F, 0.94F, 0.80F);

    private static final class Cast {
        final ResourceLocation technique;
        final int duration;
        int tick;
        final ArrayList<Puff> dust = new ArrayList<>();

        Cast(TechniqueDefinition definition) {
            technique = definition.id();
            tick = -definition.startTickOf(TechniquePhase.WINDUP);
            duration = definition.totalTicks() - definition.startTickOf(TechniquePhase.WINDUP);
        }
    }

    private static final class Puff {
        final Vec3 at;
        final Vec3 drift;
        final int cell;
        int age;

        Puff(Vec3 at, Vec3 drift, int cell) {
            this.at = at;
            this.drift = drift;
            this.cell = cell;
        }
    }

    public static void start(int entity, TechniqueDefinition definition) {
        if (definition != null && JunkArts.FUNNY.contains(definition.id())) {
            CASTS.put(entity, new Cast(definition));
        }
    }

    public static void cancel(int entity) {
        CASTS.remove(entity);
    }

    public static void reset() {
        CASTS.clear();
    }

    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            reset();
            return;
        }
        if (mc.isPaused()) {
            return;
        }
        var iterator = CASTS.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            Cast cast = entry.getValue();
            Entity entity = mc.level.getEntity(entry.getKey());
            if (entity == null || !entity.isAlive() || cast.tick >= cast.duration) {
                iterator.remove();
                continue;
            }
            cast.dust.removeIf(p -> ++p.age >= 9);
            if (JunkArts.BEGGARS_TAP.equals(cast.technique) && FunnyJunkTiming.stomp(cast.tick)
                    && entity.onGround()) {
                boolean right = FunnyJunkTiming.rightFoot(cast.tick);
                Vec3 foot = BoneAnchorLayer.position(entity,
                        right ? BoneAnchorLayer.Bone.RIGHT_FOOT : BoneAnchorLayer.Bone.LEFT_FOOT);
                // First person has no drawn leg: fall back to the foot's lateral ground position.
                if (foot == null || foot.distanceToSqr(entity.position()) > 9.0D) {
                    double yaw = Math.toRadians(entity.getYRot());
                    double side = right ? -0.14D : 0.14D;
                    foot = entity.position().add(Math.cos(yaw) * side, 0.0D, Math.sin(yaw) * side);
                }
                foot = new Vec3(foot.x, entity.getY() + 0.04D, foot.z);
                // Three puffs squeezed out around the sole (front, outer side, back): one centred puff
                // hid behind the planted foot and leg from most angles (in-game film 10.10).
                double yaw = Math.toRadians(entity.getYRot());
                Vec3 forward = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
                Vec3 outer = new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw)).scale(right ? -1.0D : 1.0D);
                Vec3[] dirs = {forward, outer, forward.scale(-1.0D).add(outer).normalize()};
                for (int i = 0; i < dirs.length; i++) {
                    cast.dust.add(new Puff(foot.add(dirs[i].scale(0.12D)), dirs[i].scale(0.22D),
                            Math.floorMod(cast.tick * 3 + i * 5, 16)));
                }
            }
            cast.tick++;
        }
    }

    @SubscribeEvent
    static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES || CASTS.isEmpty()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Vec3 camera = event.getCamera().getPosition();
        float partial = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        PoseStack ps = event.getPoseStack();
        var buffers = mc.renderBuffers().bufferSource();
        ps.pushPose();
        try {
            ps.translate(-camera.x, -camera.y, -camera.z);
            var glowType = MurimRenderTypes.essence();
            var glow = buffers.getBuffer(glowType);
            for (var entry : CASTS.entrySet()) {
                Cast cast = entry.getValue();
                Entity entity = mc.level.getEntity(entry.getKey());
                float age = cast.tick + partial;
                if (entity == null || !JunkArts.HEAVENLY_DRAGON.equals(cast.technique) || age < 0 || age > 19) {
                    continue;
                }
                float intensity = Mth.clamp(age / 11.0F, 0.0F, 1.0F)
                        * Mth.clamp((19.0F - age) / 3.0F, 0.0F, 1.0F);
                for (var bone : new BoneAnchorLayer.Bone[] {BoneAnchorLayer.Bone.RIGHT_HAND, BoneAnchorLayer.Bone.LEFT_HAND}) {
                    Vec3 hand = BoneAnchorLayer.position(entity, bone);
                    if (hand != null && hand.distanceToSqr(entity.position()) < 9.0D) {
                        CoreGlow.draw(glow, ps.last(), hand, camera, age, 0.09D, intensity, GOLD, WHITE);
                    }
                }
            }
            buffers.endBatch(glowType);
            var dustType = MurimRenderTypes.dustPuffs();
            var dust = buffers.getBuffer(dustType);
            for (Cast cast : CASTS.values()) {
                for (Puff puff : cast.dust) {
                    float age = (puff.age + partial) / 9.0F;
                    PlumVfx.puff(dust, ps.last(), camera,
                            puff.at.add(puff.drift.scale(Math.sqrt(age))).add(0.0D, 0.04D + age * 0.10D, 0.0D),
                            0.10D + age * 0.12D, puff.cell, 0.75F * (1.0F - age), 0.82F);
                }
            }
            buffers.endBatch(dustType);
        } finally {
            ps.popPose();
        }
    }

    private FunnyJunkVfx() {
    }
}
