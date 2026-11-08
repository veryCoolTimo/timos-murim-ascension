package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SectMapPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;

/** The sect map of the tablet: built from real blocks, rebuilt after a change, sent in one piece. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectMapGameTests {
    private SectMapGameTests() { }

    /** A column well above the test structure, so that it is the top of its column. */
    private static BlockPos column(GameTestHelper h, int dx, int dz, int up) {
        BlockPos o = h.absolutePos(new BlockPos(2, 0, 2));
        return new BlockPos(o.getX() + dx, o.getY() + 24 + up, o.getZ() + dz);
    }

    private static SectMap.Area area(GameTestHelper h) {
        BlockPos o = h.absolutePos(new BlockPos(2, 0, 2));
        return new SectMap.Area(o.getX(), o.getZ(), 10, 10);
    }

    private static int idOf(ServerLevel level, BlockState state, BlockPos pos) {
        return state.getMapColor(level, pos).id;
    }

    @GameTest(template = "sect_yard", batch = "sect_map", timeoutTicks = 100)
    public static void mapBytesMatchTheBlocksColours(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SectMapData d = new SectMapData(area(h));
        Block[] blocks = {Blocks.GOLD_BLOCK, Blocks.RED_WOOL, Blocks.STONE, Blocks.EMERALD_BLOCK, Blocks.SNOW_BLOCK};
        for (int i = 0; i < blocks.length; i++) level.setBlock(column(h, i, 0, 0), blocks[i].defaultBlockState(), 3);
        // a step up by three and back down: the lit and the dark side of a slope
        level.setBlock(column(h, 0, 1, 3), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        level.setBlock(column(h, 0, 2, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        // water by its depth: a source with one more below it
        level.setBlock(column(h, 6, 0, 0), Blocks.WATER.defaultBlockState(), 3);
        level.setBlock(column(h, 6, 0, -1), Blocks.WATER.defaultBlockState(), 3);
        SectMap.rebuildAll(level, d, 100);
        h.assertTrue(d.version() == 1, "the first build did not publish a version: " + d.version());
        BlockPos o = column(h, 0, 0, 0);
        for (int i = 0; i < blocks.length; i++) {
            BlockPos pos = column(h, i, 0, 0);
            int expected = idOf(level, blocks[i].defaultBlockState(), pos);
            h.assertTrue(d.idAt(pos.getX(), pos.getZ()) == expected,
                    blocks[i] + " column has colour id " + d.idAt(pos.getX(), pos.getZ()) + ", expected " + expected);
            h.assertTrue((d.colourAt(pos.getX(), pos.getZ()) >> 2) == expected, "packed colour of " + blocks[i] + " is another colour");
        }
        // vanilla map shading: the column three higher than its northern neighbour is bright, the one three lower is dark
        int bright = d.colourAt(o.getX(), o.getZ() + 1) & 3;
        int dark = d.colourAt(o.getX(), o.getZ() + 2) & 3;
        h.assertTrue(bright == MapColor.Brightness.HIGH.id, "a rise did not shade bright: " + bright);
        h.assertTrue(dark == MapColor.Brightness.LOW.id, "a fall did not shade dark: " + dark);
        BlockPos wet = column(h, 6, 0, 0);
        h.assertTrue(d.idAt(wet.getX(), wet.getZ()) == MapColor.WATER.id, "water column is not water coloured");
        h.assertTrue(d.depth[(wet.getZ() - d.z0) * d.w + (wet.getX() - d.x0)] >= 2, "water depth was not counted");
        // the whole picture is not empty, and a column nobody touched keeps its own (structure or air) colour
        h.assertTrue(Arrays.stream(boxed(d.colours())).anyMatch(b -> b != 0), "the map is empty");
        // clean up the columns above the structure
        for (int i = 0; i < 7; i++) level.setBlock(column(h, i, 0, 0), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(column(h, 6, 0, -1), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(column(h, 0, 1, 3), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(column(h, 0, 2, 0), Blocks.AIR.defaultBlockState(), 3);
        h.succeed();
    }

    private static boolean allBuilt(SectMapData d) {
        for (boolean b : d.built) if (!b) return false;
        return true;
    }

    private static Integer[] boxed(byte[] bytes) {
        Integer[] out = new Integer[bytes.length];
        for (int i = 0; i < bytes.length; i++) out[i] = bytes[i] & 0xFF;
        return out;
    }

    @GameTest(template = "sect_yard", batch = "sect_map", timeoutTicks = 100)
    public static void mapIsRebuiltAfterABlockChange(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        // the world's own saved map, as the server's tick would use it: configured on the test box
        SectMapData d = SectMap.data(level.getServer());
        d.configure(area(h));
        BlockPos pos = column(h, 3, 3, 0);
        // incremental building: a few chunks per run until every chunk of the box was sampled once
        int tick = 1000;
        for (int i = 0; i < 10 && !allBuilt(d); i++) {
            SectMap.step(level, d, tick, i);
            tick += 100;
        }
        h.assertTrue(allBuilt(d) && d.version() >= 1, "the incremental build did not finish: version " + d.version());
        int first = d.version();
        int before = d.idAt(pos.getX(), pos.getZ());
        // a block change announces itself through the neighbour notification and marks its chunk dirty
        level.setBlock(pos, Blocks.EMERALD_BLOCK.defaultBlockState(), 3);
        long key = ChunkPos.asLong(SectMap.chunk(pos.getX()), SectMap.chunk(pos.getZ()));
        h.assertTrue(d.dirty.contains(key), "the block change did not mark its chunk dirty");
        // throttled: a run soon after the last publish samples the chunk but holds the new version back
        SectMap.step(level, d, d.lastPublishTick + 10, 5);
        int emerald = idOf(level, Blocks.EMERALD_BLOCK.defaultBlockState(), pos);
        h.assertTrue(d.idAt(pos.getX(), pos.getZ()) == emerald && before != emerald, "the changed column was not sampled again");
        h.assertTrue(d.version() == first && d.dirty.isEmpty(), "version " + d.version() + " published early, or the chunk stayed dirty");
        SectMap.step(level, d, d.lastPublishTick + SectMap.PUBLISH_EVERY, 6);
        h.assertTrue(d.version() == first + 1, "the change was not published once the throttle ran out: " + d.version());
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        d.configure(new SectMap.Area(0, 0, 0, 0));
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_map", timeoutTicks = 100)
    public static void mapPayloadSurvivesTheWire(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        SectMapData d = new SectMapData(area(h));
        level.setBlock(column(h, 1, 1, 0), Blocks.GOLD_BLOCK.defaultBlockState(), 3);
        SectMap.rebuildAll(level, d, 100);
        SectMapPayload payload = new SectMapPayload(d.version(), d.area().x0(), d.area().z0(), d.area().w(), d.area().h(), d.zipped());
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), level.registryAccess());
        SectMapPayload.STREAM_CODEC.encode(buf, payload);
        SectMapPayload back = SectMapPayload.STREAM_CODEC.decode(buf);
        h.assertTrue(back.version() == payload.version() && back.x0() == payload.x0() && back.z0() == payload.z0()
                && back.w() == 10 && back.h() == 10, "the header changed on the wire");
        byte[] colours = back.colours();
        h.assertTrue(colours != null && Arrays.equals(colours, d.colours()), "the colours changed on the wire");
        h.assertTrue(payload.zipped().length < colours.length, "the picture was not compressed");
        // a damaged picture is refused instead of crashing the client
        SectMapPayload cut = new SectMapPayload(1, 0, 0, 10, 10, Arrays.copyOf(payload.zipped(), 3));
        h.assertTrue(cut.colours() == null, "a truncated picture was accepted");
        h.assertTrue(new SectMapPayload(1, 0, 0, 100000, 10, payload.zipped()).colours() == null, "an oversized picture was accepted");
        level.setBlock(column(h, 1, 1, 0), Blocks.AIR.defaultBlockState(), 3);
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_map", timeoutTicks = 40)
    public static void mapBoxAndLabelsFollowTheLayoutFile(GameTestHelper h) {
        SectLayoutFile file = SectLayoutFile.shipped();
        h.assertTrue(file != null, "the shipped layout is missing");
        SectMap.Area area = SectMap.areaFor(file);
        h.assertTrue(area != null && area.w() >= SectMap.MIN_W && area.h() >= SectMap.MIN_H, "map box " + area);
        var labels = SectMap.labels(file);
        h.assertTrue(labels.stream().anyMatch(l -> l.key().equals("main_hall")) && labels.stream().anyMatch(l -> l.key().equals("sect_gate")),
                "main hall or gate has no label: " + labels.stream().map(SectMap.Label::key).toList());
        var elders = labels.stream().filter(l -> l.key().equals("elders")).toList();
        h.assertTrue(elders.size() == 1 && labels.stream().noneMatch(l -> l.key().startsWith("elder_")), "the three elders' houses are not one label");
        for (var l : labels) {
            h.assertTrue(area.contains(l.x0(), l.z0()) && area.contains(l.x1(), l.z1()), "label " + l.key() + " lies outside the map box");
        }
        h.assertTrue(!file.boards().isEmpty() && area.contains(file.boards().get(0).getX(), file.boards().get(0).getZ()), "the board is not on the map");
        // the same file on a rotated site: the box turns with it and still holds every label
        var hua = io.github.verycooltimo.murim.world.hua.MountHuaSite.placement(0, 0, 64, 1);
        SectLayoutFile turned = SectLayoutFile.parse(new java.io.InputStreamReader(SectLayoutFile.class.getResourceAsStream(SectLayoutFile.RESOURCE),
                java.nio.charset.StandardCharsets.UTF_8), f -> SectLayoutFile.Anchor.to(f, hua));
        SectMap.Area area2 = SectMap.areaFor(turned);
        h.assertTrue(area2 != null && SectMap.labels(turned).stream().allMatch(l -> area2.contains(l.x0(), l.z0()) && area2.contains(l.x1(), l.z1())),
                "a rotated site's labels are not inside its map box");
        h.succeed();
    }
}
