package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.trade.Peddler;
import io.github.verycooltimo.murim.world.camp.BanditCampData;
import io.github.verycooltimo.murim.world.camp.CampTestFixtures;
import io.github.verycooltimo.murim.world.camp.BanditCamps;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Contract board: deterministic daily offers, take / complete / hand-in, group sharing, expiry. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectContractGameTests {
    private SectContractGameTests() { }

    private static final BlockPos GATE = new BlockPos(0, 64, 0);
    private static final List<SectContracts.CampRef> CAMPS = List.of(new SectContracts.CampRef(777L, new BlockPos(10, 64, 10)));

    private static SectState graduate() {
        return SectState.NONE.joined().with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).contribute(40);
    }

    private static ServerPlayer player(GameTestHelper h, double x) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = h.absoluteVec(new Vec3(x, 2, 10.5));
        p.moveTo(at.x, at.y, at.z, 0, 0);
        p.setData(ModAttachments.SECT, graduate());
        return p;
    }

    private record Route(Vec3 origin) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[]{4, 4}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[]{p.x - origin.x, p.z - origin.z}; }
    }

    private static SectDisciple npc(GameTestHelper h, String key) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setKeepAwake(true);
        d.setNoAi(true);
        Vec3 at = h.absoluteVec(new Vec3(11.5, 2, 10.5));
        d.setLayout(new Route(at));
        d.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** Fresh board for today with a known camp; fake players are resolved through the test hook. */
    private static SectContracts.Data board(GameTestHelper h, ServerPlayer... players) {
        return board(h, CAMPS, players);
    }

    private static SectContracts.Data board(GameTestHelper h, List<SectContracts.CampRef> camps, ServerPlayer... players) {
        MinecraftServer server = h.getLevel().getServer();
        // One escort route: the foot route, ending at the stall (the tests move the stall); the three routes have their own tests.
        SectContracts.routesOverride = List.of(new SectContracts.RouteRef(EscortRoutes.FOOT, BlockPos.ZERO));
        Map<UUID, ServerPlayer> by = new HashMap<>();
        for (ServerPlayer p : players) by.put(p.getUUID(), p);
        SectContracts.resolver = by::get;
        SectContracts.Data d = SectContracts.data(server);
        d.runs.clear();
        d.day = -1;
        SectContracts.refresh(server, SectContracts.today(server), camps);
        return d;
    }

    private static ItemStack herbs(SectContracts.Offer o, boolean first, int count) {
        return new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse(first ? o.itemA() : o.itemB())), count);
    }

    /** K1: exercise actual daily offers against registered plants and their supply tables. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 40)
    public static void boardOnlyRequestsObtainableModHerbs(GameTestHelper h) {
        var catalog = io.github.verycooltimo.murim.world.herb.HerbCatalog.all();
        int common = 0, ancient = 0;
        for (int day = 0; day < 400; day++) {
            for (var o : SectContracts.generate(1101, day, List.of(), BlockPos.ZERO, GATE)) {
                if (o.kind() != SectContracts.Kind.HERBS) continue;
                h.assertTrue(!o.itemA().equals(o.itemB()), "duplicate named herb");
                for (String item : List.of(o.itemA(), o.itemB())) {
                    var herb = catalog.stream().filter(e -> e.item().equals(item)).findFirst().orElseThrow();
                    h.assertTrue(item.startsWith("murim:") && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(item)),
                            "vanilla or missing item: " + item);
                    var block = BuiltInRegistries.BLOCK.get(ResourceLocation.parse(herb.block()));
                    h.assertTrue(BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(herb.block())), "missing plant");
                    boolean habitat = io.github.verycooltimo.murim.quest.QuestLoader.herbs().stream()
                            .anyMatch(e -> e.step().item().equals(item) && e.step().block().equals(herb.block())
                                    && block instanceof io.github.verycooltimo.murim.quest.MedicinalPlant);
                    boolean wild = io.github.verycooltimo.murim.world.herb.HerbCatalog.WILD.contains(herb);
                    boolean sold = io.github.verycooltimo.murim.trade.PeddlerStock.SELLS.stream()
                            .anyMatch(e -> e.item().equals(item) && e.chance() > 0 && e.uses() > 0);
                    h.assertTrue(habitat || wild || sold, "herb has no world or merchant source: " + item);
                    if (item.equals("murim:hundred_ginseng")) common++;
                    if (item.equals("murim:thousand_ginseng")) ancient++;
                }
            }
        }
        h.assertTrue(common > ancient * 3, "common roots do not dominate ancient roots");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 40)
    public static void boardReplacesRazedTargetAndKeepsAcceptedProof(GameTestHelper h) {
        var p = player(h, 10.5);
        var d = board(h, p);
        var old = SectContracts.offer(d, 0);
        var camp = CampTestFixtures.fresh(h, old.campKey(), old.site(), 1031);
        camp.cleared = true;
        var live = CampTestFixtures.fresh(h, h.absolutePos(new BlockPos(20, 2, 20)).asLong(),
                h.absolutePos(new BlockPos(20, 2, 20)), 1032);
        live.cleared = false;
        var run = new SectContracts.Run(java.util.UUID.randomUUID(), 0, d.day, SectContracts.Kind.CAMP);
        run.members.add(p.getUUID());
        d.runs.put(run.id, run);
        SectContracts.repairCampOffer(h.getLevel().getServer(), List.of(
                new SectContracts.CampRef(camp.key, camp.centre), new SectContracts.CampRef(live.key, live.centre)));
        h.assertTrue(SectContracts.offer(d, 0).kind() == SectContracts.Kind.CAMP
                && SectContracts.offer(d, 0).campKey() == live.key, "board offered a razed camp or herb substitute");
        h.assertTrue(SectContracts.runOffer(d, run).campKey() == old.campKey(), "accepted group's proof target changed");
        var tag = d.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess());
        var loaded = SectContracts.Data.load(tag, h.getLevel().registryAccess());
        h.assertTrue(SectContracts.runOffer(loaded, loaded.runs.get(run.id)).campKey() == old.campKey(),
                "accepted proof target did not survive save/load");
        CampTestFixtures.remove(h.getLevel(), camp.key);
        CampTestFixtures.remove(h.getLevel(), live.key);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 40)
    public static void offersAreDeterministicPerDayAndDiffer(GameTestHelper h) {
        BlockPos end = new BlockPos(0, 64, 100);
        var a = SectContracts.generate(12345L, 40, CAMPS, end, GATE);
        var b = SectContracts.generate(12345L, 40, CAMPS, end, GATE);
        h.assertTrue(a.equals(b), "same seed and day gave different contracts");
        h.assertTrue(a.size() == 3 && a.get(0).kind() == SectContracts.Kind.CAMP && a.get(1).kind() == SectContracts.Kind.ESCORT
                && a.get(2).kind() == SectContracts.Kind.HERBS, "board is not camp + escort + herbs");
        h.assertTrue(a.get(0).campKey() == 777L && a.get(1).site().equals(end), "camp or escort end missing");
        boolean varies = false;
        for (long day = 41; day < 50; day++) varies |= !SectContracts.generate(12345L, day, CAMPS, end, GATE).get(2).equals(a.get(2));
        h.assertTrue(varies, "herb order never changes between days");
        var none = SectContracts.generate(12345L, 40, List.of(), end, GATE);
        h.assertTrue(none.get(0).kind() == SectContracts.Kind.HERBS && !none.get(0).itemA().equals(none.get(2).itemA()),
                "no-camp board should fall back to a different herb order");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 40)
    public static void offersSurviveSaveAndReload(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectContracts.take(p, 2);
        var tag = d.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess());
        SectContracts.Data back = SectContracts.Data.load(tag, h.getLevel().registryAccess());
        h.assertTrue(back.day == d.day && back.offers.equals(d.offers), "offers lost on reload");
        h.assertTrue(back.runs.size() == 1 && back.runs.values().iterator().next().members.contains(p.getUUID()), "run lost on reload");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void herbContractIsTakenAndHandedInToTheAlchemistOnce(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectDisciple wrong = npc(h, "gyeong_tak"), alchemist = npc(h, "gyeong_cho");
        SectContracts.Offer o = SectContracts.offer(d, 2);
        h.assertTrue(SectContracts.take(p, 2), "member could not take the herb contract");
        h.assertTrue(!SectContracts.take(p, 0), "second contract taken at once");
        h.assertTrue(SectContracts.journal(p) != null, "active contract missing from the journal");
        h.assertTrue(!SectContracts.handIn(p, alchemist), "handed in with no herbs");
        p.getInventory().add(herbs(o, true, o.countA()));
        p.getInventory().add(herbs(o, false, o.countB()));
        h.assertTrue(!SectContracts.handIn(p, wrong), "wrong NPC accepted the hand-in");
        int before = SectService.state(p).contribution();
        h.assertTrue(SectContracts.handIn(p, alchemist), "alchemist refused the herbs");
        // The giver thanks in his own words (not the others' gossip), and the hand-in is an event the rest of the mountain answers.
        h.assertTrue(alchemist.bubbleKey().startsWith("murim.bubble.contract.done.gyeong_cho."), "giver's thanks: " + alchemist.bubbleKey());
        h.assertTrue(SectEvents.live(p, SectEvents.Event.CONTRACT) && SectEvents.reacted(p, SectEvents.Event.CONTRACT, alchemist),
                "the hand-in is not an event, or the giver would answer it twice");
        int merit = SectContracts.reward(SectContracts.Kind.HERBS, SectService.standing(p), d.day, 2).merit();
        h.assertTrue(SectService.state(p).contribution() >= before + merit, "merit not paid");
        h.assertTrue(p.getInventory().items.stream().noneMatch(s -> s.is(herbs(o, true, 1).getItem()) && s.getCount() >= o.countA()), "herbs not taken");
        h.assertTrue(SectContracts.journal(p) == null, "finished contract still in the journal");
        h.assertTrue(!SectContracts.take(p, 2), "same contract taken twice in one day");
        h.assertTrue(!SectContracts.handIn(p, alchemist), "paid twice");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void campContractNeedsTheTokenFromTheClearing(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        long key = System.nanoTime();
        SectContracts.Data d = board(h, List.of(new SectContracts.CampRef(key, p.blockPosition())), p);
        SectDisciple gate = npc(h, "gyeong_tak");
        h.assertTrue(SectContracts.take(p, 0), "camp contract not taken");
        h.assertTrue(!SectContracts.handIn(p, gate), "handed in before clearing the camp");
        BanditCampData.Camp camp = BanditCamps.data(h.getLevel()).getOrCreate(key, p.blockPosition(), 1L);
        BanditCamps.clear(h.getLevel(), camp);
        long tokens = p.getInventory().items.stream().filter(s -> s.is(ModItems.CONTRACT_TOKEN.get())).count();
        h.assertTrue(tokens == 1, "no proof token after the camp fell: " + tokens);
        int before = SectService.state(p).contribution();
        h.assertTrue(SectContracts.handIn(p, gate), "gate elder refused the token");
        h.assertTrue(SectService.state(p).contribution() > before, "merit not paid for the camp");
        h.assertTrue(p.getInventory().items.stream().noneMatch(s -> s.is(ModItems.CONTRACT_TOKEN.get())), "token not consumed");
        h.assertTrue(p.getInventory().items.stream().anyMatch(s -> s.is(ModItems.MANUAL_PAGE.get())), "no technique page reward");
        h.succeed();
    }

    /** A camp somebody else routed: arriving at the contract's camp lets the player search it and find the token, once per group. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void campClearedByOthersCanBeSearchedForTheToken(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        long key = System.nanoTime();
        BlockPos site = p.blockPosition();
        SectContracts.Data d = board(h, List.of(new SectContracts.CampRef(key, site)), p);
        SectDisciple gate = npc(h, "gyeong_tak");
        h.assertTrue(SectContracts.take(p, 0), "camp contract not taken");
        SectContracts.campSearchStep(h.getLevel());
        h.assertTrue(count(p) == 0, "token from a camp that is not cleared");
        BanditCampData.Camp camp = BanditCamps.data(h.getLevel()).getOrCreate(key, site, 1L);
        camp.cleared = true;
        // Far from the camp: nothing to find yet.
        p.moveTo(p.getX() + 80.0D, p.getY(), p.getZ());
        SectContracts.campSearchStep(h.getLevel());
        h.assertTrue(count(p) == 0, "token found from far away");
        h.assertTrue(!SectContracts.handIn(p, gate), "handed in without the token");
        p.moveTo(p.getX() - 80.0D, p.getY(), p.getZ());
        SectContracts.campSearchStep(h.getLevel());
        h.assertTrue(count(p) == 1, "no token after searching the routed camp: " + count(p));
        SectContracts.campSearchStep(h.getLevel());
        h.assertTrue(count(p) == 1, "second search gave a second token: " + count(p));
        h.assertTrue(SectContracts.active(d, p.getUUID()).searched, "run does not remember the search");
        int before = SectService.state(p).contribution();
        h.assertTrue(SectContracts.handIn(p, gate), "gate elder refused the found token");
        h.assertTrue(SectService.state(p).contribution() > before, "merit not paid");
        h.succeed();
    }

    private static long count(ServerPlayer p) {
        return p.getInventory().items.stream().filter(s -> s.is(ModItems.CONTRACT_TOKEN.get())).mapToInt(ItemStack::getCount).sum();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void escortArrivesAtTheStallAndPaysTheSteward(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectDisciple steward = npc(h, "tae_gyun");
        h.assertTrue(SectContracts.take(p, 1), "escort contract not taken");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        h.assertTrue(run != null && run.escort != null, "no merchant was created");
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        h.assertTrue(merchant != null && merchant.escorted(), "merchant is not an escort");
        h.assertTrue(!SectContracts.handIn(p, steward), "paid before the merchant arrived");
        BlockPos end = h.absolutePos(new BlockPos(14, 2, 14));
        SectContracts.stallOverride = end;
        merchant.moveTo(end.getX() + 0.5D, merchant.getY(), end.getZ() + 0.5D);
        SectContracts.escortStep(h.getLevel());
        h.assertTrue(run.arrived, "arrival at the stall not detected");
        h.assertTrue(SectContracts.handIn(p, steward), "steward refused a finished escort");
        SectContracts.stallOverride = null;
        h.assertTrue(merchant.isRemoved(), "escort merchant not sent away after the hand-in");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void groupOfThreeSharesPooledItemsAndReward(GameTestHelper h) {
        ServerPlayer a = player(h, 10.5), b = player(h, 9.5), c = player(h, 8.5), d4 = player(h, 7.5);
        SectContracts.Data d = board(h, a, b, c, d4);
        SectDisciple alchemist = npc(h, "gyeong_cho");
        SectContracts.Offer o = SectContracts.offer(d, 2);
        h.assertTrue(SectContracts.take(a, 2), "taker could not take");
        h.assertTrue(!SectContracts.invite(b, c), "a non-taker invited");
        h.assertTrue(SectContracts.invite(a, b) && SectContracts.accept(b), "first invite failed");
        h.assertTrue(SectContracts.invite(a, c) && SectContracts.accept(c), "second invite failed");
        h.assertTrue(!SectContracts.invite(a, d4), "fourth player invited");
        h.assertTrue(!SectContracts.accept(d4), "fourth player joined");
        SectContracts.Run run = SectContracts.active(d, a.getUUID());
        h.assertTrue(run.members.size() == 3 && SectContracts.active(d, c.getUUID()) == run, "group is not one run");
        a.getInventory().add(herbs(o, true, o.countA()));
        b.getInventory().add(herbs(o, false, o.countB()));
        int[] before = {SectService.state(a).contribution(), SectService.state(b).contribution(), SectService.state(c).contribution()};
        h.assertTrue(SectContracts.handIn(b, alchemist), "pooled herbs refused");
        int merit = SectContracts.reward(SectContracts.Kind.HERBS, SectService.standing(a), d.day, 2).merit();
        h.assertTrue(SectService.state(a).contribution() >= before[0] + merit && SectService.state(b).contribution() >= before[1] + merit
                && SectService.state(c).contribution() >= before[2] + merit, "not every member got the merit");
        h.assertTrue(SectContracts.active(d, a.getUUID()) == null && SectContracts.active(d, c.getUUID()) == null, "run still active");
        h.assertTrue(SectContracts.leave(d4) == false, "leave without a run succeeded");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void farMemberDoesNotShareAndLeavingIsFree(GameTestHelper h) {
        ServerPlayer a = player(h, 10.5), b = player(h, 9.5);
        SectContracts.Data d = board(h, a, b);
        SectDisciple alchemist = npc(h, "gyeong_cho");
        SectContracts.Offer o = SectContracts.offer(d, 2);
        SectContracts.take(a, 2);
        SectContracts.invite(a, b);
        SectContracts.accept(b);
        b.moveTo(b.getX() + 200, b.getY(), b.getZ());
        a.getInventory().add(herbs(o, true, o.countA()));
        a.getInventory().add(herbs(o, false, o.countB()));
        int far = SectService.state(b).contribution();
        h.assertTrue(SectContracts.handIn(a, alchemist), "taker hand-in failed");
        h.assertTrue(SectService.state(b).contribution() == far, "a member out of range was paid");
        SectContracts.Data d2 = board(h, a, b);
        h.assertTrue(SectContracts.take(a, 0), "new day contract not available");
        long merit = SectService.state(a).contribution();
        h.assertTrue(SectContracts.leave(a) && SectService.state(a).contribution() == merit, "leaving cost something");
        h.assertTrue(d2.runs.isEmpty(), "empty run was kept");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void contractsExpireAfterThreeSectDays(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectDisciple alchemist = npc(h, "gyeong_cho");
        SectContracts.Offer o = SectContracts.offer(d, 2);
        SectContracts.take(p, 1);
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        long today = d.day;
        int before = SectService.state(p).contribution();
        SectContracts.refresh(h.getLevel().getServer(), today + 1, CAMPS);
        h.assertTrue(SectContracts.active(d, p.getUUID()) == run && !merchant.isRemoved(), "accepted run lost on second day");
        run.arrived = true;
        SectContracts.refresh(h.getLevel().getServer(), today + 2, CAMPS);
        h.assertTrue(SectContracts.active(d, p.getUUID()) == run && SectContracts.journal(p) != null,
                "finished run lost on third day");
        h.assertTrue(SectContracts.ready(run, run.accepted, d.day, java.util.List.of(p)), "third-day hand-in is not ready");
        SectContracts.refresh(h.getLevel().getServer(), today + 3, CAMPS);
        h.assertTrue(d.runs.isEmpty() && SectContracts.active(d, p.getUUID()) == null, "yesterday's run survived the day change");
        h.assertTrue(merchant.isRemoved(), "escort merchant stayed after the day ended");
        h.assertTrue(SectService.state(p).contribution() == before, "expiry changed merit");
        h.assertTrue(SectContracts.journal(p) == null, "expired contract still in the journal");
        p.getInventory().add(herbs(o, true, o.countA()));
        h.assertTrue(!SectContracts.handIn(p, alchemist), "expired contract accepted");
        h.assertTrue(SectContracts.take(p, 2), "new day could not take a contract");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void thirdDayHandInPaysAndTomorrowOffersRemainAvailable(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectDisciple alchemist = npc(h, "gyeong_cho");
        SectContracts.Offer accepted = SectContracts.offer(d, 2);
        h.assertTrue(SectContracts.take(p, 2), "herb contract unavailable");
        long first = d.day;
        SectContracts.refresh(h.getLevel().getServer(), first + 2, CAMPS);
        p.getInventory().add(herbs(accepted, true, accepted.countA()));
        p.getInventory().add(herbs(accepted, false, accepted.countB()));
        h.assertTrue(SectContracts.handIn(p, alchemist), "third-day giver refused accepted herbs");
        h.assertTrue(!SectContracts.handIn(p, alchemist), "third-day hand-in paid twice");
        SectContracts.refresh(h.getLevel().getServer(), first + 3, CAMPS);
        h.assertTrue(SectContracts.take(p, 2), "paid contract blocked a later day's new offer");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 40)
    public static void rewardsScaleWithStanding(GameTestHelper h) {
        for (SectContracts.Kind k : SectContracts.Kind.values()) {
            int low = SectContracts.reward(k, SectStanding.NOVICE, 3, 0).merit();
            int high = SectContracts.reward(k, SectStanding.TRUSTED, 3, 0).merit();
            h.assertTrue(high > low, k + " merit does not grow with standing");
        }
        h.assertTrue(SectContracts.reward(SectContracts.Kind.CAMP, SectStanding.TRUSTED, 3, 0).items().size()
                > SectContracts.reward(SectContracts.Kind.CAMP, SectStanding.NOVICE, 3, 0).items().size(), "camp items do not grow");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 40)
    public static void campContractNamesDirectionAndPlaceAndIsTheGroupOne(GameTestHelper h) {
        var camp = SectContracts.generate(99L, 5, CAMPS, new BlockPos(0, 64, 100), GATE).get(0);
        h.assertTrue("south_east".equals(camp.itemA()), "direction wrong: " + camp.itemA());
        h.assertTrue(camp.countB() >= 0 && camp.countB() < SectContracts.PLACES, "place name index out of range");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void tokenOnlyFromTheClearOfTheContractCamp(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5), stranger = player(h, 9.5);
        long key = System.nanoTime();
        board(h, List.of(new SectContracts.CampRef(key, p.blockPosition())), p, stranger);
        SectContracts.take(p, 0);
        BanditCampData data = BanditCamps.data(h.getLevel());
        BanditCamps.clear(h.getLevel(), data.getOrCreate(key + 1, p.blockPosition(), 1L));
        h.assertTrue(p.getInventory().items.stream().noneMatch(s -> s.is(ModItems.CONTRACT_TOKEN.get())), "other camp gave a token");
        BanditCamps.clear(h.getLevel(), data.getOrCreate(key, p.blockPosition(), 1L));
        h.assertTrue(stranger.getInventory().items.stream().noneMatch(s -> s.is(ModItems.CONTRACT_TOKEN.get())), "non-member got a token");
        h.assertTrue(p.getInventory().items.stream().anyMatch(s -> s.is(ModItems.CONTRACT_TOKEN.get())), "contract camp gave no token");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void merchantDeathFailsWithoutPenaltyAndCanBeRetaken(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectContracts.take(p, 1);
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        int merit = SectService.state(p).contribution();
        SectContracts.merchantLost(h.getLevel().getServer(), d, run);
        h.assertTrue(SectContracts.active(d, p.getUUID()) == null, "failed run still active");
        h.assertTrue(SectService.state(p).contribution() == merit, "failure cost merit");
        h.assertTrue(SectContracts.take(p, 1), "escort cannot be retaken after the merchant died");
        merchant.discard();
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void disconnectedMemberDoesNotBreakHandIn(GameTestHelper h) {
        ServerPlayer a = player(h, 10.5), b = player(h, 9.5);
        SectContracts.Data d = board(h, a, b);
        SectDisciple alchemist = npc(h, "gyeong_cho");
        SectContracts.Offer o = SectContracts.offer(d, 2);
        SectContracts.take(a, 2);
        SectContracts.invite(a, b);
        SectContracts.accept(b);
        SectContracts.resolver = id -> id.equals(a.getUUID()) ? a : null;
        a.getInventory().add(herbs(o, true, o.countA()));
        a.getInventory().add(herbs(o, false, o.countB()));
        h.assertTrue(SectContracts.handIn(a, alchemist), "offline member blocked the hand-in");
        h.assertTrue(SectContracts.journal(a) == null, "journal error or stale line");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void nonMemberAndSecondContractAreRefusedAndWrongSlotIsRejected(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5), out = player(h, 9.5);
        out.setData(ModAttachments.SECT, SectState.NONE);
        SectContracts.Data d = board(h, p, out);
        h.assertTrue(!SectContracts.take(out, 0), "outsider took a contract");
        h.assertTrue(!SectContracts.take(p, 7), "invalid slot accepted");
        h.assertTrue(SectContracts.take(p, 0) && !SectContracts.take(p, 1) && !SectContracts.take(p, 2), "more than one active contract");
        h.assertTrue(d.runs.size() == 1, "extra run created");
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void tabletUpdateIsSentWhenAContractChanges(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectTablet.watch(p, true);
        h.assertTrue(SectTablet.pushIfChanged(p), "an open tablet got no first update");
        h.assertTrue(!SectTablet.pushIfChanged(p) && SectTablet.pushes(p) == 1, "an update was sent although nothing changed");
        // taking a contract pushes at once (the take itself calls SectTablet.touch)
        int before = SectTablet.pushes(p);
        h.assertTrue(SectContracts.take(p, 2), "contract was not taken");
        h.assertTrue(SectTablet.pushes(p) > before, "taking a contract sent no update");
        // progress: the herbs arrive in the pack, the status turns to ready; the periodic check pushes it
        SectContracts.Offer o = SectContracts.offer(d, 2);
        int mid = SectTablet.pushes(p);
        p.getInventory().add(herbs(o, true, o.countA()));
        p.getInventory().add(herbs(o, false, o.countB()));
        h.assertTrue(SectTablet.pushIfChanged(p) && SectTablet.pushes(p) == mid + 1, "contract progress sent no update");
        h.assertTrue(!SectTablet.pushIfChanged(p), "the same state was pushed twice");
        // a closed tablet gets nothing
        SectTablet.watch(p, false);
        h.assertTrue(!SectContracts.leave(p) || SectTablet.pushes(p) == mid + 1, "an update went to a closed tablet");
        h.succeed();
    }

    // ------------------------------------------------------------------ review 18.10

    /** The board opens from the standing of a disciple: a novice is turned away (politely) on take, invite and accept. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void boardIsOpenFromTheStandingOfADiscipleOnly(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5), novice = player(h, 9.5);
        novice.setData(ModAttachments.SECT, SectState.NONE.joined());
        h.assertTrue(SectService.standing(novice) == SectStanding.NOVICE, "setup: a novice, not " + SectService.standing(novice));
        SectContracts.Data d = board(h, p, novice);
        h.assertTrue(!SectContracts.take(novice, 2) && d.runs.isEmpty(), "a novice took a contract");
        h.assertTrue(SectContracts.take(p, 2), "a graduate could not take one");
        h.assertTrue(!SectContracts.invite(p, novice), "a novice was invited into a group");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        d.invites.put(novice.getUUID(), run.id);
        h.assertTrue(!SectContracts.accept(novice) && run.members.size() == 1, "a novice joined a group with a stale invitation");
        // The first lesson of the mentor makes a disciple: now the board is his.
        novice.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE));
        h.assertTrue(SectService.standing(novice) == SectStanding.DISCIPLE, "setup: a disciple, not " + SectService.standing(novice));
        h.assertTrue(SectContracts.invite(p, novice) && SectContracts.accept(novice) && run.members.size() == 2, "a disciple was kept off the group");
        h.succeed();
    }

    /**
     * The board takes the hand-in only at the board itself, and only for a giver who is gone: killed or never built. One
     * who is alive on the shelf, or alive and merely out of reach (his chunk is not loaded), is walked to.
     */
    @GameTest(template = "sect_yard", batch = "sect_contracts_board", timeoutTicks = 80)
    public static void boardTakesAHandInOnlyNearItAndOnlyForAGoneGiver(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectContracts.Offer o = SectContracts.offer(d, 2);
        h.assertTrue(SectContracts.take(p, 2), "herb contract not taken");
        p.getInventory().add(herbs(o, true, o.countA()));
        p.getInventory().add(herbs(o, false, o.countB()));
        var level = h.getLevel();
        SectLife.data(level).unplace("npc:" + o.giver());
        h.assertTrue(!SectContracts.handInAtBoard(p), "the board took a hand-in far from any board");
        // A board beside him (it needs a wall to hold it).
        h.setBlock(new BlockPos(11, 2, 11), net.minecraft.world.level.block.Blocks.STONE);
        level.setBlock(h.absolutePos(new BlockPos(11, 2, 10)), io.github.verycooltimo.murim.decor.SectMissionBoard.BOARD.get().defaultBlockState(), 2);
        h.assertTrue(SectContracts.nearBoard(p), "setup: no board beside the player");
        SectDisciple giver = npc(h, o.giver());
        h.assertTrue(!SectContracts.handInAtBoard(p), "the board took the hand-in of a giver who stands in the yard");
        // Alive but out of reach: the mark of a living member stays, his chunk is not loaded.
        giver.discard();
        SectLife.data(level).place("npc:" + o.giver());
        h.assertTrue(!SectContracts.handInAtBoard(p), "the board took the hand-in of a giver who is only far away");
        // Killed: his mark is gone with him.
        SectLife.data(level).unplace("npc:" + o.giver());
        int before = SectService.state(p).contribution();
        h.assertTrue(SectContracts.handInAtBoard(p), "the board refused a gone giver");
        h.assertTrue(SectService.state(p).contribution() > before, "no merit for the hand-in at the board");
        h.succeed();
    }

    /** A camp cleared while nobody of the run is near it gives no token and does not close the search. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void clearingWithNobodyNearLeavesTheSearchOpen(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        long key = System.nanoTime();
        BlockPos site = p.blockPosition().offset(500, 0, 0);
        SectContracts.Data d = board(h, List.of(new SectContracts.CampRef(key, site)), p);
        h.assertTrue(SectContracts.take(p, 0), "camp contract not taken");
        BanditCamps.clear(h.getLevel(), BanditCamps.data(h.getLevel()).getOrCreate(key, site, 1L));
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        h.assertTrue(count(p) == 0, "a token reached somebody who was far from the camp");
        h.assertTrue(!run.searched, "the search was closed although no token was given");
        // The member walks up to the cleared camp later and finds it.
        p.moveTo(site.getX() + 0.5D, p.getY(), site.getZ() + 0.5D);
        SectContracts.campSearchStep(h.getLevel());
        h.assertTrue(count(p) == 1 && run.searched, "the member found no token at the cleared camp: " + count(p));
        h.succeed();
    }

    /**
     * An escort merchant that is not loaded when his run ends cannot be removed then; he is remembered and sent away the
     * moment he loads, and that survives a restart.
     */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void unloadedEscortMerchantIsSentAwayWhenHeLoads(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        h.assertTrue(SectContracts.take(p, 1), "escort contract not taken");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        ((Peddler) h.getLevel().getEntity(run.escort)).discard();
        // The merchant of the run is an entity that is not in the loaded world.
        Peddler away = io.github.verycooltimo.murim.trade.ModTrade.PEDDLER.get().create(h.getLevel());
        Vec3 at = h.absoluteVec(new Vec3(14.5, 2, 14.5));
        away.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        run.escort = away.getUUID();
        SectContracts.refresh(h.getLevel().getServer(), d.day + 3, CAMPS);
        h.assertTrue(d.doomed.contains(away.getUUID()), "the unloaded merchant was not remembered");
        var back = SectContracts.Data.load(d.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(back.doomed.contains(away.getUUID()), "the memory did not survive a restart");
        h.assertTrue(!h.getLevel().addFreshEntity(away) && h.getLevel().getEntity(away.getUUID()) == null, "the merchant came back into the world");
        h.assertTrue(!d.doomed.contains(away.getUUID()), "the memory was not cleared");
        // Another peddler is not touched.
        Peddler other = io.github.verycooltimo.murim.trade.ModTrade.PEDDLER.get().create(h.getLevel());
        other.moveTo(at.x, at.y, at.z + 1.0D, 0.0F, 0.0F);
        h.assertTrue(h.getLevel().addFreshEntity(other), "an ordinary peddler was refused");
        other.discard();
        h.succeed();
    }

    /** The players told "your merchant died" are saved with the board, not kept in a static set. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void merchantLostNoteIsSavedAndShownOnce(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        h.assertTrue(SectContracts.take(p, 1), "escort contract not taken");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        Peddler merchant = (Peddler) h.getLevel().getEntity(run.escort);
        SectContracts.merchantLost(h.getLevel().getServer(), d, run);
        merchant.discard();
        h.assertTrue(d.lost.contains(p.getUUID()), "the loss was not noted");
        var back = SectContracts.Data.load(d.save(new net.minecraft.nbt.CompoundTag(), h.getLevel().registryAccess()), h.getLevel().registryAccess());
        h.assertTrue(back.lost.contains(p.getUUID()), "the note did not survive a restart");
        SectContracts.showBoard(p);
        h.assertTrue(!d.lost.contains(p.getUUID()), "the note stayed after the board showed it");
        h.succeed();
    }

    // ------------------------------------------------------------------ the board screen and the chat (author 18.10)

    /** The system lines this player was sent: chat ({@code overlay == false}) or the action bar, read off the fake connection. */
    static List<String> lines(ServerPlayer p, boolean overlay) {
        var channel = (io.netty.channel.embedded.EmbeddedChannel) p.connection.getConnection().channel();
        List<String> out = new java.util.ArrayList<>();
        for (Object o : channel.outboundMessages()) {
            if (o instanceof net.minecraft.network.protocol.game.ClientboundSystemChatPacket pk && pk.overlay() == overlay) {
                out.add(pk.content().getString());
            }
        }
        return out;
    }

    /** Chat lines only: what must stay readable. */
    static List<String> chat(ServerPlayer p) {
        return lines(p, false);
    }

    /** A board on the wall beside the players (it needs a wall to hold it). */
    private static void placeBoard(GameTestHelper h) {
        h.setBlock(new BlockPos(11, 2, 11), net.minecraft.world.level.block.Blocks.STONE);
        h.getLevel().setBlock(h.absolutePos(new BlockPos(11, 2, 10)),
                io.github.verycooltimo.murim.decor.SectMissionBoard.BOARD.get().defaultBlockState(), 2);
    }

    /**
     * The board screen's buttons act through the payload handler, not through chat commands: take, hand in and leave need
     * the board in reach, a hand-in needs the herbs, and none of it writes a line into the chat.
     */
    @GameTest(template = "sect_yard", batch = "sect_contracts_board", timeoutTicks = 80)
    public static void boardButtonsTakeAndHandInThroughThePayloadHandler(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        SectContracts.Data d = board(h, p);
        SectContracts.Offer o = SectContracts.offer(d, 2);
        // far from every board: the handler refuses and the snapshot says the buttons are out of reach
        SectContracts.act(p, "take", 2, "");
        h.assertTrue(d.runs.isEmpty(), "a contract was taken far from the board");
        h.assertTrue(!SectTablet.collect(p).contracts().reach(), "the snapshot claims the board is in reach");
        placeBoard(h);
        var near = SectTablet.collect(p).contracts();
        h.assertTrue(near.reach() && near.open() && !near.ready(), "board state beside the board: " + near);
        SectContracts.act(p, "take", 2, "");
        h.assertTrue(SectContracts.active(d, p.getUUID()) != null && SectContracts.active(d, p.getUUID()).slot == 2, "take did not start the contract");
        SectContracts.act(p, "take", 0, "");
        h.assertTrue(d.runs.size() == 1, "a second contract was taken while one is active");
        // the snapshot of the contract in progress carries the gathered counts and survives the codec
        var own = SectTablet.collect(p);
        h.assertTrue(own.active() != null && !own.active().progress().getString().isEmpty(), "the active contract has no progress line");
        h.assertTrue(own.active().counters().size() == 2 && own.active().counters().get(0).item().equals(o.itemA())
                && own.active().counters().get(1).item().equals(o.itemB()) && own.active().counters().get(0).have() == 0
                && own.active().counters().get(0).need() == o.countA(), "structured herb counters lost identity or counts");
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        io.github.verycooltimo.murim.network.SectTabletData.CODEC.encode(buf, own);
        var back = io.github.verycooltimo.murim.network.SectTabletData.CODEC.decode(buf);
        h.assertTrue(back.active() != null && back.active().progress().getString().equals(own.active().progress().getString())
                && back.active().members().equals(own.active().members()) && back.active().counters().equals(own.active().counters()), "the active contract changed over the codec");
        buf.release();
        // nothing gathered yet: «hand in» changes nothing
        int merit = SectService.state(p).contribution();
        SectContracts.act(p, "handin", 0, "");
        h.assertTrue(SectContracts.active(d, p.getUUID()) != null && SectService.state(p).contribution() == merit, "handed in with no herbs");
        p.getInventory().add(herbs(o, true, o.countA()));
        p.getInventory().add(herbs(o, false, o.countB()));
        h.assertTrue(SectTablet.collect(p).contracts().ready(), "the snapshot does not show the contract as ready");
        // the giver is not within reach of the player: the board does not take it in his place, it says whom to walk to
        SectLife.data(h.getLevel()).place("npc:" + o.giver());
        h.assertTrue(!SectTablet.collect(p).contracts().handInHere() && !SectTablet.collect(p).contracts().giverAbsent(), "UI offers hand-in while giver is away");
        SectContracts.act(p, "handin", 0, "");
        h.assertTrue(SectContracts.active(d, p.getUUID()) != null, "the board took a hand-in of a giver who is alive");
        SectDisciple alchemist = npc(h, o.giver());
        h.assertTrue(SectTablet.collect(p).contracts().handInHere(), "UI refuses the nearby giver");
        var filled = SectTablet.collect(p).active().counters();
        h.assertTrue(filled.stream().allMatch(c -> c.have() == c.need()), "HUD counters did not update after gathering");
        SectContracts.act(p, "handin", 0, "");
        h.assertTrue(SectContracts.active(d, p.getUUID()) == null && SectContracts.doneToday(d, p.getUUID(), 2), "the giver in reach did not take the hand-in");
        h.assertTrue(SectService.state(p).contribution() > merit, "no merit for the hand-in");
        // leave goes the same way
        SectContracts.act(p, "take", 0, "");
        h.assertTrue(SectContracts.active(d, p.getUUID()) != null, "setup: the camp contract was not taken");
        SectContracts.act(p, "leave", 0, "");
        h.assertTrue(SectContracts.active(d, p.getUUID()) == null, "leave did not free the player");
        h.assertTrue(chat(p).isEmpty(), "the board wrote into the chat: " + chat(p));
        h.assertTrue(lines(p, true).isEmpty() && !PlayerJournal.entries(p).isEmpty(), "feedback did not move into the journal");
        alchemist.discard();
        SectLife.data(h.getLevel()).unplace("npc:" + o.giver());
        h.succeed();
    }

    /**
     * Playtest 19.10: «I have taken no quests yet, but they are already in my cards». A member who took nothing holds nothing: no
     * active contract, no journal line, no «yours» card, and the tablet does not list the day's offers away from a board (they
     * hang on the board). Beside a board the same three show as plain offers, none of them his; once he takes one it stays on the
     * tablet as his own card wherever he walks, and the other two stay on the board.
     */
    @GameTest(template = "sect_yard", batch = "sect_contracts_tabletowns", timeoutTicks = 80)
    public static void aMemberWhoTookNothingHoldsNoContractAndSeesOnlyOffersAtTheBoard(GameTestHelper h) {
        ServerPlayer fresh = player(h, 10.5), other = player(h, 9.5);
        SectContracts.Data d = board(h, fresh, other);
        h.assertTrue(d.offers.size() == 3 && d.runs.isEmpty(), "setup: the day's board has " + d.offers.size() + " offers and " + d.runs.size() + " runs");
        // Somebody else holds a contract: it must not become the fresh member's.
        placeBoard(h);
        h.assertTrue(SectContracts.take(other, 0), "setup: the other member could not take a contract");
        // Away from the board.
        Vec3 board = fresh.position();
        Vec3 away = board.add(24.0D, 0.0D, 0.0D);
        fresh.moveTo(away.x, away.y, away.z);
        var far = SectTablet.collect(fresh);
        h.assertTrue(far.active() == null, "a member who took nothing holds a contract: " + far.active());
        h.assertTrue(far.offers().isEmpty(), "the tablet lists the day's offers away from the board: " + far.offers().size());
        h.assertTrue(!far.contracts().reach() && !far.contracts().ready() && far.contracts().invite() == null, "board state of a fresh member: " + far.contracts());
        h.assertTrue(SectContracts.active(d, fresh.getUUID()) == null && SectContracts.journal(fresh) == null && SectContracts.status(fresh) == null
                && SectContracts.progress(fresh).getString().isEmpty(), "the journal or status shows a contract that was never taken");
        // At the board the offers are offers: three of them, none his, none done.
        fresh.moveTo(board.x, board.y, board.z);
        var near = SectTablet.collect(fresh);
        h.assertTrue(near.contracts().reach() && near.offers().size() == 3 && near.active() == null, "at the board: " + near.offers().size() + " offers, active " + near.active());
        h.assertTrue(near.offers().stream().noneMatch(SectTabletData.Offer::done), "an offer is marked done for a member who took nothing");
        // He takes the herbs, walks away: only his own card stays, the other two stay on the board.
        h.assertTrue(SectContracts.take(fresh, 2), "setup: the herbs were not taken");
        fresh.moveTo(away.x, away.y, away.z);
        var held = SectTablet.collect(fresh);
        h.assertTrue(held.active() != null && held.active().slot() == 2 && held.offers().size() == 1 && held.offers().get(0).kind() == 2,
                "away from the board his own contract is not the only card: " + held.offers().size() + " offers, active " + held.active());
        // The other member's contract (slot 0) is not on the fresh member's tablet at any distance.
        h.assertTrue(held.offers().stream().noneMatch(o -> o.kind() == 0), "somebody else's contract is on his tablet");
        // The wire keeps it as it is.
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        SectTabletData.CODEC.encode(buf, held);
        var back = SectTabletData.CODEC.decode(buf);
        h.assertTrue(back.offers().size() == 1 && back.active() != null && back.active().slot() == 2, "the held card changed on the wire");
        h.succeed();
    }

    /**
     * The invented contracts of the capture stand (a herb order at 4/6 with a group of two) live in {@code SectTabletDemo}, and
     * only {@code HudGalleryCapture} may call it: no game screen can show them as a real player's. The sources are read from the
     * working copy; a run without them is skipped with a log line.
     */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void captureDemoDataIsReadOnlyByTheCaptureStand(GameTestHelper h) {
        java.nio.file.Path root = null;
        for (String candidate : new String[] {"src/main/java", "../src/main/java", "../../src/main/java"}) {
            java.nio.file.Path path = java.nio.file.Path.of(candidate, "io/github/verycooltimo/murim/client");
            if (java.nio.file.Files.isDirectory(path)) {
                root = java.nio.file.Path.of(candidate, "io/github/verycooltimo/murim");
                break;
            }
        }
        if (root == null) {
            MurimMod.LOGGER.warn("demo guard skipped: no source tree beside {}", java.nio.file.Path.of("").toAbsolutePath());
            h.succeed();
            return;
        }
        List<String> found = new java.util.ArrayList<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            for (java.nio.file.Path f : walk.filter(f -> f.toString().endsWith(".java")).toList()) {
                String name = f.getFileName().toString();
                if (name.equals("SectTabletDemo.java") || name.equals("HudGalleryCapture.java") || name.endsWith("GameTests.java")) continue;
                String text = java.nio.file.Files.readString(f);
                if (text.contains("SectTabletDemo") || text.contains("demoBoard(")) found.add(name);
            }
        } catch (java.io.IOException e) {
            h.fail("sources unreadable: " + e);
            return;
        }
        h.assertTrue(found.isEmpty(), "capture demo data is used outside the capture stand: " + found);
        h.succeed();
    }

    /** A newcomer is told (in the tablet journal) that the board opens with the first lesson; nothing is taken, nothing is chatted. */
    @GameTest(template = "sect_yard", batch = "sect_contracts_board", timeoutTicks = 60)
    public static void boardRefusesANewcomerWithoutChat(GameTestHelper h) {
        ServerPlayer novice = player(h, 10.5);
        novice.setData(ModAttachments.SECT, SectState.NONE.joined());
        SectContracts.Data d = board(h, novice);
        placeBoard(h);
        var state = SectTablet.collect(novice).contracts();
        h.assertTrue(state.reach() && !state.open(), "the board state for a newcomer: " + state);
        SectContracts.act(novice, "take", 2, "");
        h.assertTrue(d.runs.isEmpty(), "a newcomer took a contract through the payload");
        h.assertTrue(chat(novice).isEmpty(), "the refusal was written into the chat: " + chat(novice));
        h.assertTrue(lines(novice, true).isEmpty() && PlayerJournal.entries(novice).stream().anyMatch(c ->
                c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        && t.getKey().equals("murim.contract.low_standing")), "the refusal is missing from the journal");
        // a stranger to the sect gets the board's refusal as well
        ServerPlayer stranger = player(h, 9.5);
        stranger.setData(ModAttachments.SECT, SectState.NONE);
        SectContracts.act(stranger, "take", 2, "");
        h.assertTrue(d.runs.isEmpty() && chat(stranger).isEmpty(), "a stranger took a contract or got a chat line");
        h.succeed();
    }

    /** An invitation waits on the tablet and the board; accepting and declining are buttons, not chat commands. */
    @GameTest(template = "sect_yard", batch = "sect_contracts_board", timeoutTicks = 80)
    public static void invitationIsAnsweredThroughTheScreenNotTheChat(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5), friend = player(h, 9.5);
        SectContracts.Data d = board(h, p, friend);
        placeBoard(h);
        SectContracts.act(p, "take", 0, "");
        SectContracts.Run run = SectContracts.active(d, p.getUUID());
        h.assertTrue(run != null, "setup: no contract taken");
        SectContracts.act(p, "invite", 0, friend.getUUID().toString());
        var invite = SectTablet.collect(friend).contracts().invite();
        h.assertTrue(invite != null && invite.from().equals(p.getGameProfile().getName()), "the invitation is not on the friend's tablet: " + invite);
        h.assertTrue(chat(friend).isEmpty() && chat(p).isEmpty(), "the invitation went through the chat");
        SectContracts.act(friend, "decline", 0, "");
        h.assertTrue(SectTablet.collect(friend).contracts().invite() == null && run.members.size() == 1, "declining left the invitation or joined");
        SectContracts.act(p, "invite", 0, friend.getUUID().toString());
        SectContracts.act(friend, "accept", 0, "");
        h.assertTrue(run.members.size() == 2 && SectTablet.collect(friend).active() != null, "accepting did not join the group");
        h.assertTrue(chat(friend).isEmpty() && chat(p).isEmpty(), "answering the invitation wrote into the chat");
        // an id that is nobody leaves the group alone
        SectContracts.act(p, "invite", 0, "not-an-id");
        h.assertTrue(run.members.size() == 2, "the group changed on a bad invitation");
        h.succeed();
    }

    /** The marks of the sect day (formation, meal, lesson, chore) are routine: they appear in the tablet and its journal, with no chat or action bar. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void routineMarksSendNoChat(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        for (SectAttendance.Activity a : List.of(SectAttendance.Activity.FORMATION, SectAttendance.Activity.MEAL,
                SectAttendance.Activity.LESSON, SectAttendance.Activity.CHORE)) {
            h.assertTrue(SectAttendance.record(p, a), "the mark " + a + " was not recorded");
        }
        h.assertTrue(chat(p).isEmpty(), "routine marks wrote into the chat: " + chat(p));
        h.assertTrue(lines(p, true).isEmpty(), "routine marks wrote over the hotbar");
        h.assertTrue(PlayerJournal.entries(p).stream().anyMatch(c ->
                c.getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                        && t.getKey().equals("murim.sect.log.recorded")), "mark missing from journal");
        h.succeed();
    }

    /** The board snapshot (reach, hand-in readiness, candidates, an invitation, the lost merchant) survives the network codec. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void boardSnapshotSurvivesTheCodec(GameTestHelper h) {
        ServerPlayer p = player(h, 10.5);
        board(h, p);
        var board = new io.github.verycooltimo.murim.network.SectTabletData.Board(true, false, true, Component.literal("Gyeong"),
                List.of(new io.github.verycooltimo.murim.network.SectTabletData.Candidate("abc", "Min")),
                new io.github.verycooltimo.murim.network.SectTabletData.Invite("Min", Component.literal("Herbs")), true, true, true);
        var data = SectTablet.collect(p).withContracts(board);
        var buf = new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        io.github.verycooltimo.murim.network.SectTabletData.CODEC.encode(buf, data);
        var back = io.github.verycooltimo.murim.network.SectTabletData.CODEC.decode(buf);
        h.assertTrue(back.contracts().reach() && !back.contracts().open() && back.contracts().ready() && back.contracts().lost(), "flags lost: " + back.contracts());
        h.assertTrue(back.contracts().invitable().size() == 1 && back.contracts().invitable().get(0).name().equals("Min"), "candidates lost");
        h.assertTrue(back.contracts().invite() != null && back.contracts().invite().from().equals("Min"), "invitation lost");
        h.assertTrue(back.contracts().giver().getString().equals("Gyeong"), "giver lost");
        h.assertTrue(back.contracts().handInHere() && back.contracts().giverAbsent(), "hand-in availability lost");
        buf.release();
        h.succeed();
    }

    /** Normal gameplay never sends system chat or action-bar text (author 30.10). Typed commands and real errors may. */
    @GameTest(template = "sect_yard", batch = "sect_contracts", timeoutTicks = 60)
    public static void normalGameplaySendsNoChatOrActionBar(GameTestHelper h) {
        java.nio.file.Path root = null;
        for (String candidate : new String[] {"src/main/java", "../src/main/java", "../../src/main/java"}) {
            java.nio.file.Path path = java.nio.file.Path.of(candidate, "io/github/verycooltimo/murim/sect");
            if (java.nio.file.Files.isDirectory(path)) {
                root = java.nio.file.Path.of(candidate, "io/github/verycooltimo/murim");
                break;
            }
        }
        if (root == null) {
            MurimMod.LOGGER.warn("chat guard skipped: no source tree beside {}", java.nio.file.Path.of("").toAbsolutePath());
            h.succeed();
            return;
        }
        java.util.regex.Pattern raw = java.util.regex.Pattern.compile("displayClientMessage\\(|sendSystemMessage\\(");
        List<java.nio.file.Path> files = new java.util.ArrayList<>();
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.filter(f -> f.toString().endsWith(".java")).forEach(files::add);
        } catch (java.io.IOException e) {
            h.fail("sources unreadable: " + e);
            return;
        }
        List<String> found = new java.util.ArrayList<>();
        for (java.nio.file.Path f : files) {
            String name = f.getFileName().toString();
            if (name.endsWith("Tests.java") || name.endsWith("Command.java")) continue;
            String relative = root.relativize(f).toString().replace(java.io.File.separatorChar, '/');
            if (java.util.Set.of("client/HudGalleryCapture.java", "client/seal/SealCapture.java",
                    "client/vfx/VfxBackends.java", "client/vfx/preset/PresetRuntime.java").contains(relative)) continue;
            try {
                String source = java.nio.file.Files.readString(f);
                var m = raw.matcher(source);
                while (m.find()) {
                    // Explicit wall guidance is the sole cultivation action-bar exception (round 3).
                    if (relative.equals("cultivation/MeditationService.java")
                            && source.substring(m.start())
                            .startsWith("displayClientMessage(line, true);")) continue;
                    found.add(name + ": " + m.group().replaceAll("\\s+", " "));
                }
            } catch (java.io.IOException e) {
                h.fail("source unreadable: " + f + " " + e);
                return;
            }
        }
        h.assertTrue(found.isEmpty(), "chat/action-bar calls in normal gameplay (use book or tablet journal): " + found);
        h.succeed();
    }
}
