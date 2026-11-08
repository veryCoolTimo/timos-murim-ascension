package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The sect's answer to a rank breakthrough (hua-main-1810 section 4, author 18.10: «ученики подходят и кланяются»):
 * the free disciples nearby walk up, stand round the player in an arc and bow, and a few say one of the
 * {@code social.acknowledge} lines over their heads. Nothing is a duty and nobody is forced: only people who are free,
 * awake and not at a post come; elders, the head, the gate and the guards keep their places, servants keep working.
 *
 * <p>State is on the person (the {@code murim_sect_yield} tag that {@code ScheduleGoal} already walks him to, plus a
 * small {@code murim_bt_bow} tag), none in static fields.
 */
public final class SectBreakthrough {

    /** People within this many blocks of the player hear the impact and come. */
    public static final double CALL_RANGE = 16.0D;
    /** How many come at most. */
    public static final int MAX_COMERS = 6;
    /** They stop this far from the player. */
    public static final double STAND_OFF = 3.2D;
    /** They keep the place this long (ticks): time to walk up, bow and stay a little. */
    public static final int STAY_TICKS = 260;
    /** A person bows once he is this near the player. */
    public static final double BOW_RANGE = 4.6D;
    /** At most this many say a line. */
    public static final int MAX_SPEAKERS = 3;

    private static final String BOW_TAG = "murim_bt_bow";
    private static final String BOW_UNTIL = "murim_bt_bow_until";

    private SectBreakthrough() {
    }

    /** Who comes and bows: free, awake, in the sect, and not an elder, the head, the gate, a guard or a servant. */
    public static boolean willing(SectDisciple d, ServerPlayer p) {
        SectRole role = d.role();
        return d.isAlive() && d.member().isPresent() && d.free() && !d.isSleeping() && !d.wounded() && !d.dormant()
                && !d.escorting() && !d.blocking() && d.level() == p.level()
                && !role.lay() && role != SectRole.LEADER && role != SectRole.ELDER && role != SectRole.GATEKEEPER
                && role != SectRole.GUARD && Math.abs(d.getY() - p.getY()) <= 6.0D;
    }

    /** The impact: send the nearest free disciples to stand round the player. Returns who set off. */
    public static List<SectDisciple> call(ServerPlayer p) {
        // The impact itself is the event, including callers outside the cultivation tick path.
        SectSpeechWitnesses.record(p, "realm_up");
        List<SectDisciple> people = new ArrayList<>(p.level().getEntitiesOfClass(SectDisciple.class,
                p.getBoundingBox().inflate(CALL_RANGE), d -> willing(d, p) && d.distanceToSqr(p) <= CALL_RANGE * CALL_RANGE));
        people.sort(Comparator.comparingDouble((SectDisciple d) -> d.distanceToSqr(p)).thenComparing(SectDisciple::memberKey));
        if (people.size() > MAX_COMERS) {
            people = new ArrayList<>(people.subList(0, MAX_COMERS));
        }
        // An arc facing the player, in front of him: the one who is nearest takes the middle.
        Vec3 look = Vec3.directionFromRotation(0.0F, p.getYRot());
        double base = Math.atan2(look.z, look.x);
        for (int i = 0; i < people.size(); i++) {
            double slot = (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * 0.55D;
            double angle = base + slot;
            Vec3 spot = p.position().add(Math.cos(angle) * STAND_OFF, 0.0D, Math.sin(angle) * STAND_OFF);
            SectDisciple d = people.get(i);
            d.wake();
            d.attend(p, STAY_TICKS);
            SectSocial.yieldTo(d, spot, STAY_TICKS);
            d.getPersistentData().putBoolean(BOW_TAG, false);
            // a window that never closed (the player died or left) expires on its own
            d.getPersistentData().putLong(BOW_UNTIL, p.level().getGameTime() + STAY_TICKS + 200L);
        }
        return people;
    }

    /** One look at the people who were called: those who have arrived bow (once) and a few say a line. */
    public static int bow(ServerPlayer p) {
        int bowed = 0;
        int spoken = 0;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(CALL_RANGE))) {
            CompoundTag tag = d.getPersistentData();
            if (!tag.contains(BOW_TAG) || tag.getBoolean(BOW_TAG) || p.level().getGameTime() > tag.getLong(BOW_UNTIL) || d.distanceToSqr(p) > BOW_RANGE * BOW_RANGE) {
                continue;
            }
            tag.putBoolean(BOW_TAG, true);
            d.faceEntity(p, 60.0F);
            d.holdPose(SectPose.BOW, 40);
            bowed++;
            if (spoken < MAX_SPEAKERS) {
                spoken++;
                String key = SectSocial.pick(p, d, SectSocial.bubbleKeys("acknowledge"));
                SectChatter.send(d, key, Component.translatable(key));
            }
        }
        return bowed;
    }

    /** The window is over: forget who was called, so nobody bows to the player later by accident. */
    public static void release(ServerPlayer p) {
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(CALL_RANGE + 8.0D))) {
            d.getPersistentData().remove(BOW_TAG);
            d.getPersistentData().remove(BOW_UNTIL);
        }
    }

    /** Has this person been called and not bowed yet? (for tests) */
    public static boolean waitingToBow(SectDisciple d) {
        CompoundTag tag = d.getPersistentData();
        return tag.contains(BOW_TAG) && !tag.getBoolean(BOW_TAG) && d.level().getGameTime() <= tag.getLong(BOW_UNTIL);
    }

    /** Has this person bowed after the breakthrough? (for tests) */
    public static boolean bowed(SectDisciple d) {
        return d.getPersistentData().getBoolean(BOW_TAG);
    }
}
