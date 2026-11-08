package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** A rotating existing junior's night post. Session replies live on the player; no escort or HUD. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectNightWatch {
    public static final String MEMBER = "ham_doyun";
    /** Share the square post among juniors so everyone keeps their own sleeping nights. */
    public static String member(long time) {
        var juniors = SectRoster.generation(3);
        int first = 0;
        for (int i = 0; i < juniors.size(); i++) if (juniors.get(i).key().equals(MEMBER)) first = i;
        return juniors.get((int) Math.floorMod(first + SectSchedule.day(time), (long) juniors.size())).key();
    }

    private static final String ANSWERED = "murim_watch_answered";
    private static final String REFUSED = "murim_night_refused";
    private SectNightWatch() { }

    /** The baked square is authoritative; a loaded brazier is only a preference.
     * API: reference/minecraft-src/net/minecraft/core/BlockPos.java#containing, #betweenClosed;
     * reference/minecraft-src/net/minecraft/world/level/Level.java#isLoaded, #getBlockState.
     */
    public static net.minecraft.world.phys.Vec3 post(SectDisciple npc, net.minecraft.world.phys.Vec3 fallback) {
        var file = SectLife.file(npc);
        var square = file == null ? null : file.zone("training_square");
        if (square != null) {
            var r = square.rect();
            fallback = new net.minecraft.world.phys.Vec3((r.x0() + r.x1() + 1) / 2.0D, square.y(),
                    (r.z0() + r.z1() + 1) / 2.0D);
            if (npc.level().isLoaded(net.minecraft.core.BlockPos.containing(fallback))) {
                fallback = SectLife.stand(npc.level(), fallback);
            }
        }
        var centre = net.minecraft.core.BlockPos.containing(fallback);
        net.minecraft.world.phys.Vec3 best = fallback;
        double distance = Double.MAX_VALUE;
        for (var at : net.minecraft.core.BlockPos.betweenClosed(centre.offset(-24, -4, -24), centre.offset(24, 4, 24))) {
            if (!npc.level().isLoaded(at)) continue;
            var block = npc.level().getBlockState(at);
            if (!block.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                    && !block.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE)) continue;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
                if (Math.abs(dx) + Math.abs(dz) != 2) continue;
                var feet = at.offset(dx, 0, dz);
                if (!npc.level().isLoaded(feet) || !SectLife.standable(npc.level(), feet)
                        || !npc.level().getBlockState(feet).getCollisionShape(npc.level(), feet).isEmpty()) continue;
                var spot = new net.minecraft.world.phys.Vec3(feet.getX() + .5, feet.getY(), feet.getZ() + .5);
                if (square != null ? !square.rect().contains(feet.getX(), feet.getZ())
                        : npc.layout() != null && !npc.layout().inside("training", spot, 0)) continue;
                double d = spot.distanceToSqr(fallback);
                if (d < distance) { distance = d; best = spot; }
            }
        }
        return best;
    }

    private static boolean inside(SectDisciple npc, net.minecraft.world.phys.Vec3 pos) {
        var file = SectLife.file(npc);
        var square = file == null ? null : file.zone("training_square");
        return square == null ? npc.layout().inside("training", pos, 0)
                : square.rect().contains((int) Math.floor(pos.x), (int) Math.floor(pos.z)) && Math.abs(pos.y - square.y()) <= 3;
    }

    static boolean answer(ServerPlayer p, SectDisciple watch) {
        if (SectSchedule.at(p.level().getDayTime()) != SectSchedule.Period.NIGHT
                || !watch.memberKey().equals(member(p.level().getDayTime())) || watch.doingKind() != SectSchedule.Kind.WATCH
                || watch.isSleeping() || watch.distanceToSqr(p) > 36 || watch.layout() == null
                || !inside(watch, watch.position())) return false;
        long night = SectSchedule.day(p.level().getDayTime());
        var tag = p.getPersistentData();
        if (tag.contains(ANSWERED) && tag.getLong(ANSWERED) == night) return false;
        tag.putLong(ANSWERED, night);
        watch.faceEntity(p, 30);
        watch.gesture("nod");
        String key = "murim.presence.night.watch";
        SectChatter.send(watch, key, Component.translatable(key));
        return true;
    }

    /** A refused night request gives one alternative, once per night and activity. */
    public static boolean refuse(ServerPlayer p, SectDisciple npc, String activity) {
        if (SectSchedule.at(p.level().getDayTime()) != SectSchedule.Period.NIGHT) return false;
        String tag = REFUSED + activity;
        long night = SectSchedule.day(p.level().getDayTime());
        if (!p.getPersistentData().contains(tag) || p.getPersistentData().getLong(tag) != night) {
            p.getPersistentData().putLong(tag, night);
            String key = "murim.presence.night.alternative";
            SectChatter.send(npc, key, Component.translatable(key));
        }
        return true;
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator() || p.tickCount % 20 != 0
                || SectSchedule.at(p.level().getDayTime()) != SectSchedule.Period.NIGHT) return;
        SectDisciple watch = SectGuide.find(p.serverLevel(), member(p.level().getDayTime()), p.position());
        if (watch != null) answer(p, watch);
    }
}
