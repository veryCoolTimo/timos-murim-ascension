package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.PillEffects;
import io.github.verycooltimo.murim.cultivation.PillKind;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerWakeUpEvent;
import net.neoforged.neoforge.common.damagesource.DamageContainer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import java.util.UUID;

/** API: local 1.21.1 GameTestHelper, BlockState use methods, DamageContainer and wake event constructors.
 * Sleep readiness is controlled in the fixture so both wake-event branches can be exercised deterministically.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class WineGameTests {
    private WineGameTests() { }
    private static ServerPlayer player(GameTestHelper helper, boolean rested) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(UUID.randomUUID(), "box-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation()) {
            @Override public boolean isSleepingLongEnough() { return rested; }
        };
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        Vec3 at = helper.absoluteVec(new Vec3(2.5D, 3.0D, 2.5D));
        p.setPos(at.x, at.y, at.z);
        return p;
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void eightCupsStackThenEmptyJarDoesNothing(GameTestHelper h) {
        var player = player(h, false);
        var pos = h.absolutePos(new BlockPos(1, 3, 1));
        var hit = new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false);
        h.getLevel().setBlock(pos, IntWorkshop.WINE_JAR.get().defaultBlockState(), 3);
        h.assertTrue(h.getLevel().getBlockState(pos).getValue(WineBlock.CUPS) == 8, "jar must start full");
        for (int i = 0; i < 8; i++) {
            var state = h.getLevel().getBlockState(pos);
            if (i % 2 == 0) state.useWithoutItem(h.getLevel(), player, hit);
            else state.useItemOn(new ItemStack(Items.STICK), h.getLevel(), player, InteractionHand.MAIN_HAND, hit);
            h.assertTrue(h.getLevel().getBlockState(pos).getValue(WineBlock.CUPS) == 7-i, "wrong cups");
            var effect = player.getEffect(IntWorkshop.TIPSY);
            h.assertTrue(effect != null && effect.getAmplifier() == Math.min(i, 2)
                    && effect.getDuration() == Math.min(6000, (i+1)*1800), "wrong wine stack");
            h.assertTrue(!effect.isVisible() && effect.showIcon(), "wine particles/icon flags wrong");
        }
        // API: NbtUtils#writeBlockState/readBlockState and Registry#asLookup in local Minecraft sources.
        var saved = net.minecraft.nbt.NbtUtils.writeBlockState(h.getLevel().getBlockState(pos));
        var restored = net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), saved);
        h.assertTrue(restored.getValue(WineBlock.CUPS) == 0, "saved jar lost its cups");
        saved.getCompound("Properties").remove("cups");
        var legacy = net.minecraft.nbt.NbtUtils.readBlockState(net.minecraft.core.registries.BuiltInRegistries.BLOCK.asLookup(), saved);
        h.assertTrue(legacy.getValue(WineBlock.CUPS) == 8, "old cave jar must load full");
        WineService.cure(player);
        h.getLevel().getBlockState(pos).useWithoutItem(h.getLevel(), player, hit);
        h.assertTrue(!player.hasEffect(IntWorkshop.TIPSY), "empty jar granted wine");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void sneakingDoesNotDrink(GameTestHelper h) {
        var player = player(h, false);
        player.setShiftKeyDown(true);
        var pos = h.absolutePos(new BlockPos(1, 3, 1));
        var state = IntWorkshop.WINE_JAR.get().defaultBlockState();
        h.getLevel().setBlock(pos, state, 3);
        state.useWithoutItem(h.getLevel(), player, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        h.assertTrue(!player.hasEffect(IntWorkshop.TIPSY) && h.getLevel().getBlockState(pos).getValue(WineBlock.CUPS) == 8,
                "sneaking drank wine");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void completedSleepAtThreeGivesCurableHangover(GameTestHelper h) {
        var player = player(h, true);
        for (int i = 0; i < 3; i++) WineService.drink(player);
        WineService.wake(new PlayerWakeUpEvent(player, false, false));
        h.assertTrue(player.hasEffect(IntWorkshop.HANGOVER) && !player.hasEffect(IntWorkshop.TIPSY), "no hangover");
        h.assertTrue(player.getEffect(MobEffects.MOVEMENT_SLOWDOWN).getAmplifier() == 0
                && player.getEffect(MobEffects.DIG_SLOWDOWN).getDuration() == 2400, "wrong hangover penalties");
        for (var effect : java.util.List.<net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect>>of(IntWorkshop.HANGOVER, MobEffects.MOVEMENT_SLOWDOWN, MobEffects.DIG_SLOWDOWN)) {
            h.assertTrue(!player.getEffect(effect).isVisible() && player.getEffect(effect).showIcon(),
                    "hangover particles/icon flags wrong");
        }
        WineService.drink(player);
        PillEffects.use(player, PillKind.PLUM_BLOSSOM);
        h.assertTrue(!player.hasEffect(IntWorkshop.TIPSY) && !player.hasEffect(IntWorkshop.HANGOVER)
                && !player.hasEffect(MobEffects.DIG_SLOWDOWN) && !player.hasEffect(MobEffects.MOVEMENT_SLOWDOWN), "pill did not cure wine");
        var early = player(h, false);
        for (int i = 0; i < 3; i++) WineService.drink(early);
        WineService.wake(new PlayerWakeUpEvent(early, true, true));
        h.assertTrue(!early.hasEffect(IntWorkshop.HANGOVER), "interrupted sleep caused hangover");
        var mild = player(h, true);
        WineService.drink(mild);
        WineService.wake(new PlayerWakeUpEvent(mild, false, false));
        h.assertTrue(!mild.hasEffect(IntWorkshop.HANGOVER), "level I caused hangover");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void damageIncludesBothCourageAndVulnerability(GameTestHelper h) {
        var attacker = player(h, false);
        var victim = player(h, false);
        for (int i = 0; i < 3; i++) WineService.drink(attacker);
        WineService.drink(victim);
        var event = new LivingIncomingDamageEvent(victim,
                new DamageContainer(h.getLevel().damageSources().playerAttack(attacker), 10));
        WineService.damage(event);
        h.assertTrue(Math.abs(event.getAmount() - 10*1.15F*1.05F) < .0001F, "wine damage factors missing");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "decor")
    public static void staggerUsesVelocityAndRejectsWallsFluidsAndDrops(GameTestHelper h) {
        var player = player(h, false);
        var floor = h.absolutePos(new BlockPos(2, 2, 2));
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) {
            h.getLevel().setBlock(floor.offset(x, 0, z), Blocks.STONE.defaultBlockState(), 3);
            h.getLevel().setBlock(floor.offset(x, 1, z), Blocks.AIR.defaultBlockState(), 3);
            h.getLevel().setBlock(floor.offset(x, 2, z), Blocks.AIR.defaultBlockState(), 3);
        }
        player.setPos(floor.getX()+.69, floor.getY()+1, floor.getZ()+.5);
        Vec3 drift = new Vec3(.06, 0, 0);
        h.assertTrue(WineService.safeDrift(player, drift), "dry flat floor rejected");
        player.setDeltaMovement(0, 0, .04);
        var before = player.position();
        h.assertTrue(WineService.applyStagger(player, drift), "safe velocity impulse rejected");
        h.assertTrue(player.position().equals(before), "impulse teleported the player");
        h.assertTrue(Math.abs(player.getDeltaMovement().x - .06) < .000001
                && Math.abs(player.getDeltaMovement().z - .04) < .000001 && player.hurtMarked,
                "impulse failed to add and synchronize velocity");
        player.setDeltaMovement(0, 0, .04);
        WineService.drink(player);
        WineService.drink(player);
        player.setOnGround(true);
        player.setYRot(0);
        player.tickCount = 32;
        WineService.tick(new net.neoforged.neoforge.event.tick.PlayerTickEvent.Post(player));
        h.assertTrue(player.position().equals(before) && player.getDeltaMovement().x >= .05
                && player.getDeltaMovement().x <= .07, "scheduled stagger did not use velocity");
        player.setDeltaMovement(0, 0, .04);
        var edge = floor.east();
        h.getLevel().setBlock(edge.above(), Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(!WineService.safeDrift(player, drift), "drift entered wall");
        h.getLevel().setBlock(edge.above(), Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(edge, Blocks.LAVA.defaultBlockState(), 3);
        h.assertTrue(!WineService.safeDrift(player, drift), "drift entered lava");
        h.getLevel().setBlock(edge, Blocks.STONE.defaultBlockState(), 3);
        h.getLevel().setBlock(floor.above(), Blocks.WATER.defaultBlockState(), 3);
        h.assertTrue(!WineService.applyStagger(player, drift), "stagger allowed in water");
        h.getLevel().setBlock(floor.above(), Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(edge, Blocks.AIR.defaultBlockState(), 3);
        h.getLevel().setBlock(edge.below(), Blocks.STONE.defaultBlockState(), 3);
        h.assertTrue(!WineService.applyStagger(player, drift), "stagger crossed one-block drop");
        h.getLevel().setBlock(edge.below(), Blocks.AIR.defaultBlockState(), 3);
        h.assertTrue(!WineService.safeDrift(player, drift), "drift crossed two-block drop");
        h.assertTrue(!WineService.safeDrift(player, new Vec3(.072, 0, 0)), "drift exceeded speed limit");
        h.succeed();
    }
}
