package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.entity.Bandit;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.List;

/** GameTest-only isolation for fixed positions reused by later batches. */
public final class CampTestFixtures {
    private CampTestFixtures() {}

    /** Populate only after the yard is entity-ticking, and hold AI until every actor has settled.
     * API: minecraft-src/net/minecraft/world/entity/{Mob,Entity}.java
     * (setNoAi, getRandom, tickCount, onGround); util/RandomSource.java#setSeed. */
    public static List<Bandit> populatePaused(GameTestHelper helper, BanditCampData.Camp camp) {
        BanditCamps.populate(helper.getLevel(), camp);
        List<Bandit> members = new ArrayList<>();
        for (var entity : helper.getLevel().getAllEntities()) {
            if (entity instanceof Bandit b && b.campKey() == camp.key) {
                b.setNoAi(true);
                // Spawn positions are on the freshly placed yard floor.
                b.setOnGround(true);
                b.getRandom().setSeed(camp.seed ^ (0x5DEECE66DL * (b.campSlot() + 1)));
                members.add(b);
            }
        }
        helper.assertTrue(members.size() == camp.alive.size() && !members.isEmpty(), "complete fresh camp required");
        return members;
    }

    public static void resume(GameTestHelper helper, BanditCampData.Camp camp, List<Bandit> members) {
        helper.assertTrue(members.stream().allMatch(b -> b.tickCount > 0 && b.onGround()),
                "camp actors must tick and settle before starting their schedule");
        for (Bandit b : members) {
            b.getRandom().setSeed(camp.seed ^ (0x5DEECE66DL * (b.campSlot() + 1)));
            b.setNoAi(false);
        }
    }

    public static void remove(ServerLevel level, long key) {
        // Collect before discarding: entity removal updates camp membership through level events.
        List<Bandit> members = new ArrayList<>();
        for (var entity : level.getAllEntities()) {
            if (entity instanceof Bandit bandit && bandit.campKey() == key) members.add(bandit);
        }
        members.forEach(Bandit::discard);
        BanditCamps.data(level).remove(key);
    }

    public static BanditCampData.Camp fresh(GameTestHelper helper, long key, BlockPos centre, long seed) {
        remove(helper.getLevel(), key);
        var camp = BanditCamps.data(helper.getLevel()).getOrCreate(key, centre, seed);
        helper.assertTrue(camp.seed == seed && camp.alive.isEmpty() && camp.travellers.isEmpty(),
                "camp fixture must start with the requested seed and an empty roster and travellers");
        return camp;
    }
}
