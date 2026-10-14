package io.github.verycooltimo.murim.world;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BlockSwapGameTests {
    private BlockSwapGameTests() {
    }

    @GameTest(template = "sect_yard", batch = "block_swap")
    public static void shiftRightClickSwapsKeepingFacing(GameTestHelper h) {
        BlockPos rel = new BlockPos(3, 3, 3);
        h.setBlock(rel, Blocks.OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        // A detached server player (as in DuelGameTests): makeMockServerPlayerInLevel runs the login syncs, whose
        // payloads the mock connection cannot carry.
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "swap-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        p.setGameMode(GameType.CREATIVE);
        p.setShiftKeyDown(true);
        ItemStack stack = new ItemStack(Items.STONE_BRICK_STAIRS);
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = h.absolutePos(rel);
        p.gameMode.useItemOn(p, h.getLevel(), stack, InteractionHand.MAIN_HAND,
                new BlockHitResult(Vec3.atCenterOf(abs).add(0, .5, 0), Direction.UP, abs, false));
        var state = h.getBlockState(rel);
        h.assertTrue(state.is(Blocks.STONE_BRICK_STAIRS), "not swapped: " + state);
        h.assertTrue(state.getValue(StairBlock.FACING) == Direction.EAST, "facing lost: " + state);
        h.assertBlockPresent(Blocks.AIR, rel.above());
        h.succeed();
    }
}
