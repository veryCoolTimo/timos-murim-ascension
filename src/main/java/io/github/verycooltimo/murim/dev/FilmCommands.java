package io.github.verycooltimo.murim.dev;

import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;

/** Quiet film controls. Registered only below the permission-checked dev root. */
public final class FilmCommands {
    private FilmCommands() { }

    public static LiteralArgumentBuilder<CommandSourceStack> useBlockNode() {
        return Commands.literal("useblock").then(Commands.argument("pos", BlockPosArgument.blockPos())
                .executes(c -> useBlock(c.getSource().getPlayerOrException(), BlockPosArgument.getLoadedBlockPos(c, "pos")))
                // Shift + right click for the stand (pill box and crate lids): crouch only for this one use.
                .then(Commands.literal("sneak").executes(c -> {
                    ServerPlayer player = c.getSource().getPlayerOrException();
                    player.setShiftKeyDown(true);
                    try {
                        return useBlock(player, BlockPosArgument.getLoadedBlockPos(c, "pos"));
                    } finally {
                        player.setShiftKeyDown(false);
                    }
                })));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> useItemNode() {
        return Commands.literal("useitem").executes(c -> useItem(c.getSource().getPlayerOrException()));
    }
    public static LiteralArgumentBuilder<CommandSourceStack> closeNode() {
        return Commands.literal("closescreen").executes(c -> {
            var player = c.getSource().getPlayerOrException();
            player.closeContainer();
            io.github.verycooltimo.murim.network.ChannelSafe.send(player, new FilmScreenPayload());
            return 1;
        });
    }
    public static void face(ServerPlayer player, Vec3 target) {
        Vec3 d = target.subtract(player.getEyePosition());
        float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
        float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
        player.teleportTo(player.serverLevel(), player.getX(), player.getY(), player.getZ(), yaw, pitch);
    }
    public static int useBlock(ServerPlayer player, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        Vec3 toward = player.getEyePosition().subtract(center);
        Direction face = Direction.getNearest(toward.x, toward.y, toward.z);
        Vec3 hit = center.add(face.getStepX() * 0.5D, face.getStepY() * 0.5D, face.getStepZ() * 0.5D);
        face(player, hit);
        return player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(),
                InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, pos, false)).consumesAction() ? 1 : 0;
    }
    public static int useItem(ServerPlayer player) {
        return player.gameMode.useItem(player, player.serverLevel(), player.getMainHandItem(),
                InteractionHand.MAIN_HAND).consumesAction() ? 1 : 0;
    }
}
