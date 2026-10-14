package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Rewrite old palettes on server chunk load, before clients see them. No drops or neighbour notifications. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PlumShelfMigration {
    private PlumShelfMigration() { }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk) {
            migrate(event.getChunk());
        }
    }

    static void migrate(ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        boolean changed = false;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.maybeHas(state -> state.getBlock() instanceof LegacyPlumShelfBlock)) continue;
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.getBlock() instanceof LegacyPlumShelfBlock legacy) {
                            pos.set(chunk.getPos().getMinBlockX() + x, y0 + y, chunk.getPos().getMinBlockZ() + z);
                            chunk.setBlockState(pos, legacy.canonical(state), false);
                            changed = true;
                        }
                    }
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }
}
