package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.SeatEntity;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.sect.SectEventsFile.Council;
import io.github.verycooltimo.murim.sect.SectEventsFile.Invitation;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import io.github.verycooltimo.murim.sect.SectSchedule.Task;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Invitations of the offices (docs/design/38-sect-ai-locations.md, «Совет и приглашения»; author 19.10: «когда я Elder, ничего
 * не отличается — тоже должны быть ивенты: типа, вас зовут на совет»). An elder is called to the council of elders and to
 * three lighter affairs of his rank, a first disciple to lead the morning line and to drill the juniors. Everything is a light
 * invitation of the sandbox: it shows in the tablet as the current goal, a servant or a member walks up with a bubble, and
 * coming (sitting at the council table, standing by the financier, by the quarrelling pair, leading the line) earns a short
 * scene and a little contribution. Ignoring it costs nothing: the goal just goes away when the window closes.
 *
 * <p>The days and hours are data ({@link SectEventsFile}). State is on the player: a fixed journal key per invitation holds the
 * day it was issued and the day it was answered, the ticks he has stayed are in his persistent data. No static state.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectOffices {
    /** The council has its own clock and clock-keeper ({@link SectCouncil}); its invitation is built from it. */
    public static final String COUNCIL = "council";
    private static final String INVITED = "office.invited.";
    private static final String SENT = "office.sent.";
    private static final String DONE = "office.done.";
    private static final String HOLD = "murim_office_hold";
    private static final String BEAT = "murim_office_beat";
    /** On the juniors who quarrel: the day the elder judged them (they calm down). */
    public static final String DISPUTE_DONE = "murim_dispute_done";

    /** An invitation as the player sees it: the council and the rest in one shape. */
    public record Event(String id, String office, Set<Integer> days, int call, int from, int to, String host, String attend,
                        int hold, int contribution) {
        public boolean onDay(long sectDay) {
            return days.contains((int) Math.floorMod(sectDay, (long) SectEventsFile.WEEK));
        }

        public boolean called(int tick) {
            return SectEventsFile.within(tick, call, to);
        }

        public boolean open(int tick) {
            return SectEventsFile.within(tick, from, to);
        }

        /** The person who asks, as the journal names him. */
        public String person() {
            return host.isEmpty() ? SectSchedule.LEADER : host;
        }
    }

    /** One line of a scene: after {@code at} ticks of staying, {@code who} says {@code key}. */
    record Beat(int at, String who, String key) {
    }

    private SectOffices() {
    }

    // ------------------------------------------------------------------ the table

    /** Every invitation of the file: the council first. */
    public static List<Event> events(SectEventsFile f) {
        List<Event> out = new ArrayList<>();
        Council c = f.council();
        out.add(new Event(COUNCIL, "elder", c.days(), c.call(), c.from(), c.leave(), SectSchedule.LEADER, "seat", c.hold(), c.contribution()));
        for (Invitation i : f.invitations()) {
            out.add(new Event(i.id(), i.office(), i.days(), i.call(), i.from(), i.to(), i.host(), i.attend(), i.hold(), i.contribution()));
        }
        return out;
    }

    public static List<Event> events() {
        return events(SectEventsFile.shipped());
    }

    public static Event event(String id) {
        for (Event e : events()) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        return null;
    }

    /** {@code elder}, {@code first} or empty: the office whose invitations the player receives. */
    public static String office(SectState s) {
        return s.has(SectPositions.ELDER) ? "elder" : s.has(SectPositions.FIRST) ? "first" : "";
    }

    /** The goal ids that are invitations. */
    public static boolean isInvitation(String goalId) {
        return event(goalId) != null;
    }

    // ------------------------------------------------------------------ the quarrel of the juniors (a task of the schedule)

    /**
     * The two juniors who quarrel in the forecourt of the Main Hall on the day and in the hours of the {@code dispute}
     * invitation: the two who sweep the yards that day, so no pair of the training is broken. Null for everybody else.
     */
    public static Task disputeTask(SectRoster m, long dayTime) {
        Invitation inv = SectEventsFile.shipped().invitation("dispute");
        if (inv == null || m.generation() != 3 || m.role() == SectRole.GATEKEEPER || m.lay()) {
            return null;
        }
        long day = SectSchedule.day(dayTime);
        if (!inv.onDay(day) || !inv.open(SectCouncil.tick(dayTime))) {
            return null;
        }
        List<SectRoster> pair = disputePair(day);
        int side = pair.indexOf(m);
        if (side < 0) {
            return null;
        }
        return new Task(Kind.ARGUE, "main_hall", side == 0 ? -1.0D : 1.0D, -12.0D, side == 0 ? 1.0D : -1.0D, 0.0D, pair.get(1 - side).key());
    }

    /** The two juniors of that day: with the third-generation shift of {@code SectSchedule#thirdTraining} they are the first two sweepers. */
    static List<SectRoster> disputePair(long day) {
        List<SectRoster> gen = SectRoster.generation(3).stream().filter(m -> !SectSchedule.officeHelper(m.key())).toList();
        int n = gen.size();
        List<SectRoster> out = new ArrayList<>(2);
        for (int k = 10; k < 12; k++) {
            out.add(gen.get((int) Math.floorMod(k - day * 4L, (long) n)));
        }
        return out;
    }

    // ------------------------------------------------------------------ what the player is asked

    private static boolean done(SectState s, Event e, long day) {
        return s.entry(DONE + e.id(), -1L) >= day;
    }

    private static boolean invited(SectState s, Event e, long day) {
        return s.entry(INVITED + e.id(), -1L) >= day;
    }

    /** The invitations that are out for the office of this player right now and not yet answered. */
    public static List<Event> out(SectState s, long dayTime) {
        String office = office(s);
        List<Event> out = new ArrayList<>();
        if (office.isEmpty() || !s.member() || SectPositions.disciplined(s)) {
            return out;
        }
        long day = SectSchedule.day(dayTime);
        int tick = SectCouncil.tick(dayTime);
        for (Event e : events()) {
            if (e.office().equals(office) && e.onDay(day) && e.called(tick) && !done(s, e, day)) {
                out.add(e);
            }
        }
        return out;
    }

    /** The tablet goal: the first invitation that is out for him (an interruption of the journey, never a duty). Null: none. */
    public static SectJourney.Goal goal(ServerPlayer p) {
        List<Event> out = out(SectService.state(p), p.level().getDayTime());
        for (Event e : out) {
            if (invited(SectService.state(p), e, SectSchedule.day(p.level().getDayTime()))) {
                return new SectJourney.Goal(e.id(), e.person(), Component.translatable("murim.sect.journey.blocker." + e.id()));
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ the tick of a player

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 5 || p.isSpectator() || !p.isAlive()) {
            return;
        }
        if (p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD || SectLife.layout(p.serverLevel()) == null) {
            return;
        }
        update(p, fileOf(p));
    }

    /** One check (a second): issue the invitations that fell due, watch whether he came. {@code file}: the built sect, or null. */
    public static void update(ServerPlayer p, SectLayoutFile file) {
        // The affairs of the offices happen in the built sect (its council hall, its office of the financier); elsewhere nobody is called.
        if (file == null || !SectPlaces.hasCouncilHall(file)) {
            return;
        }
        SectState s = SectService.state(p);
        long time = p.level().getDayTime();
        long day = SectSchedule.day(time);
        int tick = SectCouncil.tick(time);
        for (Event e : out(s, time)) {
            if (!invited(s, e, day)) {
                issue(p, e, day);
                s = SectService.state(p);
            }
            if (s.entry(SENT + e.id(), -1L) < day && send(p, e)) {
                p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, SectService.state(p).record(SENT + e.id(), day));
            }
            if (e.open(tick) && stays(p, e, time, file)) {
                int held = hold(p, e) + 20;
                setHold(p, e, held);
                scene(p, e, held);
                if (held >= Math.max(20, e.hold())) {
                    finish(p, e, day);
                }
            } else if (hold(p, e) > 0 && !"formation".equals(e.attend())) {
                setHold(p, e, 0);
            }
        }
    }

    /** The invitation goes out: written in the journal (the tablet shows it), a soft bell. */
    static void issue(ServerPlayer p, Event e, long day) {
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, SectService.state(p).record(INVITED + e.id(), day));
        setHold(p, e, 0);
        clearBeats(p, e);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BELL_BLOCK, SoundSource.PLAYERS, 0.3F, 1.4F);
        SectTablet.touch(p);
        MurimMod.LOGGER.info("Sect office: {} is invited to {}", p.getName().getString(), e.id());
    }

    /**
     * A servant (for the council) or the host / the nearest member (for the rest) walks up to the player with a bubble. False:
     * nobody could come now (try again next second; the journal entry stands either way).
     */
    static boolean send(ServerPlayer p, Event e) {
        List<SectDisciple> people = SectApproach.candidates(p);
        if (people.isEmpty() || SectApproach.busy(p)) {
            return false;
        }
        SectDisciple who = null;
        if (COUNCIL.equals(e.id())) {
            who = people.stream().filter(d -> d.role().lay()).findFirst().orElse(null);
        } else {
            who = people.stream().filter(d -> d.memberKey().equals(e.host())).findFirst().orElse(null);
        }
        if (who == null) {
            who = people.get(0);
        }
        return who.approach(p, "invite:" + e.id(), SectApproach.PATIENCE);
    }

    // ------------------------------------------------------------------ coming

    /** The player is where the invitation wants him now. */
    static boolean stays(ServerPlayer p, Event e, long time, SectLayoutFile file) {
        switch (e.attend()) {
            case "seat" -> {
                return SectCouncil.phase(time).seated() && sitsAtCouncil(p, file);
            }
            case "near_host" -> {
                SectDisciple host = member(p, e.host(), 5.0D);
                return host != null && host.doingKind() == Kind.COUNT && host.free() && Math.abs(host.getY() - p.getY()) < 2.5D;
            }
            case "near_pair" -> {
                return !disputants(p, 4.5D).isEmpty();
            }
            case "near_drill" -> {
                return !juniors(p, 6.5D).isEmpty();
            }
            default -> {
                // "formation" is answered by the morning line itself (formationDone)
                return false;
            }
        }
    }

    static SectLayoutFile fileOf(ServerPlayer p) {
        SectLayout layout = SectLife.layout(p.serverLevel());
        return layout == null ? null : layout.file();
    }

    /** He sits on a cushion of the council hall. */
    static boolean sitsAtCouncil(ServerPlayer p, SectLayoutFile file) {
        if (!(p.getVehicle() instanceof SeatEntity seat) || file == null) {
            return false;
        }
        BlockPos at = seat.blockPosition();
        for (SectLayoutFile.Spot s : SectPlaces.councilCushions(file)) {
            if (s.pos().getX() == at.getX() && s.pos().getZ() == at.getZ() && Math.abs(s.pos().getY() - at.getY()) <= 1) {
                return true;
            }
        }
        return false;
    }

    static SectDisciple member(ServerPlayer p, String key, double range) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range),
                d -> d.isAlive() && key.equals(d.memberKey())).stream().findFirst().orElse(null);
    }

    /** The quarrelling juniors within reach. */
    static List<SectDisciple> disputants(ServerPlayer p, double range) {
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range),
                d -> d.isAlive() && d.doingKind() == Kind.ARGUE && Math.abs(d.getY() - p.getY()) < 2.5D);
    }

    /** Juniors at the poles or at their own forms within reach. */
    static List<SectDisciple> juniors(ServerPlayer p, double range) {
        return p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range),
                d -> d.isAlive() && (d.doingKind() == Kind.POLES || d.doingKind() == Kind.DRILL) && d.member().map(m -> m.generation() == 3)
                        .orElse(false) && Math.abs(d.getY() - p.getY()) < 3.0D);
    }

    /** The morning line was led to the end by a first disciple (called from {@link SectLife#onPlayerForm}). */
    public static void formationDone(ServerPlayer p) {
        SectState s = SectService.state(p);
        long time = p.level().getDayTime();
        long day = SectSchedule.day(time);
        for (Event e : out(s, time)) {
            if ("formation".equals(e.attend()) && e.open(SectCouncil.tick(time)) && invited(s, e, day)) {
                scene(p, e, 0);
                finish(p, e, day);
            }
        }
    }

    /** He came: the contribution, the answer in the journal, the closing word of the host. */
    static void finish(ServerPlayer p, Event e, long day) {
        p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, SectService.state(p).record(DONE + e.id(), day));
        setHold(p, e, 0);
        // the lines that wait for the end of the stay
        scene(p, e, Integer.MAX_VALUE / 2);
        clearBeats(p, e);
        if ("near_pair".equals(e.attend())) {
            for (SectDisciple d : disputants(p, 8.0D)) {
                d.getPersistentData().putLong(DISPUTE_DONE, day);
                d.faceEntity(p, 60.0F);
                if (SectSocial.mayBow(d, p, true)) d.holdPose(SectPose.BOW, 44);
                else d.gesture("nod");
            }
        }
        SectService.contribute(p, e.contribution());
        SectTablet.touch(p);
        MurimMod.LOGGER.info("Sect office: {} came to {}", p.getName().getString(), e.id());
    }

    // ------------------------------------------------------------------ the scenes

    /** The lines of a scene, in order. Speakers: a roster key, {@code host}, {@code pair0}/{@code pair1}, {@code junior}, {@code council}. */
    static List<Beat> beats(Event e) {
        return switch (e.id()) {
            case COUNCIL -> List.of(new Beat(0, "tae_hwi", "murim.office.council.scene.1"), new Beat(60, "council", ""),
                    new Beat(120, "tae_gyun", "murim.office.council.scene.2"), new Beat(Integer.MAX_VALUE / 2, "tae_hwi", "murim.office.council.scene.3"));
            case "audit" -> List.of(new Beat(0, "host", "murim.office.audit.scene.1"), new Beat(60, "host", "murim.office.audit.scene.2"),
                    new Beat(Integer.MAX_VALUE / 2, "host", "murim.office.audit.scene.3"));
            case "dispute" -> List.of(new Beat(0, "pair0", "murim.office.dispute.scene.1"), new Beat(40, "pair1", "murim.office.dispute.scene.2"),
                    new Beat(80, "pair0", "murim.office.dispute.scene.3"), new Beat(Integer.MAX_VALUE / 2, "pair1", "murim.office.dispute.scene.4"));
            case "lead_formation" -> List.of(new Beat(0, "host", "murim.office.lead_formation.scene.1"));
            case "drill" -> List.of(new Beat(0, "junior", "murim.office.drill.scene.1"), new Beat(Integer.MAX_VALUE / 2, "junior", "murim.office.drill.scene.2"));
            default -> List.of();
        };
    }

    /** Plays the lines whose time has come ({@code held} ticks of staying), each once. */
    static void scene(ServerPlayer p, Event e, int held) {
        CompoundTag tag = p.getPersistentData().getCompound(BEAT);
        int next = tag.getInt(e.id());
        List<Beat> beats = beats(e);
        while (next < beats.size() && held >= beats.get(next).at()) {
            Beat b = beats.get(next);
            next++;
            SectDisciple who = speaker(p, e, b.who());
            if (who != null) {
                String key = b.key().isEmpty() ? SectBubbles.Group.COUNCIL.pick(p, p.getRandom()) : b.key();
                who.faceEntity(p, 40.0F);
                who.gesture(b.at() == 0 ? "nod" : "explain");
                SectChatter.send(who, key, Component.translatable(key));
            }
        }
        tag.putInt(e.id(), next);
        p.getPersistentData().put(BEAT, tag);
    }

    private static SectDisciple speaker(ServerPlayer p, Event e, String who) {
        double range = 30.0D;
        switch (who) {
            case "host" -> {
                return member(p, e.host(), range);
            }
            case "pair0", "pair1" -> {
                List<SectDisciple> pair = disputants(p, range);
                pair.sort(java.util.Comparator.comparing(SectDisciple::memberKey));
                int i = "pair0".equals(who) ? 0 : 1;
                return i < pair.size() ? pair.get(i) : pair.isEmpty() ? null : pair.get(0);
            }
            case "junior" -> {
                List<SectDisciple> list = juniors(p, 9.0D);
                list.sort(java.util.Comparator.comparingDouble((SectDisciple d) -> d.distanceToSqr(p)));
                return list.isEmpty() ? null : list.get(0);
            }
            case "council" -> {
                // some other member at the table
                List<SectDisciple> seated = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range),
                        d -> d.isAlive() && d.doingKind() == Kind.COUNCIL && d.sitting() && !"tae_hwi".equals(d.memberKey()));
                seated.sort(java.util.Comparator.comparing(SectDisciple::memberKey));
                return seated.isEmpty() ? null : seated.get(Math.floorMod(p.tickCount / 20, seated.size()));
            }
            default -> {
                return member(p, who, range);
            }
        }
    }

    private static int hold(ServerPlayer p, Event e) {
        return p.getPersistentData().getCompound(HOLD).getInt(e.id());
    }

    private static void setHold(ServerPlayer p, Event e, int ticks) {
        CompoundTag tag = p.getPersistentData().getCompound(HOLD);
        tag.putInt(e.id(), ticks);
        p.getPersistentData().put(HOLD, tag);
    }

    private static void clearBeats(ServerPlayer p, Event e) {
        CompoundTag tag = p.getPersistentData().getCompound(BEAT);
        tag.putInt(e.id(), 0);
        p.getPersistentData().put(BEAT, tag);
    }
}
