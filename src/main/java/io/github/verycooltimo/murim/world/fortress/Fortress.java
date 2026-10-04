package io.github.verycooltimo.murim.world.fortress;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraft.world.level.storage.loot.LootTable;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Реестры крепости Зелёного Леса (docs/design/26-boss.md §2): тип структуры и куска. Структура,
 * набор и теги биомов — данные: {@code worldgen/structure/green_forest_fortress.json},
 * {@code worldgen/structure_set/green_forest_fortresses.json}, {@code tags/worldgen/biome/has_structure/green_forest_fortress.json},
 * тег для карты «Приказ Зелёного Леса» — {@code tags/worldgen/structure/on_fortress_maps.json}.
 *
 * <p>API: как у лагеря (world/camp/BanditCamp) — reference/minecraft-src/.../StructureType.java, StructurePieceType.java.
 */
public final class Fortress {

    private static final DeferredRegister<StructureType<?>> TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, MurimMod.MODID);
    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, MurimMod.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<FortressStructure>> STRUCTURE_TYPE =
            TYPES.register("green_forest_fortress", () -> () -> FortressStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE =
            PIECES.register("green_forest_fortress", () -> FortressPiece::new);

    public static final ResourceKey<Structure> KEY =
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "green_forest_fortress"));

    public static final TagKey<Structure> ON_MAPS =
            TagKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "on_fortress_maps"));

    /** Сокровищница: пилюли и серебро (книгу и страницу кладёт код — по тому, что знает победитель). */
    public static final ResourceKey<LootTable> LOOT_VAULT = ResourceKey.create(Registries.LOOT_TABLE,
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "chests/fortress_vault"));

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        PIECES.register(modBus);
    }

    private Fortress() {
    }
}
