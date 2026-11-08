package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Somebody walks in on the council of elders (author 21.10: "if you burst in on their meeting with the head he says: what are you
 * doing, what disrespect, or the guard takes you"). While the table sits, a player inside the council hall who is not an elder
 * gets a sharp word over the head of the head of the sect (or the nearest elder), the meeting stops for a moment, and the door guard
 * walks up to HIM (not to the door: sect check 24.10, the guard stood at the door and the intruder stayed), says his line and takes him
 * out of the storey down the stair, the way a guard takes a trespasser out of a closed place (forced escort: he walks ahead and the
 * intruder is carried along behind him). If he stays, or comes back, the guard comes again after {@link #RETRY} ticks. Sandbox rule:
 * no jail, no penalty, no merit lost. Another time the same day the guard is quicker.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectCouncilIntrusion {

    static final int PERIOD = 10;
    /** How long the table falls silent after a reprimand (ticks); refreshed while the intruder is still inside. */
    static final int PAUSE = 100;
    /** The guard comes for an intruder who is still inside (or back) this long after the last time he set out (ticks). */
    static final int RETRY = 100;
    /** The intruder is left alone this long after he was led out and walks in again within (ticks): a new intrusion is counted. */
    static final int FRESH = 600;
    static final double HEARS = 30.0D;

    private static final String DAY = "murim_council_day";
    private static final String COUNT = "murim_council_n";
    private static final String AT = "murim_council_at";
    private static final String SCOLD = "murim_council_scold";
    private static final String TRY = "murim_council_try";

    private static volatile long pausedUntil = Long.MIN_VALUE / 2;

    private SectCouncilIntrusion() {
    }

    /** The table is silent just now because of an intruder. */
    public static boolean paused(long gameTime) {
        return gameTime < pausedUntil;
    }

    /** Intrusions of this player counted today. */
    public static int count(ServerPlayer p) {
        long day = SectSchedule.day(p.level().getDayTime());
        return p.getPersistentData().getLong(DAY) == day + 1 ? p.getPersistentData().getInt(COUNT) : 0;
    }

    /** The council hall is a seated meeting and this person is inside the hall itself (past the door). */
    public static boolean intruding(ServerPlayer p, SectLayoutFile file) {
        if (p.isCreative() || p.isSpectator() || !p.isAlive() || !SectCouncil.phase(p.level().getDayTime()).seated()
                || SectService.state(p).has(SectPositions.ELDER)) {
            return false;
        }
        SectLayoutFile.Room hall = hall(file);
        if (hall == null) {
            return false;
        }
        // The whole storey of the council hall counts (its stair head and corridors too): walking about up there during the
        // sitting is the intrusion, not only standing on the cushions (author playtest 22.10).
        for (SectLayoutFile.Room r : file.rooms()) {
            if (hall.building().equals(r.building()) && r.floor() == hall.floor() && r.bounds().contains(p.getBlockX(), p.getBlockZ())
                    && p.getY() >= r.y() - 1.0D && p.getY() <= r.y() + 6.0D) {
                return true;
            }
        }
        return false;
    }

    static SectLayoutFile.Room hall(SectLayoutFile file) {
        String id = SectPlaces.roomId(file, SectPlaces.Landmark.COUNCIL_HALL);
        return id == null ? null : file.room(id);
    }

    @SubscribeEvent
    static void playerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % PERIOD != 5 || p.isSpectator()) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(p.server);
        if (site != null && SectTerritory.contains(site, p.getX(), p.getY(), p.getZ())) {
            check(p, SectLayout.hua(site));
        }
    }

    /** One look at the player: reprimand, escort, nudge. Never a penalty. */
    public static void check(ServerPlayer p, SectLayout layout) {
        SectLayoutFile file = layout.file();
        long now = p.level().getGameTime();
        var data = p.getPersistentData();
        if (!intruding(p, file)) {
            if (data.getLong(AT) > 0 && (!SectCouncil.phase(p.level().getDayTime()).on() || now - data.getLong(AT) > FRESH)) {
                data.remove(AT);
            }
            return;
        }
        long day = SectSchedule.day(p.level().getDayTime());
        if (data.getLong(DAY) != day + 1) {
            data.putLong(DAY, day + 1);
            data.putInt(COUNT, 0);
        }
        SectDisciple leading = leader(p);
        SectDisciple guard = leading != null ? leading : guard(p);
        boolean fresh = data.getLong(AT) <= 0 || now - data.getLong(AT) > FRESH;
        Vec3 out = exit(file);
        if (fresh) {
            data.putInt(COUNT, data.getInt(COUNT) + 1);
            data.putLong(AT, now);
            say(chair(p), SectBubbles.Group.COUNCIL_REPRIMAND, p);
            data.putLong(SCOLD, now);
            if (guard != null && (leading != null || lead(guard, p, out))) {
                data.putLong(TRY, now);
                say(guard, data.getInt(COUNT) > 1 ? SectBubbles.Group.COUNCIL_ESCORT_AGAIN : SectBubbles.Group.COUNCIL_ESCORT, p);
                guard.gesture("point");
            }
        } else {
            if (now - data.getLong(SCOLD) >= 200) {
                data.putLong(SCOLD, now);
                say(chair(p), SectBubbles.Group.COUNCIL_REPRIMAND, p);
            }
            // He is still here (or back) and nobody has him on the way out: a guard comes for him again.
            if (leading == null && guard != null && now - data.getLong(TRY) >= RETRY && lead(guard, p, out)) {
                data.putLong(TRY, now);
                say(guard, SectBubbles.Group.COUNCIL_ESCORT_AGAIN, p);
                guard.gesture("point");
            }
        }
        pausedUntil = Math.max(pausedUntil, now + PAUSE);
    }

    /** The guard sets out for the intruder and takes him out. False when he cannot (busy, no way out known). */
    private static boolean lead(SectDisciple guard, ServerPlayer p, Vec3 out) {
        return out != null && guard.escortGoal().begin(p, out, true);
    }

    /** Whoever has this player on the way out just now. */
    static SectDisciple leader(ServerPlayer p) {
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64.0D),
                d -> d.isAlive() && d.escortGoal().forPlayer(p))) {
            return d;
        }
        return null;
    }

    /** Where the guard takes him: out of the storey of the council, to the foot of the stair that leads down from its landing. */
    static Vec3 exit(SectLayoutFile file) {
        SectPlaces.Pick exit = SectPlaces.councilExit(file);
        return exit == null ? null : new Vec3(exit.x(), exit.y(), exit.z());
    }

    /** Where the guard leads him: the landing outside the council door. */
    static Vec3 door(SectLayoutFile file) {
        SectPlaces.Pick door = SectPlaces.councilDoor(file);
        return door == null ? null : new Vec3(door.x(), door.y(), door.z());
    }

    /** The head of the sect if he sits within hearing, else the nearest member of the council. */
    static SectDisciple chair(ServerPlayer p) {
        java.util.List<String> members = SectCouncil.config().members();
        SectDisciple best = null;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(HEARS),
                d -> d.isAlive() && members.contains(d.memberKey()))) {
            if (best == null || d.memberKey().equals(members.get(0))
                    || !best.memberKey().equals(members.get(0)) && d.distanceToSqr(p) < best.distanceToSqr(p)) {
                best = d;
            }
        }
        return best;
    }

    /** The nearest free guard (the one at the council door by preference). */
    static SectDisciple guard(ServerPlayer p) {
        SectDisciple best = null;
        double bestScore = Double.MAX_VALUE;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(48.0D),
                d -> d.isAlive() && !d.isSleeping() && d.free() && SectWatch.guards(d) && d.guardTarget() == null
                        && (!d.escorting() || d.escortGoal().forPlayer(p)))) {
            SectLife.Resolved r = SectLife.resolve(d);
            boolean atDoor = r != null && SectSchedule.COUNCIL_DOOR.equals(r.task().partner());
            double score = d.distanceTo(p) - (atDoor ? 100.0D : 0.0D);
            if (score < bestScore) {
                bestScore = score;
                best = d;
            }
        }
        return best;
    }

    private static void say(SectDisciple who, SectBubbles.Group group, ServerPlayer listener) {
        if (who == null) {
            return;
        }
        String key = group.pick(listener, who.getRandom());
        SectChatter.send(who, key, Component.translatable(key));
        who.faceEntity(listener, 60.0F);
        who.gesture("shake");
    }

    /** An elder of the council who is asked for business while the council sits: a word over his head instead of a dialogue. */
    public static boolean refuses(ServerPlayer p, SectDisciple npc) {
        if (!SectCouncil.member(npc.memberKey()) || !SectCouncil.phase(p.level().getDayTime()).on()) {
            return false;
        }
        // only a person who is actually at (or bound for) the council, not one who merely shares its clock
        SectLife.Resolved routine = SectLife.resolve(npc);
        if (routine == null || routine.task().kind() != SectSchedule.Kind.COUNCIL) {
            return false;
        }
        say(npc, SectBubbles.Group.COUNCIL_BUSY, p);
        return true;
    }

    /** Test hook. */
    static void clearPause() {
        pausedUntil = Long.MIN_VALUE / 2;
    }
}
