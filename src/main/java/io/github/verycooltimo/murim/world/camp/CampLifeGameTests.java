package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.CampIdleGoal;
import net.minecraft.world.entity.ai.attributes.Attributes;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.AfterBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CampfireBlock;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Runtime acceptance fixtures. API/readiness follows SectEscortArrivalGameTests and BanditCampTests.
 * Corporate checkout: authored here, never executed here. Each walking test has its own time-of-day batch. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class CampLifeGameTests {
    private CampLifeGameTests() {}

    // Patrol radius is at most 24, layout posts fit inside it; retain two blocks of clearance.
    private static final int YARD_RADIUS = 26;

    private static void floor(ServerLevel level, BlockPos centre, int radius) {
        for (int x = -radius; x <= radius; x++) for (int z = -radius; z <= radius; z++) {
            BlockPos feet = centre.offset(x, 0, z);
            level.setBlock(feet.below(), Blocks.STONE.defaultBlockState(), 3);
            for (int y = 0; y <= 8; y++) level.setBlock(feet.above(y), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    // Test harness bookkeeping only; AfterBatch also releases failed/timed-out attempts.
    private static final java.util.Map<String, Yard> YARDS = new java.util.HashMap<>();

    private record Yard(ServerLevel level, BanditCampData.Camp camp, List<Long> extraCamps,
                        TicketType<ChunkPos> ticket) {
        void loadDependencies() {
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/ChunkMap.java:
            // prepareEntityTickingChunk needs radius-two FULL neighbors. Level#getChunk(int,int)
            // requests FULL and ServerChunkCache#getChunk drives completion with managedBlock.
            // Finish generation before promotion: unpaced GameTest ticks are not an I/O budget.
            // Dependency padding is deliberately excluded from pendingChunk's readiness gate.
            for (int x = ((camp.centre.getX() - YARD_RADIUS) >> 4) - 2;
                 x <= ((camp.centre.getX() + YARD_RADIUS) >> 4) + 2; x++)
                for (int z = ((camp.centre.getZ() - YARD_RADIUS) >> 4) - 2;
                     z <= ((camp.centre.getZ() + YARD_RADIUS) >> 4) + 2; z++)
                    level.getChunk(x, z);
        }

        void force(boolean on) {
            for (int x = (camp.centre.getX() - YARD_RADIUS) >> 4; x <= (camp.centre.getX() + YARD_RADIUS) >> 4; x++)
                for (int z = (camp.centre.getZ() - YARD_RADIUS) >> 4; z <= (camp.centre.getZ() + YARD_RADIUS) >> 4; z++) {
                    ChunkPos pos = new ChunkPos(x, z);
                    // API: minecraft-src/net/minecraft/server/level/{TicketType,ServerChunkCache,DistanceManager}.
                    // FULL(33) - 2 = ENTITY_TICKING(31); forceTicks supports a playerless yard.
                    // Owned tickets survive GameTestRunner's clearing of shared forced flags.
                    if (on) level.getChunkSource().addRegionTicket(ticket, pos, 2, pos, true);
                    else level.getChunkSource().removeRegionTicket(ticket, pos, 2, pos, true);
                }
        }
        boolean ticking() {
            return pendingChunk() == null;
        }
        ChunkPos pendingChunk() {
            for (int x = (camp.centre.getX() - YARD_RADIUS) >> 4; x <= (camp.centre.getX() + YARD_RADIUS) >> 4; x++)
                for (int z = (camp.centre.getZ() - YARD_RADIUS) >> 4; z <= (camp.centre.getZ() + YARD_RADIUS) >> 4; z++)
                    if (!level.isPositionEntityTicking(new BlockPos(x << 4, camp.centre.getY(), z << 4)))
                        return new ChunkPos(x, z);
            return null;
        }
        String readiness(List<Bandit> members) {
            ChunkPos pending = pendingChunk();
            if (pending != null) {
                // API: ServerLevel#isNaturalSpawningAllowed(BlockPos) reads entity visibility;
                // ServerChunkCache#isPositionTicking(long) checks block-ticking readiness.
                BlockPos pos = new BlockPos(pending.x << 4, camp.centre.getY(), pending.z << 4);
                return "pending chunk=" + pending + " entityVisibility=" + level.isNaturalSpawningAllowed(pos)
                        + " blockTicking=" + level.getChunkSource().isPositionTicking(pending.toLong());
            }
            if (members.isEmpty()) return "chunks ready; roster not populated";
            return "chunks ready; unsettled actors=" + members.stream()
                    .filter(b -> b.tickCount == 0 || !b.onGround())
                    .map(b -> "slot=" + b.campSlot() + " ticks=" + b.tickCount + " grounded=" + b.onGround())
                    .collect(java.util.stream.Collectors.joining("; "));
        }
        List<Bandit> members() {
            List<Bandit> result = new ArrayList<>();
            for (UUID id : camp.alive.values()) if (level.getEntity(id) instanceof Bandit b) result.add(b);
            return result;
        }
        void finish() {
            CampTestFixtures.remove(level, camp.key);
            extraCamps.forEach(key -> CampTestFixtures.remove(level, key));
            force(false);
            YARDS.values().remove(this);
        }
    }

    private static void closeYard(String batch) {
        Yard yard = YARDS.remove(batch);
        if (yard != null) yard.finish();
    }

    // API: minecraft-src/net/minecraft/gametest/framework/{AfterBatch,GameTestRunner}.java:
    // hooks run after success and failure, including readiness timeouts.
    @AfterBatch(batch = "bandit_life_day")
    public static void closeDay(ServerLevel level) { closeYard("bandit_life_day"); }

    @AfterBatch(batch = "bandit_life_blocked_return")
    public static void closeBlockedReturn(ServerLevel level) { closeYard("bandit_life_blocked_return"); }

    @AfterBatch(batch = "bandit_life_night")
    public static void closeNight(ServerLevel level) { closeYard("bandit_life_night"); }

    @AfterBatch(batch = "bandit_life_roster")
    public static void closeRoster(ServerLevel level) { closeYard("bandit_life_roster"); }

    @AfterBatch(batch = "bandit_life_rebuild")
    public static void closeRebuild(ServerLevel level) { closeYard("bandit_life_rebuild"); }

    private static Yard yard(GameTestHelper h, String batch, long seed) {
        return yard(h, batch, seed, true);
    }

    private static Yard yard(GameTestHelper h, String batch, long seed, boolean populate) {
        closeYard(batch);
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(5000, 2, 5000));
        // Centre on a chunk boundary: the complete supported yard fits in exactly 4x4 chunks.
        // Distance-2 tickets already request vanilla's dependency ring; do not ticket another ring.
        // API: minecraft-src/net/minecraft/server/level/DistanceManager.java#addRegionTicket.
        at = new BlockPos(at.getX() & ~15, at.getY(), at.getZ() & ~15);
        at = new BlockPos(at.getX(), LiveCampSink.surface(level, at.getX(), at.getZ()) + 2, at.getZ());
        h.assertTrue(CampLife.outsideSect(level, at, 400), "fixture must be outside sect exclusion");
        Yard yard = new Yard(level, CampTestFixtures.fresh(h, at.asLong(), at, seed), new ArrayList<>(),
                TicketType.create("murim_camp_life_fixture", java.util.Comparator.comparingLong(ChunkPos::toLong)));
        YARDS.put(batch, yard);
        yard.loadDependencies();
        yard.force(true);
        floor(level, at, YARD_RADIUS);
        if (populate) BanditCamps.populate(level, yard.camp);
        return yard;
    }

    @GameTest(template = "small_floor", batch = "bandit_life_save", timeoutTicks = 40)
    public static void authoredPostsAndRosterSaveIndependently(GameTestHelper h) {
        var data = new BanditCampData();
        var camp = data.getOrCreate(1036, BlockPos.ZERO, 1036);
        camp.templatePosts = List.of(new CampLayout.Post(0, 0, false, false), new CampLayout.Post(4, 0, false, true));
        camp.templateRoster = List.of(new CampRoster.Member(0, false, true, true, 1));
        camp.templateFeet = List.of(new BlockPos(0, 5, 0), new BlockPos(4, 8, 0));
        camp.travellers.add(0); camp.tripTarget = new BlockPos(240, 5, 0); camp.nextCamp = 144000;
        var saved = data.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess());
        var loaded = BanditCampData.load(saved, h.getLevel().registryAccess()).get(1036);
        h.assertTrue(loaded.templatePosts.equals(camp.templatePosts) && loaded.templateRoster.equals(camp.templateRoster)
                && loaded.templateFeet.equals(camp.templateFeet), "authored post/roster/feet changed in saved data");
        h.assertTrue(loaded.travellers.equals(camp.travellers) && loaded.tripTarget.equals(camp.tripTarget)
                && loaded.nextCamp == camp.nextCamp, "lifecycle state did not survive save/load");
        h.succeed();
    }

    private static final int STARTUP_TICKS = 1200;
    private static final int MAX_PATROL_TICKS = 5000;

    /** Manhattan distance bounds the rounded octagon's ground paths, including both post legs.
     * API: minecraft-src/net/minecraft/world/entity/ai/control/MoveControl.java#tick
     * multiplies MOVEMENT_SPEED by the navigation speed modifier.
     * Allow twice that travel time for acceleration, turns and replanning, plus all nine pauses. */
    private static int patrolBudget(Bandit b, BanditCampData.Camp camp) {
        BlockPos previous = b.post();
        int distance = 0;
        int radius = 16 + (int) Math.floorMod(camp.seed, 9L);
        for (int step = 0; step <= 8; step++) {
            double angle = (step + b.campSlot() * 4) * Math.PI / 4;
            BlockPos next = step == 8 ? b.post() : camp.centre.offset(
                    (int) Math.round(Math.cos(angle) * radius), 0, (int) Math.round(Math.sin(angle) * radius));
            distance += Math.abs(next.getX() - previous.getX()) + Math.abs(next.getZ() - previous.getZ());
            previous = next;
        }
        return 20 + 9 * CampIdleGoal.PATROL_PAUSE + (int) Math.ceil(
                2 * distance / (b.getAttributeValue(Attributes.MOVEMENT_SPEED) * CampIdleGoal.WALK_SPEED));
    }

    @GameTest(template = "small_floor", batch = "bandit_life_day", timeoutTicks = STARTUP_TICKS + MAX_PATROL_TICKS)
    public static void patrolLeavesPostAndReturns(GameTestHelper h) {
        // Fixed seed fixes the posts, roster and full perimeter route; each actor also gets its own stream.
        Yard y = yard(h, "bandit_life_day", 1031L, false);
        List<Bandit> members = new ArrayList<>(), patrol = new ArrayList<>();
        boolean[] out = new boolean[2], back = new boolean[2];
        long[] start = {-1};
        int[] budget = {0};
        // API: minecraft-src/net/minecraft/gametest/framework/{GameTestHelper,GameTestSequence}.java
        // runAtTickTime/startSequence/thenWaitUntil separate readiness from the timed schedule.
        h.runAtTickTime(STARTUP_TICKS, () -> h.assertTrue(start[0] >= 0,
                "patrol yard startup exceeded budget: " + y.readiness(members)));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(y.ticking(), "patrol yard not entity ticking yet"))
                .thenExecute(() -> members.addAll(CampTestFixtures.populatePaused(h, y.camp)))
                .thenWaitUntil(() -> h.assertTrue(members.stream().allMatch(b -> b.tickCount > 0 && b.onGround()),
                        "patrol roster has not ticked and settled yet"))
                .thenExecute(() -> {
                    patrol.addAll(members.stream().filter(b -> CampLife.walkers(y.camp).contains(b.campSlot()))
                            .sorted(java.util.Comparator.comparingInt(Bandit::campSlot)).limit(2).toList());
                    h.assertTrue(patrol.size() == 2, "two ground patrol members required");
                    budget[0] = patrol.stream().mapToInt(b -> patrolBudget(b, y.camp)).max().orElseThrow();
                    h.assertTrue(budget[0] <= MAX_PATROL_TICKS, "derived route budget exceeds test allowance");
                    y.level.setDayTime(3000);
                    CampTestFixtures.resume(h, y.camp, members);
                    start[0] = y.level.getGameTime();
                    h.runAtTickTime(h.getTick() + budget[0], () ->
                            h.assertTrue(back[0] && back[1], "patrol exceeded derived walking budget"));
                }).thenWaitUntil(() -> {
            for (int i = 0; i < patrol.size(); i++) {
                Bandit b = patrol.get(i);
                double d = CampLife.horizontal(b.blockPosition(), b.post());
                out[i] |= d > 36;
                back[i] |= out[i] && d < 9;
            }
            h.assertTrue(out[0] && out[1] && back[0] && back[1], "both patrols must leave the old shuffle box and return: "
                    + java.util.stream.IntStream.range(0, patrol.size()).mapToObj(i -> "slot=" + patrol.get(i).campSlot()
                    + " out=" + out[i] + " back=" + back[i] + " distanceSquared="
                    + CampLife.horizontal(patrol.get(i).blockPosition(), patrol.get(i).post()))
                    .collect(java.util.stream.Collectors.joining("; ")));
            h.assertTrue(y.level.getGameTime() - start[0] <= budget[0], "patrol exceeded derived walking budget");
            Bandit chief = y.members().stream().filter(Bandit::isChief).findFirst().orElseThrow();
            h.assertTrue(CampLife.horizontal(chief.blockPosition(), chief.post()) < 9, "chief left his tent");
        }).thenExecute(y::finish).thenSucceed();
    }

    /** Focused goal regression: a temporarily blocked home must not advance to the next lap.
     * The walking acceptance test above still exercises real movement; here only arrivals are staged.
     * API: minecraft-src/net/minecraft/world/entity/Entity.java#moveTo/setOnGround;
     * world/entity/ai/navigation/PathNavigation.java#getPath; world/level/pathfinder/Path.java#getTarget. */
    @GameTest(template = "small_floor", batch = "bandit_life_blocked_return", timeoutTicks = STARTUP_TICKS + 40)
    public static void patrolRetriesBlockedHome(GameTestHelper h) {
        Yard y = yard(h, "bandit_life_blocked_return", 1031L, false);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(y.ticking(), "blocked-return yard not entity ticking yet"))
                .thenExecute(() -> {
                    List<Bandit> members = CampTestFixtures.populatePaused(h, y.camp);
                    y.level.setDayTime(3000);
                    Bandit b = members.stream().filter(m -> CampLife.walkers(y.camp).contains(m.campSlot()))
                            .min(java.util.Comparator.comparingInt(Bandit::campSlot)).orElseThrow();
                    CampIdleGoal goal = new CampIdleGoal(b);
                    goal.start();
                    int radius = 16 + (int) Math.floorMod(y.camp.seed, 9L);
                    for (int step = 0; step < 8; step++) {
                        // Advance the goal's pause, then stage arrival at the requested perimeter point.
                        for (int tick = 0; tick < 120; tick++) goal.tick();
                        double angle = (step + b.campSlot() * 4) * Math.PI / 4;
                        BlockPos point = y.camp.centre.offset((int) Math.round(Math.cos(angle) * radius), 0,
                                (int) Math.round(Math.sin(angle) * radius));
                        b.moveTo(point.getX() + .5, point.getY(), point.getZ() + .5);
                        b.setOnGround(true);
                        goal.tick();
                    }
                    BlockPos home = b.post();
                    b.moveTo(home.getX() + 5.5, home.getY(), home.getZ() + .5);
                    b.setOnGround(true);
                    y.level.setBlock(home, Blocks.STONE.defaultBlockState(), 3);
                    for (int tick = 0; tick < 31; tick++) goal.tick();
                    h.assertTrue(b.getNavigation().isDone(), "blocked home should reject the path");
                    y.level.setBlock(home, Blocks.AIR.defaultBlockState(), 3);
                    for (int tick = 0; tick < 41; tick++) goal.tick();
                    var path = b.getNavigation().getPath();
                    h.assertTrue(path != null && path.canReach() && path.getTarget().equals(home),
                            "patrol skipped its blocked home instead of retrying after clearance");
                    goal.stop();
                    y.finish();
                }).thenSucceed();
    }

    @GameTest(template = "small_floor", batch = "bandit_life_night", timeoutTicks = STARTUP_TICKS + 400)
    public static void oneTorchSentryWhileMostRestAndWakeOnAlert(GameTestHelper h) {
        Yard y = yard(h, "bandit_life_night", 1032L, false);
        List<Bandit> members = new ArrayList<>();
        boolean[] started = {false};
        h.runAtTickTime(STARTUP_TICKS, () -> h.assertTrue(started[0],
                "night yard startup exceeded budget: " + y.readiness(members)));
        h.startSequence()
                .thenWaitUntil(() -> h.assertTrue(y.ticking(), "night yard not entity ticking yet"))
                .thenExecute(() -> members.addAll(CampTestFixtures.populatePaused(h, y.camp)))
                .thenWaitUntil(() -> h.assertTrue(members.stream().allMatch(b -> b.tickCount > 0 && b.onGround()),
                        "night roster has not ticked and settled yet"))
                .thenExecute(() -> {
                    y.level.setDayTime(15000);
                    CampTestFixtures.resume(h, y.camp, members);
                    started[0] = true;
                    h.runAtTickTime(h.getTick() + 400, () -> h.fail("night schedule exceeded settling budget"));
                }).thenWaitUntil(() -> {
            h.assertTrue(members.stream().filter(b -> b.getItemBySlot(EquipmentSlot.OFFHAND).is(Items.TORCH)).count() == 1,
                    "exactly one torch sentry required");
            h.assertTrue(members.stream().filter(b -> b.campPose() != 0).count() > members.size() / 2, "majority did not rest");
        }).thenExecute(() -> {
            var intruder = h.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
            members.get(0).setTarget(intruder);
            h.assertTrue(members.stream().allMatch(b -> b.campPose() == 0), "alarm did not wake sleepers");
        }).thenExecute(y::finish).thenSucceed();
    }

    @GameTest(template = "small_floor", batch = "bandit_life_roster", timeoutTicks = 500)
    public static void unloadedTravellersRestoreOnceAndRosterStaysBounded(GameTestHelper h) {
        Yard y = yard(h, "bandit_life_roster", 1033L);
        h.startSequence().thenWaitUntil(() -> h.assertTrue(y.ticking(), "home chunks not ticking"))
                .thenExecute(() -> {
                    int cap = y.camp.alive.size();
                    y.level.setDayTime(3000);
                    BlockPos rendezvous = y.camp.centre.offset(240, 0, 0);
                    y.extraCamps.add(rendezvous.asLong());
                    var destination = CampTestFixtures.fresh(h, rendezvous.asLong(), rendezvous, 1035);
                    y.camp.nextTrip = y.level.getDayTime();
                    CampLife.tick(y.level, y.camp);
                    h.assertTrue(y.camp.travellers.size() >= 2 && y.camp.travellers.size() <= 3, "no bounded travelling party departed");
                    h.assertTrue(y.camp.nextTrip - y.level.getDayTime() >= 2 * CampLife.DAY
                            && y.camp.nextTrip - y.level.getDayTime() <= 3 * CampLife.DAY, "wrong travelling cadence");
                    destination.cleared = true; destination.successor = destination.key;
                    long savedTime = y.level.getDayTime();
                    for (int day = 0; day < 8; day++) {
                        List<Bandit> party = y.members().stream().filter(b -> !b.isChief() && !b.holdsPost()).limit(2).toList();
                        for (Bandit b : party) {
                            y.camp.travellers.add(b.campSlot());
                            UUID old = b.getUUID();
                            // Same transfer used by EntityLeaveLevelEvent, then simulate the entity leaving storage.
                            BanditCamps.returnUnloaded(y.level, b);
                            b.discard();
                            h.assertTrue(!y.camp.alive.containsValue(old), "unloaded UUID still owns the slot");
                        }
                        BanditCamps.restoreTravellers(y.level, y.camp);
                        BanditCamps.restoreTravellers(y.level, y.camp);
                        BanditCamps.populate(y.level, y.camp);
                        // Dawn refill must work with no player in the old 28–64 block window.
                        y.members().stream().filter(b -> !b.isChief()).findFirst().orElseThrow().discard();
                        y.level.setDayTime((Math.floorDiv(savedTime, CampLife.DAY) + day + 1) * CampLife.DAY + 10);
                        BanditCamps.tick(y.level, y.camp);
                        h.assertTrue(y.camp.returning.isEmpty() && y.camp.alive.size() == cap && y.members().size() == cap,
                                "population leaked during simulated day " + day);
                        Bandit stale = ModEntities.BANDIT_SWORDSMAN.get().create(y.level);
                        h.assertTrue(stale != null, "no stale entity fixture");
                        stale.joinCamp(y.camp.key, party.get(0).campSlot(), y.camp.centre, false);
                        h.assertTrue(!y.level.addFreshEntity(stale), "late chunk traveller duplicated a restored slot");
                    }
                    y.level.setDayTime(savedTime);
                    y.finish();
                }).thenSucceed();
    }

    @GameTest(template = "small_floor", batch = "bandit_life_rebuild", timeoutTicks = 500)
    public static void ruinWaitsFiveToSevenDaysThenMovesOnce(GameTestHelper h) {
        Yard y = yard(h, "bandit_life_rebuild", 1034L);
        BlockPos fire = y.camp.centre;
        y.level.setBlock(fire, Blocks.CAMPFIRE.defaultBlockState(), 3);
        y.members().forEach(Bandit::discard);
        long now = y.level.getDayTime();
        BanditCamps.clear(y.level, y.camp);
        h.assertTrue(!y.level.getBlockState(fire).getValue(CampfireBlock.LIT), "ruin fire is still lit");
        h.assertTrue(y.camp.nextCamp - now >= 5 * CampLife.DAY && y.camp.nextCamp - now <= 7 * CampLife.DAY, "wrong rebuilding delay");
        CampLife.tick(y.level, y.camp);
        h.assertTrue(y.camp.successor == Long.MIN_VALUE, "camp rebuilt before delay");
        // Advance the stored deadline rather than changing the global server clock by seven days.
        y.camp.nextCamp = now;
        long seed = y.camp.seed + now + y.level.getGameTime() / 1200L;
        long hash = CampLayout.hash(seed, 0, 43);
        double angle = (hash % 360L) * Math.PI / 180;
        int distance = 300 + (int) (hash / 360L % 501L);
        BlockPos next = y.camp.centre.offset((int) Math.round(Math.cos(angle) * distance), 0, (int) Math.round(Math.sin(angle) * distance));
        floor(y.level, next, 40);
        CampLife.tick(y.level, y.camp);
        var fresh = BanditCamps.data(y.level).get(y.camp.successor);
        h.assertTrue(fresh != null && !fresh.cleared && fresh.key != y.camp.key, "no replacement camp");
        double d = CampLife.horizontal(fresh.centre, y.camp.centre);
        h.assertTrue(d >= 300D * 300D && d <= 800D * 800D, "replacement outside relocation range");
        h.assertTrue(CampLife.outsideSect(y.level, fresh.centre, 300), "replacement entered sect exclusion");
        CampLife.tick(y.level, y.camp);
        h.assertTrue(y.camp.successor == fresh.key, "second replacement generated");
        y.extraCamps.add(fresh.key);
        y.finish();
        h.succeed();
    }
}
