package io.github.verycooltimo.murim.client;

import com.mojang.math.Axis;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.combat.SwordGuard;
import io.github.verycooltimo.murim.network.SwordGuardPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/** Immediate pose prediction only; server confirms windows, clangs and parries. No HUD. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class SwordGuardClient {
    private static boolean held;
    private static boolean requireRelease;
    private static net.minecraft.client.player.LocalPlayer owner;

    public static void accept(SwordGuardPayloads.View payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(payload.entityId()) instanceof LivingEntity e)) return;
        var s = SwordGuard.state(e);
        if (payload.state() == 4) {
            s.shown = 0;
            s.end = Long.MIN_VALUE;
            if (e == mc.player) {
                held = false;
                requireRelease = mc.options.keyUse.isDown();
            }
            return;
        }
        if (payload.state() == 3) {
            s.end = e.tickCount + 4; // Short blade trail after confirmation.
            if (e == mc.player) CameraShakeHandler.quake(0.10F, 3);
            if (mc.player != null && mc.player.distanceToSqr(e) < 32 * 32) {
                int count = particleCount(mc, 9);
                for (int i = 0; i < count; i++) {
                    double angle = i * Math.PI * 2 / Math.max(1, count);
                    mc.level.addParticle(ParticleTypes.ELECTRIC_SPARK, e.getX(), e.getY() + 1.2D, e.getZ(),
                            Math.cos(angle) * 0.15D, 0.05D, Math.sin(angle) * 0.15D);
                }
            }
        } else {
            s.shown = payload.state();
            s.contact = e.tickCount; // A pose lease also handles untracking, death and missed releases.
            if (payload.state() == 2) s.end = e.tickCount + 5;
        }
    }
    private static int particleCount(Minecraft mc, int full) {
        // Existing client particle path (BreakthroughParticles/ColdIronSparks); bounded cost per event.
        if (mc.options.particles().get() == net.minecraft.client.ParticleStatus.MINIMAL) return 0;
        int count = Math.max(1, full * ClientConfig.trailSegments() / 28);
        return mc.options.particles().get() == net.minecraft.client.ParticleStatus.DECREASED ? Math.max(1, count / 2) : count;
    }
    public static void technique(io.github.verycooltimo.murim.network.TechniqueEventPayload payload) {
        var mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(payload.sourceId()) instanceof LivingEntity e)) return;
        if (payload.event() == io.github.verycooltimo.murim.network.TechniqueEventPayload.Event.STARTED)
            SwordGuard.state(e).suppressPose = true;
        if (payload.event() == io.github.verycooltimo.murim.network.TechniqueEventPayload.Event.CANCELLED
                || payload.event() == io.github.verycooltimo.murim.network.TechniqueEventPayload.Event.FINISHED)
            SwordGuard.state(e).suppressPose = false;
    }
    private static boolean reservedInput() {
        return ClientPenanceState.pinned() || ClientMeditationState.state().active()
                || ClientPillState.state().active();
    }
    public static boolean pose(LivingEntity e) {
        var mc = Minecraft.getInstance();
        if (!e.isAlive() || SwordGuard.state(e).suppressPose
                || (!(e instanceof io.github.verycooltimo.murim.entity.BanditSwordsman) && !SwordGuard.handsAllowGuard(e))) return false;
        if (e == mc.player) return held && !reservedInput() && e.tickCount - SwordGuard.state(e).swing >= 3;
        var s = SwordGuard.state(e);
        return s.shown > 0 && e.tickCount - s.contact < 30;
    }
    public static void swing() {
        var p = Minecraft.getInstance().player;
        if (p != null) SwordGuard.state(p).swing = p.tickCount;
    }
    private static void send(boolean down) {
        if (held == down) return;
        held = down;
        PacketDistributor.sendToServer(new SwordGuardPayloads.Input(down ? 1 : 0));
    }
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void input(InputEvent.InteractionKeyMappingTriggered event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null || reservedInput() || !SwordGuard.handsAllowGuard(mc.player)) return;
        if (event.isUseItem() && !event.isCanceled()) {
            if (!requireRelease) send(true);
            // Outside a fight a right click on a person or a block is still that click: with a sword in hand it used to be swallowed
            // by the guard, and three clicks on a senior opened no dialogue (probe on timoserver, 24.10). In a fight the click is
            // only the guard (a bout answers every use of its partner with «busy»).
            boolean passThrough = !CombatMode.active() && mc.hitResult != null && mc.hitResult.getType() != HitResult.Type.MISS;
            if (!passThrough) {
                event.setCanceled(true);
                event.setSwingHand(false);
            }
        } else if (event.isAttack() && !event.isCanceled()
                && (mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK)) {
            swing();
            // Entity hits use AttackEntityEvent. Only air needs an explicit intent.
            if (mc.hitResult == null || mc.hitResult.getType() == HitResult.Type.MISS)
                PacketDistributor.sendToServer(new SwordGuardPayloads.Input(2));
        }
    }
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) { held = false; requireRelease = false; owner = null; return; }
        if (owner != mc.player) {
            held = false;
            requireRelease = mc.options.keyUse.isDown();
            owner = mc.player;
        }
        boolean down = mc.options.keyUse.isDown();
        if (!down) requireRelease = false;
        if (mc.screen != null || reservedInput() || !SwordGuard.handsAllowGuard(mc.player)
                || SwordGuard.state(mc.player).suppressPose) {
            if (down) requireRelease = true;
            send(false);
        } else if (!requireRelease) send(down);
        if (pose(mc.player)) mc.player.setSprinting(false);
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity e)) continue;
            boolean guarding = pose(e);
            if (e instanceof AbstractClientPlayer player) MurimPlayerAnimations.guard(player, guarding);
            var s = SwordGuard.state(e);
            if (s.end > e.tickCount && !s.suppressPose && mc.player.distanceToSqr(e) < 16 * 16) {
                // A short line across the raised blade, rather than an aura or HUD indicator.
                Vec3 forward = e.getLookAngle();
                Vec3 side = new Vec3(-forward.z, 0, forward.x).normalize();
                Vec3 base = e.position().add(0, 1.0D, 0).add(forward.scale(0.55D));
                for (int i = 0; i < particleCount(mc, 4); i++) {
                    Vec3 at = base.add(side.scale((i - 1.5D) * 0.18D)).add(0, i * 0.10D, 0);
                    mc.level.addParticle(ParticleTypes.END_ROD, at.x, at.y, at.z, 0, 0.01D, 0);
                }
            }
        }
    }
    // API: /tmp/NeoForge-1.21.1/.../client/event/RenderHandEvent.java (pose passed to vanilla hand render).
    @SubscribeEvent
    static void hand(RenderHandEvent event) {
        var p = Minecraft.getInstance().player;
        if (p == null || event.getHand() != InteractionHand.MAIN_HAND || !pose(p)) return;
        int side = p.getMainArm() == net.minecraft.world.entity.HumanoidArm.RIGHT ? 1 : -1;
        var pose = event.getPoseStack();
        event.setCanceled(true);
        pose.pushPose();
        try {
            pose.translate(0.28D * side, -0.25D, -0.65D);
            pose.mulPose(Axis.YP.rotationDegrees(-35 * side));
            pose.mulPose(Axis.ZP.rotationDegrees(55 * side));
            // Same ItemRenderer overload as TrainingPropLayer/BeltSwordLayer; balanced pose scope.
            Minecraft.getInstance().getItemRenderer().renderStatic(event.getItemStack(),
                    net.minecraft.world.item.ItemDisplayContext.FIXED, event.getPackedLight(),
                    net.minecraft.client.renderer.texture.OverlayTexture.NO_OVERLAY,
                    pose, event.getMultiBufferSource(), p.level(), p.getId());
        } finally {
            pose.popPose();
        }
    }
    private SwordGuardClient() {}
}
