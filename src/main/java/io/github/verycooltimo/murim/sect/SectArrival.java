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

    /** Persistent tag of a person sent to the gate to receive a waiting outsider: game time until which he is a greeter (holes review npc-2). */
    static final String GATE_CALL = "murim_gate_call";
    private static final String GATE_WAIT = "murim_gate_wait";
    /** An outsider waits this long at the gate with nobody to receive him before somebody is sent (15 s). */
    public static final long GATE_WAIT_TICKS = 300L;
    /** The gate talk opens itself at most this often (5 min): whoever walked away from it is not pulled back (UX review 21.10, item 3). */
    public static final long GREET_AGAIN = 6000L;
    /** Farthest a person is sent to the gate from. */
    public static final double GATE_CALL_RANGE = 80.0D;

    public static boolean greeter(SectDisciple npc) {
        if (npc.layout() == null || npc.role() == SectRole.LEADER) return false;
        if (npc.getPersistentData().getLong(GATE_CALL) > npc.level().getGameTime()) return true;
        Vec3 gate = npc.layout().at("sect_gate", 0, 0);
        return gate != null && npc.distanceToSqr(gate.x, gate.y, gate.z) < 16 * 16
                && (npc.role() == SectRole.GATEKEEPER || SectWatch.guards(npc));
    }

    /** The newcomer asked for a guide (and has not turned him away): only then is one sent on his own (UX review 21.10, item 2). */
    public static final String ORDERED = "arrival.guide_ordered";
    /** Player data: game time at which a guide gave up waiting for him; nobody is sent again until he asks again. */
    public static final String GUIDE_OFF = "murim_guide_off";

    /** He found the mentor himself: the mentor writes him down on the spot (UX review 21.10, item 1). */
    public static boolean introduce(ServerPlayer p, SectDisciple mentor) {
        if (mentor.role() != SectRole.MENTOR || mentor.distanceToSqr(p) > 64 || SectService.state(p).member()) return false;
        p.setData(ModAttachments.SECT, SectService.state(p).with(INVITED));
        decline(p);
        return true;
    }

    /** Ends every escort the newcomer has (he is led no more). */
    public static void cancelGuides(ServerPlayer p) {
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(GUIDE_RANGE),
                d -> d.escortGoal().forPlayer(p) && !d.escortGoal().forcedFor(p))) {
            d.escortGoal().cancel();
        }
    }

    /** He asks for a guide (again): the 20 s give-up of a guide who waited is forgotten. */
    public static boolean order(ServerPlayer p, SectLayout layout) {
        p.getPersistentData().remove(GUIDE_OFF);
        p.setData(ModAttachments.SECT, SectService.state(p).with(ORDERED));
        return guide(p, layout);
    }

    /** He does not want one after all. */
    public static void decline(ServerPlayer p) {
        p.setData(ModAttachments.SECT, SectService.state(p).without(ORDERED));
        cancelGuides(p);
        releaseReception(p);
    }

    /** Release only after the arriving guide's welcome, or an explicit refusal. */
    public static void releaseReception(ServerPlayer p) {
        p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(GUIDE_RANGE),
                d -> d.isAlive()).forEach(d -> d.releaseReception(p));
    }

    public static boolean invite(ServerPlayer p, SectDisciple guard) {
        if (SectService.state(p).member() || !greeter(guard) || guard.distanceToSqr(p) > 64) return false;
        p.setData(ModAttachments.SECT, SectService.state(p).with(INVITED));
        SectNotice.bar(p, Component.translatable("murim.sect.arrival.mentor"));
        return true;
    }

    /** A free outer disciple comes to the player first, then walks ahead. Busy guides are never reassigned. */
    /** Farthest a guide is looked for (the collecting walk gives up at 192 blocks of straight distance). */
    public static final double GUIDE_RANGE = 150.0D;
    /** A guide who did not come is not asked again by the same newcomer for this long (ticks). */
    public static final long GUIDE_BAN = 2400L;
    private static final String GUIDE_BAN_TAG = "murim_guide_ban";

    public static boolean guide(ServerPlayer p, SectLayout layout) {
        if (!SectService.state(p).has(INVITED) || layout == null) return false;
        long now = p.level().getGameTime();
        var nearby = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(GUIDE_RANGE),
                d -> d.isAlive() && SectEscorts.sameLayout(layout, d.layout()));
        for (SectDisciple d : nearby) {
            if (!d.escortGoal().forPlayer(p)) continue;
            if (!d.escortGoal().stalled(now)) return true;
            // The guide who was sent does not come (frozen in a chunk that does not tick, stuck): let him go, ask another.
            // Before, the first one chosen was the guide for good and the newcomer waited at the gate for nobody (playtest 19.10).
            d.escortGoal().cancel();
            d.getPersistentData().putLong(GUIDE_BAN_TAG, now + GUIDE_BAN);
        }
        if (nearby.stream().noneMatch(d -> d.awaitingGuide(p))
                && nearby.stream().anyMatch(d -> d.role() == SectRole.MENTOR && d.distanceToSqr(p) <= 64)) return true;
        // The nearest free hands; a sleeper far from every player counts as further than he stands (his chunk may not tick).
        SectDisciple guide = nearby.stream().filter(d -> d.role().third() && d.role() != SectRole.GATEKEEPER
                && d.member().isPresent() && d.free() && !d.escorting() && !d.blocking() && !d.wounded()
                && d.getPersistentData().getLong(GUIDE_BAN_TAG) <= now)
                .min(Comparator.comparingDouble(d -> Math.sqrt(d.distanceToSqr(p)) + (d.dormant() ? 32.0D : 0.0D))).orElse(null);
        if (guide == null) return false;
        Vec3 target = SectEscorts.target(p, guide, "mentor");
        if (target == null || !guide.escortGoal().begin(p, target, false)) return false;
        // the mentor's schedule moves him: the guide asks where he stands now
        guide.escortGoal().follow(() -> SectEscorts.target(p, guide, "mentor"));
        guide.escortGoal().onArrival(() -> decline(p));
        // The newcomer sees who comes: a word over the guide's head and a line over the hotbar.
        SectChatter.sayIfQuiet(guide, SectBubbles.Group.GUIDE_COMING.pick(p, guide.getRandom()), 40);
        SectNotice.bar(p, Component.translatable("murim.sect.arrival.guide_coming", guide.getName()));
        return true;
    }

    public static boolean admit(ServerPlayer p, SectDisciple mentor) {
        if (mentor.role() != SectRole.MENTOR || mentor.distanceToSqr(p) > 64
                || !SectService.state(p).has(INVITED) || SectService.state(p).member()) return false;
        decline(p);
        SectService.join(p);
        SectService.flag(p, "met_mentor");
        SectTablet.ensure(p);
        SectJourney.help(p, mentor);
        SectRooms.assign(p, mentor.layout(), true);
        return true;
    }

    /** Dev command: forget a pending newcomer entry. */
    static void forget(ServerPlayer p) {
        if (data(p.serverLevel()).newcomers.remove(p.getUUID().toString()) != null) data(p.serverLevel()).setDirty();
    }

    public static void accepted(ServerPlayer p) {
        data(p.serverLevel()).newcomers.put(p.getUUID().toString(),
                new SectHousingData.Newcomer(p.getName().getString(), SectService.state(p).entry("arrival.day", 0)));
        data(p.serverLevel()).setDirty();
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
        head.gesture("nod");
        SectChatter.send(head, "murim.sect.arrival.ceremony", Component.translatable("murim.sect.arrival.ceremony", names));
        for (ServerPlayer p : audience) {
            SectNotice.bar(p, Component.translatable("murim.sect.arrival.ceremony", names));
        }
        for (var entry : ready) data.newcomers.remove(entry.getKey());
        data.ceremonyDay = day;
        data.setDirty();
        for (ServerPlayer p : audience) syncCeremony(p);
        return ready.size();
    }

    private static void syncCeremony(ServerPlayer p) {
        // The public register does not consume the invitation to meet the head later.
        SectState state = SectService.state(p);
        if (state.has(PENDING) && !state.has("arrival.assembly_presented")
                && !data(p.serverLevel()).newcomers.containsKey(p.getUUID().toString()))
            p.setData(ModAttachments.SECT, state.with("arrival.assembly_presented"));
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator()) return;
        if (p.tickCount % 10 == 4 && p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD) {
            // the gate looks at the trail twice a second: a rider or a flyer is noticed before he is at the gate
            SectLayout watch = SectLife.layout(p.serverLevel());
            if (watch != null) SectGateWatch.tick(p, watch);
        }
        if (p.tickCount % 40 != 9) return;
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (layout == null) return;
        if (SectService.state(p).member()) {
            if (p.getPersistentData().contains("murim_joined_at")) SectBedMark.admissionRespawn(p, layout);
            syncCeremony(p);
            SectHeadIntro.tick(p, layout);
            if (p.tickCount % 200 == 9) SectRooms.assign(p, layout, false);
            return;
        }
        // Reception remains open while the guide is collected; retries must run during that talk too.
        if (SectService.state(p).has(INVITED)) {
            if (SectService.state(p).has(ORDERED) && !p.getPersistentData().contains(GUIDE_OFF)) guide(p, layout);
            return;
        }
        if (p.getData(ModAttachments.DIALOGUE).active()) return;
        Vec3 gate = layout.at("sect_gate", 0, 0);
        if (gate == null || p.position().distanceToSqr(gate) > 18 * 18) {
            p.getPersistentData().remove(GATE_WAIT);
            return;
        }
        SectDisciple guard = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(8),
                d -> d.isAlive() && d.free() && !d.escorting() && greeter(d) && d.getSensing().hasLineOfSight(p))
                .stream().min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        long now = p.level().getGameTime();
        if (guard == null) {
            callGreeter(p, now);
        } else {
            p.getPersistentData().remove(GATE_WAIT);
        }
        if (guard != null && SectGateWatch.ready(p) && SectGateWatch.facing(p, guard) && now - SectService.state(p).entry("arrival.greet_at", Long.MIN_VALUE / 2) >= GREET_AGAIN) {
            if (DialogueService.openDialogue(p, guard, DIALOGUE, "question"))
                p.setData(ModAttachments.SECT, SectService.state(p).record("arrival.greet_at", now));
        }
    }

    /**
     * The gate has nobody who may receive an outsider (the captain is at the council or at a meal, the watch stands at the halls):
     * after {@link #GATE_WAIT_TICKS} the nearest free person walks over and opens the arrival talk (holes review npc-2).
     * Returns the person sent, or null.
     */
    static SectDisciple callGreeter(ServerPlayer p, long now) {
        if (!SectGateWatch.ready(p)) return null;
        long since = p.getPersistentData().contains(GATE_WAIT) ? p.getPersistentData().getLong(GATE_WAIT) : -1L;
        if (since < 0L || since > now) {
            p.getPersistentData().putLong(GATE_WAIT, now);
            return null;
        }
        if (now - since < GATE_WAIT_TICKS) return null;
        var near = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(GATE_CALL_RANGE), d -> d.isAlive());
        for (SectDisciple d : near) {
            if (d.approachingDialogue(p, DIALOGUE)) return null;
        }
        SectDisciple who = near.stream().filter(d -> d.member().isPresent() && d.free() && !d.escorting() && !d.blocking() && !d.wounded()
                        && !d.isSleeping() && d.role() != SectRole.LEADER && d.role() != SectRole.GATEKEEPER && d.layout() != null
                        && Math.abs(d.getY() - p.getY()) <= 16.0D)
                .min(Comparator.comparingDouble((SectDisciple d) -> (SectWatch.guards(d) ? 0.0D : 40.0D) + Math.sqrt(d.distanceToSqr(p))
                        + (d.dormant() ? 32.0D : 0.0D))).orElse(null);
        if (who == null) return null;
        who.getPersistentData().putLong(GATE_CALL, now + 1200L);
        if (!who.approachDialogue(p, DIALOGUE, "question", p.position())) {
            who.getPersistentData().remove(GATE_CALL);
            return null;
        }
        p.getPersistentData().putLong(GATE_WAIT, now + 1200L); // not again while he walks
        return who;
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
