package io.github.verycooltimo.murim.library;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.functions.LootItemConditionalFunction;
import net.minecraft.world.level.storage.loot.functions.LootItemFunctionType;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.storage.loot.predicates.LootItemCondition;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Loot function {@code murim:library_map}: a map to ANOTHER ruined library (the rare hidden clue of the pavilion
 * chest). The vanilla {@code exploration_map} would point at the library the chest stands in: every structure start
 * can be referenced once, and this one is the nearest. So the library around the chest is marked referenced first,
 * then the search runs exactly as vanilla's.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/storage/loot/functions/ExplorationMapFunction.java#run (same
 * flow), reference/minecraft-src/net/minecraft/world/level/StructureManager.java#getStructureWithPieceAt/#addReference,
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructureStart.java#canBeReferenced.
 */
public class LibraryMapFunction extends LootItemConditionalFunction {

    public static final MapCodec<LibraryMapFunction> CODEC = RecordCodecBuilder.mapCodec(i -> commonFields(i)
            .apply(i, LibraryMapFunction::new));

    /** Structure tag the map looks for. */
    public static final TagKey<Structure> LIBRARIES = TagKey.create(Registries.STRUCTURE, ModLibrary.id("ruined_library"));

    /** Chunk radius of the search (vanilla treasure maps use 50). */
    private static final int RADIUS = 64;

    protected LibraryMapFunction(List<LootItemCondition> conditions) {
        super(conditions);
    }

    @Override
    public LootItemFunctionType<LibraryMapFunction> getType() {
        return ModLibrary.LIBRARY_MAP_FUNCTION.get();
    }

    @Override
    protected ItemStack run(ItemStack stack, LootContext context) {
        Vec3 origin = context.getParamOrNull(LootContextParams.ORIGIN);
        if (!stack.is(Items.MAP) || origin == null) {
            return stack;
        }
        ServerLevel level = context.getLevel();
        BlockPos here = BlockPos.containing(origin);
        StructureStart own = level.structureManager().getStructureWithPieceAt(here, LIBRARIES);
        if (own.isValid() && own.canBeReferenced()) {
            level.structureManager().addReference(own);
        }
        BlockPos target = level.findNearestMapStructure(LIBRARIES, here, RADIUS, true);
        if (target == null) {
            return stack;
        }
        ItemStack map = MapItem.create(level, target.getX(), target.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, target, "+", MapDecorationTypes.TARGET_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable("junk.murim.map.other"));
        return map;
    }
}
