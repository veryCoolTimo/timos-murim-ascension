package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectGuidePayload;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * "Who do I need?" (playtest 19.10: the tester never knew whom to go to and did not know the names). For the player's
 * current goal ({@link SectJourney}) and for one daily task (today's drill, the contract, a favour) the server works out the
 * person: a live entity if he stands in a loaded place, otherwise the place of his zone. The client draws a quiet arrow and a
 * mote over the goal's person from it ({@link SectGuidePayload}); a guide that shows nothing when the reminder is off.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectGuide {
    /** How far the marker over a person's head shows (client). */
    public static final double MARK_RANGE = 48.0D;
    /** How far around the player a person off the mountain is looked for (blocks). */
    static final double NEAR_SEARCH = 128.0D;
    /** The digest of what the player's client holds (a session value in the player's data, dropped at login). */
    private static final String DIGEST = "murim_guide_digest";

    /** Goals the player does by himself: reading, sitting, practising, growing a rank. Talking to the person gains nothing, so no mote. */
    static final java.util.Set<String> NO_TALK = java.util.Set.of("read_method", "seed_feel", "seed_hold", "seed_compress",
            "learn_six", "learn_petal", "learn_seven", "practice_six", "rank_one", "rank_two", "rank_three", "peak", "peak_stage",
            "partners", "free_practice");

    private SectGuide() { }

    /** One place to go to: a person with the thing to do, or a place. */
    public record Spot(boolean goal, String id, Component what, String key, Component name, Component role, Component where,
                       Vec3 pos, int entity) {
        public SectGuidePayload.Entry entry() {
            return new SectGuidePayload.Entry(goal, id, what, name, role, where, pos.x, pos.y, pos.z, entity);
        }
    }

    /** The goal's spot first, then the daily task's. Empty for a non-member, with the reminder off, or out of the overworld. */
    public static List<Spot> spots(ServerPlayer p) {
        List<Spot> out = new ArrayList<>();
        SectState s = SectService.state(p);
        if (!s.member() || !(p.level() instanceof ServerLevel level)
                || level.dimension() != Level.OVERWORLD) {
            return out;
        }
        Spot trial = SectTrials.marker(p);
        if (trial != null) { out.add(trial); return out; }
        if (s.has(SectJourney.HUD_OFF)) return out;
        if (io.github.verycooltimo.murim.cultivation.Realm.atWall(p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE))) {
            Component reason = io.github.verycooltimo.murim.cultivation.MeditationService.readiness(p);
            if (reason != null && reason.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents text
                    && java.util.Set.of("murim.rank.wall.exam", "murim.rank.wall.join").contains(text.getKey())) {
                Spot wall = person(level, p, true, "wall_teacher", reason, "gyeong_pil");
                if (wall != null) out.add(wall);
                return out;
            }
        }
        SectJourney.Goal goal = SectJourney.next(p);
        // The mote marks a person only when talking to him moves the goal on (playtest 22.10: it hung over the mentor while the
        // goal was to practise, with nothing to ask); the spot stays for the «where is he» lines.
        Spot main = person(level, p, !NO_TALK.contains(goal.id()), goal.id(), goal.name(), goal.person());
        if (main != null) out.add(main);
        Spot task = task(level, p, goal.person());
        if (task != null) out.add(task);
        return out;
    }

    /** The daily task's spot: the drill the teacher gave, the contract in hand, or a favour waiting to be reported. */
    static Spot task(ServerLevel level, ServerPlayer p, String goalPerson) {
        SectDefeat.Open drill = SectDefeat.open(p);
        if (drill != null && !drill.teacher().equals(goalPerson)) {
            return person(level, p, false, "drill", Component.translatable("murim.guide.task.drill",
                    Component.translatable("murim.sect.defeat.name." + drill.drill().id()), drill.progress(), drill.need()), drill.teacher());
        }
        SectContracts.Guide contract = SectContracts.guide(p);
        if (contract != null && !contract.giver().equals(goalPerson)) {
            return person(level, p, false, "contract", Component.translatable(contract.ready() ? "murim.guide.task.contract_ready"
                    : "murim.guide.task.contract", contract.title()), contract.giver());
        }
        String[] favour = SectRequests.guide(p);
        if (favour != null && !favour[1].equals(goalPerson)) {
            return person(level, p, false, "favour_" + favour[0], Component.translatable("murim.guide.task.favour." + favour[0]), favour[1]);
        }
        return null;
    }

    /** A person of the roster: live if he stands in a loaded place, else where his zone is in the built sect. */
    static Spot person(ServerLevel level, ServerPlayer p, boolean goal, String id, Component what, String key) {
        Optional<SectRoster> m = SectRoster.of(key);
        SectRole role = m.map(SectRoster::role).orElse(SectRole.DISCIPLE);
        Component name = SectNames.of(level, key);
        Component roleName = SectRoleNames.of(key, role);
        SectDisciple npc = find(level, key, p.position());
        if (npc != null) {
            String fn = SectLife.placeFunction(npc);
            Component where = Component.translatable(fn.isEmpty() ? "murim.guide.where.road" : "murim.sect.place." + fn);
            return new Spot(goal && (id.startsWith("trial_") || availableNow(p, npc, id)), id, what, key, name, roleName, where, npc.position(), npc.getId());
        }
        SectLayout layout = SectLife.layout(level);
        Vec3 home = layout == null || m.isEmpty() ? null : layout.at(m.get().home(), 0.0D, 0.0D);
        return home == null ? null : new Spot(goal, id, what, key, name, roleName, Component.translatable("murim.guide.where.away"), home, -1);
    }

    /** Schedule availability and the actual action, not merely the next person's name. */
    static boolean availableNow(ServerPlayer p, SectDisciple npc, String id) {
        if (SectSchedule.at(p.level().getDayTime()) == SectSchedule.Period.NIGHT
                || NO_TALK.contains(id) || !npc.free() || npc.isSleeping() || npc.escorting()
                || npc.pose() == io.github.verycooltimo.murim.entity.SectPose.SLEEP
                || npc.pose() == io.github.verycooltimo.murim.entity.SectPose.EAT
                || npc.walking().goal() != null) return false;
        return switch (id) {
            case "method" -> true;
            case "take_six" -> !SectService.state(p).has("lesson.six");
            case "submit_spar" -> SectService.state(p).has(SectService.SPAR_CLEAN)
                    && p.getData(io.github.verycooltimo.murim.registry.ModAttachments.PROFILE).rank() >= 2;
            case "centre", "standing" -> false;
            case "request" -> SectRequests.canStart(p, npc, "pills");
            case "apology" -> SectProgression.canApologise(p, npc);
            case "first", "elder" -> SectPositions.eligible(p, id);
            case "submit_six" -> SectProgression.firstExamReady(p);
            case "personal_lesson" -> SectProgression.canLesson(p, npc);
            case "deliver_pills" -> SectRequests.canComplete(p, npc, "pills");
            case "direct" -> SectProgression.matches(p, npc, "direct");
            case "ceremony" -> SectProgression.matches(p, npc, "ceremony");
            case "repair" -> SectProgression.canRepair(p, npc);
            default -> SectSchedule.at(p.level().getDayTime()) != SectSchedule.Period.NIGHT;
        };
    }

    /** The nearest live person with this key on the mountain, or null (not placed yet, or his chunk is not loaded). */
    static SectDisciple find(ServerLevel level, String key, Vec3 near) {
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        // The mountain first; a person who walked out of its box (an escort on the road) is still found near the player.
        java.util.List<SectDisciple> pool = new ArrayList<>(SectLife.members(level));
        pool.addAll(level.getEntitiesOfClass(SectDisciple.class, new net.minecraft.world.phys.AABB(near, near).inflate(NEAR_SEARCH),
                d -> !d.memberKey().isEmpty() && !pool.contains(d)));
        for (SectDisciple d : pool) {
            if (d.isAlive() && key.equals(d.memberKey()) && d.distanceToSqr(near) < bestD) {
                best = d;
                bestD = d.distanceToSqr(near);
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ sending

    /** Sends the guide if what the client holds is out of date (a person moved a few blocks, a goal changed). */
    public static boolean sync(ServerPlayer p) {
        List<Spot> spots = spots(p);
        SectGuidePayload payload = new SectGuidePayload(spots.stream().map(Spot::entry).toList());
        long digest = digest(spots);
        var data = p.getPersistentData();
        if (data.contains(DIGEST) && data.getLong(DIGEST) == digest) return false;
        data.putLong(DIGEST, digest);
        if (p.connection != null && p.connection.hasChannel(payload)) PacketDistributor.sendToPlayer(p, payload);
        return true;
    }

    /** What the player's client shows, as a number: texts and ids, the position in 3-block cells (a walker is re-sent as he crosses one). */
    static long digest(List<Spot> spots) {
        long h = spots.size();
        for (Spot s : spots) {
            h = h * 31 + (s.goal() ? 1 : 0) + s.id().hashCode() * 7L + s.what().getString().hashCode() * 13L
                    + s.where().getString().hashCode() * 17L + s.entity() * 19L
                    + Math.floorDiv((long) Math.floor(s.pos().x), 3L) * 23L + Math.floorDiv((long) Math.floor(s.pos().z), 3L) * 29L
                    + Math.floorDiv((long) Math.floor(s.pos().y), 3L) * 31L;
        }
        return h;
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 20 == 5 && !p.isSpectator()) sync(p);
    }

    @SubscribeEvent
    static void loggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) p.getPersistentData().remove(DIGEST);
    }
}
