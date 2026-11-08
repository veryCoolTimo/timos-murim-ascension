package io.github.verycooltimo.murim.world.hua;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

/**
 * The sparse pieces of docs/adr/0021-baked-trail-and-foot-lake.md as shipped: the index parses like the sect's, the groups are the two
 * the stamp relies on, every cell has its file, every file is a sparse template (blocks listed, fewer than the cell box holds, all inside
 * it), no file is also in the sect index, and the trail cells lie between the foot terrace and the sect approach.
 */
class HuaBakedExtraIndexTest {

    private static JsonObject load(String path) throws Exception {
        try (InputStream in = HuaBakedExtraIndexTest.class.getResourceAsStream(path)) {
            assertNotNull(in, path + " missing");
            return new Gson().fromJson(new InputStreamReader(in, StandardCharsets.UTF_8), JsonObject.class);
        }
    }

    @Test
    void groupsCellsAndTemplatesAreConsistent() throws Exception {
        HuaBaked.Index extra = HuaBaked.parse(load("/data/murim/hua_baked/extra/index.json"));
        HuaBaked.Index sect = HuaBaked.parse(load("/data/murim/hua_baked/index.json"));
        Set<String> sectFiles = new HashSet<>();
        sect.groups().forEach(g -> g.cells().forEach(c -> sectFiles.add(c.file())));
        HuaBaked.Group trail = extra.groups().stream().filter(g -> g.id().equals("trail")).findFirst().orElseThrow();
        HuaBaked.Group stair = extra.groups().stream().filter(g -> g.id().equals("dorm_stair")).findFirst().orElseThrow();
        assertEquals("base", trail.mode());
        assertEquals("base", stair.mode());
        assertTrue(trail.cells().size() >= 40, "the climb and the lake need their cells: " + trail.cells().size());
        assertEquals(2, stair.cells().size());
        HuaBaked.Group cave = extra.groups().stream().filter(g -> g.id().equals("cave")).findFirst().orElseThrow();
        assertEquals("base", cave.mode());
        int blocks = 0;
        int caveBlocks = 0;
        for (HuaBaked.Group g : extra.groups()) {
            for (HuaBaked.Cell c : g.cells()) {
                assertFalse(sectFiles.contains(c.file()), "a sect template: " + c.file());
                assertTrue(c.file().startsWith("extra/"), c.file());
                try (InputStream in = HuaBakedExtraIndexTest.class.getResourceAsStream("/data/murim/hua_baked/" + c.file())) {
                    assertNotNull(in, "template missing: " + c.file());
                    CompoundTag tag = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
                    ListTag size = tag.getList("size", Tag.TAG_INT);
                    assertEquals(16, size.getInt(0));
                    assertEquals(c.h(), size.getInt(1), c.file());
                    assertEquals(16, size.getInt(2));
                    ListTag list = tag.getList("blocks", Tag.TAG_COMPOUND);
                    assertTrue(list.size() > 0 && list.size() < 16 * c.h() * 16, "not sparse: " + c.file() + " " + list.size());
                    for (Tag t : list) {
                        ListTag pos = ((CompoundTag) t).getList("pos", Tag.TAG_INT);
                        assertTrue(pos.getInt(0) >= 0 && pos.getInt(0) < 16 && pos.getInt(1) >= 0 && pos.getInt(1) < c.h()
                                && pos.getInt(2) >= 0 && pos.getInt(2) < 16, "block outside the cell box in " + c.file());
                    }
                    if (g == cave) {
                        caveBlocks += list.size();
                    } else {
                        blocks += list.size();
                    }
                }
            }
        }
        assertTrue(blocks > 4000 && blocks < 6000, "the author's difference is about five thousand blocks: " + blocks);
        // Chung Myung's cave: 825 changed blocks of the author's and the shell round them (tools/hua_bake/bake_extra.py cave)
        assertTrue(caveBlocks > 1000 && caveBlocks < 1600, "the cave piece: " + caveBlocks);
        for (HuaBaked.Cell c : cave.cells()) {
            assertTrue(c.u() >= 48 && c.u() <= 80 && c.v() >= 144 && c.v() <= 170, "cave cell off the cave: " + c.u() + "," + c.v());
        }
        // the climb runs from the foot terrace (v -404, u -104) to the sect approach (u 13, v -30)
        int minV = Integer.MAX_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (HuaBaked.Cell c : trail.cells()) {
            minV = Math.min(minV, c.v());
            maxV = Math.max(maxV, c.v() + 15);
        }
        assertTrue(minV <= -430 && maxV >= -40 && maxV <= 20, "trail cells v " + minV + ".." + maxV);
    }
}
