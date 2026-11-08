package io.github.verycooltimo.murim.world.hua;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.brigadier.StringReader;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.Map;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The baked Mount Hua sect (docs/adr/0004-baked-hua-sect.md): stamps the whole sect on a flat test level at a site of each
 * rotation and compares signature blocks, sampled from the source world by {@code tools/hua_bake/signatures.py}, at their
 * relative positions: landmarks (main hall plaque, ancestors' shrine, weapon stands with their swords, treasury stair,
 * storage with items), a fixed-seed random sample of solid and air blocks, and the sect AI layout re-anchored to the site
 * must find a real bed under every bed record.
 *
 * <p><b>Default vs full.</b> By default only the cells that hold a named landmark are stamped (a few dozen; ~3 s of work
 * spread over ticks). With the environment variable {@code MURIM_BAKED_FULL=1} all 237 cells are stamped at every rotation
 * and the whole layout is checked; that run is long enough to delay the batches after it, and the NPC tests whose timeouts
 * count from the start of the run (door, desk, seat) then time out in the same run (seen 17.10, 3 of 3 runs): use it by
 * itself, not as part of the green gate.
 *
 * <p>API: reference/minecraft-src/net/minecraft/commands/arguments/blocks/BlockStateParser.java#parseForBlock,
 * reference/minecraft-src/net/minecraft/world/level/block/state/BlockState.java#rotate.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class HuaBakedGameTests {

    private record Signature(String name, String group, int u, int y, int v, BlockState state, String be, String item) {
    }

    /**
     * Ticks the background loading of all templates gets before the first stamp. All tests of a run share the server thread:
     * reading 237 files on it (~8 s) made the time-based NPC tests flaky (seen 17.10: door, desk and seat tests).
     */
    private static final int PRELOAD_TICKS = 400;

    private HuaBakedGameTests() {
    }

    private static List<Signature> signatures(GameTestHelper helper) throws Exception {
        List<Signature> out = new ArrayList<>();
        try (Reader r = new InputStreamReader(HuaBakedGameTests.class.getResourceAsStream(
                "/data/murim/hua_baked/signatures.json"), StandardCharsets.UTF_8)) {
            JsonArray arr = new Gson().fromJson(r, JsonArray.class);
            for (JsonElement e : arr) {
                JsonObject o = e.getAsJsonObject();
                BlockState state = BlockStateParser.parseForBlock(BuiltInRegistries.BLOCK.asLookup(),
                        new StringReader(o.get("state").getAsString()), false).blockState();
                out.add(new Signature(o.get("name").getAsString(), o.get("group").getAsString(), o.get("u").getAsInt(),
                        o.get("y").getAsInt(), o.get("v").getAsInt(), state,
                        o.get("be").isJsonNull() ? null : o.get("be").getAsString(),
                        o.get("item").isJsonNull() ? null : o.get("item").getAsString()));
            }
        }
        return out;
    }

    /** Every cell in full mode ({@code MURIM_BAKED_FULL=1}); else only those that hold a named landmark signature. */
    private static List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cellsToStamp(HuaBaked.Index index, List<Signature> sigs) {
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> all = HuaBaked.cells(index);
        if ("1".equals(System.getenv("MURIM_BAKED_FULL"))) {
            return all;
        }
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> out = new java.util.ArrayList<>();
        for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : all) {
            for (Signature s : sigs) {
                if (!s.name().startsWith("random") && inCell(e, s)) {
                    out.add(e);
                    break;
                }
            }
        }
        return out;
    }

    private static boolean inCell(Map.Entry<HuaBaked.Group, HuaBaked.Cell> e, Signature s) {
        HuaBaked.Cell c = e.getValue();
        return e.getKey().id().equals(s.group()) && s.u() >= c.u() && s.u() < c.u() + 16 && s.v() >= c.v() && s.v() < c.v() + 16
                && s.y() >= c.y() && s.y() < c.y() + c.h();
    }

    private static void preloadOffThread(GameTestHelper helper) {
        net.minecraft.server.MinecraftServer server = helper.getLevel().getServer();
        net.minecraft.Util.backgroundExecutor().execute(() -> HuaBaked.preload(server));
    }

    /**
     * One cell per tick: all 379 tests of a run share the server thread, and stamping the whole sect in one go stalls
     * it for ~8 s, which broke the time-based NPC tests (seen 17.10). Returns the first free tick after the check.
     */
    private static int schedule(GameTestHelper helper, int start, int rot, int slot, boolean beds, List<Signature> sigs) {
        ServerLevel level = helper.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        // Foot height 66 (the bake world had 67): exercises the base-mode shift without leaving the build height.
        MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + slot * 600, corner.getZ() + 3000, 66, rot, 1L, true);
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> cells = cellsToStamp(index, sigs);
        for (int i = 0; i < cells.size(); i++) {
            Map.Entry<HuaBaked.Group, HuaBaked.Cell> e = cells.get(i);
            helper.runAtTickTime(start + i, () -> helper.assertTrue(HuaBaked.placeCell(level.getServer(), level, site, index,
                    e.getKey(), e.getValue(), null, level.getRandom(), 2), "template missing: " + e.getValue().file()));
        }
        int check = start + cells.size() + 1;
        helper.runAtTickTime(check, () -> checkRotation(helper, site, rot, beds, sigs, cells));
        return check + 1;
    }

    /** Whole sect at a turned site: each signature block must be where the relative position says. */
    private static void checkRotation(GameTestHelper helper, MountHuaSite site, int rot, boolean beds, List<Signature> sigs,
            List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> stamped) {
        ServerLevel level = helper.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        Rotation rotation = Rotation.values()[rot];
        for (Signature s : sigs) {
            if (stamped.stream().noneMatch(e -> inCell(e, s))) {
                continue;
            }
            HuaBaked.Group group = index.groups().stream().filter(g -> g.id().equals(s.group())).findFirst().orElseThrow();
            int[] d = site.rotateDir(s.u(), s.v());
            BlockPos pos = new BlockPos(site.centerX() + d[0], s.y() + HuaBaked.shift(index, group, site), site.centerZ() + d[1]);
            BlockState expected = s.state().rotate(rotation);
            BlockState actual = level.getBlockState(pos);
            helper.assertTrue(actual == expected, "rotation " + rot + " " + s.name() + " at " + pos + ": expected "
                    + expected + " but found " + actual);
            if (s.be() != null) {
                BlockEntity be = level.getBlockEntity(pos);
                helper.assertTrue(be != null && BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()).toString().equals(s.be()),
                        "rotation " + rot + " " + s.name() + ": block entity " + s.be() + " missing at " + pos);
                if (s.item() != null) {
                    String saved = be.saveWithoutMetadata(level.registryAccess()).toString();
                    helper.assertTrue(saved.contains(s.item()), "rotation " + rot + " " + s.name() + ": " + s.item() + " missing in " + saved);
                }
            }
        }
        if (beds) {
            checkLayoutBeds(helper, site, rot, stamped);
        }
    }

    /** Stamp the actual bedroom cell, including the saved note shelf inventory, at every rotation. */
    @GameTest(template = "sect_yard", batch = "hua_baked_head_note", timeoutTicks = 200)
    public static void headsShelfContainsExactlyOneTornPage(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        helper.assertTrue(index != null, "baked index missing");
        // Bake (-1076,248,4277), relative to frame centre (-1071,4216).
        int u = -5, y = 248, v = 61;
        var bedroom = HuaBaked.cells(index).stream().filter(e -> {
            HuaBaked.Cell c = e.getValue();
            return u >= c.u() && u < c.u() + 16 && v >= c.v() && v < c.v() + 16
                    && y >= c.y() && y < c.y() + c.h();
        }).findFirst().orElseThrow();
        for (int rot = 0; rot < 4; rot++) {
            BlockPos corner = helper.absolutePos(BlockPos.ZERO);
            MountHuaSite site = new MountHuaSite(corner.getX() + 6000 + rot * 600,
                    corner.getZ() + 6000, 66, rot, 1L, true);
            helper.assertTrue(HuaBaked.placeCell(level.getServer(), level, site, index,
                    bedroom.getKey(), bedroom.getValue(), null, level.getRandom(), 2), "bedroom template missing");
            int[] d = site.rotateDir(u, v);
            BlockPos pos = new BlockPos(site.centerX() + d[0], y + HuaBaked.shift(index, bedroom.getKey(), site),
                    site.centerZ() + d[1]);
            helper.assertTrue(level.getBlockEntity(pos) instanceof io.github.verycooltimo.murim.sect.vault.VaultChestBlockEntity,
                    "head's shelf missing at rotation " + rot);
            var chest = (io.github.verycooltimo.murim.sect.vault.VaultChestBlockEntity) level.getBlockEntity(pos);
            int total = 0;
            for (int slot = 0; slot < chest.getContainerSize(); slot++) {
                var stack = chest.getItem(slot);
                if (stack.isEmpty()) continue;
                helper.assertTrue(stack.is(io.github.verycooltimo.murim.library.ModLibrary.HEAD_NOTE.get()),
                        "unexpected item on head's shelf at rotation " + rot + ": " + stack);
                total += stack.getCount();
            }
            helper.assertTrue(total == 1, "head's shelf must contain exactly one torn page, got " + total);
        }
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "hua_baked", timeoutTicks = 4000)
    public static void bakedSectMatchesSignatureBlocksAndLayoutBedsAtEveryRotation(GameTestHelper helper) throws Exception {
        helper.assertTrue(HuaBaked.index(helper.getLevel().getServer()) != null, "baked index missing from the mod resources");
        List<Signature> sigs = signatures(helper);
        helper.assertTrue(sigs.size() > 100, "too few signatures: " + sigs.size());
        preloadOffThread(helper);
        int tick = PRELOAD_TICKS;
        for (int rot = 0; rot < 4; rot++) {
            tick = schedule(helper, tick, rot, rot, rot == 0 || rot == 3, sigs) + 1;
        }
        helper.runAtTickTime(tick, helper::succeed);
    }

    /** Worldgen cost: the share of one chunk, as the cleanup pass stamps it (a rotation 2 site, stamped before). */
    @GameTest(template = "sect_yard", batch = "hua_baked", timeoutTicks = 4000)
    public static void stampOfOneChunkIsCheap(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        helper.assertTrue(index != null, "baked index missing from the mod resources");
        BlockPos corner = helper.absolutePos(BlockPos.ZERO);
        // the cost of reading a file is measured elsewhere; this one is about placing, and must not stall the other tests
        preloadOffThread(helper);
        MountHuaSite site = new MountHuaSite(corner.getX() + 8000, corner.getZ() + 3000, 66, 2, 1L, true);
        long[] stat = new long[4]; // total ns, max ns, chunks, processed entries
        int n = 0;
        for (int cx = (site.centerX() - 80) >> 4; cx <= (site.centerX() + 80) >> 4; cx += 2) {
            for (int cz = (site.centerZ() - 160) >> 4; cz <= site.centerZ() >> 4; cz += 2) {
                int x = cx;
                int z = cz;
                helper.runAtTickTime(PRELOAD_TICKS + n++, () -> {
                    BoundingBox clip = new BoundingBox(x << 4, level.getMinBuildHeight(), z << 4, (x << 4) + 15,
                            level.getMaxBuildHeight() - 1, (z << 4) + 15);
                    long t = System.nanoTime();
                    var work = new HuaStampBlocks.Work();
                    HuaBaked.place(level.getServer(), level, site, clip, net.minecraft.util.RandomSource.create(1101L), 2, work);
                    // Static index volumes across all 256 grid alignments of these sampled chunks
                    // bound candidates at 120576 and clipped entries at 35712 (rotation 2).
                    // Allow overlapping templates and asset growth, but reject a whole-sect scan/write.
                    helper.assertTrue(work.entries <= 262144L, "chunk stamp processed too many template entries: " + work.entries);
                    helper.assertTrue(work.clippedEntries <= 131072L, "chunk stamp touched too many blocks: " + work.clippedEntries);
                    int maxSections = (level.getMaxBuildHeight() - level.getMinBuildHeight() + 15) / 16;
                    helper.assertTrue(work.sections.size() <= maxSections, "chunk stamp touched too many sections");
                    for (BlockPos section : work.sections)
                        helper.assertTrue(section.getX() == x && section.getZ() == z, "chunk stamp escaped its clip: " + section);
                    stat[3] += work.entries;
                    t = System.nanoTime() - t;
                    stat[0] += t;
                    stat[1] = Math.max(stat[1], t);
                    stat[2]++;
                });
            }
        }
        int columns = n;
        helper.runAtTickTime(PRELOAD_TICKS + columns + 2, () -> {
            MurimMod.LOGGER.info("Baked Mount Hua sect: per-chunk stamp over {} chunks (cold template cache, flat level): avg {} ms, max {} ms",
                    stat[2], String.format("%.2f", stat[0] / 1e6 / stat[2]), String.format("%.2f", stat[1] / 1e6));
            helper.assertTrue(stat[2] == columns && stat[2] > 0 && stat[3] > 0, "no chunk stamps were measured");
            helper.succeed();
        });
    }

    /** The sect AI layout, anchored to the live site, finds a real bed head under each of its bed records. */
    private static void checkLayoutBeds(GameTestHelper helper, MountHuaSite site, int rot,
            List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> stamped) {
        ServerLevel level = helper.getLevel();
        // parse() instead of anchored(): anchored() keeps one static cache that other sect tests read
        SectLayoutFile layout;
        try (Reader r = new InputStreamReader(HuaBakedGameTests.class.getResourceAsStream("/data/murim/sect/layout.json"),
                StandardCharsets.UTF_8)) {
            layout = SectLayoutFile.parse(r, frame -> SectLayoutFile.Anchor.to(frame, site));
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        // beds of the file in the frame it was made in (the bake world's): those inside a stamped cell must resolve
        java.util.Set<String> covered = new java.util.HashSet<>();
        for (SectLayoutFile.Bed shipped : SectLayoutFile.shipped().beds()) {
            int u = shipped.head().getX() + 1071;
            int v = shipped.head().getZ() - 4216;
            if (stamped.stream().anyMatch(e -> e.getKey().id().equals("sect") && u >= e.getValue().u() && u < e.getValue().u() + 16
                    && v >= e.getValue().v() && v < e.getValue().v() + 16 && shipped.head().getY() >= e.getValue().y()
                    && shipped.head().getY() < e.getValue().y() + e.getValue().h())) {
                covered.add(shipped.id());
            }
        }
        int beds = 0;
        for (SectLayoutFile.Bed bed : layout.beds()) {
            if (!covered.contains(bed.id())) {
                continue;
            }
            BlockState state = level.getBlockState(bed.head());
            String name = BuiltInRegistries.BLOCK.getKey(state.getBlock()).getPath();
            helper.assertTrue(name.startsWith("sto_bed"), "rotation " + rot + " " + bed.id() + ": no bed at " + bed.head()
                    + " but " + state);
            helper.assertTrue(partOf(state).equals("head"), "rotation " + rot + " " + bed.id() + ": not the head part at " + bed.head());
            BlockState feet = level.getBlockState(bed.approach());
            BlockState above = level.getBlockState(bed.approach().above());
            // a rug or cushion is walkable (step height 0.6); anything taller blocks the cell
            helper.assertTrue((feet.getCollisionShape(level, bed.approach()).isEmpty()
                    || feet.getCollisionShape(level, bed.approach()).max(net.minecraft.core.Direction.Axis.Y) <= 0.6)
                    && above.getCollisionShape(level, bed.approach().above()).isEmpty(),
                    "rotation " + rot + " " + bed.id() + ": approach cell " + bed.approach() + " is blocked by " + feet + " / " + above);
            beds++;
        }
        helper.assertTrue(beds == covered.size() && beds >= (stamped.size() > 100 ? 40 : 1), "beds resolved: " + beds + " of " + covered.size());
    }

    private static String partOf(BlockState state) {
        for (Property<?> p : state.getProperties()) {
            if (p.getName().equals("part")) {
                return state.getValue(p).toString();
            }
        }
        return "";
    }
}
