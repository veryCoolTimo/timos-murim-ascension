package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.seal.SealRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkDataEvent;

/** Registry aliases prevent missing blocks while decoding; this pass matches the adjacent masonry.
 * ChunkDataEvent.Load is asynchronous: access only this chunk, never ask the level for neighbours.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultSealMigration {
    private VaultSealMigration() { }

    @SubscribeEvent
    static void load(ChunkDataEvent.Load event) { migrate(event.getChunk(), event.getData()); }

    static void migrate(ChunkAccess chunk, CompoundTag data) {
        for (Tag raw : data.getList("sections", Tag.TAG_COMPOUND)) {
            CompoundTag section = (CompoundTag) raw;
            CompoundTag states = section.getCompound("block_states");
            var palette = states.getList("palette", Tag.TAG_COMPOUND);
            java.util.BitSet retired = new java.util.BitSet();
            for (int i = 0; i < palette.size(); i++)
                if (palette.getCompound(i).getString("Name").equals("murim:vault_seal")) retired.set(i);
            if (retired.isEmpty()) continue;
            int bits = Math.max(4, 32 - Integer.numberOfLeadingZeros(palette.size() - 1));
            int perLong = 64 / bits;
            long mask = (1L << bits) - 1;
            long[] packed = states.getLongArray("data");
            for (int i = 0; i < 4096; i++) {
                int index = palette.size() == 1 ? 0 : i / perLong < packed.length
                        ? (int) ((packed[i / perLong] >>> ((i % perLong) * bits)) & mask) : -1;
                if (index < 0 || !retired.get(index)) continue;
                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + (i & 15),
                        (section.getByte("Y") << 4) + (i >> 8), chunk.getPos().getMinBlockZ() + ((i >> 4) & 15));
                BlockState wall = SealRegistry.SEALED_POLISHED_HUA_GRANITE.get().defaultBlockState();
                for (Direction direction : Direction.values()) {
                    BlockPos neighbour = pos.relative(direction);
                    if ((neighbour.getX() >> 4) != chunk.getPos().x || (neighbour.getZ() >> 4) != chunk.getPos().z) continue;
                    BlockState candidate = chunk.getBlockState(neighbour);
                    if (candidate.is(VaultRegistry.VAULT_BRICK.get()) || candidate.is(VaultRegistry.VAULT_IRON.get())
                            || candidate.is(SealRegistry.SEALED_STONE_BRICKS.get()) || candidate.is(SealRegistry.SEALED_HUA_GRANITE.get())) {
                        wall = candidate;
                        break;
                    }
                }
                chunk.setBlockState(pos, wall, false);
                chunk.setUnsaved(true);
            }
        }
    }
}
