package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.WandererApproachGoal;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Guidance regression scenarios. Run on the runtime machine; no terrain outside the fixture is edited. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class GuidanceGameTests {
    private GuidanceGameTests() { }

    private static ServerPlayer player(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setGameMode(GameType.SURVIVAL);
        p.moveTo(h.absoluteVec(new Vec3(4.5, 2, 4.5)));
        return p;
    }

    private static SectDisciple person(GameTestHelper h) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of("yul_jin").orElseThrow());
        npc.setNoAi(true);
        npc.moveTo(h.absoluteVec(new Vec3(4.5, 2, 6.5)));
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    @GameTest(template = "small_floor", batch = "guidance_map")
    public static void lostMapIsReplacedThroughSectDialogueOncePerDay(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple npc = person(h);
        Wanderer.siteOverride = new MountHuaSite(3000, -3000, 64, 0, 1L);
        try {
            h.assertTrue(DialogueService.openDialogue(p, npc, SectArrival.DIALOGUE, "question"), "gate conversation unavailable");
            DialogueService.choose(p, p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.MAP));
            h.assertTrue(p.getInventory().items.stream().anyMatch(s -> s.is(Items.FILLED_MAP)), "no replacement map");
            p.getInventory().items.stream().filter(s -> s.is(Items.FILLED_MAP)).forEach(s -> s.setCount(0));
            DialogueService.choose(p, p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.MAP));
            h.assertFalse(p.getInventory().items.stream().anyMatch(s -> s.is(Items.FILLED_MAP)), "second same-day map");
            p.getPersistentData().putLong("murim_gate_map_at", h.getLevel().getDayTime() - 24000L);
            p.setData(ModAttachments.SECT, SectState.NONE.joined());
            DialogueService.choose(p, p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.MAP));
            h.assertFalse(p.getInventory().items.stream().anyMatch(s -> s.is(Items.FILLED_MAP)), "member received road map");
            h.assertTrue(SectTablet.collect(p).marks().stream().anyMatch(m -> m.id().equals("gate") && m.task()), "tablet has no passive gate bearing");
        } finally {
            DialogueService.close(p, npc);
            npc.discard();
            Wanderer.siteOverride = null;
            Wanderer.data(h.getLevel()).byPlayer.remove(p.getUUID());
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_at_mountain")
    public static void gateAndMountainHideMapAndCancelStaleOffers(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple npc = person(h);
        MountHuaSite site = new MountHuaSite(3000, -3000, 64, 0, 1L);
        Wanderer.siteOverride = site;
        try {
            h.assertTrue(Wanderer.needsDirections(p), "distant outsider cannot ask");
            h.assertTrue(DialogueService.openDialogue(p, npc, SectArrival.DIALOGUE, "question"), "no dialogue");
            int stale = p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.MAP);
            h.assertTrue(stale >= 0, "distant outsider has no map option");
            int[] gate = SectService.zone(site, "sect_gate");
            p.moveTo(gate[0], gate[1], gate[2], 0, 0);
            h.assertFalse(Wanderer.needsDirections(p), "gate still offers directions");
            DialogueService.choose(p, stale);
            h.assertFalse(Wanderer.giveMap(p), "stale offer delivered map");
            // Show directly to avoid the ordinary conversation-distance guard after moving the fixture player.
            Dialogue d = DialogueLoader.get(SectArrival.DIALOGUE);
            DialogueService.show(p, npc, SectArrival.DIALOGUE, d, "question", "");
            h.assertFalse(p.getData(ModAttachments.DIALOGUE).shown().contains(SectGeneralTalk.MAP), "gate menu offers map");
            p.moveTo(site.centerX(), site.baseY(), site.centerZ(), 0, 0);
            h.assertFalse(Wanderer.needsDirections(p), "mountain interior offers map");
            p.moveTo(h.absoluteVec(new Vec3(4.5, 2, 4.5)));
            p.setData(ModAttachments.SECT, SectState.NONE.joined());
            h.assertFalse(Wanderer.needsDirections(p), "distant member offered map");
        } finally {
            DialogueService.close(p, npc);
            npc.discard();
            Wanderer.siteOverride = null;
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_respawn")
    public static void admissionSetsSafeDormRespawnAndDoesNotOverrideLaterBed(GameTestHelper h) {
        ServerPlayer p = player(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        Vec3 dorm = h.absoluteVec(new Vec3(4.5, 2, 4.5));
        BlockPos floor = BlockPos.containing(dorm).below();
        h.getLevel().setBlock(floor, Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(floor.above(2), Blocks.AIR.defaultBlockState(), 3);
        SectLayout layout = new SectLayout() {
            public Vec3 at(String zone, double u, double v) { return "dorm_3rd".equals(zone) ? dorm.add(u, 0, v) : null; }
            public float yaw(double u, double v) { return 0; }
            public double[] half(String zone) { return new double[] {4, 4}; }
            public double[] local(String zone, Vec3 at) { return new double[] {at.x - dorm.x, at.z - dorm.z}; }
        };
        h.assertTrue(SectBedMark.admissionRespawn(p, layout), "admission did not install fallback");
        h.assertTrue(floor.above().equals(p.getRespawnPosition()), "fallback not on safe dorm floor");
        BlockPos home = floor.offset(2, 1, 0);
        p.setRespawnPosition(p.level().dimension(), home, 0, true, false);
        h.assertFalse(SectBedMark.admissionRespawn(p, layout), "fallback was installed twice");
        h.assertTrue(home.equals(p.getRespawnPosition()), "later bed/home was overwritten");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_wanderer")
    public static void wandererRequiresIdleAndStopsAfterThreeApproachesOrTwoDeclines(GameTestHelper h) {
        ServerPlayer p = player(h);
        var level = h.getLevel();
        level.addNewPlayer(p);
        SectDisciple npc = person(h);
        // small_floor is only 5 x 4 x 5: keep both actors inside its barrier walls for line of sight.
        p.moveTo(h.absoluteVec(new Vec3(3.5, 2, 3.5)));
        npc.moveTo(h.absoluteVec(new Vec3(2.5, 2, 3.5)));
        npc.makeWanderer(1); // A recruiter obeys the same limits as the original traveller.
        Wanderer.Entry entry = new Wanderer.Entry();
        entry.entity = npc.getUUID();
        Wanderer.data(level).byPlayer.put(p.getUUID(), entry);
        try {
            GuidanceIdle.active(p);
            WandererApproachGoal goal = new WandererApproachGoal(npc);
            h.assertFalse(goal.canUse(), "building/action was treated as idle");
            p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100);
            GuidanceIdle.fight(new net.neoforged.neoforge.event.entity.player.AttackEntityEvent(p, npc));
            h.assertFalse(goal.canUse(), "combat attack was treated as idle");
            p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 99);
            h.assertFalse(goal.canUse(), "screen may open before five seconds idle");
            p.getPersistentData().putLong("murim_guidance_idle", level.getGameTime() - 100);
            h.assertTrue(npc.hasLineOfSight(p), "fixture blocks recruiter line of sight");
            h.assertTrue(GuidanceIdle.idle(p), "fixture player is not idle");
            h.assertTrue(goal.canUse(), "idle player not found");
            goal.start();
            GuidanceIdle.active(p); // Building resumes while the recruiter walks up.
            for (int i = 0; i < 30; i++) goal.tick();
            h.assertFalse(p.getData(ModAttachments.DIALOGUE).active(), "recruiter interrupted building");
            goal.stop();
            h.assertTrue(p.getPersistentData().getInt("murim_wander_approaches") == 0,
                    "starting an unseen approach consumed an allowance");
            // ADR 0100: only a noticed visit consumes the allowance, not Goal.start().
            Wanderer.observe(p, npc, level.getGameTime());
            Wanderer.noticed(p, npc);
            h.assertTrue(p.getPersistentData().getInt("murim_wander_approaches") == 1,
                    "nearby recruiter must count exactly once");
            Wanderer.approached(p); // Second and third noticed visits.
            Wanderer.approached(p);
            h.assertFalse(Wanderer.mayApproach(p), "fourth automatic approach permitted");
            Wanderer.request(p);
            h.assertTrue(Wanderer.mayApproach(p), "explicit request ignored");
            Wanderer.approached(p);
            p.getPersistentData().putInt("murim_wander_approaches", 0);
            Wanderer.decline(p);
            h.assertTrue(Wanderer.mayApproach(p), "first decline was permanent");
            Wanderer.decline(p);
            h.assertFalse(Wanderer.mayApproach(p), "recruiter ignores second decline");
        } finally {
            npc.discard();
            Wanderer.data(level).byPlayer.remove(p.getUUID());
            level.removePlayerImmediately(p, net.minecraft.world.entity.Entity.RemovalReason.DISCARDED);
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_gate")
    public static void leavingGateDialogueBacksOffFiveThenFifteenMinutes(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple guard = person(h);
        try {
            h.assertTrue(SectGateWatch.facing(p, guard), "nearby guard ahead not recognised");
            p.moveTo(p.getX(), p.getY(), p.getZ(), 180, 0);
            h.assertFalse(SectGateWatch.facing(p, guard), "guard behind the player may open a screen");
            p.moveTo(p.getX(), p.getY(), p.getZ() - 7, 0, 0);
            h.assertFalse(SectGateWatch.facing(p, guard), "distant guard may open a screen");
            h.assertTrue(DialogueService.openDialogue(p, guard, SectArrival.DIALOGUE, "question"), "gate talk unavailable");
            DialogueService.choose(p, p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.CLOSE));
            h.assertFalse(SectGateWatch.ready(p), "Leave did not suppress automatic greetings");
            h.assertTrue(p.getPersistentData().getLong("murim_gate_leave_until") == p.level().getGameTime() + 6000, "first Leave not five minutes");
            h.assertTrue(DialogueService.openDialogue(p, guard, SectArrival.DIALOGUE, "question"), "manual conversation blocked by cooldown");
            DialogueService.choose(p, p.getData(ModAttachments.DIALOGUE).shown().indexOf(SectGeneralTalk.CLOSE));
            h.assertTrue(p.getPersistentData().getLong("murim_gate_leave_until") == p.level().getGameTime() + 18000, "second Leave not fifteen minutes");
            p.getPersistentData().putLong("murim_gate_leave_until", p.level().getGameTime());
            h.assertTrue(SectGateWatch.ready(p), "greeting remains suppressed after cooldown");
        } finally {
            guard.discard();
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_grace")
    public static void outsidersAndFirstDayMembersGetTwoWarnings(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple npc = person(h);
        try {
            for (boolean member : new boolean[] {false, true}) {
                p.setData(ModAttachments.SECT, member ? SectState.NONE.joined() : SectState.NONE);
                p.getPersistentData().putLong("murim_joined_at", p.level().getGameTime());
                p.getPersistentData().remove("murim_grace_hits");
                SectWar.data(h.getLevel()).warnings.remove(p.getUUID());
                h.assertTrue(SectWar.struck(p, npc, p.damageSources().playerAttack(p), 1) == SectWar.Verdict.WARN, "first blow not forgiven");
                h.assertTrue(SectWar.struck(p, npc, p.damageSources().playerAttack(p), 1) == SectWar.Verdict.WARN, "second blow not forgiven");
                h.assertFalse(SectWar.atWar(p), "two accidental blows started war");
            }
            h.assertTrue(SectWar.struck(p, npc, p.damageSources().playerAttack(p), 1) == SectWar.Verdict.WAR, "third blow ignored");
        } finally {
            SectWar.data(h.getLevel()).wars.remove(p.getUUID());
            SectWar.data(h.getLevel()).warnings.remove(p.getUUID());
            npc.discard();
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_light_warning")
    public static void lightWarningDoesNotKillAnInjuredDisciple(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple npc = person(h);
        try {
            npc.setHealth(1.0F);
            npc.hurt(p.damageSources().playerAttack(p), 1.0F);
            h.assertTrue(npc.isAlive() && npc.getHealth() == 1.0F, "light warning killed an injured disciple");
            h.assertFalse(SectWar.atWar(p), "light warning on an injured disciple started war");
            h.assertTrue(SectWar.data(h.getLevel()).warnings.containsKey(p.getUUID()), "no warning recorded");
        } finally {
            SectWar.data(h.getLevel()).wars.remove(p.getUUID());
            SectWar.data(h.getLevel()).warnings.remove(p.getUUID());
            npc.discard();
        }
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "guidance_atonement")
    public static void atonementScalesToWealthAndAcceptsWorkWithoutSilver(GameTestHelper h) {
        ServerPlayer p = player(h);
        SectDisciple npc = person(h);
        SectDisciple messenger = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        messenger.setMessengerFor(p.getUUID());
        try {
            h.assertTrue(SectWar.atonementCost(p) == 0, "poor player owes silver");
            p.getInventory().add(new ItemStack(ModItems.SILVER_TAEL.get(), 3));
            h.assertTrue(SectWar.atonementCost(p) == 2, "partial wealth price wrong");
            p.getInventory().items.forEach(s -> s.setCount(0));
            SectWar.declare(p, npc, "second");
            h.assertFalse(SectWar.pay(p, messenger, false), "free silver option pardoned player");
            h.assertFalse(SectWar.pay(p, messenger, true), "work pardoned before delivery");
            p.getInventory().add(new ItemStack(Items.COBBLESTONE, 16));
            h.assertTrue(SectWar.pay(p, messenger, true), "work did not end war");
            h.assertFalse(SectWar.atWar(p), "war persists after work");
            h.assertFalse(p.getInventory().items.stream().anyMatch(s -> s.is(Items.COBBLESTONE)), "repair stones not consumed");
        } finally {
            SectWar.data(h.getLevel()).wars.remove(p.getUUID());
            npc.discard();
        }
        h.succeed();
    }
}
