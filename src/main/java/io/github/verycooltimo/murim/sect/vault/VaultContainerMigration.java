package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.ChunkEvent;

/** Chunk-local, no drops and no neighbouring chunk loads. Follows PlumShelfMigration and HuaStampBlocks.
 * Contents, components, custom names, loot keys and seeds travel together in the block entity NBT.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class VaultContainerMigration {
    static final String CRATE_MIGRATED = "murim_vault_crate_1030";
    private VaultContainerMigration() { }

    @SubscribeEvent
    public static void load(ChunkEvent.Load event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || !(event.getChunk() instanceof LevelChunk chunk)) return;
        MountHuaSite site = MountHuaSites.get(level.getServer());
        if (HuaVault.applies(site)) migrate(level, chunk, site);
    }

    public static void migrate(ServerLevel level, LevelChunk chunk, MountHuaSite site) {
        var index = HuaBaked.index(level.getServer());
        BlockPos bedroom = index == null ? null : HuaVault.world(site, new BlockPos(-5, 248 - index.bakeBaseY(), 61));
        var positions = new java.util.ArrayList<BlockPos>();
        // Saved entities may still be pending NBT on ChunkEvent.Load. Resolve only the
        // container blocks in this chunk; iterating instantiated entities alone misses them.
        var bounds = HuaVault.box(site);
        boolean overlaps = chunk.getPos().getMaxBlockX() >= bounds.minX() && chunk.getPos().getMinBlockX() <= bounds.maxX()
                && chunk.getPos().getMaxBlockZ() >= bounds.minZ() && chunk.getPos().getMinBlockZ() <= bounds.maxZ();
        if (overlaps) {
            for (int x = chunk.getPos().getMinBlockX(); x <= chunk.getPos().getMaxBlockX(); x++) {
                for (int z = chunk.getPos().getMinBlockZ(); z <= chunk.getPos().getMaxBlockZ(); z++) {
                    BlockPos local = HuaVault.local(site, new BlockPos(x, site.baseY(), z));
                    if (local.getX() < VaultPlan.STUDY_U0 || local.getX() > VaultPlan.STUDY_U1
                            || local.getZ() < 44 || local.getZ() > 62) continue;
                    for (int y = site.baseY() + VaultPlan.PIT_FLOOR + 1; y <= site.baseY() + VaultPlan.STUDY_TOP; y++) {
                        BlockPos pos = new BlockPos(x, y, z);
                        var state = chunk.getBlockState(pos);
                        if (state.is(net.minecraft.world.level.block.Blocks.CHEST)
                                || state.is(net.minecraft.world.level.block.Blocks.BARREL)) positions.add(pos);
                    }
                }
            }
        }
        if (bedroom != null && (bedroom.getX() >> 4) == chunk.getPos().x && (bedroom.getZ() >> 4) == chunk.getPos().z
                && !positions.contains(bedroom)) positions.add(bedroom);
        for (BlockPos pos : positions) {
            var old = chunk.getBlockEntity(pos);
            boolean vault = !pos.equals(bedroom);
            if (old instanceof ChestBlockEntity chest && (vault || pos.equals(bedroom))) {
                var saved = chest.saveWithFullMetadata(level.registryAccess());
                boolean note = false;
                if (pos.equals(bedroom) && chest.getLootTable() == null) {
                    for (int slot = 0; slot < chest.getContainerSize(); slot++) note |= chest.getItem(slot).is(ModLibrary.HEAD_NOTE.get());
                }
                // Do not overwrite a player's construction above an old bedside chest.
                note &= chunk.getBlockState(pos.above()).isAir();
                var state = note ? VaultRegistry.VAULT_NOTE_SHELF.get().defaultBlockState()
                        .setValue(VaultNoteShelfBlock.FACING, chest.getBlockState().getValue(ChestBlock.FACING))
                        .setValue(VaultNoteShelfBlock.TAKEN, false)
                        : VaultRegistry.VAULT_CHEST.get().defaultBlockState()
                        .setValue(VaultChestBlock.FACING, chest.getBlockState().getValue(ChestBlock.FACING));
                chunk.removeBlockEntity(pos);
                chunk.setBlockState(pos, state, false);
                var replacement = new VaultChestBlockEntity(pos, state);
                replacement.loadWithComponents(saved, level.registryAccess());
                chunk.addAndRegisterBlockEntity(replacement);
                if (note) chunk.setBlockState(pos.above(), state.setValue(VaultNoteShelfBlock.HALF, DoubleBlockHalf.UPPER), false);
                chunk.setUnsaved(true);
            } else if (vault && old instanceof BarrelBlockEntity barrel) {
                // API: NeoForge 1.21.1 IBlockEntityExtension#getPersistentData (source verified).
                if (barrel.getPersistentData().getBoolean(CRATE_MIGRATED)) continue;
                if (barrel.getLootTable() == null && barrel.isEmpty()) {
                    var saved = barrel.saveWithFullMetadata(level.registryAccess());
                    saved.putString("LootTable", "murim:chests/main_vault_crate");
                    saved.putLong("LootTableSeed", level.random.nextLong());
                    barrel.loadWithComponents(saved, level.registryAccess());
                }
                barrel.getPersistentData().putBoolean(CRATE_MIGRATED, true);
                barrel.setChanged();
                chunk.setUnsaved(true);
            }
        }
    }
}
