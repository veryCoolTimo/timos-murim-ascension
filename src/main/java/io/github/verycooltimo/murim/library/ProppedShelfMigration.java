package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Old propped_shelf palettes decode as lower. Add the upright half only in air; keep the secret latch. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ProppedShelfMigration {
    private ProppedShelfMigration() { }

    @SubscribeEvent
    public static void onChunkLoad(ChunkEvent.Load event) {
        if (!event.getLevel().isClientSide() && event.getChunk() instanceof LevelChunk) {
            migrate(event.getChunk());
        }
    }

    public static void migrate(ChunkAccess chunk) {
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        LevelChunkSection[] sections = chunk.getSections();
        boolean changed = false;
        for (int i = 0; i < sections.length; i++) {
            LevelChunkSection section = sections[i];
            if (!section.maybeHas(state -> state.is(ModLibrary.PROPPED_SHELF.get()) && state.getValue(ProppedShelfBlock.HALF) == DoubleBlockHalf.LOWER)) continue;
            int y0 = chunk.getSectionYFromSectionIndex(i) << 4;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState state = section.getBlockState(x, y, z);
                        if (state.is(ModLibrary.PROPPED_SHELF.get()) && state.getValue(ProppedShelfBlock.HALF) == DoubleBlockHalf.LOWER) {
                            pos.set(chunk.getPos().getMinBlockX() + x, y0 + y, chunk.getPos().getMinBlockZ() + z);
                            if (addUpper(chunk, pos, state)) changed = true;
                        }
                    }
                }
            }
        }
        if (changed) chunk.setUnsaved(true);
    }

    public static boolean addUpper(ChunkAccess chunk, BlockPos pos, BlockState state) {
        if (!state.is(ModLibrary.PROPPED_SHELF.get()) || state.getValue(ProppedShelfBlock.HALF) != DoubleBlockHalf.LOWER
                || pos.getY() >= chunk.getMaxBuildHeight() - 1 || !chunk.getBlockState(pos.above()).isAir()) return false;
        chunk.setBlockState(pos.above(), state.setValue(ProppedShelfBlock.HALF, DoubleBlockHalf.UPPER)
                .setValue(ProppedShelfBlock.HAS_BOOK, false), false);
        return true;
    }
}
