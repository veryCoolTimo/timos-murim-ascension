package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.MurimMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class TemplateBlockEntityGameTests {
    private TemplateBlockEntityGameTests() {}

    // API sources (Minecraft 1.21.1): GameTestHelper#getLevel/assertTrue/succeed;
    // MinecraftServer#getStructureManager/getResourceManager; StructureTemplateManager#listTemplates/get/readStructure;
    // ResourceManager#listResources; Resource#open; ResourceLocation#getNamespace/getPath;
    // NbtIo#readCompressed, NbtAccounter#unlimitedHeap; StructureTemplate#save;
    // CompoundTag#getList/getCompound/getInt/contains; ListTag#getCompound/getList;
    // Registry#asLookup, NbtUtils#readBlockState; BlockBehaviour.BlockStateBase#hasBlockEntity;
    // EntityBlock#newBlockEntity. All verified under /tmp/mr-ref-1121/minecraft-src/net/minecraft/.
    // NeoForge: gametest/GameTestHolder.java, PrefixGameTestTemplate.java (1.21.1 source).
    @GameTest(template = "small_floor", timeoutTicks = 200, batch = "release_templates")
    public static void shippedTemplatesHaveValidBlockEntities(GameTestHelper helper) throws Exception {
        var server = helper.getLevel().getServer();
        var manager = server.getStructureManager();
        List<String> failures = new ArrayList<>();
        int checked = 0;
        for (var id : manager.listTemplates().filter(id -> id.getNamespace().equals(MurimMod.MODID)).toList()) {
            var template = manager.get(id);
            if (template.isEmpty()) failures.add(id + ": failed to load");
            else check(id.toString(), template.get(), failures);
            checked++;
        }
        // Baked authored cells are structure NBT outside structure/, loaded through the same manager as HuaBaked.
        for (var entry : server.getResourceManager().listResources("hua_baked",
                id -> id.getNamespace().equals(MurimMod.MODID) && id.getPath().endsWith(".nbt")).entrySet()) {
            try (var in = entry.getValue().open()) {
                check(entry.getKey().toString(), manager.readStructure(NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap())), failures);
                checked++;
            }
        }
        helper.assertTrue(checked > 0, "No mod templates found");
        helper.assertTrue(failures.isEmpty(), "Invalid stored block entities:\n" + String.join("\n", failures));
        helper.succeed();
    }

    private static void check(String name, StructureTemplate template, List<String> failures) {
        CompoundTag stored = template.save(new CompoundTag());
        ListTag palettes = stored.getList("palettes", 9);
        List<ListTag> states = new ArrayList<>();
        if (palettes.isEmpty()) states.add(stored.getList("palette", 10));
        else for (int i = 0; i < palettes.size(); i++) states.add(palettes.getList(i));
        ListTag blocks = stored.getList("blocks", 10);
        for (int i = 0; i < blocks.size(); i++) {
            CompoundTag block = blocks.getCompound(i);
            if (!block.contains("nbt", 10)) continue;
            for (int palette = 0; palette < states.size(); palette++) {
                var state = NbtUtils.readBlockState(BuiltInRegistries.BLOCK.asLookup(), states.get(palette).getCompound(block.getInt("state")));
                // hasBlockEntity alone is insufficient for multi-cell blocks whose non-master cells return null.
                if (!state.hasBlockEntity() || ((EntityBlock) state.getBlock()).newBlockEntity(BlockPos.ZERO, state) == null)
                    failures.add(name + " block " + i + " palette " + palette + ": " + state);
            }
        }
    }
}
