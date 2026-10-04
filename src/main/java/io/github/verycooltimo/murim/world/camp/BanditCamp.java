package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Реестры лагеря бандитов (docs/design/24-bandit-camp.md): тип структуры и тип куска. Сама
 * структура, набор и тег биомов — данные: {@code data/murim/worldgen/structure/bandit_camp.json},
 * {@code worldgen/structure_set/bandit_camps.json}, {@code tags/worldgen/biome/has_structure/bandit_camp.json}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructureType.java,
 * reference/minecraft-src/net/minecraft/world/level/levelgen/structure/pieces/StructurePieceType.java.
 */
public final class BanditCamp {

    private static final DeferredRegister<StructureType<?>> TYPES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, MurimMod.MODID);
    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, MurimMod.MODID);

    public static final DeferredHolder<StructureType<?>, StructureType<BanditCampStructure>> STRUCTURE_TYPE =
            TYPES.register("bandit_camp", () -> () -> BanditCampStructure.CODEC);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> PIECE =
            PIECES.register("bandit_camp", () -> BanditCampPiece::new);

    /** Ключ структуры из данных — для {@code /locate}, поиска лагеря у игрока и стенда. */
    public static final ResourceKey<Structure> KEY =
            ResourceKey.create(Registries.STRUCTURE, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "bandit_camp"));

    public static void register(IEventBus modBus) {
        TYPES.register(modBus);
        PIECES.register(modBus);
    }

    private BanditCamp() {
    }
}
