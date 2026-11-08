package io.github.verycooltimo.murim.sect.vault;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.BreathingAccess;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.decor.ShrineBlock;
import io.github.verycooltimo.murim.decor.ShrineDecor;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.quest.Clues;
import io.github.verycooltimo.murim.quest.PlumRestoration;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.SectAccess;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.sect.SectStanding;
import io.github.verycooltimo.murim.sect.seal.SealAccess;
import io.github.verycooltimo.murim.sect.seal.VaultLadder;
import io.github.verycooltimo.murim.sect.seal.VaultTrial;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.projectile.ThrownEnderpearl;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * GameTests of the main vault (design doc 42, author 21.10 and 23.10): the plan is stamped at a site of each rotation, wrapped in
 * unbreakable blocks, clashes with nothing of the baked sect, cannot be entered by pearl; the founders' rite opens the hatch; the
 * door entry requires a form of the Twenty-Four Movements; exit is free; the fragments restore the art; nothing else hands it out.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class VaultGameTests {

    private static final String YARD = "sect_yard";

    private VaultGameTests() {
    }

    private static MountHuaSite site(GameTestHelper h, int slot, int rot) {
        BlockPos c = h.absolutePos(BlockPos.ZERO);
        return new MountHuaSite(c.getX() + 20000 + slot * 400, c.getZ() + 20000, 66, rot, 1L, true);
    }

    private static BlockPos w(MountHuaSite s, int u, int y, int v) {
        return HuaVault.world(s, new BlockPos(u, y, v));
    }

    private static ServerPlayer player(GameTestHelper h, BlockPos at) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "vault-test"), false);
        ServerPlayer p = new ServerPlayer(h.getLevel().getServer(), h.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(h.getLevel().getServer(), connection, p, cookie);
        p.moveTo(at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        p.setGameMode(GameType.SURVIVAL);
        return p;
    }

    private static DantianProfile peak() {
        DantianProfile p = DantianProfile.INITIAL.withTags("clear", "plum").withRank(Realm.PEAK);
        return p.withCirculating(p.maxCirculating());
    }

    private static boolean unbreakable(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).getDestroySpeed(level, p) < 0.0F;
    }

    @GameTest(template = YARD, batch = "vault_door", timeoutTicks = 100)
    public static void worldgenDoorRoundTrip(GameTestHelper h) {
        var level = h.getLevel();
        for (int rotation = 0; rotation < 4; rotation++) {
            var site = site(h, 20 + rotation, rotation);
            var chunks = new java.util.HashMap<net.minecraft.world.level.ChunkPos, net.minecraft.world.level.chunk.ProtoChunk>();
            java.util.function.Function<BlockPos, net.minecraft.world.level.chunk.ProtoChunk> chunk = pos ->
                    chunks.computeIfAbsent(new net.minecraft.world.level.ChunkPos(pos), key ->
                            new net.minecraft.world.level.chunk.ProtoChunk(key, net.minecraft.world.level.chunk.UpgradeData.EMPTY,
                                    level, level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.BIOME), null));
            // Same real-ProtoChunk adapter as HuaBakedExtraGameTests: WorldGenRegion#setBlock writes DUMMY
            // for ALL EntityBlock states, including the 39 non-controller cells whose factory returns null.
            var access = (net.minecraft.world.level.LevelAccessor) java.lang.reflect.Proxy.newProxyInstance(
                    VaultGameTests.class.getClassLoader(), new Class<?>[] {net.minecraft.world.level.LevelAccessor.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("getChunk") && args.length == 1 && args[0] instanceof BlockPos pos)
                            return chunk.apply(pos);
                        if (method.getName().equals("getBlockState")) return chunk.apply((BlockPos) args[0]).getBlockState((BlockPos) args[0]);
                        if (method.getName().equals("setBlock")) {
                            BlockPos pos = (BlockPos) args[0];
                            BlockState state = (BlockState) args[1];
                            var proto = chunk.apply(pos);
                            proto.setBlockState(pos, state, false);
                            if (state.hasBlockEntity()) {
                                var dummy = new net.minecraft.nbt.CompoundTag();
                                dummy.putString("id", "DUMMY");
                                dummy.putInt("x", pos.getX()); dummy.putInt("y", pos.getY()); dummy.putInt("z", pos.getZ());
                                proto.setBlockEntityNbt(dummy);
                            }
                            return true;
                        }
                        return method.invoke(level, args);
                    });
            var positions = VaultPlan.doorCells().stream().map(pos -> w(site, pos.getX(), pos.getY(), pos.getZ())).toList();
            int minX = Integer.MAX_VALUE, minY = minX, minZ = minX;
            int maxX = Integer.MIN_VALUE, maxY = maxX, maxZ = maxX;
            for (var pos : positions) {
                minX = Math.min(minX, pos.getX()); minY = Math.min(minY, pos.getY()); minZ = Math.min(minZ, pos.getZ());
                maxX = Math.max(maxX, pos.getX()); maxY = Math.max(maxY, pos.getY()); maxZ = Math.max(maxZ, pos.getZ());
            }
            HuaVault.place(access, site, new BoundingBox(minX, minY, minZ, maxX, maxY, maxZ), 2);
            int controllers = 0;
            for (var pos : positions) {
                var proto = chunk.apply(pos);
                var state = proto.getBlockState(pos);
                h.assertTrue(state.is(VaultRegistry.VAULT_DOOR.get()), "worldgen lost a door cell");
                h.assertTrue(proto.getBlockEntityNbt(pos) == null, "worldgen retained pending DUMMY at " + pos);
                var entity = proto.getBlockEntity(pos);
                level.setBlock(pos, state, 2);
                if (VaultDoorBlock.controller(state)) {
                    controllers++;
                    h.assertTrue(entity instanceof VaultDoorBlockEntity, "worldgen lost controller");
                    // API: LibraryShelfGameTests#saveSyncCreativeAndBreak and BookcaseManualGameTests.
                    var saved = entity.saveWithFullMetadata(level.registryAccess());
                    var restored = net.minecraft.world.level.block.entity.BlockEntity.loadStatic(pos, state, saved, level.registryAccess());
                    h.assertTrue(restored instanceof VaultDoorBlockEntity, "controller failed NBT round trip");
                    level.setBlockEntity(restored);
                } else {
                    h.assertTrue(entity == null && level.getBlockEntity(pos) == null, "non-controller acquired an entity");
                }
            }
            h.assertTrue(controllers == 1, "expected exactly one controller");
            var corner = w(site, VaultPlan.doorCorner().getX(), VaultPlan.doorCorner().getY(), VaultPlan.doorCorner().getZ());
            // The puzzle's successful finish uses this same entry point; exercise its opening and closing after reload.
            VaultDoorBlock.openCompleted(level, corner);
            for (var pos : positions) {
                var state = level.getBlockState(pos);
                h.assertTrue(state.getValue(VaultDoorBlock.STATE) == VaultDoorBlock.State.OPEN
                        && state.getCollisionShape(level, pos).isEmpty(), "reloaded door did not open");
                var block = (VaultDoorBlock) state.getBlock();
                block.tick(state, level, pos, level.random); // OPEN -> DIM
            }
            ((VaultDoorBlock) level.getBlockState(corner).getBlock()).tick(level.getBlockState(corner), level, corner, level.random);
            for (var pos : positions) h.assertTrue(level.getBlockState(pos).getValue(VaultDoorBlock.STATE) == VaultDoorBlock.State.CLOSED,
                    "reloaded door did not close");
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_door", timeoutTicks = 60)
    public static void creativeBreakRemovesEveryDoorCell(GameTestHelper h) {
        var level = h.getLevel();
        var origin = h.absolutePos(new BlockPos(8, 1, 8));
        var p = player(h, origin.south(3));
        for (var facing : Direction.Plane.HORIZONTAL) for (var phase : new VaultDoorBlock.State[] {
                VaultDoorBlock.State.CLOSED, VaultDoorBlock.State.DIM}) {
            var right = facing.getCounterClockWise();
            for (int row = 0; row < 7; row++) for (int col = 0; col < 6; col++) {
                if (row == 6 && (col == 0 || col == 5)) continue;
                var at = origin.relative(right, col).above(row);
                var state = VaultRegistry.VAULT_DOOR.get().defaultBlockState().setValue(VaultDoorBlock.FACING, facing)
                        .setValue(VaultDoorBlock.COL, col).setValue(VaultDoorBlock.ROW, row).setValue(VaultDoorBlock.STATE, phase);
                level.setBlock(at, state, 3);
                h.assertTrue(state.getDestroySpeed(level, at) == -1, "door part is breakable in survival");
            }
            // Disconnect the top row to ensure cleanup uses the footprint, not flood fill.
            for (int col = 0; col < 6; col++) level.removeBlock(origin.relative(right, col).above(5), false);
            p.setGameMode(GameType.CREATIVE);
            h.assertTrue(p.gameMode.destroyBlock(origin.relative(right, 2).above(2)), "creative break failed");
            for (int row = 0; row < 7; row++) for (int col = 0; col < 6; col++) {
                var at = origin.relative(right, col).above(row);
                h.assertTrue(level.getBlockState(at).isAir(), "door block remains at " + at);
                h.assertTrue(level.getBlockState(at).getCollisionShape(level, at).isEmpty(), "invisible collision remains at " + at);
            }
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_door", timeoutTicks = 60)
    public static void oldSealChunkUsesSurroundingWall(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(6, 2, 6));
        level.setBlock(at, io.github.verycooltimo.murim.sect.seal.SealRegistry.SEALED_POLISHED_HUA_GRANITE.get().defaultBlockState(), 3);
        for (var direction : Direction.values()) level.setBlock(at.relative(direction), VaultRegistry.VAULT_BRICK.get().defaultBlockState(), 3);
        var palette = new net.minecraft.nbt.ListTag();
        var retired = new net.minecraft.nbt.CompoundTag();
        retired.putString("Name", "murim:vault_seal");
        palette.add(retired);
        var air = new net.minecraft.nbt.CompoundTag();
        air.putString("Name", "minecraft:air");
        palette.add(air);
        long[] packed = new long[256];
        java.util.Arrays.fill(packed, 0x1111111111111111L);
        int index = ((at.getY() & 15) << 8) | ((at.getZ() & 15) << 4) | (at.getX() & 15);
        packed[index / 16] &= ~(15L << ((index % 16) * 4));
        var states = new net.minecraft.nbt.CompoundTag();
        states.put("palette", palette);
        states.putLongArray("data", packed);
        var section = new net.minecraft.nbt.CompoundTag();
        section.putByte("Y", (byte) (at.getY() >> 4));
        section.put("block_states", states);
        var sections = new net.minecraft.nbt.ListTag();
        sections.add(section);
        var data = new net.minecraft.nbt.CompoundTag();
        data.put("sections", sections);
        var chunk = level.getChunkAt(at);
        VaultSealMigration.load(new net.neoforged.neoforge.event.level.ChunkDataEvent.Load(
                chunk, data, net.minecraft.world.level.chunk.status.ChunkType.LEVELCHUNK));
        h.assertTrue(level.getBlockState(at).is(VaultRegistry.VAULT_BRICK.get()), "old seal did not match adjacent brick");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_note_shelf", timeoutTicks = 100)
    public static void bedsideNoteMigratesAndCanBeTakenOnlyOnce(GameTestHelper h) {
        var level = h.getLevel();
        var index = HuaBaked.index(level.getServer());
        h.assertTrue(index != null, "baked index missing");
        for (int rotation = 0; rotation < 4; rotation++) {
            var site = site(h, 30 + rotation, rotation);
            BlockPos at = HuaVault.world(site, new BlockPos(-5, 248 - index.bakeBaseY(), 61));
            level.setBlock(at.above(), Blocks.AIR.defaultBlockState(), 2);
            level.setBlock(at, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, HuaVault.turn(site, Direction.SOUTH)), 2);
            var old = (RandomizableContainerBlockEntity) level.getBlockEntity(at);
            var note = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.library.ModLibrary.HEAD_NOTE.get());
            old.setItem(0, note);
            old.setItem(2, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.PAPER, 9));
            VaultContainerMigration.migrate(level, level.getChunkAt(at), site);
            h.assertTrue(level.getBlockState(at).is(VaultRegistry.VAULT_NOTE_SHELF.get()), "note shelf not restored");
            var p = player(h, at.south());
            var hit = new BlockHitResult(Vec3.atCenterOf(at.above()), Direction.SOUTH, at.above(), false);
            level.getBlockState(at.above()).useWithoutItem(level, p, hit);
            h.assertTrue(p.getInventory().countItem(note.getItem()) == 1, "click did not give the original note");
            h.assertTrue(level.getBlockState(at).getValue(VaultNoteShelfBlock.TAKEN)
                    && level.getBlockState(at.above()).getValue(VaultNoteShelfBlock.TAKEN), "page still visible");
            level.getBlockState(at).useWithoutItem(level, p, new BlockHitResult(Vec3.atCenterOf(at), Direction.SOUTH, at, false));
            h.assertTrue(p.getInventory().countItem(note.getItem()) == 1, "second click duplicated the note");
            p.closeContainer();
            var shelf = (VaultChestBlockEntity) level.getBlockEntity(at);
            h.assertTrue(shelf.getItem(2).getCount() == 9, "migration lost unrelated contents");
            var saved = shelf.saveWithFullMetadata(level.registryAccess());
            var loaded = new VaultChestBlockEntity(at, level.getBlockState(at));
            loaded.loadWithComponents(saved, level.registryAccess());
            h.assertTrue(loaded.getItem(0).isEmpty(), "taken note returned after save/load");
            VaultContainerMigration.migrate(level, level.getChunkAt(at), site);
            h.assertTrue(shelf.getItem(0).isEmpty(), "migration recreated taken note");
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_container_migration", timeoutTicks = 100)
    public static void oldVaultContainersKeepItemsAndUnrolledLoot(GameTestHelper h) {
        var level = h.getLevel();
        var site = site(h, 28, 1);
        BlockPos items = HuaVault.world(site, VaultPlan.CHEST);
        BlockPos loot = HuaVault.world(site, VaultPlan.STUDY_CHEST);
        BlockPos crate = HuaVault.world(site, new BlockPos(22, VaultPlan.TUNNEL_FLOOR + 1, 53));
        BlockPos stocked = crate.above();
        for (BlockPos pos : List.of(items, loot)) level.setBlock(pos, Blocks.CHEST.defaultBlockState()
                .setValue(ChestBlock.FACING, Direction.EAST), 2);
        var original = (RandomizableContainerBlockEntity) level.getBlockEntity(items);
        var stack = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 7);
        stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("kept"));
        original.setItem(4, stack.copy());
        net.minecraft.world.RandomizableContainer.setBlockEntityLootTable(level, level.random, loot,
                net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.LOOT_TABLE,
                        ResourceLocationOf.of("chests/main_vault_study")));
        var before = level.getBlockEntity(loot).saveWithFullMetadata(level.registryAccess());
        level.setBlock(crate, Blocks.BARREL.defaultBlockState(), 2);
        level.setBlock(stocked, Blocks.BARREL.defaultBlockState(), 2);
        ((RandomizableContainerBlockEntity) level.getBlockEntity(stocked)).setItem(0, stack.copy());
        var chunks = new java.util.HashSet<net.minecraft.world.level.chunk.LevelChunk>();
        for (BlockPos pos : List.of(items, loot, crate, stocked)) chunks.add(level.getChunkAt(pos));
        for (var chunk : chunks) VaultContainerMigration.migrate(level, chunk, site);
        h.assertTrue(level.getBlockEntity(items) instanceof VaultChestBlockEntity, "legacy chest not replaced");
        var kept = (VaultChestBlockEntity) level.getBlockEntity(items);
        h.assertTrue(kept.getItem(4).getCount() == 7
                && net.minecraft.world.item.ItemStack.isSameItemSameComponents(kept.getItem(4), stack), "inventory/components lost");
        h.assertTrue(kept.getBlockState().getValue(VaultChestBlock.FACING) == Direction.EAST, "facing lost");
        var after = level.getBlockEntity(loot).saveWithFullMetadata(level.registryAccess());
        h.assertTrue(before.getString("LootTable").equals(after.getString("LootTable"))
                && before.getLong("LootTableSeed") == after.getLong("LootTableSeed"), "unrolled loot changed");
        var barrel = (RandomizableContainerBlockEntity) level.getBlockEntity(crate);
        h.assertTrue(barrel.getLootTable() != null, "legacy empty barrel has no supplies");
        h.assertTrue(((RandomizableContainerBlockEntity) level.getBlockEntity(stocked)).getItem(0).getCount() == 7, "stocked barrel changed");
        barrel.unpackLootTable(null);
        barrel.clearContent();
        for (var chunk : chunks) VaultContainerMigration.migrate(level, chunk, site);
        h.assertTrue(barrel.getLootTable() == null && barrel.isEmpty(), "migration refilled a collected barrel");
        h.succeed();
    }

    /** API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/item/ItemStack.java#set
     * /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java#saveWithoutId,load
     * /tmp/mr-ref-1121/neoforge-src/net/neoforged/neoforge/common/extensions/IEntityExtension.java#copyAttachmentsFrom */
    private static net.minecraft.world.item.ItemStack original(String name, boolean method) {
        var stack = new net.minecraft.world.item.ItemStack(method
                ? io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get()
                : io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get());
        stack.set(method ? io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get()
                : io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(), ResourceLocationOf.of(name));
        stack.set(io.github.verycooltimo.murim.registry.ModDataComponents.ORIGINAL.get(), true);
        return stack;
    }

    @GameTest(template = YARD, batch = "vault_archive", timeoutTicks = 100)
    public static void freshArchiveHasEveryOriginalExactlyOnce(GameTestHelper h) {
        var level = h.getLevel();
        var site = site(h, 50, 0);
        HuaVault.place(level, site, null, 2);
        var found = new java.util.HashSet<String>();
        int shelves = 0;
        VaultChestBlockEntity first = null;
        for (var special : VaultPlan.get().specials) {
            if (special.kind() != VaultPlan.Kind.ORIGINAL && special.kind() != VaultPlan.Kind.LETTER) continue;
            var shelf = (VaultChestBlockEntity) level.getBlockEntity(HuaVault.world(site, special.local()));
            h.assertTrue(shelf != null, "archive shelf missing: " + special.local());
            if (special.kind() == VaultPlan.Kind.LETTER) {
                h.assertTrue(shelf.getItem(0).is(io.github.verycooltimo.murim.library.ModLibrary.CHEONGMUN_LETTER.get()), "letter missing");
                continue;
            }
            if (first == null) first = shelf;
            shelves++;
            var book = shelf.getItem(0);
            h.assertTrue(io.github.verycooltimo.murim.mastery.Originals.isOriginal(book) && book.getCount() == 1, "ordinary or duplicate book");
            var technique = book.get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get());
            var method = book.get(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get());
            String key = method == null ? io.github.verycooltimo.murim.mastery.Arts.masteryId(technique).getPath() : "method/" + method.getPath();
            h.assertTrue(found.add(key), "repeated art: " + key);
        }
        h.assertTrue(shelves == 8 && found.equals(Set.of("six_harmonies", "falling_petal_sword", "seven_plum",
                "seven_stars", "dark_fragrance", "method/six_harmonies", "method/purple_sky_qi", "bamboo_leaf_hand")), "incomplete archive: " + found);
        var chest = (VaultChestBlockEntity) level.getBlockEntity(HuaVault.world(site, VaultPlan.CHEST));
        h.assertTrue(chest.crown() && !chest.crownTaken(), "crown not lit before opening");
        chest.unpackLootTable(null);
        int crownSlot = -1, manuals = 0, divine = 0;
        for (int i = 0; i < chest.getContainerSize(); i++) {
            var book = chest.getItem(i);
            if (ResourceLocationOf.of("twenty_four_plum_rainfall").equals(book.get(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get()))) {
                h.assertTrue(io.github.verycooltimo.murim.mastery.Originals.isOriginal(book) && book.getCount() == 1, "24 Plum not original");
                crownSlot = i; manuals++;
            }
            if (ResourceLocationOf.of("purple_sky_divine").equals(book.get(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get()))) {
                h.assertTrue(io.github.verycooltimo.murim.mastery.Originals.isOriginal(book) && book.getCount() == 1, "divine scroll not original");
                divine++;
            }
        }
        h.assertTrue(manuals == 1 && divine == 1, "pit originals missing or duplicated");
        chest.checkCrown();
        h.assertTrue(!chest.crownTaken(), "opening alone extinguished the crown");
        var alice = java.util.UUID.fromString("00000000-0000-0000-0000-000000000001");
        var bob = java.util.UUID.fromString("00000000-0000-0000-0000-000000000002");
        var removed = chest.claimOriginal(alice, crownSlot);
        h.assertTrue(!removed.isEmpty() && !chest.claimOriginal(bob, crownSlot).isEmpty(), "two players must receive the original");
        h.assertTrue(chest.claimOriginal(alice, crownSlot).isEmpty(), "same player claimed twice");
        h.assertTrue(!chest.getItem(crownSlot).isEmpty(), "shared original disappeared");
        chest.checkCrown();
        h.assertTrue(chest.crownTaken() && chest.getUpdateTag(level.registryAccess()).getBoolean("ArchiveCrownTaken"), "taken flag not synced");
        chest.setItem(crownSlot, removed);
        chest.checkCrown();
        var saved = chest.saveWithFullMetadata(level.registryAccess());
        var loaded = new VaultChestBlockEntity(chest.getBlockPos(), chest.getBlockState());
        loaded.loadWithComponents(saved, level.registryAccess());
        h.assertTrue(loaded.crownTaken(), "crown relit after returning book or reloading");
        h.assertTrue(loaded.claimOriginal(alice, crownSlot).isEmpty(), "claim ledger lost on reload");
        first.setItem(0, net.minecraft.world.item.ItemStack.EMPTY);
        var shelfSaved = first.saveWithFullMetadata(level.registryAccess());
        first.loadWithComponents(shelfSaved, level.registryAccess());
        HuaVault.place(level, site, null, 2);
        h.assertTrue(first.getItem(0).isEmpty(), "restamping duplicated an original");
        shelfSaved.remove("OriginalClaims"); // Genuine old-save shape: empty Items, no ledger.
        first.loadWithComponents(shelfSaved, level.registryAccess());
        first.restoreOriginals(HuaVault.local(site, first.getBlockPos()));
        h.assertTrue(!first.claimOriginal(alice, 0).isEmpty() && !first.claimOriginal(bob, 0).isEmpty(), "legacy shelf did not serve its assignment");
        h.assertTrue(first.claimOriginal(alice, 0).isEmpty(), "legacy shelf allowed duplicate claim");
        var emptyChest = chest.saveWithFullMetadata(level.registryAccess());
        emptyChest.remove("OriginalClaims");
        emptyChest.remove("Items");
        loaded.loadWithComponents(emptyChest, level.registryAccess());
        loaded.restoreOriginals(VaultPlan.CHEST);
        int claimed = 0;
        for (int i = 0; i < loaded.getContainerSize(); i++) if (!loaded.claimOriginal(alice, i).isEmpty()) claimed++;
        h.assertTrue(claimed == 2, "legacy empty crown chest must serve both assigned originals");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "vault_archive", timeoutTicks = 20)
    public static void twoPlayersReceiveOriginalsOnceThroughServerInteraction(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(at, VaultRegistry.VAULT_CHEST.get().defaultBlockState(), 3);
        var chest = (VaultChestBlockEntity) h.getLevel().getBlockEntity(at);
        chest.setItem(0, original("twenty_four_plum_rainfall", false));
        chest.setItem(1, original("purple_sky_divine", true));
        chest.markCrown();
        var alice = player(h, at);
        var bob = player(h, at);
        for (var p : List.of(alice, bob)) {
            chest.giveOriginals(p);
            chest.giveOriginals(p);
            h.assertTrue(p.getInventory().countItem(io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get()) == 1,
                    "player must receive exactly one 24 Plum original");
            h.assertTrue(p.getInventory().countItem(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get()) == 1,
                    "player must receive exactly one Divine original");
        }
        var menu = chest.createMenu(1, alice.getInventory());
        menu.clicked(0, 0, net.minecraft.world.inventory.ClickType.PICKUP, alice);
        h.assertTrue(menu.getCarried().isEmpty(), "menu pickup copied the shared original");
        h.assertTrue(menu.quickMoveStack(alice, 0).isEmpty(), "shift-click copied the shared original");
        h.assertTrue(chest.removeItem(0, 1).isEmpty() && chest.removeItemNoUpdate(0).isEmpty(), "automation removed an original");
        h.assertTrue(!chest.getItem(0).isEmpty() && !chest.getItem(1).isEmpty(), "shared books disappeared");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_archive", timeoutTicks = 40)
    public static void crownTickerDetectsRemovalWithoutContainerNotification(GameTestHelper h) {
        BlockPos at = h.absolutePos(new BlockPos(2, 2, 2));
        h.getLevel().setBlock(at, VaultRegistry.VAULT_CHEST.get().defaultBlockState(), 3);
        var chest = (VaultChestBlockEntity) h.getLevel().getBlockEntity(at);
        chest.setItem(0, original("twenty_four_plum_rainfall", false));
        chest.markCrown();
        h.assertTrue(chest.removeItemNoUpdate(0).isEmpty(), "automation extracted shared original");
        chest.setItem(0, net.minecraft.world.item.ItemStack.EMPTY); // Simulate an old/external in-place removal.
        h.runAfterDelay(2, () -> {
            h.assertTrue(chest.crownTaken(), "server ticker did not latch taken after no-update removal");
            h.succeed();
        });
    }

    @GameTest(template = YARD, batch = "vault_archive", timeoutTicks = 60)
    public static void originalsTeachRewardOnceCapAndSurviveRelogAndDeath(GameTestHelper h) {
        var p = player(h, h.absolutePos(BlockPos.ZERO));
        p.setData(ModAttachments.PROFILE, peak());
        var bamboo = ResourceLocationOf.of("bamboo_leaf_hand");
        var bambooBook = original("bamboo_leaf_hand", false);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, bambooBook);
        io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(p, net.minecraft.world.InteractionHand.MAIN_HAND, bamboo);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(bamboo).stars() == 1,
                "Bamboo original must lift its zero-star learning to one");
        h.assertTrue(!io.github.verycooltimo.murim.mastery.Originals.read(p, bambooBook,
                ResourceLocationOf.of("bamboo_iron_wall")), "another Bamboo form rewarded the same original twice");
        p.getCooldowns().removeCooldown(bambooBook.getItem());
        var six = ResourceLocationOf.of("six_harmonies");
        var book = original("six_harmonies", false);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, book);
        h.assertTrue(!p.getData(ModAttachments.MASTERY).knows(six), "fixture already knows six");
        io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(p, net.minecraft.world.InteractionHand.MAIN_HAND, six);
        h.assertTrue(p.getData(ModAttachments.MASTERY).knows(six) && p.getData(ModAttachments.MASTERY).progress(six).stars() == 1,
                "unknown original did not teach and reward");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(six, TechniqueProgress.learned(4, 12)));
        var seven = ResourceLocationOf.of("seven_plum_blossoms");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(seven, TechniqueProgress.learned(4, 12)));
        p.getCooldowns().removeCooldown(book.getItem());
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, original("seven_plum_blossoms", false));
        io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(p, net.minecraft.world.InteractionHand.MAIN_HAND, seven);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(seven).stars() == 5, "known art not lifted one star");
        p.getCooldowns().removeCooldown(book.getItem());
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, original("seven_plum_basic", false));
        io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(p, net.minecraft.world.InteractionHand.MAIN_HAND, ResourceLocationOf.of("seven_plum_basic"));
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(seven).stars() == 5, "another form original rewarded twice");
        var petal = ResourceLocationOf.of("falling_petal_sword");
        p.setData(ModAttachments.MASTERY, p.getData(ModAttachments.MASTERY).with(petal, TechniqueProgress.learned(12, 12)));
        h.assertTrue(io.github.verycooltimo.murim.mastery.Originals.read(p, original("falling_petal_sword", false), petal), "cap reading not recorded");
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(petal).stars() == 12, "original exceeded twelve");
        var method = ResourceLocationOf.of("six_harmonies");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, original("six_harmonies", true));
        io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p, method);
        var methodArt = io.github.verycooltimo.murim.mastery.Arts.methodArt(method);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(methodArt).stars() == 1, "method original did not teach and reward");
        io.github.verycooltimo.murim.item.MethodScrollItem.requestLearn(p, method);
        h.assertTrue(p.getData(ModAttachments.MASTERY).progress(methodArt).stars() == 1, "method rewarded twice");
        var saved = p.saveWithoutId(new net.minecraft.nbt.CompoundTag());
        var relogged = player(h, h.absolutePos(BlockPos.ZERO));
        relogged.load(saved);
        h.assertTrue(relogged.getData(ModAttachments.READ_ORIGINALS).equals(p.getData(ModAttachments.READ_ORIGINALS)), "ledger lost on relog");
        h.assertTrue(!io.github.verycooltimo.murim.mastery.Originals.read(relogged, book, six), "relog allowed second reward");
        var respawned = player(h, h.absolutePos(BlockPos.ZERO));
        respawned.copyAttachmentsFrom(relogged, true);
        h.assertTrue(respawned.getData(ModAttachments.READ_ORIGINALS).equals(relogged.getData(ModAttachments.READ_ORIGINALS)), "ledger lost on death");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_loot", timeoutTicks = 100)
    public static void bothVaultChestsRollAtLeastThreeStacks(GameTestHelper h) {
        var level = h.getLevel();
        var site = site(h, 27, 0);
        HuaVault.place(level, site, null, 2);
        var p = player(h, HuaVault.world(site, VaultPlan.STUDY_CHEST).south());
        for (var special : VaultPlan.get().specials) {
            if (special.kind() != VaultPlan.Kind.CHEST) continue;
            var at = HuaVault.world(site, special.local());
            h.assertTrue(level.getBlockEntity(at) instanceof RandomizableContainerBlockEntity, "not a vault container: " + at);
            h.assertTrue(level.getBlockState(at).is(VaultRegistry.VAULT_CHEST.get()) || level.getBlockState(at).is(Blocks.BARREL),
                    "unexpected vault container block");
            var chest = (RandomizableContainerBlockEntity) level.getBlockEntity(at);
            h.assertTrue(chest.getLootTable() != null && chest.getLootTable().location().getPath().equals(
                    special.data().substring("murim:".length())), "wrong or absent loot table");
            chest.unpackLootTable(p);
            int stacks = 0;
            for (int i = 0; i < chest.getContainerSize(); i++) if (!chest.getItem(i).isEmpty()) stacks++;
            h.assertTrue(stacks >= (special.data().endsWith("crate") ? 1 : 3), "chest rolled only " + stacks + " stacks");
        }
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_loot", timeoutTicks = 60)
    public static void treasureIsFiniteAndFoundersSwordNeedsBothHands(GameTestHelper h) {
        var level = h.getLevel();
        var at = h.absolutePos(new BlockPos(6, 1, 6));
        var p = player(h, at.south(2));
        level.setBlock(at.below(), VaultRegistry.VAULT_PIT_FLAG.get().defaultBlockState(), 3);
        int gold = 0;
        while ((VaultTreasureLayout.LOOT[gold] & 1) == 0) gold++;
        level.setBlock(at, VaultTreasureBlock.of(VaultRegistry.VAULT_TREASURE.get(), gold), 3);
        h.assertTrue(VaultHarvest.take(level, at, p), "gold click gave nothing");
        h.assertTrue(level.getBlockState(at).getValue(VaultTreasureBlock.LAYERS) == 2, "heap did not shrink");
        // Exercise the real server mining path as well as the click transaction.
        p.gameMode.destroyBlock(at);
        h.assertTrue(level.getBlockState(at).getValue(VaultTreasureBlock.LAYERS) == 1, "mining did not remove exactly one layer");
        VaultHarvest.take(level, at, p);
        h.assertTrue(level.getBlockState(at).isAir() && level.getBlockState(at.below()).is(VaultRegistry.VAULT_PIT_FLAG.get()), "last layer damaged the floor");
        h.assertTrue(p.getInventory().countItem(net.minecraft.world.item.Items.GOLD_NUGGET) == 9, "wrong gold payout");
        h.assertTrue(!VaultHarvest.take(level, at, p), "empty heap duplicated loot");
        level.setBlock(at, VaultDecorBlock.of(VaultRegistry.VAULT_DECOR.get(), VaultDecorBlock.RACK_A, Direction.SOUTH), 3);
        VaultHarvest.take(level, at, p);
        h.assertTrue(level.getBlockState(at).getValue(VaultDecorBlock.TAKEN), "rack did not become empty");
        h.assertTrue(p.getInventory().countItem(io.github.verycooltimo.murim.registry.ModItems.HUASHAN_SWORD.get()) == 3, "rack did not yield three real swords");
        h.assertTrue(!VaultHarvest.take(level, at, p), "empty rack duplicated swords");
        level.setBlock(at, VaultDecorBlock.of(VaultRegistry.VAULT_DECOR.get(), VaultDecorBlock.HERO, Direction.SOUTH), 3);
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.STICK));
        h.assertTrue(!VaultHarvest.take(level, at, p), "founders' sword ignored occupied hand");
        p.setItemInHand(net.minecraft.world.InteractionHand.OFF_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.item.ItemStack.EMPTY);
        h.assertTrue(VaultHarvest.take(level, at, p), "two free hands could not lift sword");
        h.assertTrue(!VaultHarvest.take(level, at, p), "pedestal duplicated founders' sword");
        int gem = 0;
        while ((VaultTreasureLayout.LOOT[gem] & 4) == 0) gem++;
        level.setBlock(at, VaultTreasureBlock.of(VaultRegistry.VAULT_TREASURE.get(), gem).setValue(VaultTreasureBlock.LAYERS, 1), 3);
        VaultHarvest.take(level, at, p);
        h.assertTrue(p.getInventory().countItem(net.minecraft.world.item.Items.EMERALD) > 0, "display gem was not takeable");
        h.succeed();
    }

    // ------------------------------------------------------------------ placement

    @GameTest(template = YARD, batch = "vault_rot", timeoutTicks = 800)
    public static void vaultIsPlacedAndSealedAtAllFourRotations(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        for (int rot = 0; rot < 4; rot++) {
            int r = rot;
            h.runAtTickTime(2 + rot * 60, () -> {
                MountHuaSite site = site(h, r, r);
                HuaVault.place(level, site, null, 2);
                Rotation rotation = Rotation.values()[r];
                int bad = 0;
                String first = "";
                Set<BlockPos> special = new java.util.HashSet<>();
                for (VaultPlan.Special sp : VaultPlan.get().specials) {
                    special.add(sp.local());
                }
                for (Map.Entry<Long, BlockState> e : VaultPlan.get().blocks.entrySet()) {
                    if (special.contains(BlockPos.of(e.getKey()))) {
                        continue;
                    }
                    BlockPos p = w(site, BlockPos.getX(e.getKey()), BlockPos.getY(e.getKey()), BlockPos.getZ(e.getKey()));
                    BlockState want = e.getValue().rotate(rotation);
                    if (level.getBlockState(p) != want) {
                        if (bad++ == 0) {
                            first = r + " at " + p + " local " + BlockPos.of(e.getKey()) + ": wanted " + want + " found " + level.getBlockState(p);
                        }
                    }
                }
                h.assertTrue(bad == 0, bad + " plan blocks differ, first: " + first);
                // the door, turned with the site
                BlockState door = level.getBlockState(HuaVault.world(site, VaultPlan.doorCorner()));
                h.assertTrue(door.is(VaultRegistry.VAULT_DOOR.get()) && door.getValue(VaultDoorBlock.FACING) == HuaVault.turn(site, Direction.SOUTH),
                        "door facing at rotation " + r + ": " + door);
                // chest with loot, swords on stands (no clue block: the verse stone is gone)
                BlockPos chest = HuaVault.world(site, VaultPlan.CHEST);
                h.assertTrue(level.getBlockState(chest).getBlock() instanceof VaultChestBlock && level.getBlockEntity(chest) instanceof RandomizableContainerBlockEntity c
                        && c.getLootTable() != null && c.getLootTable().location().getPath().equals("chests/main_vault"), "no loot chest at " + chest);
                h.assertTrue(level.getBlockState(HuaVault.world(site, VaultPlan.ARMS)).getBlock() instanceof VaultPropBlock, "no spears leaning on the wall");
                h.assertTrue(level.getBlockState(w(site, 8, 140, 51)).getBlock() instanceof VaultTreasureBlock, "no heap under the hole");
                h.assertTrue(!(level.getBlockState(w(site, 4, 177, 91)).getBlock() instanceof io.github.verycooltimo.murim.quest.ClueBlock), "a verse clue block is placed again");
                // cold iron on all sides: nothing outside reaches the shaft, the tunnel or the vault through breakable blocks
                int reached = reachableFromOutside(level, site);
                h.assertTrue(reached == 0, reached + " hollow cells of the vault can be reached by digging, rotation " + r);
            });
        }
        h.runAtTickTime(2 + 4 * 60, h::succeed);
    }

    /** Flood from the open air round the vault through everything a survival player can dig or walk: how many hollow cells it reaches. */
    private static int reachableFromOutside(ServerLevel level, MountHuaSite site) {
        BoundingBox box = HuaVault.box(site);
        BoundingBox big = new BoundingBox(box.minX() - 4, box.minY() - 4, box.minZ() - 4, box.maxX() + 4, box.maxY() + 4, box.maxZ() + 4);
        java.util.Set<Long> seen = new java.util.HashSet<>();
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        for (int x = big.minX(); x <= big.maxX(); x++) {
            for (int y = big.minY(); y <= big.maxY(); y++) {
                for (int z = big.minZ(); z <= big.maxZ(); z++) {
                    boolean edge = x == big.minX() || x == big.maxX() || y == big.minY() || y == big.maxY() || z == big.minZ() || z == big.maxZ();
                    if (edge) {
                        BlockPos p = new BlockPos(x, y, z);
                        if (!unbreakable(level, p) && seen.add(p.asLong())) {
                            queue.add(p);
                        }
                    }
                }
            }
        }
        int hollow = 0;
        while (!queue.isEmpty()) {
            BlockPos c = queue.poll();
            BlockPos local = HuaVault.local(site, c);
            if (VaultPlan.inApproach(local.getX(), local.getY(), local.getZ()) || VaultPlan.inSuite(local.getX(), local.getY(), local.getZ())) {
                hollow++;
            }
            for (Direction d : Direction.values()) {
                BlockPos n = c.relative(d);
                if (big.isInside(n) && !unbreakable(level, n) && seen.add(n.asLong())) {
                    queue.add(n);
                }
            }
        }
        return hollow;
    }

    private static boolean terrain(BlockState s) {
        if (s.isAir()) {
            return true;
        }
        String id = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
        return Set.of("stone", "dirt", "grass_block", "gravel", "coal_ore", "iron_ore", "moss_block", "red_mushroom_block", "deepslate",
                "andesite", "diorite", "granite", "tuff", "calcite", "polished_hua_granite", "polished_hua_granite_stairs").contains(id)
                || id.startsWith("hua_");
    }

    @GameTest(template = YARD, batch = "vault_baked", timeoutTicks = 1200)
    public static void vaultClashesWithNothingOfTheBakedSectAtAnyRotation(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        h.assertTrue(index != null, "baked index missing");
        for (int rot = 0; rot < 4; rot++) {
            int r = rot;
            h.runAtTickTime(2 + rot * 100, () -> {
                MountHuaSite site = site(h, 8 + r, r);
                BoundingBox box = HuaVault.box(site);
                for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : HuaBaked.cells(index)) {
                    if (HuaBaked.cellBox(index, e.getKey(), e.getValue(), site).intersects(box)) {
                        HuaBaked.placeCell(level.getServer(), level, site, index, e.getKey(), e.getValue(), box, level.getRandom(), 2);
                    }
                }
                Map<BlockPos, BlockState> before = new java.util.HashMap<>();
                Rotation rotation = Rotation.values()[r];
                for (Map.Entry<Long, BlockState> e : VaultPlan.get().blocks.entrySet()) {
                    BlockPos local = BlockPos.of(e.getKey());
                    BlockPos p = w(site, local.getX(), local.getY(), local.getZ());
                    before.put(local, level.getBlockState(p));
                }
                HuaVault.place(level, site, null, 2);
                List<String> clashes = new ArrayList<>();
                for (Map.Entry<Long, BlockState> e : VaultPlan.get().blocks.entrySet()) {
                    BlockPos local = BlockPos.of(e.getKey());
                    BlockState was = before.get(local);
                    boolean hatch = local.getY() == VaultPlan.HALL_FLOOR;
                    boolean mat = local.getY() == VaultPlan.HALL_FLOOR + 1 && local.getZ() == 90 && (local.getX() == 9 || local.getX() == 10);
                    if (!terrain(was) && was != e.getValue().rotate(rotation) && !hatch && !mat) {
                        clashes.add(local.toShortString() + " " + BuiltInRegistries.BLOCK.getKey(was.getBlock()).getPath());
                    }
                }
                h.assertTrue(clashes.isEmpty(), "rotation " + r + ": the vault overwrites baked structure: " + clashes.subList(0, Math.min(8, clashes.size())));
            });
        }
        h.runAtTickTime(2 + 4 * 100, h::succeed);
    }

    // ------------------------------------------------------------------ cold iron: no mining, no TNT, no pearl

    @GameTest(template = YARD, batch = "vault_iron", timeoutTicks = 200)
    public static void coldIronCannotBeBrokenOrBlownUpAndPearlsAreBlocked(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.runAtTickTime(2, () -> {
            MountHuaSite site = site(h, 12, 1);
            HuaVault.place(level, site, null, 2);
            BlockPos wall = w(site, 3, 150, 56);
            BlockPos door = HuaVault.world(site, VaultPlan.doorCorner());
            BlockPos sealedBrick = w(site, 11, 150, 80);
            ServerPlayer p = player(h, w(site, 15, 147, 58));
            for (BlockPos q : new BlockPos[] {wall, door, sealedBrick}) {
                h.assertTrue(level.getBlockState(q).getDestroyProgress(p, level, q) == 0.0F, "survival digs " + level.getBlockState(q));
            }
            h.assertTrue(level.getBlockState(wall).is(VaultRegistry.VAULT_IRON.get()), "no iron at the wall: " + level.getBlockState(wall));
            // a blast in the middle of the study and one against the tunnel wall
            level.explode(null, w(site, 15, 148, 58).getX() + 0.5D, w(site, 15, 148, 58).getY() + 0.5D, w(site, 15, 148, 58).getZ() + 0.5D, 16.0F, Level.ExplosionInteraction.TNT);
            level.explode(null, w(site, 15, 149, 80).getX() + 0.5D, w(site, 15, 149, 80).getY() + 0.5D, w(site, 15, 149, 80).getZ() + 0.5D, 16.0F, Level.ExplosionInteraction.TNT);
            Rotation rotation = Rotation.values()[1];
            int lost = 0;
            for (Map.Entry<Long, BlockState> e : VaultPlan.get().blocks.entrySet()) {
                BlockState want = e.getValue().rotate(rotation);
                if (want.getDestroySpeed(level, BlockPos.ZERO) < 0.0F) {
                    BlockPos q = w(site, BlockPos.getX(e.getKey()), BlockPos.getY(e.getKey()), BlockPos.getZ(e.getKey()));
                    if (level.getBlockState(q) != want) {
                        lost++;
                    }
                }
            }
            h.assertTrue(lost == 0, lost + " unbreakable blocks were blown away");
            // pearls: from outside into the tunnel, from the tunnel into the vault, and inside the vault
            Vec3 outside = Vec3.atCenterOf(w(site, 33, 150, 70));
            Vec3 tunnel = Vec3.atCenterOf(w(site, 15, 148, 80));
            Vec3 study = Vec3.atCenterOf(w(site, 15, 148, 58));
            Vec3 study2 = Vec3.atCenterOf(w(site, 9, 148, 58));
            h.assertTrue(VaultGuard.blocks(site, outside, tunnel), "outside to the tunnel is free");
            h.assertTrue(VaultGuard.blocks(site, outside, study), "outside to the vault is free");
            h.assertTrue(VaultGuard.blocks(site, tunnel, study), "tunnel to the vault is free");
            h.assertTrue(!VaultGuard.blocks(site, study, study2) && !VaultGuard.blocks(site, study, outside) && !VaultGuard.blocks(site, tunnel, outside),
                    "moving inside or out is blocked");
            // the event itself
            VaultGuard.testSite = site;
            try {
                ServerPlayer thrower = player(h, BlockPos.containing(outside));
                ThrownEnderpearl pearl = new ThrownEnderpearl(level, thrower);
                EntityTeleportEvent.EnderPearl event = new EntityTeleportEvent.EnderPearl(thrower, study.x, study.y, study.z, pearl, 5.0F,
                        new BlockHitResult(study, Direction.UP, BlockPos.containing(study), false));
                NeoForge.EVENT_BUS.post(event);
                h.assertTrue(event.isCanceled(), "a pearl into the vault was not cancelled");
                EntityTeleportEvent.EnderPearl free = new EntityTeleportEvent.EnderPearl(thrower, outside.x + 3, outside.y, outside.z, pearl, 5.0F,
                        new BlockHitResult(outside, Direction.UP, BlockPos.containing(outside), false));
                NeoForge.EVENT_BUS.post(free);
                h.assertTrue(!free.isCanceled(), "a pearl outside was cancelled");
            } finally {
                VaultGuard.testSite = null;
            }
            h.succeed();
        });
    }

    // ------------------------------------------------------------------ the rite in the Ancestors' Hall

    private static void tablets(ServerLevel level, MountHuaSite site) {
        for (int[] t : VaultPlan.TABLETS) {
            level.setBlock(w(site, t[0], VaultPlan.TABLET_Y, VaultPlan.TABLET_V), ShrineDecor.ANCESTOR_TABLETS.get().defaultBlockState()
                    .setValue(ShrineBlock.FACING, HuaVault.turn(site, Direction.SOUTH)), 2);
        }
    }

    private static void touch(ServerLevel level, ServerPlayer p, BlockPos at) {
        level.getBlockState(at).useWithoutItem(level, p, new BlockHitResult(Vec3.atCenterOf(at), Direction.UP, at, false));
    }

    @GameTest(template = YARD, batch = "vault_rite", timeoutTicks = 200)
    public static void foundersRiteOpensTheHatchAndNothingElseDoes(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.runAtTickTime(2, () -> {
            MountHuaSite site = site(h, 13, 2);
            tablets(level, site);
            HuaVault.place(level, site, null, 2);
            VaultData.get(level).unlock();
            BlockPos hatch = w(site, 8, 176, 89);
            h.assertTrue(level.getBlockState(hatch).is(VaultRegistry.VAULT_FLOOR.get()) && !level.getBlockState(hatch).getValue(VaultFloorBlock.OPEN), "hatch not shut");
            BlockPos[] t = new BlockPos[5];
            for (int o = 1; o <= 4; o++) {
                t[o] = HuaVault.tablet(site, o);
                h.assertTrue(level.getBlockState(t[o]).is(VaultRegistry.LOCK_TABLET.get()), "tablet " + o + " not swapped");
            }
            ServerPlayer p = player(h, w(site, 6, 177, 89));
            // wrong order, and a plain tablet: nothing
            touch(level, p, t[2]);
            touch(level, p, t[3]);
            touch(level, p, t[4]);
            h.assertTrue(!level.getBlockState(hatch).getValue(VaultFloorBlock.OPEN), "the hatch opened for a wrong order");
            // the rite: right near, left near, right next, left next
            for (int o = 1; o <= 4; o++) {
                touch(level, p, t[o]);
            }
            h.assertTrue(level.getBlockState(hatch).getValue(VaultFloorBlock.OPEN), "the hatch did not open");
            // it shuts by itself, unless somebody stands in it
            BlockState open = level.getBlockState(hatch);
            open.tick(level, hatch, level.random);
            h.assertTrue(!level.getBlockState(hatch).getValue(VaultFloorBlock.OPEN), "the hatch did not shut again");
            // a changed lock keeps it shut
            VaultData.get(level).lock(level.getGameTime() + 1000);
            for (int o = 1; o <= 4; o++) {
                touch(level, p, t[o]);
            }
            h.assertTrue(!level.getBlockState(hatch).getValue(VaultFloorBlock.OPEN), "the hatch opened under a changed lock");
            VaultData.get(level).unlock();
            h.succeed();
        });
    }

    // Allow the existing 1800-tick chunk-startup window AND the 400-tick exit observation.
    // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/gametest/framework/GameTestInfo.java#tickInternal
    // The annotation's timeout includes setup/readiness; succeedWhen does not restart that clock.
    @GameTest(template = YARD, batch = "vault_exit", timeoutTicks = 2205)
    public static void insideExitNeedsNoFlagsAndClosesBehind(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        var fixture = new io.github.verycooltimo.murim.sect.EntryTestFixtures(h, "vault_exit");
        // API: Minecraft 1.21.1 GameTestInfo#tickInternal runs sequences after scheduled tasks.
        // GameTestHelper#onEachTick adds scheduled tasks: registering it inside runAtTickTime
        // mutates the map being iterated and can repeat setup (and the final-clause registration).
        h.startSequence().thenExecuteAfter(2, () -> {
            MountHuaSite[] sites = new MountHuaSite[4];
            for (int rotation = 0; rotation < 4; rotation++) {
                MountHuaSite site = site(h, 20 + rotation, rotation);
                sites[rotation] = site;
                // API pattern: SectGateWatchGameTests#trail. Include neighbours of edge cells.
                BoundingBox bounds = HuaVault.box(site);
                fixture.force(new BlockPos(bounds.minX(), bounds.minY(), bounds.minZ()),
                        new BlockPos(bounds.maxX(), bounds.maxY(), bounds.maxZ()));
                HuaVault.place(level, site, null, 2);
            }
            // API pattern: SectEscortArrivalGameTests#roadTicking. A fixed delay does not
            // establish readiness of remote chunks; schedule closing only after every site ticks.
            boolean[] opened = {false};
            boolean[] left = {false};
            long[] openedAt = {-1L};
            ServerPlayer[] exiting = new ServerPlayer[4];
            h.onEachTick(() -> {
                if (!opened[0]) h.assertTrue(h.getTick() <= 1800,
                        "vault exit chunks did not become ready within 1800 ticks");
                else h.assertTrue(level.getGameTime() - openedAt[0] <= 400,
                        "vault exit timers exceeded 400 ticks after chunk readiness; left=" + left[0]);
            });
            h.succeedWhen(() -> {
                h.assertTrue(fixture.ready(), "vault exit chunks are not entity-ticking");
                if (!opened[0]) {
                    for (int rotation = 0; rotation < sites.length; rotation++) {
                        MountHuaSite site = sites[rotation];
                        BlockPos door = w(site, 14, 147, VaultPlan.DOOR_V);
                        ServerPlayer p = fixture.track(player(h, w(site, 14, 147, VaultPlan.DOOR_V + 1)));
                        // Scheduled block ticks use level entity queries, not this Java reference.
                        level.addNewPlayer(p);
                        exiting[rotation] = p;
                        p.setData(ModAttachments.SECT, SectState.NONE);
                        VaultExit.approach(p);
                        h.assertTrue(!VaultDoorBlock.isOpen(level, door), "outside approach bypassed form");
                        BlockPos inside = w(site, 14, 147, VaultPlan.DOOR_V - 1);
                        p.moveTo(inside.getX() + 0.5, inside.getY(), inside.getZ() + 0.5, 0, 0);
                        if (rotation % 2 == 0) VaultExit.approach(p);
                        else ((VaultDoorBlock) level.getBlockState(door).getBlock()).useWithoutItem(level.getBlockState(door), level, door, p,
                                new BlockHitResult(door.getCenter(), Direction.NORTH, door, false));
                        for (BlockPos cell : VaultDoorBlock.cells(level, door))
                            h.assertTrue(level.getBlockState(cell).getCollisionShape(level, cell).isEmpty(), "inside door blocks exit");
                        BlockPos top = w(site, 10, 175, 89);
                        p.moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, 0, 0);
                        BlockPos hatch = w(site, 10, 176, 89);
                        if (rotation % 2 == 0) VaultExit.approach(p);
                        else ((VaultFloorBlock) level.getBlockState(hatch).getBlock()).useWithoutItem(level.getBlockState(hatch), level, hatch, p,
                                new BlockHitResult(hatch.getCenter(), Direction.DOWN, hatch, false));
                        for (BlockPos local : VaultPlan.hatchCells()) {
                            BlockPos at = HuaVault.world(site, local);
                            h.assertTrue(level.getBlockState(at).getCollisionShape(level, at).isEmpty(), "hatch blocks ascent");
                        }
                        for (int y = VaultPlan.PIT_FLOOR + 1; y <= VaultPlan.TUNNEL_FLOOR; y++) {
                            BlockPos ladder = w(site, VaultPlan.LADDER_U, y, VaultPlan.LADDER_V);
                            h.assertTrue(level.getBlockState(ladder).getBlock() instanceof net.minecraft.world.level.block.LadderBlock,
                                    "gap in pit ladder at " + y);
                            BlockPos backing = w(site, VaultPlan.LADDER_U - 1, y, VaultPlan.LADDER_V);
                            h.assertTrue(!level.getBlockState(backing).getCollisionShape(level, backing).isEmpty(), "unsupported ladder");
                        }
                        h.assertTrue(level.getBlockState(w(site, VaultPlan.LADDER_U + 1, VaultPlan.PIT_FLOOR + 1, VaultPlan.LADDER_V - 1))
                                .is(Blocks.LANTERN), "pit climb has no lantern");
                    }
                    opened[0] = true;
                    openedAt[0] = level.getGameTime();
                }
                h.assertTrue(level.getGameTime() - openedAt[0] >= 80, "waiting through the first hatch close timer");
                if (!left[0]) {
                    for (int rotation = 0; rotation < sites.length; rotation++) {
                        for (BlockPos local : VaultPlan.hatchCells())
                            h.assertTrue(level.getBlockState(HuaVault.world(sites[rotation], local)).getValue(VaultFloorBlock.OPEN),
                                    "hatch closed on the exiting player, rotation=" + rotation);
                        BlockPos hall = w(sites[rotation], 4, 177, 89);
                        exiting[rotation].moveTo(hall.getX() + 0.5, hall.getY(), hall.getZ() + 0.5, 0, 0);
                    }
                    left[0] = true;
                }
                // Observe real scheduled ticks; never invoke block tick methods in this test.
                for (int rotation = 0; rotation < sites.length; rotation++) {
                    MountHuaSite site = sites[rotation];
                    BlockPos door = w(site, 14, 147, VaultPlan.DOOR_V);
                    h.assertTrue(!VaultDoorBlock.isOpen(level, door),
                            "door did not close behind, rotation=" + rotation + ", state=" + level.getBlockState(door));
                    for (BlockPos local : VaultPlan.hatchCells()) {
                        BlockPos at = HuaVault.world(site, local);
                        h.assertTrue(!level.getBlockState(at).getValue(VaultFloorBlock.OPEN),
                                "hatch did not close behind, rotation=" + rotation + ", cell=" + at
                                        + ", elapsed=" + (level.getGameTime() - openedAt[0])
                                        + ", player=" + exiting[rotation].blockPosition());
                    }
                }
            });
        });
    }

    @net.minecraft.gametest.framework.AfterBatch(batch = "vault_exit")
    public static void cleanupExit(ServerLevel level) {
        io.github.verycooltimo.murim.sect.EntryTestFixtures.closeBatch("vault_exit");
    }

    // ------------------------------------------------------------------ the door

    @GameTest(template = YARD, batch = "vault_door", timeoutTicks = 400)
    public static void doorOpensOnlyForTheFormsOfTwentyFourPlum(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.runAtTickTime(2, () -> {
            MountHuaSite site = site(h, 14, 3);
            HuaVault.place(level, site, null, 2);
            BlockPos seal = w(site, VaultPlan.TRIAL_SPOT.getX(), VaultPlan.TRIAL_SPOT.getY(), VaultPlan.TRIAL_SPOT.getZ());
            BlockPos doorCell = HuaVault.world(site, VaultPlan.doorCorner());
            BlockPos middle = w(site, 15, 148, 64);
            ServerPlayer p = player(h, w(site, 15, 147, 67));
            p.setData(ModAttachments.SECT, SectState.NONE);
            h.assertTrue(VaultLadder.LADDER.size() == 5 && VaultLadder.LADDER.stream().allMatch(s -> Styles.TWENTY_FOUR_PLUM.forms().contains(s.technique())),
                    "the ladder is not the five forms");
            // the old ladder does not open it: six forms of Six Harmonies, Falling Petal, all of Seven Plum
            VaultTrial.begin(p, seal);
            for (int i = 0; i < 6; i++) {
                VaultTrial.onFoundation(p, VaultLadder.SIX, i);
            }
            VaultTrial.onTechnique(p, VaultLadder.PETAL);
            for (var form : Styles.SEVEN_PLUM.forms()) {
                VaultTrial.onTechnique(p, form);
            }
            h.assertTrue(!VaultDoorBlock.isOpen(level, doorCell), "the old ladder opened the door");
            // Starting an accepted form must keep every cell sealed until a successful finish.
            var form = Styles.TWENTY_FOUR_PLUM.forms().get(2);
            p.setData(ModAttachments.TECHNIQUE_STATE,
                    io.github.verycooltimo.murim.combat.TechniqueState.started(form, level.getGameTime()));
            VaultTrial.onTechnique(p, form);
            h.assertTrue(VaultTrial.active(p), "trial did not track the form");
            h.assertTrue(!VaultDoorBlock.isOpen(level, doorCell), "form start opened door");
            io.github.verycooltimo.murim.combat.TechniqueService.cancel(p);
            h.assertTrue(!VaultDoorBlock.isOpen(level, doorCell), "cancel opened door");
            // Exercise the unchanged opening/closing mechanism independently of the cancelled trial.
            VaultDoorBlock.openCompleted(level, doorCell);
            h.runAfterDelay(2, () -> {
                h.assertTrue(level.getBlockState(doorCell).getValue(VaultDoorBlock.STATE) == VaultDoorBlock.State.OPEN, "the door did not open");
                h.assertTrue(level.getBlockState(middle).getCollisionShape(level, middle).isEmpty(), "the middle of the open door is not free");
                h.assertTrue(!p.getData(ModAttachments.SECT).has(SealAccess.VAULT_FLAG), "cancel granted vault access");
                h.succeed();
            });
        });
    }

    private static final class ResourceLocationOf {
        static net.minecraft.resources.ResourceLocation of(String path) {
            return net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
        }
    }

    // ------------------------------------------------------------------ the shelf and the pit

    /** Solid blocks left of the hidden shelf (4 wide, 2 deep, 4 high). */
    private static int shelfBlocks(ServerLevel level, MountHuaSite site) {
        int n = 0;
        for (int u = VaultPlan.MIDDLE_U; u < VaultPlan.MIDDLE_U + VaultPlan.SHELF_W; u++) {
            for (int v = VaultPlan.SHELF_V; v < VaultPlan.SHELF_V + VaultPlan.SHELF_DEPTH; v++) {
                for (int y = VaultPlan.TUNNEL_FLOOR + 1; y <= VaultPlan.TUNNEL_FLOOR + 4; y++) {
                    if (!level.getBlockState(HuaVault.world(site, new BlockPos(u, y, v))).isAir()) {
                        n++;
                    }
                }
            }
        }
        return n;
    }

    @GameTest(template = YARD, batch = "vault_shelf", timeoutTicks = 100)
    public static void digThePlainMiddleShelfAndTheHoleShowsWithTheHeadsScroll(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        h.runAtTickTime(2, () -> {
            MountHuaSite site = site(h, 15, 0);
            HuaVault.place(level, site, null, 2);
            BlockPos shelf = w(site, VaultPlan.MIDDLE_U + 1, 148, VaultPlan.SHELF_V);
            BlockPos under = w(site, VaultPlan.MIDDLE_U + 1, 146, VaultPlan.SHELF_V);
            ServerPlayer p = player(h, w(site, 15, 147, 55));
            h.assertTrue(level.getBlockState(shelf).getBlock() == VaultRegistry.VAULT_SHELF.get(), "not an archive shelf: " + level.getBlockState(shelf));
            h.assertTrue(level.getBlockState(shelf).getDestroyProgress(p, level, shelf) > 0.0F, "the shelf cannot be dug");
            h.assertTrue(level.getBlockState(under).getCollisionShape(level, under).isEmpty() && level.getBlockState(under.below()).getCollisionShape(level, under.below()).isEmpty(), "no hole under the shelf");
            h.assertTrue(level.getBlockState(under).isAir(), "the hole is not an open hole");
            h.assertTrue(level.getBlockState(w(site, VaultPlan.LADDER_U, 146, VaultPlan.LADDER_V)).getBlock() instanceof net.minecraft.world.level.block.LadderBlock, "no ladder in the hole");
            // dig it: one block at a time, nothing else moves (author 24.10: no scripted collapse); the hole is there to see, the stone round it holds
            int before = shelfBlocks(level, site);
            h.assertTrue(before == VaultPlan.SHELF_W * VaultPlan.SHELF_DEPTH * 4, "the hidden shelf is not 4 x 2 x 4 plain blocks: " + before);
            // a library shelf is two halves of one piece (like a door): one dig takes the piece, never a neighbour
            level.destroyBlock(shelf, false);
            int after = shelfBlocks(level, site);
            h.assertTrue(after == before - 2 || after == before - 1, "breaking one shelf took more than its own piece: " + (before - after));
            BlockPos next = w(site, VaultPlan.MIDDLE_U + 2, 148, VaultPlan.SHELF_V);
            h.assertTrue(!level.getBlockState(next).isAir(), "breaking a shelf broke its neighbour");
            level.destroyBlock(shelf.below(), false);
            level.destroyBlock(shelf.above(), false);
            h.assertTrue(!level.getBlockState(next).isAir(), "breaking a shelf broke its neighbour (2)");
            int dug = shelfBlocks(level, site);
            h.runAfterDelay(30, () -> h.assertTrue(shelfBlocks(level, site) == dug, "the rest of the shelf moved or fell by itself"));
            h.assertTrue(level.getBlockState(shelf).isAir() && level.getBlockState(under).getCollisionShape(level, under).isEmpty(), "digging the shelf closed the hole");
            h.assertTrue(reachableFromOutside(level, site) == 0, "digging the shelf opened the vault to the outside");
            BlockPos chest = HuaVault.world(site, VaultPlan.CHEST);
            RandomizableContainerBlockEntity c = (RandomizableContainerBlockEntity) level.getBlockEntity(chest);
            c.unpackLootTable(p);
            boolean scroll = false;
            for (int i = 0; i < c.getContainerSize(); i++) {
                var s = c.getItem(i);
                if (s.is(io.github.verycooltimo.murim.registry.ModItems.METHOD_SCROLL.get())
                        && ResourceLocationOf.of("purple_sky_divine").equals(s.get(io.github.verycooltimo.murim.registry.ModDataComponents.METHOD.get()))) {
                    scroll = true;
                }
            }
            h.assertTrue(scroll, "the head's scroll is not in the chest");
            h.runAfterDelay(35, h::succeed);
        });
    }

    @GameTest(template = YARD, batch = "vault_access", timeoutTicks = 40)
    public static void headsDivineArtIsReadableWithoutVaultPermission(GameTestHelper h) {
        var divine = MethodLoader.get(ResourceLocationOf.of("purple_sky_divine"));
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(3, 1, 3)));
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withRank(Realm.FIRST));
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        h.assertTrue(BreathingAccess.allowed(p, divine), "held head scroll still requires vault permission");
        // nobody is allowed into the vault zone, title or pass or no
        SectAccess.Rule vault = SectAccess.RULES.stream().filter(r -> "vault".equals(r.id())).findFirst().orElseThrow();
        for (SectStanding st : SectStanding.values()) {
            h.assertTrue(!SectAccess.allowed(vault, st, Set.of("vault.permitted", "vault.opened", "pass.main_hall", "pass.elders"), SectSchedule.Period.TRAINING),
                    "the vault zone lets in " + st);
        }
        h.succeed();
    }

    // ------------------------------------------------------------------ the lost art

    @GameTest(template = YARD, batch = "vault_art", timeoutTicks = 100)
    public static void completeVaultManualTeachesAllFiveFormsToTwelveStarsInOrder(GameTestHelper h) {
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(3, 1, 3)));
        p.setData(ModAttachments.PROFILE, peak());
        for (int i = 0; i < PlumRestoration.FRAGMENTS; i++) Clues.learn(p, PlumRestoration.clue(i));
        var forms = Styles.TWENTY_FOUR_PLUM.forms();
        var book = new net.minecraft.world.item.ItemStack(io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get());
        book.set(io.github.verycooltimo.murim.registry.ModDataComponents.TECHNIQUE.get(), forms.get(0));
        h.assertTrue(io.github.verycooltimo.murim.mastery.ManualDepth.stars(book, forms.get(0)) == 0, "vault book is torn");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, book);
        // The complete book still cannot teach the next form before Rainfall reaches five stars.
        io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(p, net.minecraft.world.InteractionHand.MAIN_HAND, forms.get(1));
        h.assertTrue(!MasteryService.knows(p, forms.get(1)), "book skipped previous form");
        for (int i = 0; i < forms.size(); i++) {
            p.getCooldowns().addCooldown(book.getItem(), 0);
            io.github.verycooltimo.murim.item.TechniqueManualItem.learnFromHand(p, net.minecraft.world.InteractionHand.MAIN_HAND, forms.get(i));
            var st = p.getData(ModAttachments.MASTERY);
            h.assertTrue(st.knows(forms.get(i)) && st.techniques().get(forms.get(i)).cap() == 12, "complete manual failed form " + i);
            var now = st.techniques().get(forms.get(i));
            p.setData(ModAttachments.MASTERY, st.with(forms.get(i), new TechniqueProgress(5, 0, 0, 0L, now.cap())));
        }
        PlumRestoration.sync(p);
        h.assertTrue(p.getData(ModAttachments.MASTERY).techniques().size() >= 5, "forms lost after fragment sync");
        for (var form : forms) h.assertTrue(p.getData(ModAttachments.MASTERY).techniques().get(form).cap() == 12, "fragments lowered vault cap");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_art", timeoutTicks = 60)
    public static void fragmentsTeachTwentyFourPlumAndTheArtGrowsWithThem(GameTestHelper h) {
        ServerPlayer p = player(h, h.absolutePos(new BlockPos(3, 1, 3)));
        p.setData(ModAttachments.PROFILE, peak());
        var forms = Styles.TWENTY_FOUR_PLUM.forms();
        h.assertTrue(!MasteryService.knows(p, forms.get(0)), "known from the start");
        Clues.give(p, "frag_1");
        h.assertTrue(MasteryService.knows(p, forms.get(0)), "the rainfall fragment taught nothing");
        h.assertTrue(p.getData(ModAttachments.MASTERY).techniques().get(forms.get(0)).cap() == 2, "one fragment is two stars");
        h.assertTrue(!MasteryService.knows(p, forms.get(1)), "the dome came from one fragment");
        // the four fragments that can be found today
        Clues.give(p, "frag_0");
        Clues.give(p, "frag_3");
        Clues.give(p, "frag_5");
        Clues.learn(p, "frag_2");
        Clues.learn(p, "frag_4");
        h.assertTrue(p.getData(ModAttachments.MASTERY).techniques().get(forms.get(0)).cap() == 5, "fragments exceed five stars");
        for (int i = 1; i < forms.size(); i++) {
            h.assertTrue(!MasteryService.knows(p, forms.get(i)), "fragments taught form " + i);
        }
        // without the Peak nothing is learned
        ServerPlayer q = player(h, h.absolutePos(new BlockPos(5, 1, 3)));
        q.setData(ModAttachments.PROFILE, DantianProfile.INITIAL.withTags("clear", "plum").withRank(Realm.FIRST));
        Clues.give(q, "frag_1");
        h.assertTrue(!MasteryService.knows(q, forms.get(0)), "learned below the Peak");
        // the pure rules: no frag_0 means no dome and no river from the pieces around them
        h.assertTrue(PlumRestoration.restored(n -> n == 1 || n == 3 || n == 5).equals(List.of(0)), "without the order");
        h.assertTrue(PlumRestoration.restored(n -> n == 2 || n == 4).equals(List.of(0)), "the stele pieces");
        h.succeed();
    }

    @GameTest(template = YARD, batch = "vault_art", timeoutTicks = 100)
    public static void twentyFourPlumIsGrantedNowhereElse(GameTestHelper h) throws Exception {
        for (String field : new String[] {"PAGES"}) {
            var f = io.github.verycooltimo.murim.sect.SectContracts.class.getDeclaredField(field);
            f.setAccessible(true);
            for (Object o : (List<?>) f.get(null)) {
                h.assertTrue(!o.toString().contains("twenty_four_plum"), "contract pages hand out " + o);
            }
        }
        var m = io.github.verycooltimo.murim.sect.SectProgression.class.getDeclaredField("MANUALS");
        m.setAccessible(true);
        for (Object o : (List<?>) m.get(null)) {
            h.assertTrue(!o.toString().contains("twenty_four_plum"), "progression manuals hand out " + o);
        }
        // Only the main vault may contain the intended Rainfall reward; all other forms and sources remain forbidden.
        var manager = h.getLevel().getServer().getResourceManager();
        for (var e : manager.listResources("loot_table", rl -> rl.getNamespace().equals(MurimMod.MODID) && rl.getPath().endsWith(".json")).entrySet()) {
            try (var in = e.getValue().openAsReader()) {
                String text = new String(readAll(in));
                if (e.getKey().getPath().equals("loot_table/chests/main_vault.json")) {
                    text = text.replace("\"murim:twenty_four_plum_rainfall\"", "\"vault_reward\"");
                }
                h.assertTrue(!text.contains("twenty_four_plum"), "loot table " + e.getKey() + " hands out the lost art");
            }
        }
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 100)
    public static void vaultPillsBelongToMountHua(GameTestHelper h) throws Exception {
        var manager = h.getLevel().getServer().getResourceManager();
        for (String name : new String[] {"main_vault", "main_vault_study"}) {
            var id = net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID,
                    "loot_table/chests/" + name + ".json");
            try (var reader = manager.getResource(id).orElseThrow().openAsReader()) {
                var pools = com.google.gson.JsonParser.parseReader(reader).getAsJsonObject().getAsJsonArray("pools");
                int purplePools = 0;
                for (var pool : pools) {
                    var object = pool.getAsJsonObject();
                    var entries = object.getAsJsonArray("entries");
                    for (var entry : entries) {
                        var item = entry.getAsJsonObject();
                        String itemName = item.get("name").getAsString();
                        h.assertTrue(!java.util.Set.of("murim:pill_blue", "murim:pill_quick_recovery", "murim:pill_origin_energy",
                                "murim:pill_antidote", "murim:pill_blood_replenishing", "murim:pill_blood").contains(itemName),
                                "foreign pill in " + name + ": " + itemName);
                        if (itemName.equals("murim:pill_purple_heaven")) {
                            purplePools++;
                            h.assertTrue(entries.size() == 1 && object.get("rolls").getAsInt() == 1, "purple pill is not guaranteed");
                            var count = item.getAsJsonArray("functions").get(0).getAsJsonObject().getAsJsonObject("count");
                            h.assertTrue(count.get("min").getAsInt() == 1 && count.get("max").getAsInt()
                                    == (name.equals("main_vault") ? 2 : 1), "wrong purple pill count");
                        }
                    }
                }
                h.assertTrue(purplePools == 1, "missing or duplicated purple heaven pool");
            }
        }
        h.succeed();
    }

    private static char[] readAll(java.io.Reader r) throws java.io.IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[4096];
        int n;
        while ((n = r.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        return sb.toString().toCharArray();
    }
}
