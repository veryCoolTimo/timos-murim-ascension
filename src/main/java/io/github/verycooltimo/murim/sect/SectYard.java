package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The training yard of the sect (docs/design/research/hua-main-1810.md section 6, T1; Track C): {@link #SLOTS} dummies in a
 * row on the sword and poles ground. A dummy is a plain wooden post ({@link TrainingDummy}): it wobbles when hit and breaks
 * under one great blow; the practice rules are {@link io.github.verycooltimo.murim.mastery.YardRules}.
 *
 * <p>The yard is set up by the sect, never by hand in the author's world: the places come from the layout (the zone of the
 * poles and the pole-drill spots of the built sect, else the plan's poles zone), and {@link #ensure} fills the empty ones.
 * The dummy entity type is not saved on purpose (a debug dummy must not pile up between runs), so a restart or an unloaded
 * chunk simply drops the yard and the next {@link #ensure} puts it back: one dummy per slot, never two (a slot is asked by
 * its number and place before a new one is made).
 */
public final class SectYard {

    /** Dummies in the yard: four to six were asked, five make a row. */
    public static final int SLOTS = 5;
    /** Blocks between two dummies: a swing of one never reaches the next. */
    public static final int SPACING = 3;
    /** The yard fills only when somebody is this near (blocks), like the people of the mountain. */
    static final double WAKE_RANGE = 96.0D;
    /** The dummy stands within this of its place to count as the one of the slot. */
    static final double SLOT_REACH = 1.5D;

    /** A broken dummy is put back after this many ticks (a minute: the servants set up a new post). */
    public static final int REBUILD_TICKS = 1200;
    /** Slot -> game time when its dummy broke; {@link #ensure} leaves the slot empty until the pause is over. */
    private static final java.util.Map<Integer, Long> BROKEN = new java.util.concurrent.ConcurrentHashMap<>();

    /** Server stopped: forget cross-world state. */
    public static void clearBroken() { BROKEN.clear(); }

    private SectYard() {
    }

    /** A dummy of the slot broke under a great blow ({@link TrainingDummy#BREAK_DAMAGE}): the sect puts a new one after a pause. */
    public static void broken(ServerLevel level, int slot) {
        if (slot >= 0) {
            BROKEN.put(slot, level.getGameTime());
        }
    }

    /** Forget the pauses (a test, the operator command): the next {@link #ensure} fills every empty slot. */
    public static void forgetBroken() {
        BROKEN.clear();
    }

    /**
     * The standing cells (feet) of the yard, west to east: in the built sect a row across the poles ground, on the line
     * that keeps the farthest from the pole-drill places; without the built sect, a row on the plan's poles zone.
     */
    public static List<Vec3> places(SectLayout layout) {
        List<Vec3> out = new ArrayList<>();
        SectLayoutFile file = layout.file();
        SectLayoutFile.Zone zone = file == null ? null : file.zone("poles_area");
        if (zone != null) {
            SectLayoutFile.Rect r = zone.rect();
            int row = row(file, r);
            for (int i = 0; i < SLOTS; i++) {
                out.add(new Vec3(r.x0() + 1 + SPACING * i + 0.5D, zone.y(), row + 0.5D));
            }
            return out;
        }
        for (int i = 0; i < SLOTS; i++) {
            Vec3 at = layout.at("poles", (i - (SLOTS - 1) / 2.0D) * SPACING, 0.0D);
            if (at != null) {
                out.add(at);
            }
        }
        return out;
    }

    /** The line of the rect that keeps farthest from the pole-drill places (the middle one on a tie): the yard stands between them. */
    static int row(SectLayoutFile file, SectLayoutFile.Rect r) {
        List<SectLayoutFile.Spot> poles = file.spots().stream().filter(s -> "pole_stand".equals(s.kind())).toList();
        int mid = (r.z0() + r.z1()) / 2;
        int best = mid;
        int bestClear = -1;
        for (int z = r.z0() + 1; z <= r.z1() - 1; z++) {
            int clear = Integer.MAX_VALUE;
            for (SectLayoutFile.Spot p : poles) {
                clear = Math.min(clear, Math.abs(p.pos().getZ() - z));
            }
            if (clear > bestClear || clear == bestClear && Math.abs(z - mid) < Math.abs(best - mid)) {
                bestClear = clear;
                best = z;
            }
        }
        return best;
    }

    /** Where the yard dummies face: the middle of the training square (the players come from there). */
    static Vec3 facing(SectLayout layout) {
        Vec3 c = layout.at("training", 0.0D, 0.0D);
        return c == null ? Vec3.ZERO : c;
    }

    /** The yard dummy of a slot standing at its place, or null. */
    static TrainingDummy at(ServerLevel level, int slot, Vec3 place) {
        List<TrainingDummy> near = dummies(level, place, slot);
        return near.isEmpty() ? null : near.get(0);
    }

    /** Yard dummies of a slot near a place, the oldest first. */
    static List<TrainingDummy> dummies(ServerLevel level, Vec3 place, int slot) {
        List<TrainingDummy> found = new ArrayList<>(level.getEntitiesOfClass(TrainingDummy.class,
                new AABB(place, place).inflate(SLOT_REACH, 4.0D, SLOT_REACH), d -> d.isAlive() && d.yardSlot() == slot));
        found.sort(Comparator.comparingInt(TrainingDummy::getId));
        return found;
    }

    /** Every yard dummy near the places (for tests and the command). */
    public static List<TrainingDummy> all(ServerLevel level, SectLayout layout) {
        List<TrainingDummy> out = new ArrayList<>();
        List<Vec3> places = places(layout);
        for (int i = 0; i < places.size(); i++) {
            out.addAll(dummies(level, places.get(i), i));
        }
        return out;
    }

    /**
     * Puts a dummy in every empty slot whose place is loaded (and, when {@code needPlayer}, has somebody within
     * {@link #WAKE_RANGE}); a second dummy of a slot is removed.
     *
     * @return how many dummies were put
     */
    public static int ensure(ServerLevel level, SectLayout layout, boolean needPlayer) {
        List<Vec3> places = places(layout);
        Vec3 look = facing(layout);
        int made = 0;
        for (int i = 0; i < places.size(); i++) {
            Vec3 guess = places.get(i);
            BlockPos cell = BlockPos.containing(guess);
            if (!level.isPositionEntityTicking(cell)) {
                continue;
            }
            List<TrainingDummy> have = dummies(level, guess, i);
            for (int k = 1; k < have.size(); k++) {
                have.get(k).discard();
            }
            if (!have.isEmpty()) {
                continue;
            }
            Long broke = BROKEN.get(i);
            if (broke != null) {
                if (level.getGameTime() - broke < REBUILD_TICKS && level.getGameTime() >= broke) {
                    continue;
                }
                BROKEN.remove(i);
            }
            if (needPlayer && level.getNearestPlayer(guess.x, guess.y, guess.z, WAKE_RANGE, false) == null) {
                continue;
            }
            Vec3 spot = SectLife.stand(level, guess);
            TrainingDummy dummy = new TrainingDummy(ModEntities.DUMMY.get(), level);
            dummy.setYardSlot(i);
            float yaw = look.equals(Vec3.ZERO) ? 180.0F : SectLayout.yawOf(look.x - spot.x, look.z - spot.z);
            dummy.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
            dummy.setYHeadRot(yaw);
            dummy.setYBodyRot(yaw);
            level.addFreshEntity(dummy);
            made++;
        }
        if (made > 0) {
            MurimMod.LOGGER.info("Sect yard: {} training dummies put", made);
        }
        return made;
    }
}
