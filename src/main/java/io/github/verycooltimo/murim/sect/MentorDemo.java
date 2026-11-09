package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * The mentor shows one action on request (persona pillar 1, author 8.11): a newcomer who holds a breathing method and has no
 * dantian yet may ask «Show me». No text tutorial: for {@link #TOTAL} ticks the mentor takes the scroll out, sits in the
 * routine's meditation pose and a ring of light closes in front of him, then he stands and the routine takes him on from where
 * he is. Server driven; the player is neither frozen nor moved. The ring has no world-space client renderer (the minigame ring
 * is a HUD), so it is drawn from server particles in the minigame's cyan.
 */
public final class MentorDemo {
    /** Whole demonstration, ticks (8 s). */
    public static final int TOTAL = 160;
    /** The scroll is in his hand until this tick (1.5 s). */
    static final int SCROLL = 30;
    /** The ring shows between these ticks (5 s). */
    static final int RING_FROM = 40;
    static final int RING_TO = 140;
    /** He stands up at this tick. */
    static final int STAND = 150;
    /** Per player, between two requests (20 s). */
    public static final int COOLDOWN = 400;
    /** How long he waits for the dialogue to close before the demo is dropped. */
    static final int WAIT = 100;
    static final String LAST = "murim_demo_at";
    static final String BUBBLE = "murim.bubble.mentor.demo";
    private static final DustParticleOptions CYAN = new DustParticleOptions(new Vector3f(0.56F, 0.89F, 1.0F), 0.9F);

    private MentorDemo() { }

    /** One running demonstration, kept on the mentor. */
    public static final class State {
        final UUID player;
        long start = -1;
        int waited;
        ItemStack hand = ItemStack.EMPTY;
        boolean wasSitting;

        State(UUID player) { this.player = player; }

        /** Game time of the first tick of the show, or -1 while he waits for the dialogue to close. */
        public long start() { return start; }
    }

    /** Pure rule: he holds a method and has not formed a dantian yet. */
    public static boolean eligible(boolean holdsMethod, boolean seeded, boolean awakened) {
        return holdsMethod && !seeded && !awakened;
    }

    /** Pure rule: never more often than every {@link #COOLDOWN} ticks; {@code last < 0} = never asked. */
    public static boolean cooledDown(long last, long now) {
        return last < 0 || now - last >= COOLDOWN;
    }

    public static boolean canAsk(ServerPlayer p, SectDisciple npc) {
        if (npc == null || npc.role() != SectRole.MENTOR || !npc.isAlive() || npc.level() != p.level() || npc.distanceToSqr(p) > 64
                || npc.demo() != null || !SectService.state(p).member() || p.isSpectator()) return false;
        var cultivation = p.getData(ModAttachments.CULTIVATION);
        boolean holds = cultivation.method().isPresent() || hasScroll(p);
        if (!eligible(holds, cultivation.seeded(), p.getData(ModAttachments.PROFILE).isAwakened())) return false;
        var data = p.getPersistentData();
        return cooledDown(data.contains(LAST) ? data.getLong(LAST) : -1L, p.level().getGameTime());
    }

    private static boolean hasScroll(ServerPlayer p) {
        return java.util.stream.Stream.concat(p.getInventory().items.stream(), p.getInventory().offhand.stream())
                .anyMatch(s -> s.is(ModItems.METHOD_SCROLL.get()));
    }

    /** Dialogue action. False (retry) when the request is not allowed. */
    public static boolean start(ServerPlayer p, SectDisciple npc) {
        if (!canAsk(p, npc)) return false;
        p.getPersistentData().putLong(LAST, p.level().getGameTime());
        npc.setDemo(new State(p.getUUID()));
        return true;
    }

    public static boolean active(SectDisciple npc) {
        return npc.demo() != null;
    }

    /** Called every tick from the person's own tick (server). */
    public static void tick(SectDisciple n) {
        State s = n.demo();
        if (s == null || !(n.level() instanceof ServerLevel level)) return;
        if (!n.isAlive() || n.isSleeping() || n.escorting() || n.guardTarget() != null || n.blocking()) { end(n, s); return; }
        ServerPlayer p = level.getServer().getPlayerList().getPlayer(s.player);
        if (s.start < 0) {
            // The dialogue is still open: he waits for it to close, then begins.
            if (n.talkingTo() >= 0 && ++s.waited < WAIT) return;
            if (n.talkingTo() >= 0) { n.setDemo(null); return; }
            s.start = level.getGameTime();
            s.wasSitting = n.sitting();
            s.hand = n.getItemBySlot(EquipmentSlot.MAINHAND).copy();
            n.walking().stop();
            ItemStack scroll = new ItemStack(ModItems.METHOD_SCROLL.get());
            scroll.set(ModDataComponents.METHOD.get(), SectService.SIX);
            n.setItemSlot(EquipmentSlot.MAINHAND, scroll);
            SectChatter.send(n, BUBBLE, Component.translatable(BUBBLE));
        }
        long t = level.getGameTime() - s.start;
        n.getNavigation().stop();
        if (t < SCROLL) {
            if (p != null && !s.wasSitting) n.faceEntity(p, 30.0F);
            return;
        }
        if (t == SCROLL) {
            n.setItemSlot(EquipmentSlot.MAINHAND, s.hand);
            if (!s.wasSitting) n.sit(SeatStyle.LOTUS);
            n.playPose(SectPose.MEDITATE);
        }
        if (t < STAND) {
            n.setPose(SectPose.MEDITATE);
            if (t >= RING_FROM && t < RING_TO && t % 2 == 0) ring(level, n, (int) (t - RING_FROM));
        }
        if (t == STAND && !s.wasSitting) n.standUp();
        if (t >= TOTAL) end(n, s);
    }

    /** Back to his routine: hand as it was, pose off; the routine walks him to his place from where he stands. */
    private static void end(SectDisciple n, State s) {
        if (s.start >= 0) n.setItemSlot(EquipmentSlot.MAINHAND, s.hand);
        if (!s.wasSitting && n.sitting()) n.standUp();
        if (n.pose() == SectPose.MEDITATE) n.setPose(SectPose.NONE);
        n.setDemo(null);
    }

    /** Radius of the ring after {@code i} ticks of the show: it closes in, slow at both ends (the minigame's ring pressing to the seed). */
    static double radius(int i) {
        double x = Math.min(1.0D, i / (double) (RING_TO - RING_FROM - 20));
        double e = x * x * (3.0D - 2.0D * x);
        return 0.85D - 0.5D * e;
    }

    private static void ring(ServerLevel level, SectDisciple n, int i) {
        double yaw = Math.toRadians(n.getYRot());
        Vec3 fwd = new Vec3(-Math.sin(yaw), 0.0D, Math.cos(yaw));
        Vec3 right = new Vec3(Math.cos(yaw), 0.0D, Math.sin(yaw));
        Vec3 c = n.position().add(fwd.scale(0.95D)).add(0.0D, 1.25D, 0.0D);
        double r = radius(i);
        int points = 20;
        double spin = i * 0.05D;
        for (int k = 0; k < points; k++) {
            double a = spin + k * Math.PI * 2.0D / points;
            Vec3 at = c.add(right.scale(Math.cos(a) * r)).add(0.0D, Math.sin(a) * r, 0.0D);
            level.sendParticles(CYAN, at.x, at.y, at.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
        if (i >= RING_TO - RING_FROM - 20) {
            level.sendParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 1, 0.0D, 0.0D, 0.0D, 0.0D);
        }
    }
}
