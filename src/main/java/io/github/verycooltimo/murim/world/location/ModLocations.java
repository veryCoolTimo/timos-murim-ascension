package io.github.verycooltimo.murim.world.location;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registries of captured locations (docs/design/28-location-capture.md): the structure piece type that stamps a
 * captured template. Own class so the shared registries other tasks edit stay untouched.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/pieces/StructurePieceType.java.
 */
public final class ModLocations {

    /** Known locations: what {@code /murim capture} suggests and the worldgen integrations look up. */
    public static final String CAMP = "camp";
    public static final String ARCHIVE = "archive";
    public static final String FORTRESS = "fortress";
    public static final String HUA_SECT = "hua_sect";
    public static final java.util.List<String> KNOWN = java.util.List.of(CAMP, ARCHIVE, FORTRESS, HUA_SECT);

    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, MurimMod.MODID);

    public static final DeferredHolder<StructurePieceType, StructurePieceType> TEMPLATE_PIECE =
            PIECES.register("location_template", () -> LocationTemplatePiece::new);

    public static void register(IEventBus modBus) {
        PIECES.register(modBus);
    }

    private ModLocations() {
    }
}
