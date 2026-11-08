package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/** The wandering disciple (docs/adr/0020): appears for a new player, not for a member, points at the mountain, never twice. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WandererGameTests {
    private WandererGameTests() {
    }

    private static final String BATCH = "wanderer";

    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Wanderer.data(h.getLevel()).byPlayer.remove(p.getUUID());
        Vec3 at = h.absoluteVec(new Vec3(3.5, 2, 3.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.tickCount = 200;
        p.getRandom().setSeed(1101L);
        return p;
    }

    /** A mountain 3 km away from the player, in the given direction (degrees, 0 = east). */
    private static MountHuaSite mountain(ServerPlayer p, double degrees, double distance) {
        int x = (int) Math.round(p.getX() + Math.cos(Math.toRadians(degrees)) * distance);
        int z = (int) Math.round(p.getZ() + Math.sin(Math.toRadians(degrees)) * distance);
        return new MountHuaSite(x, z, 64, 0, 1L);
    }

    private static void reset(ServerLevel level) {
        // Clearing ownership alone leaves failed fixtures' actors in later tests' proximity queries.
        // Collect first: discarding updates the level's entity storage.
        var stale = new java.util.ArrayList<SectDisciple>();
        for (var entity : level.getAllEntities())
            if (entity instanceof SectDisciple disciple && disciple.wanderer()) stale.add(disciple);
        stale.forEach(SectDisciple::discard);
        var data = Wanderer.data(level);
        data.byPlayer.clear();
        data.setDirty();
        Wanderer.siteOverride = null;
        Wanderer.skipTickingCheck = false;
    }

    /**
     * The chunks around a fresh test player may not tick yet (the real service retries every two seconds): try again every 10
     * ticks, up to 15 times, then hand the wanderer over.
     */
    private static void awaitWanderer(GameTestHelper h, ServerPlayer p, MountHuaSite site, int attempt,
                                      java.util.function.Consumer<SectDisciple> then) {
        SectDisciple d = Wanderer.step(h.getLevel(), p, site);
        if (d != null) {
            then.accept(d);
        } else if (attempt >= 15) {
            reset(h.getLevel());
            h.fail("no wanderer for a new non-member after " + attempt + " tries");
        } else {
            h.runAfterDelay(10, () -> awaitWanderer(h, p, site, attempt + 1, then));
        }
    }

    private static List<SectDisciple> wanderers(ServerLevel level, Vec3 around) {
        return level.getEntitiesOfClass(SectDisciple.class, new net.minecraft.world.phys.AABB(around, around).inflate(300.0D),
                SectDisciple::wanderer);
    }

    @GameTest(template = "small_floor", batch = BATCH + "unseen_departure")
    public static void unseenDeparturePreservesVisitAllowance(GameTestHelper h) {
        ServerPlayer p = player(h);
        ServerLevel level = h.getLevel();
        reset(level);
        MountHuaSite site = mountain(p, 0, 3000);
        SectDisciple d = Wanderer.create(level, p.blockPosition().offset(30, 0, 0), site);
        try {
            Wanderer.Entry entry = new Wanderer.Entry();
            entry.entity = d.getUUID();
            entry.expiresAt = level.getGameTime();
            Wanderer.data(level).byPlayer.put(p.getUUID(), entry);
            h.assertTrue(level.addFreshEntity(d), "could not add unseen visitor");
            Wanderer.step(level, p, site);
            h.assertTrue(entry.entity == null && entry.visits == 0 && Wanderer.mayApproach(p),
                    "unseen departure consumed a visit");
        } finally {
            d.discard();
            reset(level);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = BATCH + "notice")
    public static void unseenVisitsDoNotCountAndNoticingCountsOnce(GameTestHelper h) {
        ServerPlayer p = player(h);
        ServerLevel level = h.getLevel();
        reset(level);
        SectDisciple d = Wanderer.create(level, p.blockPosition().offset(20, 0, 0), mountain(p, 0, 3000));
        try {
            Wanderer.Entry entry = new Wanderer.Entry();
            entry.entity = d.getUUID();
            Wanderer.data(level).byPlayer.put(p.getUUID(), entry);
            Wanderer.observe(p, d, 100);
            Wanderer.observe(p, d, 140);
            h.assertTrue(entry.visits == 0 && p.getPersistentData().getInt("murim_wander_approaches") == 0,
                    "an unseen wanderer used a visit");
            // sect_yard is 24 x 8 x 24 with an unobstructed floor. small_floor's
            // barrier walls cut the 12-block eye ray even when the yaw is correct.
            // API: Minecraft 1.21.1 LivingEntity#hasLineOfSight clips between entity eyes.
            // Looking east at him for less than a second is insufficient; a full second counts.
            d.moveTo(p.getX() + 12, p.getY(), p.getZ(), 0, 0);
            p.moveTo(p.getX(), p.getY(), p.getZ(), -90, 0);
            h.assertTrue(d.distanceToSqr(p) > 36 && d.distanceToSqr(p) <= 256,
                    "look fixture must be outside proximity range and within gaze range");
            h.assertTrue(p.hasLineOfSight(d), "fixture blocks the sustained look");
            h.assertTrue(p.getLookAngle().dot(d.getEyePosition().subtract(p.getEyePosition()).normalize()) >= 0.97,
                    "fixture player is not looking at the wanderer");
            Wanderer.observe(p, d, 141);
            Wanderer.observe(p, d, 151);
            h.assertTrue(entry.visits == 0, "a fleeting glance used a visit");
            Wanderer.observe(p, d, 161);
            h.assertTrue(entry.visits == 1, "a sustained look did not count");
            d.moveTo(p.getX() + 5, p.getY(), p.getZ(), 0, 0);
            Wanderer.observe(p, d, 171);
            Wanderer.noticed(p, d);
            h.assertTrue(entry.visits == 1 && p.getPersistentData().getInt("murim_wander_approaches") == 1,
                    "noticing must count exactly once");
        } finally {
            d.discard();
            reset(level);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = BATCH + "merchant_map")
    public static void merchantMapForMembersAndOutsidersAndKnownDistance(GameTestHelper h) {
        ServerPlayer p = player(h);
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.siteOverride = mountain(p, 0, 3000);
        try {
            var merchant = new io.github.verycooltimo.murim.trade.Peddler(
                    io.github.verycooltimo.murim.trade.ModTrade.PEDDLER.get(), level);
            int[] gate = SectService.zone(Wanderer.siteOverride, "sect_gate");
            p.moveTo(gate[0] - 1000, p.getY(), gate[2] + 1000, 0, 0);
            merchant.updateMapOffer(p);
            var maps = merchant.getOffers().stream().filter(o -> o.getResult().is(net.minecraft.world.item.Items.FILLED_MAP)).toList();
            h.assertTrue(maps.size() == 1, "merchant must always offer a map to outsiders");
            var map = maps.getFirst().getResult();
            var saved = net.minecraft.world.item.MapItem.getSavedData(map, level);
            var decorations = map.get(net.minecraft.core.component.DataComponents.MAP_DECORATIONS);
            h.assertTrue(decorations != null && decorations.decorations().values().stream().anyMatch(target ->
                    target.type().equals(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.TARGET_X)
                            && Math.abs(target.x() - gate[0]) <= 8 && Math.abs(target.z() - gate[2]) <= 8),
                    "merchant map has no gate marker");
            h.assertTrue(saved != null && Math.abs(gate[0] - saved.centerX) <= 256
                    && Math.abs(gate[2] - saved.centerZ) <= 256, "gate outside merchant map");
            var lore = map.get(net.minecraft.core.component.DataComponents.LORE);
            h.assertTrue(lore != null && lore.lines().equals(List.of(net.minecraft.network.chat.Component.translatable(
                    "murim.hua.map.distance", 1400L, net.minecraft.network.chat.Component.translatable("murim.hua.direction.north_east")))),
                    "wrong distance/direction in map lore");
            h.assertTrue(Wanderer.distanceBlocks(0, 0, 1000, -1000) == 1400
                    && Wanderer.direction(0, 0, 1000, -1000).equals("north_east"), "wrong known distance/direction");
            SectService.join(p);
            merchant.updateMapOffer(p);
            var memberMaps = merchant.getOffers().stream()
                    .filter(o -> o.getResult().is(net.minecraft.world.item.Items.FILLED_MAP)).toList();
            h.assertTrue(memberMaps.size() == 1, "member cannot buy replacement map");
            h.assertTrue(memberMaps.getFirst().satisfiedBy(new net.minecraft.world.item.ItemStack(
                    io.github.verycooltimo.murim.registry.ModItems.SILVER_TAEL.get(), 2), net.minecraft.world.item.ItemStack.EMPTY),
                    "member map price changed");
            h.assertFalse(Wanderer.needsDirections(p), "member received unsolicited approach");
        } finally {
            reset(level);
        }
        h.succeed();
    }

    /** The first wanderer waits for 5 to 7 minutes of play in the world (author 24.10), not the first seconds at the spawn. */
    @GameTest(template = "small_floor", batch = BATCH + "0")
    public static void firstWandererWaitsFiveToSevenMinutes(GameTestHelper h) {
        ServerPlayer p = player(h);
        var playTime = net.minecraft.stats.Stats.CUSTOM.get(net.minecraft.stats.Stats.PLAY_TIME);
        int at = Wanderer.firstMeetAt(p);
        h.assertTrue(at >= 6000 && at <= 8400, "first meeting outside 5 to 7 minutes: " + at);
        p.getStats().setValue(p, playTime, 200);
        h.assertFalse(Wanderer.firstMeetingDue(p), "the wanderer may come in the first seconds of play");
        p.getStats().setValue(p, playTime, at - 1);
        h.assertFalse(Wanderer.firstMeetingDue(p), "the wanderer may come before his time");
        p.getStats().setValue(p, playTime, at);
        h.assertTrue(Wanderer.firstMeetingDue(p), "the wanderer does not come after 7 minutes of play");
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 1500, batch = BATCH + "1")
    public static void newPlayerMeetsOneWandererNearby(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        var fixture = new EntryTestFixtures(h, BATCH + "1");
        // Cover the entire random 22..46-block spawn ring before adding any entities.
        fixture.force(h.absolutePos(new BlockPos(-48, 2, -48)), h.absolutePos(new BlockPos(52, 2, 52)));
        fixture.whenReady(() -> {
            ServerPlayer p = player(h);
            MountHuaSite site = mountain(p, 30, 3000);
            SectDisciple d = Wanderer.step(level, p, site, net.minecraft.util.RandomSource.create(1101L));
            h.assertTrue(d != null, "no wanderer in the ready spawn ring");
            fixture.track(d);
            double dist = d.distanceTo(p);
            h.assertTrue(dist >= 15 && dist <= 60, "wanderer is " + dist + " blocks from the new player");
            h.assertTrue(d.wanderer() && d.member().isEmpty(), "he must not be a sect roster member");
            h.assertTrue(Wanderer.step(level, p, site) == null, "a second step made a second wanderer");
            h.assertTrue(wanderers(level, p.position()).size() == 1, "expected exactly one visible wanderer");
            d.discard();
            h.assertTrue(Wanderer.step(level, p, site) == null, "an absent wanderer was replaced before his stay ended");
            var loaded = Wanderer.Data.load(Wanderer.data(level).save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(loaded.byPlayer.get(p.getUUID()) != null && d.getUUID().equals(loaded.byPlayer.get(p.getUUID()).entity),
                    "entry lost on save");
            h.succeed();
        });
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH + "1")
    public static void cleanupFirstMeeting(ServerLevel level) {
        EntryTestFixtures.closeBatch(BATCH + "1");
        reset(level);
    }

    /** Near the mountain's foot come up to three recruiters with their own names, one at a time (author 22.10: not a long road). */
    @GameTest(template = "small_floor", timeoutTicks = 400, batch = BATCH + "1r")
    public static void recruitersWaitNearTheFootOfTheMountain(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        awaitWanderer(h, p, mountain(p, 30, 3000), 0, first -> {
            try {
                h.assertTrue(first.wandererIndex() == 0, "the first wanderer is Seo Han");
                // He is left behind (a real leave needs a loaded chunk 160 blocks off): drop him as the service does.
                Wanderer.data(level).byPlayer.get(p.getUUID()).entity = null;
                first.discard();
                h.assertTrue(Wanderer.step(level, p, mountain(p, 30, 1000)) == null, "no recruiter outside the 900-block mist");
                awaitWanderer(h, p, mountain(p, 30, 850), 0, second -> {
                    try {
                        h.assertTrue(second.wandererIndex() == 1 && second.memberKey().equals("mok_rin"), "recruiter " + second.memberKey());
                        h.assertTrue(second.dialogue().getPath().equals("mok_rin"), "recruiter has his own dialogue file");
                        h.assertTrue(Wanderer.step(level, p, mountain(p, 30, 600)) == null, "two at once");
                        h.assertTrue(Wanderer.data(level).byPlayer.get(p.getUUID()).extra == 1, "extra count");
                    } finally {
                        reset(level);
                    }
                    h.succeed();
                });
            } catch (RuntimeException ex) {
                reset(level);
                throw ex;
            }
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 60, batch = BATCH + "2")
    public static void membersAndPlayersNearTheMountainGetNone(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        try {
            h.assertTrue(Wanderer.step(level, p, mountain(p, 90, 900)) == null, "wanderer inside the mist range");
            p.setData(ModAttachments.SECT, SectService.state(p).joined());
            h.assertTrue(Wanderer.step(level, p, mountain(p, 90, 3000)) == null, "wanderer for a sect member");
            h.assertTrue(wanderers(level, p.position()).isEmpty(), "a wanderer exists");
        } finally {
            reset(level);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 300, batch = BATCH + "3")
    public static void leftBehindWandererLeavesAndComesBackLater(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        MountHuaSite site = mountain(p, 200, 3500);
        awaitWanderer(h, p, site, 0, d -> {
            try {
                Vec3 home = p.position();
                p.moveTo(home.x + 400, home.y, home.z, 0, 0);
                Wanderer.step(level, p, site);
                h.assertTrue(!d.isAlive() || d.isRemoved(), "he did not leave a player who walked off");
                var e = Wanderer.data(level).byPlayer.get(p.getUUID());
                h.assertTrue(e.entity == null && e.nextAt > level.getGameTime(), "no cool-down after he left");
            } finally {
                reset(level);
            }
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 80, batch = BATCH + "4")
    public static void pointingFacesTheMountainFromSeveralSpotsAndRotations(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        double[][] cases = {{0, 90, 3000, 0.5, 0.5}, {90, 250, 2800, 2.5, 3.5}, {-135, 10, 4100, 4.5, 1.5},
                {179, -75, 3300, 1.5, 4.5}, {45, 180, 2700, 4.5, 4.5}, {-30, 135, 4800, 3.5, 2.5}};
        List<SectDisciple> npcs = new java.util.ArrayList<>();
        List<Float> want = new java.util.ArrayList<>();
        var data = Wanderer.data(level);
        for (double[] c : cases) {
            Vec3 at = h.absoluteVec(new Vec3(c[3], 2, c[4]));
            MountHuaSite site = mountain(p, c[1], c[2]);
            SectDisciple d = Wanderer.create(level, BlockPos.containing(at), site);
            d.moveTo(at.x, at.y, at.z, (float) c[0], 0);
            d.setYBodyRot((float) c[0]);
            UUID owner = UUID.randomUUID();
            var entry = new Wanderer.Entry();
            entry.entity = d.getUUID();
            data.byPlayer.put(owner, entry);
            h.assertTrue(level.addFreshEntity(d), "could not add the wanderer");
            Wanderer.point(d, site);
            npcs.add(d);
            want.add(Wanderer.yawToMountain(d.getX(), d.getZ(), site));
        }
        h.runAfterDelay(20, () -> {
            try {
                for (int i = 0; i < npcs.size(); i++) {
                    SectDisciple d = npcs.get(i);
                    float diff = Mth.degreesDifferenceAbs(d.yBodyRot, want.get(i));
                    h.assertTrue(diff <= 3.0F, "case " + i + ": body yaw " + d.yBodyRot + " but the mountain is at " + want.get(i));
                    h.assertTrue(d.pointing() && "point_far".equals(d.gesture()), "case " + i + ": not pointing");
                    d.discard();
                }
            } finally {
                reset(level);
            }
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 400, batch = BATCH + "5")
    public static void askingAgainPointsAgainFromWhereHeStandsNow(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        // A nearby synthetic mountain makes the direction change measurable on the test floor.
        MountHuaSite site = mountain(p, 60, 40);
        Wanderer.siteOverride = site;
        asksAgain(h, level, p, site, placed(h, site, 4.5, 4.5));
    }

    private static void asksAgain(GameTestHelper h, ServerLevel level, ServerPlayer p, MountHuaSite site, SectDisciple d) {
        // API: minecraft-src/net/minecraft/server/level/ServerLevel.java#addNewPlayer/removePlayerImmediately.
        // The conversation spans the NPC watchdog tick, which resolves its talker from the level.
        level.addNewPlayer(p);
        d.setKeepAwake(true);
        Vec3 near = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        d.moveTo(near.x, near.y, near.z, 0, 0);
        h.assertTrue(DialogueService.route(p, d) != null, "no route for the wanderer's dialogue");
        DialogueService.open(p, d);
        h.assertTrue("greet".equals(p.getData(ModAttachments.DIALOGUE).node()), "opened at " + p.getData(ModAttachments.DIALOGUE).node());
        assertDirectionQuestionFirst(h, p);
        DialogueService.choose(p, 0); // Where is Mount Hua?
        h.assertTrue("point".equals(p.getData(ModAttachments.DIALOGUE).node()), "no point node");
        h.assertTrue(d.pointing(), "he does not point after the question");
        float first = Wanderer.yawToMountain(d.getX(), d.getZ(), site);
        h.runAfterDelay(14, () -> {
            h.assertTrue(Mth.degreesDifferenceAbs(d.yBodyRot, first) <= 3.0F, "first: body " + d.yBodyRot + " vs " + first);
            // He is moved elsewhere, then asked again: the direction is recomputed from the new spot.
            Vec3 far = h.absoluteVec(new Vec3(0.5, 2, 0.5));
            d.moveTo(far.x, far.y, far.z, 0, 0);
            p.moveTo(d.getX() + 2, d.getY(), d.getZ(), 0, 0);
            assertDirectionQuestionFirst(h, p);
            DialogueService.choose(p, 0); // Show me again.
            h.assertTrue(d.pointing(), "repeat question did not point");
            float second = Wanderer.yawToMountain(d.getX(), d.getZ(), site);
            h.assertTrue(Math.abs(Mth.degreesDifferenceAbs(first, second)) > 0.5F, "the direction should differ from the new spot");
            h.runAfterDelay(14, () -> {
                try {
                    h.assertTrue(Mth.degreesDifferenceAbs(d.yBodyRot, second) <= 3.0F, "again: body " + d.yBodyRot + " vs " + second);
                    DialogueService.choose(p, -1);
                    d.discard();
                } finally {
                    level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                    reset(level);
                }
                h.succeed();
            });
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 300, batch = BATCH + "6")
    public static void aMemberOnlyGetsAGreeting(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        MountHuaSite site = mountain(p, 60, 3200);
        awaitWanderer(h, p, site, 0, d -> {
            p.setData(ModAttachments.SECT, SectService.state(p).joined());
            h.assertFalse(SectSocial.matches(p, d, "road_guidance"), "members still get a direction question");
            DialogueService.open(p, d);
            h.assertTrue("member".equals(p.getData(ModAttachments.DIALOGUE).node()), "member node expected, got " + p.getData(ModAttachments.DIALOGUE).node());
            h.assertFalse(d.pointing(), "pointing for a member");
            DialogueService.choose(p, -1);
            d.discard();
            reset(level);
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "map")
    public static void pointingGivesOneVanillaMapAcrossMeetingsAndReloads(GameTestHelper h) {
        pointingGivesOneVanillaMapAcrossMeetingsAndReloads(h, 3000);
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "map_near")
    public static void pointingNearTheMountainStillGivesAVanillaMap(GameTestHelper h) {
        pointingGivesOneVanillaMapAcrossMeetingsAndReloads(h, 300);
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "map_border_nw")
    public static void pointingKeepsTargetVisibleAtNegativeXPositiveZMapBorders(GameTestHelper h) {
        pointingGivesOneVanillaMapAcrossMeetingsAndReloads(h, 300, 448, 447);
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "map_border_se")
    public static void pointingKeepsTargetVisibleAtPositiveXNegativeZMapBorders(GameTestHelper h) {
        pointingGivesOneVanillaMapAcrossMeetingsAndReloads(h, 300, 447, 448);
    }

    private static void pointingGivesOneVanillaMapAcrossMeetingsAndReloads(GameTestHelper h, double distance) {
        pointingGivesOneVanillaMapAcrossMeetingsAndReloads(h, distance, -1, -1);
    }

    private static void pointingGivesOneVanillaMapAcrossMeetingsAndReloads(GameTestHelper h, double distance,
                                                                         int gateGridX, int gateGridZ) {
        ServerLevel level = h.getLevel();
        reset(level);
        ServerPlayer p = player(h);
        p.getInventory().clearContent();
        MountHuaSite site = mountain(p, 60, distance);
        if (gateGridX >= 0) {
            // Scale 2 centres are 512*n + 192: residues 448/447 put the gate at -256/+255,
            // outside the drawable +/-252-block range on both axes, regardless of test placement.
            int[] initialGate = SectService.zone(site, "sect_gate");
            int dx = Math.floorMod(gateGridX - initialGate[0] + 256, 512) - 256;
            int dz = Math.floorMod(gateGridZ - initialGate[2] + 256, 512) - 256;
            site = new MountHuaSite(site.centerX() + dx, site.centerZ() + dz, 64, 0, 1L);
        }
        Wanderer.siteOverride = site;
        SectDisciple d = placed(h, site, 2.5, 2.5);
        try {
            DialogueService.open(p, d);
            assertDirectionQuestionFirst(h, p);
            DialogueService.choose(p, 0);
            h.assertTrue(d.pointing(), "the pointing step did not run");
            var maps = p.getInventory().items.stream().filter(s -> s.is(net.minecraft.world.item.Items.FILLED_MAP)).toList();
            h.assertTrue(maps.size() == 1 && maps.getFirst().getCount() == 1, "expected exactly one filled map");
            var map = maps.getFirst();
            var decorations = map.get(net.minecraft.core.component.DataComponents.MAP_DECORATIONS);
            int[] gate = SectService.zone(site, "sect_gate");
            h.assertTrue(decorations != null && decorations.decorations().values().stream().anyMatch(target ->
                    target.type().equals(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.TARGET_X)
                            && Math.abs(target.x() - gate[0]) <= 8 && Math.abs(target.z() - gate[2]) <= 8),
                    "no vanilla target at the sect gate");
            var savedMap = net.minecraft.world.item.MapItem.getSavedData(map, level);
            h.assertTrue(savedMap != null, "no saved map data");
            h.assertTrue(savedMap.scale == 2, "expected explorer-map scale 2");
            h.assertTrue(savedMap.centerX == gate[0] && savedMap.centerZ == gate[2], "explorer map is not centred on the gate");
            // Vanilla 1.21.1 MapItemSavedData#tickCarriedBy copies MAP_DECORATIONS into saved
            // decorations. Call it directly: ServerPlayer.doTick is not an inventory-tick contract
            // for this unregistered fixture and can leave the saved decorations empty.
            savedMap.tickCarriedBy(p, map);
            boolean targetInside = false;
            for (var target : savedMap.getDecorations()) {
                if (target.type().equals(net.minecraft.world.level.saveddata.maps.MapDecorationTypes.TARGET_X)) {
                    // Decoration coordinates are half-pixels; scale 2 makes each half-pixel two blocks.
                    double x = savedMap.centerX + target.x() * 2.0D;
                    double z = savedMap.centerZ + target.y() * 2.0D;
                    targetInside |= Math.abs(x - gate[0]) <= 8 && Math.abs(z - gate[2]) <= 8;
                }
            }
            h.assertTrue(targetInside, "no visible vanilla target inside the map at the sect gate: gate="
                    + gate[0] + "," + gate[2] + " centre=" + savedMap.centerX + "," + savedMap.centerZ);
            h.assertTrue(net.minecraft.network.chat.Component.translatable("murim.wanderer.map")
                    .equals(map.get(net.minecraft.core.component.DataComponents.CUSTOM_NAME)), "wrong map name");
            DialogueService.close(p, d);
            var data = Wanderer.data(level);
            var restored = Wanderer.Data.load(data.save(new CompoundTag(), level.registryAccess()), level.registryAccess());
            h.assertTrue(restored.byPlayer.get(p.getUUID()).mapGiven, "map receipt did not survive saving");
            data.byPlayer.put(p.getUUID(), restored.byPlayer.get(p.getUUID()));
            DialogueService.open(p, d);
            assertDirectionQuestionFirst(h, p);
            DialogueService.choose(p, 0);
            Wanderer.point(p, d);
            h.assertTrue(p.getInventory().items.stream().filter(s -> s.is(net.minecraft.world.item.Items.FILLED_MAP))
                    .mapToInt(net.minecraft.world.item.ItemStack::getCount).sum() == 1, "second meeting gave another map");
        } finally {
            DialogueService.close(p, d);
            d.discard();
            reset(level);
        }
        h.succeed();
    }

    private static void assertDirectionQuestionFirst(GameTestHelper h, ServerPlayer p) {
        // The greet/point nodes both put their explicit direction question at native index zero.
        // Check the rendered mapping too: a hidden condition otherwise makes choose(0) ask something else.
        var session = p.getData(ModAttachments.DIALOGUE);
        h.assertTrue(!session.shown().isEmpty() && session.shown().getFirst() == 0,
                "direction question is hidden at " + session.node() + ": " + session.shown());
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "map_full")
    public static void aFullInventoryDropsTheMapOnlyOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        ServerPlayer p = player(h);
        for (int i = 0; i < p.getInventory().items.size(); i++) {
            p.getInventory().items.set(i, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STONE, 64));
        }
        MountHuaSite site = mountain(p, 60, 3000);
        Wanderer.siteOverride = site;
        SectDisciple d = placed(h, site, 2.5, 2.5);
        try {
            Wanderer.point(p, d);
            Wanderer.point(p, d);
            var drops = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                    p.getBoundingBox().inflate(2), e -> e.getItem().is(net.minecraft.world.item.Items.FILLED_MAP));
            h.assertTrue(drops.size() == 1 && drops.getFirst().getItem().getCount() == 1, "expected one map at the player's feet");
            drops.forEach(net.minecraft.world.entity.Entity::discard);
        } finally {
            d.discard();
            reset(level);
        }
        h.succeed();
    }

    private static SectDisciple placed(GameTestHelper h, MountHuaSite site, double x, double z) {
        ServerLevel level = h.getLevel();
        Vec3 at = h.absoluteVec(new Vec3(x, 2, z));
        SectDisciple d = Wanderer.create(level, BlockPos.containing(at), site);
        d.moveTo(at.x, at.y, at.z, 0, 0);
        var entry = new Wanderer.Entry();
        entry.entity = d.getUUID();
        Wanderer.data(level).byPlayer.put(UUID.randomUUID(), entry);
        h.assertTrue(level.addFreshEntity(d), "could not add the wanderer");
        return d;
    }

    @GameTest(template = "small_floor", timeoutTicks = 400, batch = BATCH + "7")
    public static void heSeesAStrangerWavesGreetsWalksUpAndOpensTheTalk(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        MountHuaSite site = mountain(p, 60, 3200);
        Vec3 corner = h.absoluteVec(new Vec3(0.5, 2, 0.5));
        p.moveTo(corner.x, corner.y, corner.z, 0, 0);
        level.addNewPlayer(p);
        p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100L);
        p.getPersistentData().putDouble("murim_idle_x", p.getX());
        p.getPersistentData().putDouble("murim_idle_y", p.getY());
        p.getPersistentData().putDouble("murim_idle_z", p.getZ());
        SectDisciple d = placed(h, site, 4.5, 4.5);
        d.setKeepAwake(true);
        d.setNoAi(true);
        Vec3 start = d.position();
        h.runAfterDelay(110, () -> {
            p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100L);
            p.getPersistentData().putDouble("murim_idle_x", p.getX());
            p.getPersistentData().putDouble("murim_idle_y", p.getY());
            p.getPersistentData().putDouble("murim_idle_z", p.getZ());
            d.setNoAi(false);
        });
        h.runAfterDelay(128, () -> {
            h.assertTrue(d.bubbleKey().startsWith("murim.bubble.wander_greet."), "no greeting bubble, last: '" + d.bubbleKey() + "' dist=" + d.distanceTo(p) + " los=" + d.hasLineOfSight(p)
                    + " free=" + d.free() + " pointing=" + d.pointing() + " ready=" + d.approachReadyAt() + " now=" + level.getGameTime()
                    + " players=" + level.players().size() + " inList=" + level.players().contains(p) + " alive=" + d.isAlive()
                    + " tick=" + d.tickCount + " noAi=" + d.isNoAi() + " member=" + SectService.state(p).member());
            h.assertTrue("wave".equals(d.gesture()), "he did not wave: '" + d.gesture() + "'");
        });
        h.runAfterDelay(120, () -> {
            h.assertTrue(d.position().distanceTo(start) < 0.1D, "he walked before finishing the wave");
            h.assertFalse(p.getData(ModAttachments.DIALOGUE).active(), "he opened the talk before walking up");
        });
        h.runAfterDelay(160, () -> h.assertTrue(d.position().distanceTo(start) > 0.1D,
                "he is still waiting after the 24-tick wave: npc=" + d.position() + " start=" + start));
        h.runAfterDelay(280, () -> {
            try {
                h.assertTrue(d.position().distanceTo(start) > 2.0D, "he did not walk up to the player: npc=" + d.position()
                        + " start=" + start + " player=" + p.position() + " distance=" + d.distanceTo(p)
                        + " navDone=" + d.getNavigation().isDone() + " talking=" + d.talkingTo()
                        + " bubble=" + d.bubbleKey());
                h.assertTrue(d.distanceTo(p) <= 3.6D, "he stopped " + d.distanceTo(p) + " blocks away"
                        + " npc=" + d.position() + " player=" + p.position() + " start=" + start
                        + " navDone=" + d.getNavigation().isDone() + " free=" + d.free()
                        + " pointing=" + d.pointing() + " ready=" + d.approachReadyAt() + " now=" + level.getGameTime()
                        + " talking=" + d.talkingTo() + " dlgNpc=" + p.getData(ModAttachments.DIALOGUE).npc()
                        + " disconnected=" + p.hasDisconnected() + " bubble=" + d.bubbleKey());
                h.assertTrue(d.talkingTo() == p.getId() && p.getData(ModAttachments.DIALOGUE).npc() == d.getId(),
                        "he did not open the conversation: bubble=" + d.bubbleKey() + " talking=" + d.talkingTo() + " dlgNpc="
                                + p.getData(ModAttachments.DIALOGUE).npc() + " id=" + d.getId() + " ready=" + d.approachReadyAt()
                                + " hurtTime=" + p.hurtTime + " lastHurt=" + p.getLastHurtMobTimestamp() + " tc=" + p.tickCount
                                + " sprint=" + p.isSprinting() + " swim=" + p.isSwimming() + " fly=" + p.getAbilities().flying);
                h.assertTrue(d.approachReadyAt() >= level.getGameTime() + 5000L, "no cool-down after the approach");
                DialogueService.choose(p, -1);
                // The cool-down: a player who declined is not approached again at once.
                p.moveTo(p.getX() - 2, p.getY(), p.getZ(), 0, 0);
                var goal = new io.github.verycooltimo.murim.entity.WandererApproachGoal(d);
                h.assertFalse(goal.canUse(), "he would walk up again straight away");
                d.discard();
            } finally {
                level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                reset(level);
            }
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "fresh_calm")
    public static void aCalmPlayerWithFiveSecondsIdleGetsTheConversation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        level.addNewPlayer(p);
        p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100L);
        p.getPersistentData().putDouble("murim_idle_x", p.getX());
        p.getPersistentData().putDouble("murim_idle_y", p.getY());
        p.getPersistentData().putDouble("murim_idle_z", p.getZ());
        SectDisciple d = placed(h, mountain(p, 60, 3200), 2.5, 3.5);
        try {
            // The fixture has a full idle interval, without a combat timestamp.
            p.tickCount = 24;
            h.assertTrue(p.getLastHurtMobTimestamp() == 0, "fresh player has a combat timestamp");
            var goal = new io.github.verycooltimo.murim.entity.WandererApproachGoal(d);
            h.assertTrue(goal.canUse(), "the stranger was not selected");
            goal.start();
            for (int tick = 0; tick < 24; tick++) {
                goal.tick();
            }
            h.assertTrue(d.talkingTo() == p.getId() && p.getData(ModAttachments.DIALOGUE).npc() == d.getId(),
                    "fresh calm player got a hint instead of a conversation: " + d.bubbleKey());
            goal.stop();
        } finally {
            DialogueService.close(p, d);
            d.discard();
            level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            reset(level);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "finished_tick")
    public static void aFinishedGreetingCanTickBeforeTheSelectorStopsIt(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        level.addNewPlayer(p);
        p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100L);
        p.getPersistentData().putDouble("murim_idle_x", p.getX());
        p.getPersistentData().putDouble("murim_idle_y", p.getY());
        p.getPersistentData().putDouble("murim_idle_z", p.getZ());
        SectDisciple d = placed(h, mountain(p, 60, 3200), 2.5, 3.5);
        try {
            var goal = new io.github.verycooltimo.murim.entity.WandererApproachGoal(d);
            h.assertTrue(goal.canUse(), "idle stranger was not selected");
            goal.start();
            p.setSprinting(true);
            for (int tick = 0; tick < 24; tick++) goal.tick();
            h.assertFalse(p.getData(ModAttachments.DIALOGUE).active(), "busy player got a screen");
            goal.stop();
            long readyAt = d.approachReadyAt();
            goal.tick();
            goal.tick();
            h.assertTrue(d.approachReadyAt() == readyAt, "extra ticks changed the cooldown");
            h.assertFalse(p.getData(ModAttachments.DIALOGUE).active(), "extra ticks opened a dialogue");
            h.assertFalse(goal.canUse(), "the cooldown allowed another greeting");
        } finally {
            d.discard();
            level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            reset(level);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "8")
    public static void aBlowHurtsHimAndHeFightsBackWithoutSectLaw(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        level.addNewPlayer(p);
        p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100L);
        p.getPersistentData().putDouble("murim_idle_x", p.getX());
        p.getPersistentData().putDouble("murim_idle_y", p.getY());
        p.getPersistentData().putDouble("murim_idle_z", p.getZ());
        MountHuaSite site = mountain(p, 60, 3200);
        SectDisciple d = placed(h, site, 4.5, 3.5);
        float health = d.getHealth();
        try {
            boolean taken = d.hurt(level.damageSources().playerAttack(p), 3.0F);
            h.assertTrue(taken && d.getHealth() < health, "the blow did not hurt him: " + d.getHealth());
            h.assertTrue(d.grudgeTarget() == p, "he does not fight back");
            var goal = new io.github.verycooltimo.murim.entity.WandererFightGoal(d);
            h.assertTrue(goal.canUse(), "the fight goal does not start");
            d.moveTo(p.getX() + 1.0D, p.getY(), p.getZ(), 0, 0);
            goal.start();
            goal.tick();
            h.assertTrue(d.blows() == 1, "he did not strike back");
            h.assertFalse(io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.active(p), "the player was punished");
            h.assertFalse(SectWar.atWar(p), "a war started over a stranger");
        } finally {
            d.discard();
            level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
            reset(level);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "8d")
    public static void heDiesAndDropsHisMethodScrollAndArtBookAndStaysDead(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        Wanderer.skipTickingCheck = true;
        ServerPlayer p = player(h);
        level.addNewPlayer(p);
        p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100L);
        p.getPersistentData().putDouble("murim_idle_x", p.getX());
        p.getPersistentData().putDouble("murim_idle_y", p.getY());
        p.getPersistentData().putDouble("murim_idle_z", p.getZ());
        MountHuaSite site = mountain(p, 60, 3200);
        SectDisciple d = placed(h, site, 4.5, 3.5);
        UUID id = d.getUUID();
        Vec3 at = d.position();
        var mine = new Wanderer.Entry();
        mine.entity = id;
        mine.expiresAt = level.getGameTime() + 100000L;
        Wanderer.data(level).byPlayer.put(p.getUUID(), mine);
        var warBefore = SectWar.atWar(p);
        d.hurt(level.damageSources().playerAttack(p), 1000.0F);
        h.assertTrue(d.isDeadOrDying() || !d.isAlive(), "a wanderer cannot be killed");
        h.runAfterDelay(25, () -> {
            try {
                var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(at, at).inflate(6.0D));
                boolean scroll = items.stream().anyMatch(e -> e.getItem().is(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get())
                        && SectDisciple.WANDERER_METHOD.equals(e.getItem().get(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get())));
                long books = items.stream().filter(e -> e.getItem().is(io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get())
                        && io.github.verycooltimo.murim.mastery.Arts.masteryId(SectDisciple.WANDERER_ART)
                        .equals(e.getItem().get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get()))).count();
                h.assertTrue(scroll, "no breathing method scroll dropped: " + items);
                h.assertTrue(books == 1, "art book drops: " + books);
                h.assertTrue(level.getEntity(id) == null, "a dead wanderer is still in the world");
                h.assertTrue(SectWar.atWar(p) == warBefore, "his death started a war");
                // The visit is over: no immortal copy comes back at once.
                Wanderer.step(level, p, site);
                h.assertTrue(wanderers(level, at).isEmpty(), "a wanderer stands again right after the death");
            } finally {
                level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
                reset(level);
            }
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "9")
    public static void pointingEndsAfterThreeSeconds(GameTestHelper h) {
        reset(h.getLevel());
        ServerPlayer p = player(h);
        MountHuaSite site = mountain(p, 60, 3000);
        SectDisciple d = placed(h, site, 2.5, 2.5);
        Wanderer.point(d, site);
        h.runAfterDelay(45, () -> h.assertTrue(d.pointing(), "pointing ended before the direction could be read"));
        h.runAfterDelay(61, () -> {
            try {
                h.assertFalse(d.pointing(), "pointing lasted longer than three seconds");
            } finally {
                d.discard();
                reset(h.getLevel());
            }
            h.succeed();
        });
    }

    @GameTest(template = "small_floor", timeoutTicks = 100, batch = BATCH + "10")
    public static void anOrphanCannotJoinAndASectVisitEnds(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        reset(level);
        ServerPlayer p = player(h);
        MountHuaSite site = mountain(p, 60, 3000);
        SectDisciple orphan = Wanderer.create(level, p.blockPosition(), site);
        h.assertFalse(level.addFreshEntity(orphan), "an unowned wanderer joined the world");
        SectDisciple d = placed(h, site, 2.5, 2.5);
        try {
            var data = Wanderer.data(level);
            data.byPlayer.clear();
            var entry = new Wanderer.Entry();
            entry.entity = d.getUUID();
            entry.expiresAt = level.getGameTime() + Wanderer.STAY;
            data.byPlayer.put(p.getUUID(), entry);
            // Model walking into the sect by putting its bounds around the test floor.
            MountHuaSite here = mountain(p, 0, 0);
            Wanderer.step(level, p, here);
            h.assertTrue(d.isRemoved() && entry.entity == null, "the wanderer remained in the sect");
            h.assertTrue(entry.nextAt > level.getGameTime(), "no cooldown after leaving");
            h.assertTrue(Wanderer.step(level, p, site) == null, "a visit ignored its cooldown");
        } finally {
            d.discard();
            reset(level);
        }
        h.succeed();
    }
}
