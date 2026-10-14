package io.github.verycooltimo.murim.world.hua;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import java.util.Arrays;
import java.util.Locale;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * {@code /murim tree <courtyard|cliff|young> [pink|pale|red] [seed]} — grows a plum tree on the block
 * the player looks at (up to 64 blocks), or at {@code /murim tree <variant> at <pos> [palette] [seed]}.
 * The tree leans the way the player faces (cliff variant). For the author to place trees by hand
 * and for the capture stand. Permission 2, like the rest of {@code /murim}.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java;
 * Brigadier merges this {@code murim} literal with the others.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PlumTreeCommand {

    private PlumTreeCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        String[] variants = Arrays.stream(PlumTree.Variant.values()).map(PlumTree.Variant::getSerializedName).toArray(String[]::new);
        String[] palettes = Arrays.stream(PlumTree.Palette.values()).map(p -> p.name().toLowerCase(Locale.ROOT)).toArray(String[]::new);
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("tree")
                        .then(Commands.argument("variant", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(variants, b))
                                .executes(c -> grow(c, null, null, null))
                                .then(Commands.literal("at")
                                        .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                .executes(c -> grow(c, BlockPosArgument.getBlockPos(c, "pos"), null, null))
                                                .then(Commands.argument("palette", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(palettes, b))
                                                        .executes(c -> grow(c, BlockPosArgument.getBlockPos(c, "pos"),
                                                                StringArgumentType.getString(c, "palette"), null))
                                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                                .executes(c -> grow(c, BlockPosArgument.getBlockPos(c, "pos"),
                                                                        StringArgumentType.getString(c, "palette"),
                                                                        LongArgumentType.getLong(c, "seed")))))))
                                .then(Commands.argument("palette", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(palettes, b))
                                        .executes(c -> grow(c, null, StringArgumentType.getString(c, "palette"), null))
                                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                                .executes(c -> grow(c, null, StringArgumentType.getString(c, "palette"),
                                                        LongArgumentType.getLong(c, "seed"))))))));
    }

    private static int grow(CommandContext<CommandSourceStack> c, BlockPos at, String paletteName, Long seed) {
        CommandSourceStack source = c.getSource();
        PlumTree.Variant variant = Arrays.stream(PlumTree.Variant.values())
                .filter(v -> v.getSerializedName().equals(StringArgumentType.getString(c, "variant"))).findFirst().orElse(null);
        if (variant == null) {
            source.sendFailure(Component.translatable("command.murim.tree.unknown", StringArgumentType.getString(c, "variant")));
            return 0;
        }
        PlumTree.Palette palette = variant.defaultPalette();
        if (paletteName != null) {
            try {
                palette = PlumTree.Palette.valueOf(paletteName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                source.sendFailure(Component.translatable("command.murim.tree.unknown", paletteName));
                return 0;
            }
        }
        ServerLevel level = source.getLevel();
        Entity entity = source.getEntity();
        Direction lean = entity != null ? entity.getDirection() : null;
        BlockPos base = at;
        if (base == null) {
            if (entity == null) {
                source.sendFailure(Component.translatable("command.murim.tree.no_target"));
                return 0;
            }
            Vec3 eye = entity.getEyePosition();
            Vec3 end = eye.add(entity.getViewVector(1.0F).scale(64.0));
            BlockHitResult hit = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, entity));
            if (hit.getType() != HitResult.Type.BLOCK) {
                source.sendFailure(Component.translatable("command.murim.tree.no_target"));
                return 0;
            }
            base = hit.getBlockPos().above();
        }
        long s = seed != null ? seed : level.random.nextLong();
        if (!PlumTree.grow(level, base, variant, palette, s, lean, 3)) {
            source.sendFailure(Component.translatable("command.murim.tree.blocked"));
            return 0;
        }
        BlockPos where = base;
        source.sendSuccess(() -> Component.translatable("command.murim.tree.grown", variant.getSerializedName(),
                where.getX(), where.getY(), where.getZ(), String.valueOf(s)), false);
        return 1;
    }
}
