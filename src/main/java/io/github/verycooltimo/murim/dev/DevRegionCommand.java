package io.github.verycooltimo.murim.dev;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.HuaGround;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * DEV ONLY (never registered in a production build): move a hand-built piece between worlds.
 * <ul>
 *   <li>{@code /murim dev region save <name> <from> <to>} writes {@code <server dir>/devregions/<name>.nbt}
 *       (vanilla structure template; air kept, structure voids skipped);</li>
 *   <li>{@code /murim dev region load <name> <pos> [0|90|180|270]} places it with its origin at {@code pos},
 *       rotated about that origin, then runs a footing under the lowest block of every column (dry stone down to
 *       solid ground) so a piece lifted onto different terrain does not hang in the air.</li>
 * </ul>
 * The file can be edited between the two (for instance to drop terrain blocks from a capture).
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/templatesystem/StructureTemplate.java
 * (#fillFromWorld, #save, #load, #placeInWorld, #calculateRelativePosition).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DevRegionCommand {

    /** Footing depth limit under a piece. */
    static final int MAX_FOOTING = 24;

    private DevRegionCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        if (FMLEnvironment.production) {
            return;
        }
        event.getDispatcher().register(Commands.literal("murim")
                .then(Commands.literal("dev").requires(s -> s.hasPermission(4))
                        .then(Commands.literal("region")
                                .then(Commands.literal("save")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                                .executes(DevRegionCommand::save)))))
                                .then(Commands.literal("load")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .then(Commands.argument("pos", BlockPosArgument.blockPos())
                                                        .executes(c -> load(c, Rotation.NONE))
                                                        .then(Commands.argument("rotation", StringArgumentType.word())
                                                                .suggests((c, b) -> SharedSuggestionProvider.suggest(
                                                                        java.util.List.of("0", "90", "180", "270"), b))
                                                                .executes(c -> load(c, rotation(
                                                                        StringArgumentType.getString(c, "rotation")))))))))));
    }

    static Rotation rotation(String deg) {
        return switch (deg) {
            case "90" -> Rotation.CLOCKWISE_90;
            case "180" -> Rotation.CLOCKWISE_180;
            case "270", "-90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    private static Path file(ServerLevel level, String name) {
        return level.getServer().getServerDirectory().resolve("devregions").resolve(name.replaceAll("[^A-Za-z0-9_-]", "_") + ".nbt");
    }

    private static int save(CommandContext<CommandSourceStack> c) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        ServerLevel level = source.getLevel();
        String name = StringArgumentType.getString(c, "name");
        BlockPos a = BlockPosArgument.getBlockPos(c, "from");
        BlockPos b = BlockPosArgument.getBlockPos(c, "to");
        BoundingBox box = BoundingBox.fromCorners(a, b);
        for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
            for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                level.getChunk(cx, cz);
            }
        }
        StructureTemplate template = new StructureTemplate();
        template.fillFromWorld(level, new BlockPos(box.minX(), box.minY(), box.minZ()),
                new Vec3i(box.getXSpan(), box.getYSpan(), box.getZSpan()), false, Blocks.STRUCTURE_VOID);
        try {
            Path path = file(level, name);
            Files.createDirectories(path.getParent());
            NbtIo.writeCompressed(template.save(new CompoundTag()), path);
            source.sendSuccess(() -> Component.literal("Saved " + name + " (" + box.getXSpan() + "x" + box.getYSpan() + "x"
                    + box.getZSpan() + ", min corner " + box.minX() + " " + box.minY() + " " + box.minZ() + ") to " + path), false);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Save failed: " + e));
            return 0;
        }
    }

    private static int load(CommandContext<CommandSourceStack> c, Rotation rotation) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        CommandSourceStack source = c.getSource();
        ServerLevel level = source.getLevel();
        String name = StringArgumentType.getString(c, "name");
        BlockPos at = BlockPosArgument.getBlockPos(c, "pos");
        try {
            CompoundTag tag = NbtIo.readCompressed(file(level, name), NbtAccounter.unlimitedHeap());
            StructureTemplate template = new StructureTemplate();
            template.load(level.holderLookup(Registries.BLOCK), tag);
            StructurePlaceSettings settings = new StructurePlaceSettings().setRotation(rotation).setIgnoreEntities(true);
            BoundingBox box = template.getBoundingBox(settings, at);
            for (int cx = box.minX() >> 4; cx <= box.maxX() >> 4; cx++) {
                for (int cz = box.minZ() >> 4; cz <= box.maxZ() >> 4; cz++) {
                    level.getChunk(cx, cz);
                }
            }
            template.placeInWorld(level, at, at, settings, level.getRandom(), 2);
            int footing = footing(level, tag, settings, at);
            source.sendSuccess(() -> Component.literal("Placed " + name + " at " + at.toShortString() + " rotation " + rotation
                    + ", box " + box + ", footing blocks " + footing), true);
            return 1;
        } catch (IOException e) {
            source.sendFailure(Component.literal("Load failed: " + e));
            return 0;
        }
    }

    /** Dry stone down from under the lowest non-air block of each column until the ground is solid. */
    private static int footing(ServerLevel level, CompoundTag tag, StructurePlaceSettings settings, BlockPos at) {
        ListTag palette = tag.getList("palette", 10);
        Map<Long, Integer> lowest = new HashMap<>();
        for (var t : tag.getList("blocks", 10)) {
            CompoundTag block = (CompoundTag) t;
            if (palette.getCompound(block.getInt("state")).getString("Name").equals("minecraft:air")) {
                continue;
            }
            ListTag p = block.getList("pos", 3);
            BlockPos world = StructureTemplate.calculateRelativePosition(settings, new BlockPos(p.getInt(0), p.getInt(1), p.getInt(2))).offset(at);
            lowest.merge(new BlockPos(world.getX(), 0, world.getZ()).asLong(), world.getY(), Math::min);
        }
        BlockState stone = HuaGround.HUA_DRY_STONE.get().defaultBlockState();
        int n = 0;
        for (var e : lowest.entrySet()) {
            BlockPos column = BlockPos.of(e.getKey());
            if (e.getValue() > at.getY() + 14) {
                continue; // eaves and roof overhangs: no footing under open air (it filled the gate's passage, 13.10)
            }
            for (int y = e.getValue() - 1; y >= e.getValue() - MAX_FOOTING; y--) {
                BlockPos pos = new BlockPos(column.getX(), y, column.getZ());
                BlockState s = level.getBlockState(pos);
                if (s.isSolid() && !s.is(net.minecraft.tags.BlockTags.LEAVES)) {
                    break;
                }
                level.setBlock(pos, stone, 2);
                n++;
            }
        }
        return n;
    }
}
