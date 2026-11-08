package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.ShrineBlock;
import io.github.verycooltimo.murim.decor.ShrineDecor;
import io.github.verycooltimo.murim.decor.WeaponRacks;
import io.github.verycooltimo.murim.decor.WeaponStandBlock;
import io.github.verycooltimo.murim.decor.WeaponStandBlockEntity;
import io.github.verycooltimo.murim.quest.ClueBlock;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.loot.LootTable;

import java.util.Map;

/**
 * Stamps the main vault ({@link VaultPlan}) into the world of a canonical Mount Hua site, after the baked sect: it is part of
 * the same cleanup pass and uses the same clip (the 3 x 3 chunks round the chunk, docs/adr/0018), so the last pass over a
 * block puts the baked sect and then the vault back, whatever order the chunks finish in. Positions turn with the site
 * ({@code centre + rotateDir(u, v)}) and shift with its foot ({@code baseY + y}), exactly as the baked cells do.
 */
public final class HuaVault {

    /** The same assignment used to stock a shelf; also serves empty slots in old saves. */
    static ItemStack original(String data) {
        boolean method = data.startsWith("method/");
        ItemStack book = new ItemStack(method ? ModItems.METHOD_SCROLL.get() : ModItems.TECHNIQUE_MANUAL.get());
        book.set(method ? io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get()
                : io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(),
                ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, method ? data.substring(7) : data));
        book.set(io.github.verycooltimo.murim.registry.ModDataComponents.ORIGINAL.get(), true);
        return book;
    }

    static ItemStack assignedOriginal(BlockPos local) {
        for (VaultPlan.Special special : VaultPlan.get().specials) {
            if (special.kind() == VaultPlan.Kind.ORIGINAL && special.local().equals(local)) return original(special.data());
        }
        return ItemStack.EMPTY;
    }

    private HuaVault() {
    }

    /** Cheap tick gate; direct fixture entry points remain available to small test vaults. */
    public static boolean near(net.minecraft.server.level.ServerPlayer player, int margin) {
        if (player.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return false;
        var site = io.github.verycooltimo.murim.world.hua.MountHuaSites.get(player.serverLevel().getServer());
        if (!applies(site)) return false;
        return near(box(site), player.blockPosition(), margin);
    }

    static boolean near(BoundingBox b, BlockPos p, int margin) {
        return p.getX() >= b.minX() - margin && p.getX() <= b.maxX() + margin
                && p.getY() >= b.minY() - margin && p.getY() <= b.maxY() + margin
                && p.getZ() >= b.minZ() - margin && p.getZ() <= b.maxZ() + margin;
    }

    public static boolean applies(MountHuaSite site) {
        return site != null && site.canonical();
    }

    /** World position of a local block of the plan. */
    public static BlockPos world(MountHuaSite site, BlockPos local) {
        int[] d = site.rotateDir(local.getX(), local.getZ());
        return new BlockPos(site.centerX() + d[0], site.baseY() + local.getY(), site.centerZ() + d[1]);
    }

    /** Local block (of the plan) of a world position. */
    public static BlockPos local(MountHuaSite site, BlockPos world) {
        int[] uv = site.stampBlock(world.getX(), world.getZ());
        return new BlockPos(uv[0], world.getY() - site.baseY(), uv[1]);
    }

    /** The world box of everything the plan touches. */
    public static BoundingBox box(MountHuaSite site) {
        BoundingBox b = VaultPlan.get().bounds;
        BlockPos a = world(site, new BlockPos(b.minX(), b.minY(), b.minZ()));
        BlockPos c = world(site, new BlockPos(b.maxX(), b.maxY(), b.maxZ()));
        return BoundingBox.fromCorners(a, c);
    }

    /** Direction in the world of a local direction (the site's turn). */
    public static Direction turn(MountHuaSite site, Direction local) {
        return Rotation.values()[site.rotation()].rotate(local);
    }

    /** Worldgen entry (cleanup pass of the mountain feature): this chunk's share, with the same clip as the baked sect. */
    public static void apply(WorldGenLevel level, ChunkPos chunk, MountHuaSite site) {
        if (!applies(site)) {
            return;
        }
        BoundingBox clip = new BoundingBox(chunk.getMinBlockX() - 16, level.getMinBuildHeight(), chunk.getMinBlockZ() - 16,
                chunk.getMaxBlockX() + 16, level.getMaxBuildHeight() - 1, chunk.getMaxBlockZ() + 16);
        place(level, site, clip, 2);
    }

    /**
     * Puts the blocks of the plan that are inside {@code clip} (null: all) and its special blocks.
     *
     * @return the number of blocks set
     */
    public static int place(LevelAccessor level, MountHuaSite site, BoundingBox clip, int flags) {
        BoundingBox box = box(site);
        if (clip != null && !box.intersects(clip)) {
            return 0;
        }
        Rotation rotation = Rotation.values()[site.rotation()];
        int n = 0;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Map.Entry<Long, BlockState> e : VaultPlan.get().blocks.entrySet()) {
            long k = e.getKey();
            int[] d = site.rotateDir(BlockPos.getX(k), BlockPos.getZ(k));
            pos.set(site.centerX() + d[0], site.baseY() + BlockPos.getY(k), site.centerZ() + d[1]);
            if (clip != null && !clip.isInside(pos)) {
                continue;
            }
            // UPDATE_KNOWN_SHAPE: no neighbour shape updates, so rugs, lamps and ladders do not drop off while the rest is still being set
            BlockState state = e.getValue().rotate(rotation);
            int placementFlags = flags | net.minecraft.world.level.block.Block.UPDATE_KNOWN_SHAPE;
            // WorldGenRegion writes DUMMY NBT for every EntityBlock, even non-controller door cells.
            // Reuse the baked stamp lifecycle (ADR 0021) before the proto chunk is promoted/loaded.
            boolean emptyShelfHalf = (state.getBlock() instanceof VaultArchiveShelfBlock
                    || state.getBlock() instanceof VaultNoteShelfBlock)
                    && state.getValue(io.github.verycooltimo.murim.decor.ScrollShelfBlock.HALF)
                    == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER;
            if (state.getBlock() instanceof VaultDoorBlock || emptyShelfHalf) {
                io.github.verycooltimo.murim.world.hua.HuaStampBlocks.set(level, pos, state, placementFlags);
            } else {
                level.setBlock(pos, state, placementFlags);
            }
            n++;
        }
        for (VaultPlan.Special s : VaultPlan.get().specials) {
            BlockPos at = world(site, s.local());
            if (clip == null || clip.isInside(at)) {
                special(level, site, s, at, rotation);
            }
        }
        swapTablets(level, site, clip);
        return n;
    }

    private static void special(LevelAccessor level, MountHuaSite site, VaultPlan.Special s, BlockPos at, Rotation rotation) {
        switch (s.kind()) {
            case CHEST -> {
                if (!level.getBlockState(at).is(s.state().getBlock())) {
                    level.setBlock(at, s.state().rotate(rotation), 2);
                    if (s.local().equals(VaultPlan.CHEST) && level.getBlockEntity(at) instanceof VaultChestBlockEntity chest) {
                        chest.markCrown();
                    }
                    RandomizableContainer.setBlockEntityLootTable(level, level.getRandom(), at,
                            ResourceKey.create(Registries.LOOT_TABLE, ResourceLocation.parse(s.data())));
                    if (level.getBlockEntity(at) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity barrel) {
                        barrel.getPersistentData().putBoolean(VaultContainerMigration.CRATE_MIGRATED, true);
                        barrel.setChanged();
                    }
                }
            }
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/block/entity/BlockEntity.java#getPersistentData
            // Persist stocking separately from contents so an emptied shelf never regenerates a volume.
            case ORIGINAL, LETTER -> {
                if (level.getBlockEntity(at) instanceof VaultChestBlockEntity shelf
                        && !shelf.getPersistentData().getBoolean("ArchiveStocked")) {
                    ItemStack book;
                    if (s.kind() == VaultPlan.Kind.LETTER) {
                        book = new ItemStack(io.github.verycooltimo.murim.library.ModLibrary.CHEONGMUN_LETTER.get());
                    } else {
                        book = original(s.data());
                    }
                    shelf.setItem(0, book);
                    shelf.getPersistentData().putBoolean("ArchiveStocked", true);
                    shelf.setChanged();
                }
            }
            case SWORD_STAND -> {
                if (!level.getBlockState(at).is(WeaponRacks.SWORD_STAND.get())) {
                    level.setBlock(at, WeaponRacks.SWORD_STAND.get().defaultBlockState()
                            .setValue(WeaponStandBlock.FACING, turn(site, Direction.SOUTH)), 2);
                    BlockEntity be = level.getBlockEntity(at);
                    if (be instanceof WeaponStandBlockEntity stand) {
                        var item = "huashan_sword".equals(s.data()) ? ModItems.HUASHAN_SWORD.get() : ModItems.PLUM_GUARD_SWORD.get();
                        stand.items().set(0, new ItemStack(item));
                        stand.setChanged();
                    }
                }
            }
            case CLUE_VERSE -> {
                if (!(level.getBlockState(at).getBlock() instanceof ClueBlock)) {
                    ClueBlock.place(level, at, s.data());
                }
            }
        }
    }

    /** The four tablets of the founders' rite look like the others: swap them in place, keeping their facing. */
    private static void swapTablets(LevelAccessor level, MountHuaSite site, BoundingBox clip) {
        for (int[] t : VaultPlan.TABLETS) {
            BlockPos at = world(site, new BlockPos(t[0], VaultPlan.TABLET_Y, VaultPlan.TABLET_V));
            if (clip != null && !clip.isInside(at)) {
                continue;
            }
            BlockState cur = level.getBlockState(at);
            Block block = cur.getBlock();
            if (block == ShrineDecor.ANCESTOR_TABLETS.get()) {
                level.setBlock(at, VaultRegistry.LOCK_TABLET.get().defaultBlockState().setValue(ShrineBlock.FACING, cur.getValue(ShrineBlock.FACING))
                        .setValue(LockTabletBlock.ORDER, t[1]), 2);
            }
        }
    }

    /** World position of the tablet of the rite with this order, in this site. */
    public static BlockPos tablet(MountHuaSite site, int order) {
        for (int[] t : VaultPlan.TABLETS) {
            if (t[1] == order) {
                return world(site, new BlockPos(t[0], VaultPlan.TABLET_Y, VaultPlan.TABLET_V));
            }
        }
        throw new IllegalArgumentException("order " + order);
    }

    static {
        MurimMod.LOGGER.debug("main vault plan: {} blocks", VaultPlan.get().blocks.size());
    }
}
