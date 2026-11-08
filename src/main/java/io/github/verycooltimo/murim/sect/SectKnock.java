package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.HallGrandDoorBlock;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * Knocking (author 21.10): a right click on a door that is not yet yours does not open it. The door knocks three times
 * (the gate also shudders) and somebody who hears it comes: the gatekeeper to the main gate, a guard or an elder to a
 * closed room. A tablet in the inventory, or the right to the place (standing, a pass), opens the door as before. Ordinary
 * doors (dorms, dining hall, kitchen, yards) are never touched. No penalty; the summons has a cooldown.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectKnock {
    /** Farthest the knock is heard. */
    public static final double HEARD = 32.0D;
    /** The same player summons somebody once per this long (ticks); the knocks themselves are free but spaced. */
    public static final long SUMMON_GAP = 600L;
    private static final long KNOCK_GAP = 30L;
    private static final String KNOCK_AT = "murim_knock_at";
    private static final String SUMMON_AT = "murim_knock_summon";

    private record Later(ServerLevel level, long at, Runnable run) { }

    private static final List<Later> LATER = new ArrayList<>();

    /** Server stopped: forget cross-world state. */
    public static void clearLater() { LATER.clear(); }

    private SectKnock() { }

    /** What keeps the door shut for {@code p}: "gate", an access rule id, or null (the door is open to him). */
    static String shutFor(ServerPlayer p, BlockPos door, SectLayout layout) {
        return shutFor(p, door, layout, false);
    }

    static String shutFor(ServerPlayer p, BlockPos door, SectLayout layout, boolean forced) {
        if (p.isCreative() && !forced || p.isSpectator() || layout == null) return null;
        Vec3 at = Vec3.atCenterOf(door);
        Vec3 gate = layout.at("sect_gate", 0, 0);
        if (gate != null && Math.hypot(at.x - gate.x, at.z - gate.z) < 9.0D && Math.abs(at.y - gate.y) < 10.0D) {
            return SectTablet.has(p) ? null : "gate";
        }
        SectState s = SectService.state(p);
        SectStanding standing = SectStanding.of(s, p.getData(ModAttachments.PROFILE).rank());
        SectSchedule.Period period = SectSchedule.at(p.level().getDayTime());
        for (SectAccess.Rule r : SectAccess.RULES) {
            if (SectAccess.GROUNDS.equals(r.zone()) || "penance".equals(r.id()) || "vault".equals(r.id())) continue;
            if ("main_hall".equals(r.id()) && SectAccess.inside(layout, r, p.position(), 0.0D)) continue;
            if (SectAccess.inside(layout, r, at, 1.5D) && !SectAccess.allowed(r, standing, s.flags(), period)) return r.id();
        }
        return null;
    }

    /**
     * Called by a door on a right click. True: the click was a knock and the door stays as it is.
     *
     * @param event the block event id of the shudder for big gates, -1 for an ordinary door leaf
     */
    public static boolean knock(ServerPlayer p, BlockPos pos, BlockPos origin, Block door, int event) {
        return knock(p, pos, origin, door, event, p.level().dimension() == Level.OVERWORLD ? SectLife.layout(p.serverLevel()) : null);
    }

    static boolean knock(ServerPlayer p, BlockPos pos, BlockPos origin, Block door, int event, SectLayout layout) {
        return knock(p, pos, origin, door, event, layout, false);
    }

    static boolean knock(ServerPlayer p, BlockPos pos, BlockPos origin, Block door, int event, SectLayout layout, boolean forced) {
        String why = shutFor(p, pos, layout, forced);
        if (why == null) return false;
        ServerLevel level = p.serverLevel();
        long now = level.getGameTime();
        if (now - p.getPersistentData().getLong(KNOCK_AT) < KNOCK_GAP && p.getPersistentData().contains(KNOCK_AT)) return true;
        p.getPersistentData().putLong(KNOCK_AT, now);
        for (int i = 0; i < 3; i++) {
            float pitch = 0.8F + i * 0.07F;
            if (i == 0) hit(level, pos, pitch, event, origin, door);
            else {
                int n = i;
                LATER.add(new Later(level, now + 5L * n, () -> hit(level, pos, pitch, event, origin, door)));
            }
        }
        boolean summon = !p.getPersistentData().contains(SUMMON_AT) || now - p.getPersistentData().getLong(SUMMON_AT) >= SUMMON_GAP;
        boolean coming = summon && answer(p, pos, origin, door, why);
        if (coming) p.getPersistentData().putLong(SUMMON_AT, now);
        // Say so when somebody comes (author 22.10: the knock only said the door stays shut, and nobody seemed to hear it).
        SectNotice.bar(p, Component.translatable(coming ? "murim.sect.knock.coming" : "murim.sect.knock.shut"));
        return true;
    }

    private static void hit(ServerLevel level, BlockPos pos, float pitch, int event, BlockPos origin, Block door) {
        level.playSound(null, pos, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 1.2F, pitch);
        level.playSound(null, pos, SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 0.25F, 1.6F);
        if (event >= 0) level.blockEvent(origin, door, event, 0);
    }

    /** Somebody who hears the knock comes. True if somebody was sent. */
    static boolean answer(ServerPlayer p, BlockPos pos, BlockPos origin, Block door, String why) {
        ServerLevel level = p.serverLevel();
        Vec3 at = Vec3.atCenterOf(pos);
        boolean gate = "gate".equals(why);
        SectDisciple who = level.getEntitiesOfClass(SectDisciple.class, new AABB(at, at).inflate(HEARD),
                        d -> d.isAlive() && d.layout() != null && d.free() && !d.escorting() && !d.wounded() && !d.isSleeping()
                                && !d.dormant() && !d.blocking() && d.role() != SectRole.LEADER && d.distanceToSqr(at) <= HEARD * HEARD
                                && (d.role() == SectRole.GATEKEEPER || SectWatch.guards(d) || !gate && d.role() == SectRole.ELDER))
                .stream().min(Comparator.comparingDouble((SectDisciple d) -> (d.role() == SectRole.GATEKEEPER ? 0.0D : 15.0D) + Math.sqrt(d.distanceToSqr(at)))).orElse(null);
        if (who == null) return false;
        boolean member = SectService.state(p).member();
        if (gate && !member) {
            // an outsider: the arrival talk, as when the guard sees him on the trail
            if (!who.approachDialogue(p, SectArrival.DIALOGUE, "question", p.position())) return false;
            String line = SectBubbles.Group.GATE_ANSWER.pick(p, who.getRandom());
            SectChatter.send(who, line, Component.translatable(line));
            p.getPersistentData().putLong("murim_gate_cool", p.level().getGameTime() + SectGateWatch.REAPPROACH);
            return true;
        }
        who.block(p, p.position(), 200);
        if (!who.blocking()) return false;
        if (gate) {
            // a member without his tablet: the guard comes, says so and opens the gate for him
            String line = SectBubbles.Group.GATE_ANSWER.pick(p, who.getRandom());
            SectChatter.send(who, line, Component.translatable(line));
            LATER.add(new Later(level, level.getGameTime() + 50L, () -> openFor(level, origin, door, who)));
        } else {
            // «Coming!» at once, the verdict when he has walked up to the door
            String call = SectBubbles.Group.GATE_ANSWER.pick(p, who.getRandom());
            SectChatter.send(who, call, Component.translatable(call));
            String line = SectBubbles.Group.GATE_LATER.pick(p, who.getRandom());
            who.queueBubble(line, Component.translatable(line), 70);
            who.markBubble(line);
        }
        return true;
    }

    /**
     * The guard swings the big door open for the member he came to. A door that stands open already is left alone (it used to be toggled
     * SHUT in the face of the member, who then had to knock again; sect check 24.10) and only entered for closing behind him.
     */
    static boolean openFor(ServerLevel level, BlockPos origin, Block door, SectDisciple who) {
        var state = level.getBlockState(origin);
        if (door instanceof GateDoorBlock g && state.is(g)) {
            if (state.getValue(GateDoorBlock.OPEN) || g.toggle(level, origin, who)) {
                SectDoors.mark(who, origin, level.getGameTime());
                return true;
            }
        } else if (door instanceof HallGrandDoorBlock h && state.is(h)) {
            if (state.getValue(HallGrandDoorBlock.OPEN) || h.toggle(level, origin, who)) {
                SectDoors.mark(who, origin, level.getGameTime());
                return true;
            }
        }
        return false;
    }

    @SubscribeEvent
    static void tick(ServerTickEvent.Post event) {
        if (LATER.isEmpty()) return;
        Iterator<Later> it = LATER.iterator();
        while (it.hasNext()) {
            Later l = it.next();
            if (l.level().getGameTime() >= l.at()) {
                it.remove();
                try {
                    l.run().run();
                } catch (RuntimeException ex) {
                    MurimMod.LOGGER.warn("Sect knock: a delayed action failed", ex);
                }
            }
        }
    }

    /** Dev command {@code /murim dev sect knock}: knock on the nearest big gate or hall door within eight blocks, as a creative player too. */
    static int devKnock(ServerPlayer p) {
        BlockPos at = p.blockPosition();
        for (BlockPos q : BlockPos.betweenClosed(at.offset(-8, -2, -8), at.offset(8, 4, 8))) {
            var st = p.level().getBlockState(q);
            if (st.getBlock() instanceof GateDoorBlock) {
                return knock(p, q.immutable(), GateDoorBlock.origin(q, st), st.getBlock(), GateDoorBlock.EVENT_KNOCK,
                        SectLife.layout(p.serverLevel()), true) ? 1 : 0;
            }
            if (st.getBlock() instanceof HallGrandDoorBlock) {
                return knock(p, q.immutable(), HallGrandDoorBlock.origin(q, st), st.getBlock(), HallGrandDoorBlock.EVENT_KNOCK,
                        SectLife.layout(p.serverLevel()), true) ? 1 : 0;
            }
        }
        return 0;
    }
}
