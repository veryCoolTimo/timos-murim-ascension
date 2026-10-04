package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * GameTests of the abandoned archive (docs/design/25-ruined-library.md §6): the piece builds on a test floor with its
 * shelves full of clone junk and the propped shelf in place; pulling the book drops one genuine manual; the loot tables
 * hold their promises over 1000 rolls; and the first-visit model runs over 1000 archives with the barrel chance measured
 * on the real desk table.
 *
 * <p>Template: {@code data/murim/structure/library_floor.nbt} (tools/gametest/library_template.py).
 * API: reference/neoforge-src/net/neoforged/neoforge/gametest/GameTestHolder.java,
 * reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class LibraryTests {

    private static final long SEED = 0x1915L;

    private LibraryTests() {
    }

    /** Builds the whole piece into the test box (orientation NORTH, mouth at local y 27). */
    static LibraryPiece build(GameTestHelper helper, long seed) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        LibraryPiece piece = new LibraryPiece(seed, origin.getX(), origin.getY(), origin.getZ(), Direction.NORTH, 27);
        BoundingBox b = piece.getBoundingBox();
        BoundingBox box = new BoundingBox(b.minX(), b.minY() - 1, b.minZ(), b.maxX(), b.maxY(), b.maxZ());
        piece.postProcess(level, level.structureManager(), level.getChunkSource().getGenerator(), level.getRandom(),
                box, new ChunkPos(origin), origin);
        return piece;
    }

    private static LootTable table(ServerLevel level, net.minecraft.resources.ResourceKey<LootTable> key) {
        return level.getServer().reloadableRegistries().getLootTable(key);
    }

    private static LootParams params(ServerLevel level, BlockPos at) {
        return new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(at)).create(LootContextParamSets.CHEST);
    }

    /** The archive stands: shelves with junk, lanterns, record barrels, the keeper's note, the propped shelf. */
    @GameTest(template = "library_floor", timeoutTicks = 100)
    public static void archiveBuilds(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LibraryPiece piece = build(helper, SEED);
        LibraryPlan plan = piece.plan();
        int shelvesWithBooks = 0;
        int books = 0;
        int junk = 0;
        int lanterns = 0;
        int barrels = 0;
        boolean clue = false;
        BoundingBox b = piece.getBoundingBox();
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.LANTERN)) {
                lanterns++;
            }
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof ChiseledBookShelfBlockEntity shelf && !shelf.isEmpty()) {
                shelvesWithBooks++;
                for (int i = 0; i < 6; i++) {
                    ItemStack st = shelf.getItem(i);
                    if (!st.isEmpty()) {
                        books++;
                        junk += st.has(ModLibrary.JUNK.get()) ? 1 : 0;
                    }
                }
            }
            if (be instanceof BarrelBlockEntity barrel) {
                barrels++;
                helper.assertTrue(barrel.getLootTable() != null, "record barrel without loot table at " + p);
                ItemStack note = barrel.getItem(13);
                if (note.has(ModLibrary.JUNK.get()) && note.get(ModLibrary.JUNK.get()).kind() == JunkKind.CLUE) {
                    clue = true;
                    helper.assertTrue(note.get(ModLibrary.JUNK.get()).hint() == plan.proppedNumber(), "the note names another shelf");
                }
            }
        }
        int[] c = plan.proppedCell();
        BlockPos proppedPos = piece.archive(c[0], 1, c[1]);
        helper.assertTrue(level.getBlockState(proppedPos).is(ModLibrary.PROPPED_SHELF.get()), "no propped shelf at " + proppedPos);
        helper.assertTrue(clue, "no keeper's note in the top record barrel");
        helper.assertTrue(barrels == 4, "record barrels " + barrels + " instead of 4");
        helper.assertTrue(shelvesWithBooks > 150, "shelves with books " + shelvesWithBooks);
        helper.assertTrue(books == plan.books(), "volumes in the world " + books + " vs plan " + plan.books());
        helper.assertTrue(junk >= books * 0.98D, "junk " + junk + " of " + books);
        helper.assertTrue(lanterns >= 30, "lanterns " + lanterns);
        MurimMod.LOGGER.info("GameTest archive: {} volumes ({} junk) on {} shelves, {} lanterns, propped shelf No. {} at {}",
                books, junk, shelvesWithBooks, lanterns, plan.proppedNumber(), proppedPos);
        helper.succeed();
    }

    /** Pulling the book from under the propped shelf drops exactly one genuine manual; the shelf stays a shelf. */
    @GameTest(template = "library_floor", timeoutTicks = 100)
    public static void proppedShelfGivesTheBook(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LibraryPiece piece = build(helper, SEED + 7);
        int[] c = piece.plan().proppedCell();
        BlockPos pos = piece.archive(c[0], 1, c[1]);
        BlockState state = level.getBlockState(pos);
        helper.assertTrue(state.is(ModLibrary.PROPPED_SHELF.get()), "no propped shelf");
        ProppedShelfBlock.pull(level, pos, state);
        helper.assertTrue(level.getBlockState(pos).is(Blocks.CHISELED_BOOKSHELF), "the propped shelf did not become a shelf");
        List<ItemEntity> drops = level.getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(2.0D));
        long manuals = drops.stream().filter(e -> e.getItem().is(ModItems.TECHNIQUE_MANUAL.get())
                && e.getItem().get(ModDataComponents.TECHNIQUE.get()) != null).count();
        helper.assertTrue(manuals == 1, "genuine manuals dropped: " + manuals);
        ResourceLocation id = drops.get(0).getItem().get(ModDataComponents.TECHNIQUE.get());
        MurimMod.LOGGER.info("GameTest archive: the propped shelf gave {}", id);
        drops.forEach(ItemEntity::discard);
        helper.succeed();
    }

    /**
     * Loot over 1000 rolls: the propped table always gives one genuine manual; the desk table gives clone junk, paper,
     * sometimes a map or a torn page. Then the visit model over 1000 archives with the measured barrel chance.
     */
    @GameTest(template = "library_floor", timeoutTicks = 400)
    public static void lootSimulation(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos at = helper.absolutePos(new BlockPos(2, 2, 2));
        LootTable propped = table(level, ModLibrary.PROPPED_LOOT);
        Map<String, Integer> found = new HashMap<>();
        for (int i = 0; i < 1000; i++) {
            List<ItemStack> roll = propped.getRandomItems(params(level, at), 1000L + i);
            helper.assertTrue(roll.size() == 1 && roll.get(0).is(ModItems.TECHNIQUE_MANUAL.get()), "propped roll " + i + ": " + roll);
            ResourceLocation id = roll.get(0).get(ModDataComponents.TECHNIQUE.get());
            helper.assertTrue(id != null, "manual without a technique");
            Integer depth = roll.get(0).get(ModDataComponents.MANUAL_DEPTH.get());
            found.merge(id.getPath() + (depth == null ? "" : " (torn " + depth + ")"), 1, Integer::sum);
        }
        LootTable desk = table(level, ModLibrary.DESK_LOOT);
        int rolls = 400;
        int junk = 0;
        int genuine = 0;
        int maps = 0;
        int letters = 0;
        for (int i = 0; i < rolls; i++) {
            boolean any = false;
            for (ItemStack st : desk.getRandomItems(params(level, at), 5000L + i)) {
                if (st.getItem() instanceof JunkBookItem) {
                    helper.assertTrue(st.has(ModLibrary.JUNK.get()), "junk without text");
                    junk++;
                    letters += st.get(ModLibrary.JUNK.get()).kind().letters() ? 1 : 0;
                    helper.assertTrue(st.get(ModLibrary.JUNK.get()).kind().letters() == st.is(ModLibrary.JUNK_LETTERS.get()), "letters in a manual cover");
                }
                any |= st.is(ModItems.TECHNIQUE_MANUAL.get());
                maps += st.is(Items.MAP) || st.is(Items.FILLED_MAP) ? 1 : 0;
            }
            genuine += any ? 1 : 0;
        }
        double barrelChance = (double) genuine / rolls;
        helper.assertTrue(junk >= rolls * 2, "too little junk in record barrels: " + junk);
        helper.assertTrue(barrelChance < 0.1D, "record barrels too generous: " + barrelChance);
        LibrarySim.Summary sim = LibrarySim.run(20261004L, 1000, barrelChance);
        helper.assertTrue(sim.sourceShare()[LibrarySim.Source.NONE.ordinal()] == 0.0D, "an archive with no find");
        MurimMod.LOGGER.info("GameTest archive loot: propped x1000 {}", found);
        MurimMod.LOGGER.info("GameTest archive loot: desk x{} junk {} (letters {}), maps {}, torn genuine {} ({}%)",
                rolls, junk, letters, maps, genuine, String.format("%.1f", barrelChance * 100));
        MurimMod.LOGGER.info("GameTest archive sim: {}", sim.describe());
        helper.succeed();
    }
}
