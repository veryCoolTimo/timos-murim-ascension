package io.github.verycooltimo.murim.training;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of body training on a real server: squats from the crouch key temper the body, a meal takes fatigue away,
 * the training stone carried uphill counts blocks and slows the carrier, the body level raises max health. The fake
 * player is not ticked by the level, so each test calls {@link TrainingService#tick} itself, as the player tick would.
 *
 * <p>API: reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#onEachTick/runAfterDelay
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
        return p;
    }

    /** Squats: two taps start the set, each tap on the beat is a rep; the body gains points and the set ends idle. */
    @GameTest(template = FLOOR, timeoutTicks = 400, batch = "training")
    public static void squatsTemperTheBody(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        int[] t = {0};
        helper.onEachTick(() -> {
            int i = t[0]++;
            // Taps every 24 ticks (the squat beat), crouch held 4 ticks; a 12-tick double tap to start.
            int phase = i < 12 ? i : (i - 12) % Exercise.SQUAT.beat();
            boolean crouch = i < 300 && (i < 12 ? i % 12 < 4 : phase < 4);
            p.setShiftKeyDown(crouch);
            p.setOnGround(true);
            TrainingService.tick(p);
        });
        helper.runAfterDelay(200, () -> {
            TrainingSession s = p.getData(TrainingRegistry.SESSION);
            helper.assertTrue(s.set().exercise() == Exercise.SQUAT, "not squatting: " + s.set().exercise());
            helper.assertTrue(s.set().reps() >= 6, "reps " + s.set().reps());
            helper.assertTrue(s.set().good() >= s.set().reps() - 1, "on the beat " + s.set().good() + "/" + s.set().reps());
        });
        helper.runAfterDelay(390, () -> {
            BodyState b = p.getData(TrainingRegistry.BODY);
            helper.assertTrue(b.points() > 6.0D, "points " + b.points());
            helper.assertTrue(b.fatigue() > 0.0D, "no fatigue");
            helper.assertTrue(!p.getData(TrainingRegistry.SESSION).set().active(), "the set did not end when idle");
            helper.succeed();
        });
    }

    /** With blocks in hand the same crouch rhythm is just sneaking. */
    @GameTest(template = FLOOR, timeoutTicks = 200, batch = "training")
    public static void blocksInHandAreNotTraining(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        p.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.COBBLESTONE, 32));
        int[] t = {0};
        helper.onEachTick(() -> {
            p.setShiftKeyDown(t[0]++ % 12 < 4);
            p.setOnGround(true);
            TrainingService.tick(p);
        });
        helper.runAfterDelay(150, () -> {
            helper.assertTrue(!p.getData(TrainingRegistry.SESSION).set().active(), "a builder at an edge started a set");
            helper.assertTrue(p.getData(TrainingRegistry.BODY).points() == 0.0D, "points without training");
            helper.succeed();
        });
    }

    /** A meal takes fatigue away (here away from the sect: the plain rate). */
    @GameTest(template = FLOOR, timeoutTicks = 20, batch = "training")
    public static void mealRecovers(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        p.setData(TrainingRegistry.BODY, BodyState.NONE.withFatigue(0.8D));
        ItemStack bread = new ItemStack(Items.BREAD);
        NeoForge.EVENT_BUS.post(new LivingEntityUseItemEvent.Finish(p, bread, 0, ItemStack.EMPTY));
        double f = p.getData(TrainingRegistry.BODY).fatigue();
        helper.assertTrue(Math.abs(f - BodyRules.afterMeal(0.8D, 5, false)) < 1e-6, "fatigue after bread " + f);
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
        helper.assertTrue(pts > 3.0D, "points " + pts);
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
