package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.Comparator;
import java.util.List;

/** Per-player reception and a saved ceremony queue. Lessons never wait for a ceremony or another player. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectArrival {
    public static final String INVITED = "arrival.mentor";
    public static final String PENDING = "arrival.ceremony_pending";
    public static final ResourceLocation DIALOGUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "arrival");
    private SectArrival() { }

    private static SectHousingData data(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SectHousingData.FACTORY, "murim_sect_rooms");
    }

    public static boolean greeter(SectDisciple npc) {
        if (npc.layout() == null || npc.role() == SectRole.LEADER) return false;
        Vec3 gate = npc.layout().at("sect_gate", 0, 0);
        return gate != null && npc.distanceToSqr(gate.x, gate.y, gate.z) < 16 * 16
                && (npc.role() == SectRole.GATEKEEPER || SectWatch.guards(npc));
    }

    public static boolean invite(ServerPlayer p, SectDisciple guard) {
        if (SectService.state(p).member() || !greeter(guard) || guard.distanceToSqr(p) > 64) return false;
        p.setData(ModAttachments.SECT, SectService.state(p).with(INVITED));
        p.displayClientMessage(Component.translatable("murim.sect.arrival.mentor"), false);
        return true;
    }

    /** A free outer disciple comes to the player first, then walks ahead. Busy guides are never reassigned. */
    public static boolean guide(ServerPlayer p, SectLayout layout) {
        if (!SectService.state(p).has(INVITED) || layout == null) return false;
        var nearby = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(192), SectDisciple::isAlive);
        if (nearby.stream().anyMatch(d -> d.escortGoal().forPlayer(p))) return true;
        if (nearby.stream().anyMatch(d -> d.role() == SectRole.MENTOR && d.distanceToSqr(p) <= 64)) return true;
        SectDisciple guide = nearby.stream().filter(d -> d.role().third() && d.role() != SectRole.GATEKEEPER
                && d.member().isPresent() && d.free() && !d.escorting() && !d.blocking() && !d.wounded())
                .min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (guide == null) return false;
        Vec3 target = SectEscorts.target(p, guide, "mentor");
        return target != null && guide.escortGoal().begin(p, target, false);
    }

    public static boolean admit(ServerPlayer p, SectDisciple mentor) {
        if (mentor.role() != SectRole.MENTOR || mentor.distanceToSqr(p) > 64
                || !SectService.state(p).has(INVITED) || SectService.state(p).member()) return false;
        SectService.join(p);
        SectService.flag(p, "met_mentor");
        SectJourney.help(p, mentor);
        SectRooms.assign(p, mentor.layout(), true);
        return true;
    }

    public static void accepted(ServerPlayer p) {
        data(p.serverLevel()).newcomers.put(p.getUUID().toString(),
                new SectHousingData.Newcomer(p.getName().getString(), SectService.state(p).entry("arrival.day", 0)));
        data(p.serverLevel()).setDirty();
        p.displayClientMessage(Component.translatable("murim.sect.arrival.ceremony_hint"), false);
    }

    /** Includes absent accepted players by name. Missing the event never changes standing or progress. */
    public static int ceremony(ServerLevel level, SectLayout layout, SectDisciple head, List<ServerPlayer> audience) {
        long day = SectSchedule.day(level.getDayTime());
        SectHousingData data = data(level);
        if (SectSchedule.at(level.getDayTime()) != SectSchedule.Period.FORMATION || data.ceremonyDay >= day
                || head == null || head.role() != SectRole.LEADER || !head.free() || !layout.inside("training", head.position(), 0)) return 0;
        var ready = data.newcomers.entrySet().stream().filter(e -> e.getValue().day() <= day).sorted(java.util.Map.Entry.comparingByKey()).toList();
        if (ready.isEmpty()) return 0;
        String names = String.join(", ", ready.stream().map(e -> e.getValue().name()).toList());
        head.holdPose(SectPose.BOW, 44);
        SectChatter.send(head, "murim.sect.arrival.ceremony", Component.translatable("murim.sect.arrival.ceremony", names));
        for (ServerPlayer p : audience) {
            p.displayClientMessage(Component.translatable("murim.sect.arrival.ceremony", names), false);
        }
        for (var entry : ready) data.newcomers.remove(entry.getKey());
        data.ceremonyDay = day;
        data.setDirty();
        for (ServerPlayer p : audience) syncCeremony(p);
        return ready.size();
    }

    private static void syncCeremony(ServerPlayer p) {
        if (SectService.state(p).has(PENDING) && !data(p.serverLevel()).newcomers.containsKey(p.getUUID().toString()))
            p.setData(ModAttachments.SECT, SectService.state(p).without(PENDING).with("arrival.presented"));
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 40 != 9 || p.isSpectator()) return;
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (layout == null) return;
        if (SectService.state(p).member()) {
            syncCeremony(p);
            if (p.tickCount % 200 == 9) SectRooms.assign(p, layout, false);
            return;
        }
        if (p.getData(ModAttachments.DIALOGUE).active()) return;
        if (SectService.state(p).has(INVITED)) { guide(p, layout); return; }
        Vec3 gate = layout.at("sect_gate", 0, 0);
        if (gate == null || p.position().distanceToSqr(gate) > 18 * 18) return;
        SectDisciple guard = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(8),
                d -> d.isAlive() && d.free() && !d.escorting() && greeter(d) && d.getSensing().hasLineOfSight(p))
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        long now = p.level().getGameTime();
        if (guard != null && now - SectService.state(p).entry("arrival.greet_at", Long.MIN_VALUE / 2) >= 600) {
            if (DialogueService.openDialogue(p, guard, DIALOGUE, "question"))
                p.setData(ModAttachments.SECT, SectService.state(p).record("arrival.greet_at", now));
        }
    }

    @SubscribeEvent
    static void serverTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 20 != 13) return;
        ServerLevel level = event.getServer().overworld();
        SectLayout layout = SectLife.layout(level);
        if (layout == null || SectSchedule.at(level.getDayTime()) != SectSchedule.Period.FORMATION) return;
        Vec3 square = layout.at("training", 0, 0);
        if (square == null) return;
        SectDisciple head = level.getEntitiesOfClass(SectDisciple.class, new AABB(square, square).inflate(48),
                d -> d.isAlive() && d.role() == SectRole.LEADER).stream().findFirst().orElse(null);
        ceremony(level, layout, head, level.players());
    }
}
