package io.github.verycooltimo.murim.world.hua;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntWorkshop;
import io.github.verycooltimo.murim.decor.PillBoxBlockEntity;
import io.github.verycooltimo.murim.registry.ModItems;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The sparse pieces next to the baked sect ({@link HuaBakedExtra}, docs/adr/0021-baked-trail-and-foot-lake.md): the author's climb with the
 * foot lake and the dorm staircase. Every cell is stamped at a site of each of the four rotations (foot height 66, the bake's is 67, so the
 * height shift is exercised too) and the signature blocks, sampled from the source worlds by {@code tools/hua_bake/bake_extra.py} (a few of
 * every kind of block, among them the lake water, a step, a spruce plank, and random ones), must stand at their turned positions. A sparse
 * stamp must also leave everything it does not list alone: a marker block next to the lake survives.
 *
 * <p>API: reference/minecraft-src/net/minecraft/commands/arguments/blocks/BlockStateParser.java#parseForBlock,
 * reference/minecraft-src/net/minecraft/world/level/block/state/BlockState.java#rotate.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaBakedExtraGameTests {

    private record Signature(String name, String group, int u, int y, int v, BlockState state) {
    }

    private static final int PRELOAD_TICKS = 300;

    private HuaBakedExtraGameTests() {
    }

    private static List<Signature> signatures() throws Exception {
        List<Signature> out = new ArrayList<>();
        try (Reader r = new InputStreamReader(HuaBakedExtraGameTests.class.getResourceAsStream(
                "/data/murim/hua_baked/extra/signatures.json"), StandardCharsets.UTF_8)) {
            JsonArray arr = new Gson().fromJson(r, JsonArray.class);
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                        new StringReader(o.get("state").getAsString()), false).blockState();
                out.add(new Signature(o.get("name").getAsString(), o.get("group").getAsString(), o.get("u").getAsInt(),
                        o.get("y").getAsInt(), o.get("v").getAsInt(), state));
            }
        }
        return out;
    }

    private static boolean inCell(HuaBaked.Group g, HuaBaked.Cell c, Signature s) {
        return g.id().equals(s.group()) && s.u() >= c.u() && s.u() < c.u() + 16 && s.v() >= c.v() && s.v() < c.v() + 16
                && s.y() >= c.y() && s.y() < c.y() + c.h();
    }

    @GameTest(template = "sect_yard", batch = "hua_baked_extra", timeoutTicks = 3000)
    public static void climbLakeAndStairLandAtEveryRotation(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        HuaBaked.Index index = HuaBakedExtra.index(level.getServer());
        helper.assertTrue(index != null, "extra index missing from the mod resources");
        helper.assertTrue(index.groups().stream().anyMatch(g -> g.id().equals("trail") && g.cells().size() > 20), "trail group missing");
        helper.assertTrue(index.groups().stream().anyMatch(g -> g.id().equals("dorm_stair") && !g.cells().isEmpty()), "dorm_stair group missing");
        helper.assertTrue(index.groups().stream().anyMatch(g -> g.id().equals("cave") && !g.cells().isEmpty()), "cave group missing");
        List<Signature> sigs = signatures();
        helper.assertTrue(sigs.stream().anyMatch(s -> s.state().is(Blocks.WATER)), "no lake water among the signatures");
        helper.assertTrue(sigs.stream().anyMatch(s -> s.state().is(Blocks.SPRUCE_PLANKS)), "no spruce plank among the signatures");
        helper.assertTrue(sigs.stream().anyMatch(s -> s.state().is(IntWorkshop.PILL_CRATE.get()))
                && sigs.stream().anyMatch(s -> s.state().is(IntWorkshop.WINE_JAR.get())), "no crate or wine jar among the cave signatures");
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cells = HuaBaked.cells(index);
        int tick = PRELOAD_TICKS;
        // the templates are read off the server thread: reading them on it delays the time-based NPC tests of the run
        net.minecraft.server.MinecraftServer server = level.getServer();
        net.minecraft.Util.backgroundExecutor().execute(() -> {
            for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : HuaBaked.cells(index)) {
                HuaBaked.templateFor(server, e.getValue().file());
            }
        });
        for (int rot = 0; rot < 4; rot++) {
            int r = rot;
            // x apart from the sect test's sites (its slots are +3000 + 600 * slot at z + 3000)
            MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + r * 600, corner.getZ() + 9000, 66, r, 1L, true);
            // a marker above the first lake cell, inside its column but over its height: a sparse stamp must not clear columns
            HuaBaked.Group trail = index.groups().stream().filter(g -> g.id().equals("trail")).findFirst().orElseThrow();
            HuaBaked.Cell first = trail.cells().get(0);
            int[] md = site.rotateDir(first.u() + 8, first.v() + 8);
            BlockPos marker = new BlockPos(site.centerX() + md[0], first.y() + first.h() + 3 + HuaBaked.shift(index, trail, site), site.centerZ() + md[1]);
            helper.runAtTickTime(tick, () -> level.setBlock(marker, Blocks.GOLD_BLOCK.defaultBlockState(), 2));
            for (int i = 0; i < cells.size(); i++) {
                Map.Entry<HuaBaked.Group, HuaBaked.Cell> e = cells.get(i);
                helper.runAtTickTime(tick + 1 + i, () -> {
                    helper.assertTrue(HuaBaked.placeCell(server, level, site, index, e.getKey(),
                            e.getValue(), null, level.getRandom(), 2), "template missing: " + e.getValue().file());
                    checkBlockEntities(helper, HuaBaked.cellBox(index, e.getKey(), e.getValue(), site));
                });
            }
            int check = tick + cells.size() + 2;
            helper.runAtTickTime(check, () -> check(helper, site, r, index, sigs, marker));
            tick = check + 2;
        }
        helper.runAtTickTime(tick, helper::succeed);
    }

    @GameTest(template = "sect_yard", batch = "hua_baked_extra", timeoutTicks = 100)
    public static void stampReplacesChestEntitiesAndPendingData(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(BlockPos.ZERO).above(40);
        var template = level.getServer().getStructureManager().readStructure(net.minecraft.nbt.TagParser.parseTag("""
                {size:[4,1,1],palette:[{Name:"minecraft:stone"},{Name:"minecraft:chest"}],
                 blocks:[{pos:[0,0,0],state:0},{pos:[1,0,0],state:1},
                         {pos:[2,0,0],state:1,nbt:{id:"minecraft:chest",CustomName:'{"text":"carried"}'}}],entities:[]}
                """));
        for (int rotation = 0; rotation < 4; rotation++) {
            var settings = new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings()
                    .setKnownShape(true).setRotation(Rotation.values()[rotation]);
            BlockPos at = origin.offset(rotation * 8, 0, 0);
            for (int x = 0; x < 4; x++) {
                BlockPos pos = at.offset(new BlockPos(x, 0, 0).rotate(Rotation.values()[rotation]));
                level.setBlock(pos, Blocks.CHEST.defaultBlockState(), 2);
                var old = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(pos);
                old.setItem(0, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND));
                level.getChunkAt(pos).setBlockEntityNbt(old.saveWithFullMetadata(level.registryAccess()));
            }
            HuaStampBlocks.place(template, level, at, settings, level.getRandom(), 2);
            BlockPos stone = at;
            helper.assertTrue(level.getBlockState(stone).is(Blocks.STONE), "chest was not stamped to stone");
            helper.assertTrue(level.getChunkAt(stone).getBlockEntityNbt(stone) == null
                    && level.getBlockEntity(stone) == null, "stone retained a chest entity or pending NBT");
            for (int x = 1; x <= 2; x++) {
                BlockPos pos = at.offset(new BlockPos(x, 0, 0).rotate(Rotation.values()[rotation]));
                var entity = level.getBlockEntity(pos);
                helper.assertTrue(entity instanceof net.minecraft.world.level.block.entity.ChestBlockEntity,
                        "carried chest has no valid BE at " + pos);
                var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) entity;
                helper.assertTrue(chest.getItem(0).isEmpty(), "carried chest inherited old contents");
                helper.assertTrue(level.getChunkAt(pos).getBlockEntityNbt(pos) == null, "carried chest retained pending NBT");
                if (x == 2) helper.assertTrue(chest.getCustomName() != null && chest.getCustomName().getString().equals("carried"),
                        "template chest NBT was lost");
            }
            BlockPos untouched = at.offset(new BlockPos(3, 0, 0).rotate(Rotation.values()[rotation]));
            helper.assertTrue(((net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(untouched))
                    .getItem(0).is(net.minecraft.world.item.Items.DIAMOND), "sparse gap was touched");
        }
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "hua_baked_extra", timeoutTicks = 100)
    public static void protoChunkStampDiscardsInstantiatedAndPendingEntities(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(BlockPos.ZERO).above(40);
        // API: Minecraft 1.21.1 ProtoChunk constructor, UpgradeData#EMPTY; real proto storage without generating a world.
        var proto = new net.minecraft.world.level.chunk.ProtoChunk(new net.minecraft.world.level.ChunkPos(pos),
                net.minecraft.world.level.chunk.UpgradeData.EMPTY, level,
                level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null);
        proto.setBlockState(pos, Blocks.CHEST.defaultBlockState(), false);
        var old = new net.minecraft.world.level.block.entity.ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState());
        proto.setBlockEntity(old);
        proto.setBlockEntityNbt(old.saveWithFullMetadata(level.registryAccess()));
        proto.setBlockState(pos, Blocks.STONE.defaultBlockState(), false);
        // Exercise the template path on real ProtoChunk storage. This adapter follows WorldGenRegion#setBlock's
        // pending-DUMMY behavior; all block/BE operations stay in the isolated proto, never the live test chunk.
        var access = (net.minecraft.world.level.ServerLevelAccessor) java.lang.reflect.Proxy.newProxyInstance(
                HuaBakedExtraGameTests.class.getClassLoader(), new Class<?>[] {net.minecraft.world.level.ServerLevelAccessor.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("getChunk")) return proto;
                    if (method.getName().equals("getBlockState")) return proto.getBlockState((BlockPos) args[0]);
                    if (method.getName().equals("getFluidState")) return proto.getFluidState((BlockPos) args[0]);
                    if (method.getName().equals("getBlockEntity")) return proto.getBlockEntity((BlockPos) args[0]);
                    if (method.getName().equals("setBlock")) {
                        BlockPos at = (BlockPos) args[0];
                        BlockState state = (BlockState) args[1];
                        BlockState before = proto.setBlockState(at, state, false);
                        if (state.hasBlockEntity()) {
                            var dummy = new net.minecraft.nbt.CompoundTag();
                            dummy.putString("id", "DUMMY");
                            dummy.putInt("x", at.getX()); dummy.putInt("y", at.getY()); dummy.putInt("z", at.getZ());
                            proto.setBlockEntityNbt(dummy);
                        } else if (before != null && before.hasBlockEntity()) {
                            proto.removeBlockEntity(at);
                        }
                        return true;
                    }
                    return method.invoke(level, args);
                });
        var template = level.getServer().getStructureManager().readStructure(net.minecraft.nbt.TagParser.parseTag("""
                {size:[3,1,1],palette:[{Name:"minecraft:stone"},{Name:"minecraft:chest"},
                 {Name:"murim:red_pillar"}],blocks:[{pos:[0,0,0],state:0},{pos:[1,0,0],state:1},
                 {pos:[2,0,0],state:2}],entities:[]}
                """));
        HuaStampBlocks.place(template, access, pos,
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setKnownShape(true),
                level.getRandom(), 2);
        helper.assertTrue(proto.getBlockState(pos).is(Blocks.STONE) && proto.getBlockEntity(pos) == null
                && proto.getBlockEntityNbt(pos) == null, "proto stone retained a BE or pending NBT");
        BlockPos chest = pos.east();
        helper.assertTrue(proto.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                && proto.getBlockEntityNbt(chest) == null, "proto carried chest has no fresh entity");
        BlockPos pillar = pos.east(2);
        helper.assertTrue(proto.getBlockEntity(pillar) == null && proto.getBlockEntityNbt(pillar) == null,
                "a BE-less pillar retained DUMMY NBT");
        // The optimized repeat-stamp path must still remove orphan instances even without pending NBT.
        proto.setBlockEntity(new net.minecraft.world.level.block.entity.ChestBlockEntity(pos, Blocks.CHEST.defaultBlockState()));
        HuaStampBlocks.place(template, access, pos,
                new net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings().setKnownShape(true),
                level.getRandom(), 2);
        helper.assertTrue(proto.getBlockState(pos).is(Blocks.STONE) && proto.getBlockEntity(pos) == null
                && proto.getBlockEntityNbt(pos) == null, "repeat stamp retained an orphan instance in unchanged stone");
        helper.assertTrue(proto.getBlockEntity(chest) instanceof net.minecraft.world.level.block.entity.ChestBlockEntity
                && proto.getBlockEntityNbt(chest) == null, "repeat stamp lost the carried chest");
        helper.succeed();
    }

    /** Scan before resolving pending NBT: an invalid entry must fail without logging a load warning first. */
    private static void checkBlockEntities(GameTestHelper helper, net.minecraft.world.level.levelgen.structure.BoundingBox box) {
        ServerLevel level = helper.getLevel();
        for (BlockPos pos : BlockPos.betweenClosed(box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ())) {
            var chunk = level.getChunkAt(pos);
            BlockState state = level.getBlockState(pos);
            var expected = state.getBlock() instanceof net.minecraft.world.level.block.EntityBlock factory
                    ? factory.newBlockEntity(pos, state) : null;
            var pending = chunk.getBlockEntityNbt(pos);
            var actual = chunk.getBlockEntities().get(pos);
            helper.assertTrue(expected != null || (pending == null && actual == null), "orphan BE/NBT at " + pos + " in " + state);
            if (expected != null) {
                actual = level.getBlockEntity(pos);
                helper.assertTrue(actual != null && actual.getType() == expected.getType() && actual.getType().isValid(state)
                        && actual.getBlockState() == state && !actual.isRemoved(), "missing or mismatched BE at " + pos + " in " + state);
            }
        }
    }

    private static final java.util.Set<String> LIVE_PROPERTIES = java.util.Set.of("north", "east", "south", "west", "up", "shape", "distance", "waterlogged");

    private static boolean sameButShape(BlockState expected, BlockState actual) {
        if (expected.getBlock() != actual.getBlock()) {
            return false;
        }
        for (net.minecraft.world.level.block.state.properties.Property<?> p : expected.getProperties()) {
            if (!LIVE_PROPERTIES.contains(p.getName()) && !expected.getValue(p).equals(actual.getValue(p))) {
                return false;
            }
        }
        return true;
    }

    private static void check(GameTestHelper helper, MountHuaSite site, int rot, HuaBaked.Index index, List<Signature> sigs, BlockPos marker) {
        ServerLevel level = helper.getLevel();
        Rotation rotation = Rotation.values()[rot];
        int checked = 0;
        int tolerated = 0;
        for (Signature s : sigs) {
            HuaBaked.Group group = index.groups().stream().filter(g -> g.id().equals(s.group())).findFirst().orElseThrow();
            if (group.cells().stream().noneMatch(c -> inCell(group, c, s))) {
                helper.fail("signature outside every cell: " + s.name());
            }
            int[] d = site.rotateDir(s.u(), s.v());
            BlockPos pos = new BlockPos(site.centerX() + d[0], s.y() + HuaBaked.shift(index, group, site), site.centerZ() + d[1]);
            BlockState expected = s.state().rotate(rotation);
            BlockState actual = level.getBlockState(pos);
            // Worldgen writes the states exactly (tools/hua_bake/check_extra.py finds every block of a generated world as baked). A live
            // level, as this test has, lets a neighbour placed later turn the connection flags of a fence or the shape of a stair, so those
            // properties are not compared here; the block, its direction, half and type are.
            // A plant or a vine placed in the flat test level has no ground under it (the pieces list the changed blocks, not the earth) and
            // is removed by the shape update of the live level; where the block could not stand here, air is the right answer.
            boolean cannotStandHere = actual.isAir() && !expected.canSurvive(level, pos);
            helper.assertTrue(cannotStandHere || sameButShape(expected, actual), "rotation " + rot + " " + s.name() + " at " + pos + ": expected " + expected + " but found " + actual);
            if (cannotStandHere) {
                tolerated++;
            }
            // Chung Myung's pills come with the cave (tools/hua_bake/bake_extra.py CAVE_PILLS)
            if (expected.is(IntWorkshop.PILL_CRATE.get())) {
                helper.assertTrue(level.getBlockEntity(pos) instanceof PillBoxBlockEntity crate
                        && crate.countItem(ModItems.PILL_SNOW_PLUM.get()) == 3 && crate.countItem(ModItems.PILL_VITALITY.get()) == 32,
                        "rotation " + rot + ": the cave crate at " + pos + " lost its pills");
            }
            checked++;
        }
        helper.assertTrue(checked == sigs.size(), "checked " + checked + " of " + sigs.size());
        helper.assertTrue(tolerated * 10 < sigs.size(), "too many blocks that could not stand in the flat level: " + tolerated);
        helper.assertTrue(level.getBlockState(marker).is(Blocks.GOLD_BLOCK), "rotation " + rot + ": a block above the first lake cell was cleared by the stamp");
    }

    /**
     * Hanging crowns (docs/adr/0021): a crown with no log goes with the vine under it; a leaf chain from a log stays to the decay distance 6
     * and goes from 7 on; a persistent leaf, a plum blossom and a crown outside the rectangle stay.
     */
    @GameTest(template = "sect_yard", batch = "hua_baked_extra", timeoutTicks = 100)
    public static void hangingCrownsGoAndSupportedLeavesStay(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos o = helper.absolutePos(BlockPos.ZERO).above(40);
        BlockState leaf = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, false);
        for (int dx = 0; dx < 3; dx++) {
            for (int dy = 0; dy < 3; dy++) {
                for (int dz = 0; dz < 3; dz++) {
                    level.setBlock(o.offset(dx, dy + 2, dz), leaf, 2);
                }
            }
        }
        BlockPos vine = o.offset(1, 1, 1);
        level.setBlock(vine, Blocks.VINE.defaultBlockState().setValue(net.minecraft.world.level.block.VineBlock.UP, true), 2);
        boolean vineHung = level.getBlockState(vine).is(Blocks.VINE);
        for (int y = -4; y <= 0; y++) {
            level.setBlock(o.offset(10, y, 0), Blocks.OAK_LOG.defaultBlockState(), 2);
        }
        for (int k = 1; k <= 12; k++) {
            level.setBlock(o.offset(10 + k, 0, 0), leaf, 2);
        }
        BlockState persistent = Blocks.OAK_LEAVES.defaultBlockState().setValue(net.minecraft.world.level.block.LeavesBlock.PERSISTENT, true);
        level.setBlock(o.offset(30, 0, 0), persistent, 2);
        level.setBlock(o.offset(32, 0, 0), io.github.verycooltimo.murim.registry.ModBlocks.PLUM_BLOSSOM.get().defaultBlockState(), 2);
        level.setBlock(o.offset(60, 0, 0), leaf, 2);                      // outside the rectangle
        net.minecraft.world.level.levelgen.structure.BoundingBox write = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                o.getX() - 2, o.getY() - 10, o.getZ() - 2, o.getX() + 40, o.getY() + 10, o.getZ() + 10);
        net.minecraft.world.level.levelgen.structure.BoundingBox read = new net.minecraft.world.level.levelgen.structure.BoundingBox(
                write.minX() - 8, write.minY(), write.minZ() - 8, write.maxX() + 8, write.maxY(), write.maxZ() + 8);
        BlockPos stale = o.offset(1, 3, 1);
        var orphan = new net.minecraft.world.level.block.entity.ChestBlockEntity(stale, Blocks.CHEST.defaultBlockState());
        level.getChunkAt(stale).getBlockEntities().put(stale, orphan);
        level.getChunkAt(stale).setBlockEntityNbt(orphan.saveWithFullMetadata(level.registryAccess()));
        int removed = HuaBakedExtra.prune(level, write, read, List.of(new int[] {write.minX(), write.minZ(), write.maxX(), write.maxZ()}));
        helper.assertTrue(level.getChunkAt(stale).getBlockEntityNbt(stale) == null && level.getBlockEntity(stale) == null,
                "crown clearing retained an orphan entity or pending NBT");
        helper.assertTrue(level.getBlockState(o.offset(1, 3, 1)).isAir() && level.getBlockState(o.offset(0, 2, 0)).isAir(), "the crown without a log is still there");
        if (vineHung) {
            helper.assertTrue(level.getBlockState(vine).isAir(), "the vine under the removed crown is still there");
        }
        for (int k = 1; k <= 6; k++) {
            helper.assertTrue(level.getBlockState(o.offset(10 + k, 0, 0)).is(Blocks.OAK_LEAVES), "the leaf at distance " + k + " from the log went");
        }
        for (int k = 7; k <= 12; k++) {
            helper.assertTrue(level.getBlockState(o.offset(10 + k, 0, 0)).isAir(), "the leaf at distance " + k + " from the log stayed");
        }
        helper.assertTrue(level.getBlockState(o.offset(10, 0, 0)).is(Blocks.OAK_LOG), "the log went");
        helper.assertTrue(level.getBlockState(o.offset(30, 0, 0)) == persistent, "a persistent leaf went");
        helper.assertTrue(level.getBlockState(o.offset(32, 0, 0)).is(io.github.verycooltimo.murim.registry.ModBlocks.PLUM_BLOSSOM.get()), "a plum blossom went");
        helper.assertTrue(level.getBlockState(o.offset(60, 0, 0)).is(Blocks.OAK_LEAVES), "a leaf outside the rectangle went");
        helper.assertTrue(removed >= 27 + 6, "removed " + removed);
        helper.succeed();
    }
}
