package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the body on a real server: ordinary play (vanilla statistics) tempers it, the first look credits a
 * capped initial grant once, Shift starts nothing, the training stone carried uphill counts blocks and slows the carrier,
 * the body level raises max health. The fake player is not ticked by the level, so each test calls
 * {@link TrainingService#tick} / {@link TrainingService#look} itself, as the player tick would.
 *
 * <p>API: reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#onEachTick/runAfterDelay,
 * reference/minecraft-src/net/minecraft/world/entity/player/Player.java#awardStat(ResourceLocation, int)
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class TrainingGameTests {

    private static final String FLOOR = "small_floor";

    private TrainingGameTests() {
    }

    /** A player without login (agent-log 04.10: a mock login sends our payloads to a test connection and fails). */
    static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "training-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 2.0D, 2.5D));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    /** The first tick of a fresh player: seeded, nothing owed, nothing granted. */
    private static void seedFresh(ServerPlayer p) {
        TrainingService.tick(p);
    }

    /**
     * Shift never starts anything (author 24.10): the old squat rhythm, a held crouch and the push-up look (pitch down)
     * for 300 ticks with empty hands give no points, no drill and no session.
     */
    @GameTest(template = FLOOR, timeoutTicks = 400, batch = "training")
    public static void shiftStartsNothing(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        int[] t = {0};
        helper.onEachTick(() -> {
            int i = t[0]++;
            // Double tap, then a tap every 24 ticks; later a long hold, with the view on the ground.
            boolean crouch = i < 150 ? (i < 12 ? i % 12 < 4 : (i - 12) % 24 < 4) : i < 300;
            p.setShiftKeyDown(crouch);
            p.setXRot(i < 220 ? 0.0F : 70.0F);
            p.setOnGround(true);
            TrainingService.tick(p);
        });
        helper.runAfterDelay(320, () -> {
            helper.assertTrue(p.getData(TrainingRegistry.SESSION).current() == null,
                    "a drill started: " + p.getData(TrainingRegistry.SESSION).current());
            helper.assertTrue(p.getData(TrainingRegistry.BODY).points() == 0.0D, "points from Shift: " + p.getData(TrainingRegistry.BODY).points());
            helper.succeed();
        });
    }

    /** Legs: kilometres walked and sprinted (vanilla statistics) become points, sprinting pays more than walking. */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void walkingAndSprintingTemperTheBody(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        seedFresh(p);
        p.awardStat(Stats.WALK_ONE_CM, 100_000);
        TrainingService.look(p);
        double walked = p.getData(TrainingRegistry.BODY).points();
        helper.assertTrue(Math.abs(walked - TrainingBalance.WALK_PER_KM) < 1e-6, "1 km walked gave " + walked);
        p.awardStat(Stats.SPRINT_ONE_CM, 100_000);
        TrainingService.look(p);
        double both = p.getData(TrainingRegistry.BODY).points();
        helper.assertTrue(both - walked > walked, "a sprinted km paid " + (both - walked) + " against " + walked);
        // The same statistics read again change nothing.
        TrainingService.look(p);
        helper.assertTrue(p.getData(TrainingRegistry.BODY).points() == both, "the same reading paid twice");
        helper.succeed();
    }

    /** Labour: stone with a pickaxe, ores and felled logs are told apart; an elytra flight pays nothing. */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void miningAndChoppingTemperTheBody(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        seedFresh(p);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.STONE), 100);
        TrainingService.look(p);
        double stone = p.getData(TrainingRegistry.BODY).points();
        helper.assertTrue(Math.abs(stone - 100 * TrainingBalance.MINE_STONE) < 1e-6, "100 stone gave " + stone);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.DIAMOND_ORE), 10);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.OAK_LOG), 10);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.DIRT), 10);
        TrainingService.look(p);
        double more = p.getData(TrainingRegistry.BODY).points() - stone;
        double expected = 10 * TrainingBalance.MINE_ORE + 10 * TrainingBalance.MINE_LOG + 10 * TrainingBalance.MINE_DIG;
        helper.assertTrue(Math.abs(more - expected) < 1e-6, "ore + log + dirt gave " + more + ", expected " + expected);
        double before = p.getData(TrainingRegistry.BODY).points();
        p.awardStat(Stats.AVIATE_ONE_CM, 5_000_000);
        p.awardStat(Stats.BOAT_ONE_CM, 5_000_000);
        TrainingService.look(p);
        helper.assertTrue(p.getData(TrainingRegistry.BODY).points() == before, "flying or rowing tempered the legs");
        helper.succeed();
    }

    /** A held jump key farms 100 000 jumps; the jump source stays under its tiny daily limit. */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void afkJumpingIsCapped(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        seedFresh(p);
        p.awardStat(Stats.JUMP, 100_000);
        TrainingService.look(p);
        double pts = p.getData(TrainingRegistry.BODY).points();
        helper.assertTrue(pts > 2.0D && pts <= BodySource.JUMP.dayMax() + 1e-9, "100k jumps gave " + pts);
        helper.succeed();
    }

    /** Enough varied work in one day crosses stage 1: the effects are re-applied (max health grows). */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void aVariedDayRaisesTheStageAndHealth(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        seedFresh(p);
        double base = p.getMaxHealth();
        p.awardStat(Stats.SPRINT_ONE_CM, 300_000);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.STONE), 1000);
        p.awardStat(Stats.MOB_KILLS, 20);
        TrainingService.look(p);
        helper.assertTrue(TrainingService.level(p) >= 1, "stage " + TrainingService.level(p) + ", points "
                + p.getData(TrainingRegistry.BODY).points());
        helper.assertTrue(Math.abs(p.getMaxHealth() - base - BodyRules.health(TrainingService.level(p))) < 1e-6,
                "max health " + p.getMaxHealth());
        helper.succeed();
    }

    /** Creative play tempers nothing, but moves the baseline: the same statistics are not paid for later in survival. */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void creativeTempersNothing(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        seedFresh(p);
        p.setGameMode(GameType.CREATIVE);
        p.awardStat(Stats.SPRINT_ONE_CM, 300_000);
        TrainingService.look(p);
        p.setGameMode(GameType.SURVIVAL);
        TrainingService.look(p);
        helper.assertTrue(p.getData(TrainingRegistry.BODY).points() == 0.0D, "creative walking paid " + p.getData(TrainingRegistry.BODY).points());
        helper.succeed();
    }

    /**
     * Existing world, player in diamond armour: on the first tick the statistics he already has are credited, capped at the
     * middle of the ladder (stage 5), once. A second look, and a second first tick, add nothing more than the day's work.
     */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void initialGrantIsCappedAndHappensOnce(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        p.awardStat(Stats.WALK_ONE_CM, 4_000_000);
        p.awardStat(Stats.SPRINT_ONE_CM, 4_500_000);
        p.awardStat(Stats.JUMP, 8000);
        p.awardStat(Stats.MOB_KILLS, 500);
        p.awardStat(Stats.DAMAGE_DEALT, 150_000);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.STONE), 9000);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.DEEPSLATE_DIAMOND_ORE), 300);
        p.awardStat(Stats.BLOCK_MINED.get(Blocks.SPRUCE_LOG), 600);
        helper.assertTrue(!p.getData(TrainingRegistry.BODY).seeded(), "seeded before the first tick");
        TrainingService.tick(p);
        BodyState first = p.getData(TrainingRegistry.BODY);
        helper.assertTrue(first.seeded(), "not seeded by the first tick");
        helper.assertTrue(first.level() == TrainingBalance.INITIAL_CAP_LEVEL, "diamond-armour player landed on stage " + first.level());
        helper.assertTrue(first.points() <= BodyRules.threshold(TrainingBalance.INITIAL_CAP_LEVEL) + 1e-9, "above the cap: " + first.points());
        TrainingService.look(p);
        TrainingService.look(p);
        helper.assertTrue(p.getData(TrainingRegistry.BODY).points() == first.points(), "the history was paid again");
        helper.succeed();
    }

    /** A brand-new player: nothing in the statistics, nothing granted; and a squat-era save keeps its points. */
    @GameTest(template = FLOOR, timeoutTicks = 40, batch = "training")
    public static void initialGrantNeverLowersAndStartsAtZero(GameTestHelper helper) {
        ServerPlayer fresh = fakePlayer(helper);
        TrainingService.tick(fresh);
        helper.assertTrue(fresh.getData(TrainingRegistry.BODY).seeded() && fresh.getData(TrainingRegistry.BODY).points() == 0.0D,
                "a new player got " + fresh.getData(TrainingRegistry.BODY).points());
        ServerPlayer old = fakePlayer(helper);
        // A world saved before 25.10: points from the squat ladder (stage 4), format 1, never seeded.
        old.setData(TrainingRegistry.BODY, BodyState.NONE.withPoints(BodyRules.threshold(4)));
        old.awardStat(Stats.WALK_ONE_CM, 200_000);
        TrainingService.tick(old);
        helper.assertTrue(old.getData(TrainingRegistry.BODY).points() >= BodyRules.threshold(4), "old points lowered to "
                + old.getData(TrainingRegistry.BODY).points());
        helper.assertTrue(TrainingService.level(old) == 4, "stage " + TrainingService.level(old));
        helper.succeed();
    }

    /** The training stone carried up: each new block of height counts once, the carrier is slowed, put down — not. */
    @GameTest(template = FLOOR, timeoutTicks = 60, batch = "training")
    public static void carryStoneUphill(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(TrainingRegistry.TRAINING_STONE_ITEM.get()));
        double y0 = p.getY();
        TrainingService.tick(p);
        helper.assertTrue(p.getData(TrainingRegistry.SESSION).current() == Exercise.CARRY_STONE, "not carrying");
        helper.assertTrue(p.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(TrainingService.CARRY_ID) != null, "not slowed");
        // Up three blocks, down one, up one again: three new blocks, not four.
        for (double dy : new double[] {1, 2, 3, 2, 3}) {
            p.setPos(p.getX(), y0 + dy, p.getZ());
            p.setOnGround(true);
            TrainingService.tick(p);
        }
        TrainingSession s = p.getData(TrainingRegistry.SESSION);
        helper.assertTrue(s.carried == 3, "blocks " + s.carried);
        double pts = p.getData(TrainingRegistry.BODY).points();
        helper.assertTrue(Math.abs(pts - 3 * TrainingBalance.CARRY_EACH) < 1e-6, "points " + pts);
        p.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        TrainingService.tick(p);
        helper.assertTrue(p.getAttribute(Attributes.MOVEMENT_SPEED).getModifier(TrainingService.CARRY_ID) == null, "still slowed");
        helper.assertTrue(p.getData(TrainingRegistry.SESSION).current() == null, "still carrying");
        helper.succeed();
    }

    /** Body level raises max health by HEALTH_PER_LEVEL per level; reset takes it away. */
    @GameTest(template = FLOOR, timeoutTicks = 20, batch = "training")
    public static void bodyLevelRaisesHealth(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        double base = p.getMaxHealth();
        TrainingService.setPoints(p, BodyRules.threshold(5));
        helper.assertTrue(TrainingService.level(p) == 5, "level " + TrainingService.level(p));
        helper.assertTrue(Math.abs(p.getMaxHealth() - base - BodyRules.health(5)) < 1e-6, "max health " + p.getMaxHealth());
        helper.assertTrue(p.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE) >= BodyRules.knockback(5) - 1e-6, "knockback");
        TrainingService.setPoints(p, 0.0D);
        helper.assertTrue(Math.abs(p.getMaxHealth() - base) < 1e-6, "health not reset");
        helper.succeed();
    }
}
