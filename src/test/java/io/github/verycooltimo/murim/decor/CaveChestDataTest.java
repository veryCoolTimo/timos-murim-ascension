package io.github.verycooltimo.murim.decor;

import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CaveChestDataTest {
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/nbt/{NbtIo,NbtAccounter,CompoundTag,ListTag}.java
    // The template keeps exactly the author's one chest at (-838,232,3252), and its facing/state.
    @Test
    void newWorldCaveHasOneStockedWoodenChest() throws Exception {
        Path dir = CaveTestFiles.root().resolve("src/main/resources/data/murim/hua_baked/extra/cave");
        int chests = 0;
        try (var paths = Files.list(dir)) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                var nbt = NbtIo.readCompressed(path, NbtAccounter.unlimitedHeap());
                var palette = nbt.getList("palette", Tag.TAG_COMPOUND);
                for (Tag entry : nbt.getList("blocks", Tag.TAG_COMPOUND)) {
                    var block = (net.minecraft.nbt.CompoundTag) entry;
                    String name = palette.getCompound(block.getInt("state")).getString("Name");
                    if (!name.equals("murim:sto_chest") && !name.equals("minecraft:chest")) continue;
                    chests++;
                    assertEquals("murim:sto_chest", name);
                    assertEquals("k_-53_203.nbt", path.getFileName().toString());
                    var pos = block.getList("pos", Tag.TAG_INT);
                    assertEquals(10, pos.getInt(0));
                    assertEquals(2, pos.getInt(1));
                    assertEquals(4, pos.getInt(2));
                    var entity = block.getCompound("nbt");
                    var contents = new HashMap<String, Integer>();
                    for (Tag item : entity.getList("Items", Tag.TAG_COMPOUND)) {
                        var stack = (net.minecraft.nbt.CompoundTag) item;
                        contents.merge(stack.getString("id"), stack.getInt("count"), Integer::sum);
                    }
                    assertEquals(java.util.Map.of("murim:chungmyung_note", 1, "murim:pill_plum_blossom", 2), contents);
                    assertTrue(entity.getCompound("NeoForgeData").getBoolean(ChungMyungCave.SUPPLIES));
                }
            }
        }
        assertEquals(1, chests);
    }
}
