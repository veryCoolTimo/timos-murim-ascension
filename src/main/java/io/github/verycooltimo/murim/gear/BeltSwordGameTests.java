package io.github.verycooltimo.murim.gear;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the belt sword choice (gear/BeltSword): first sword in slot order, the sword held last wins, a sword
 * in either hand empties the belt, no sword — no belt; the server state remembers the held sword and the belt sent.
 * API: reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java#makeMockPlayer,
 * the server player as in SectGameTests#fakePlayer (embedded connection without our channels, not in the level).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BeltSwordGameTests {

    private static final String FLOOR = "small_floor";

    private BeltSwordGameTests() {
    }

    @GameTest(template = FLOOR, batch = "gear")
    public static void beltPicksFirstThenLastHeld(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().selected = 0;
        player.getInventory().setItem(1, new ItemStack(Items.BREAD));
        helper.assertTrue(BeltSword.pick(player, null).isEmpty(), "no sword, no belt");
        player.getInventory().setItem(20, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        helper.assertTrue(BeltSword.pick(player, ModItems.HUASHAN_SWORD.get()).isEmpty(),
                "a sword in the main inventory only: no belt (hotbar only)");
        player.getInventory().setItem(4, new ItemStack(ModItems.WOODEN_SWORD.get()));
        helper.assertTrue(BeltSword.pick(player, ModItems.HUASHAN_SWORD.get()).is(ModItems.WOODEN_SWORD.get()),
                "the main inventory sword is ignored even if held last");
        player.getInventory().setItem(7, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        helper.assertTrue(BeltSword.pick(player, null).is(ModItems.WOODEN_SWORD.get()), "first hotbar sword");
        helper.assertTrue(BeltSword.pick(player, ModItems.HUASHAN_SWORD.get()).is(ModItems.HUASHAN_SWORD.get()),
                "the sword held last wins");
        helper.assertTrue(BeltSword.pick(player, Items.IRON_SWORD).is(ModItems.WOODEN_SWORD.get()),
                "last held not carried: first sword");
        helper.succeed();
    }

    @GameTest(template = FLOOR, batch = "gear")
    public static void swordInHandEmptiesBelt(GameTestHelper helper) {
        Player player = helper.makeMockPlayer(GameType.SURVIVAL);
        player.getInventory().setItem(4, new ItemStack(ModItems.WOODEN_SWORD.get()));
        player.getInventory().setItem(5, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        player.getInventory().selected = 4;
        helper.assertTrue(BeltSword.pick(player, null).isEmpty(), "main hand sword: belt empty");
        player.getInventory().selected = 0;
        helper.assertTrue(!BeltSword.pick(player, null).isEmpty(), "sheathed again");
        player.setItemInHand(InteractionHand.OFF_HAND, new ItemStack(Items.IRON_SWORD));
        helper.assertTrue(BeltSword.pick(player, null).isEmpty(), "offhand sword: belt empty");
        helper.succeed();
    }

    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "belt-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }

    @GameTest(template = FLOOR, batch = "gear")
    public static void serverRemembersHeldAndSent(GameTestHelper helper) {
        ServerPlayer player = fakePlayer(helper);
        player.getInventory().setItem(2, new ItemStack(ModItems.WOODEN_SWORD.get()));
        player.getInventory().setItem(6, new ItemStack(ModItems.HUASHAN_SWORD.get()));
        player.getInventory().selected = 6;
        BeltSword.update(player);
        BeltSword.State state = player.getData(ModAttachments.BELT_SWORD);
        helper.assertTrue(state.lastHeld() == ModItems.HUASHAN_SWORD.get(), "held sword remembered");
        helper.assertTrue(state.sent().isEmpty(), "drawn: belt empty");
        player.getInventory().selected = 0;
        BeltSword.update(player);
        helper.assertTrue(state.sent().is(ModItems.HUASHAN_SWORD.get()), "sheathed: the sword held last on the belt");
        helper.succeed();
    }
}
