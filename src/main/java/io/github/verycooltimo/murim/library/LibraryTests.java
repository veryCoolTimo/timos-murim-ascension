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

    /** Builds the whole piece into the test box, with the normal deep surface descent. */
    static LibraryPiece build(GameTestHelper helper, long seed) {
        return build(helper, seed, LibraryPlan.ROCK_TOP + RuinedLibraryStructure.COVER);
    }

    static LibraryPiece build(GameTestHelper helper, long seed, int mouthY) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(1, 2, 1));
        LibraryPiece probe = new LibraryPiece(seed, 0, 0, 0, Direction.NORTH, mouthY);
        LibraryPiece piece = new LibraryPiece(seed, origin.getX() - probe.getBoundingBox().minX(), origin.getY(),
                origin.getZ() - probe.getBoundingBox().minZ(), Direction.NORTH, mouthY);
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
        int railings = 0;
        int bookcaseParts = 0;
        int tallBookcaseParts = 0;
        int rackManuals = 0;
        boolean clue = false;
        BoundingBox b = piece.getBoundingBox();
        for (BlockPos p : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            BlockState s = level.getBlockState(p);
            if (s.is(Blocks.LANTERN) || s.is(io.github.verycooltimo.murim.decor.HallLights.ROUND_SILK_LANTERN.get())
                    || s.is(io.github.verycooltimo.murim.decor.HallLights.SQUARE_WOOD_LANTERN.get())) {
                lanterns++;
            }
            if (s.is(ModLibrary.BOOKSHELF_RAILING.get())) railings++;
            if (s.is(io.github.verycooltimo.murim.decor.HallFurniture.LIBRARY_BOOKCASE.get())) bookcaseParts++;
            if (s.is(io.github.verycooltimo.murim.decor.HallFurniture.LIBRARY_BOOKCASE_TALL.get())) tallBookcaseParts++;
            BlockEntity be = level.getBlockEntity(p);
            if (be instanceof ArchiveShelfBlockEntity shelf && !shelf.isEmpty()) {
                shelvesWithBooks++;
                int accessible = 0;
                for (int i = 0; i < 6; i++) {
                    if (!shelf.getItem(i).isEmpty()) {
                        accessible++;
                        helper.assertTrue(LibraryShelfLayout.available(s.getValue(ArchiveShelfBlock.VARIANT), i),
                                "world generation filled a decorative cell at " + p);
                    }
                }
                helper.assertTrue(accessible <= 2, "too many takeable books at " + p);
                for (int i = 0; i < 6; i++) {
                    ItemStack st = shelf.getItem(i);
                    helper.assertTrue(s.getValue(net.minecraft.world.level.block.ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i))
                            == !st.isEmpty(), "shelf state disagrees with slot " + i + " at " + p);
                    if (!st.isEmpty()) {
                        books++;
                        junk += st.has(ModLibrary.JUNK.get()) ? 1 : 0;
                    }
                }
            }
            if (be instanceof io.github.verycooltimo.murim.decor.BookcaseManualBlockEntity rack) {
                helper.assertTrue(s.getValue(io.github.verycooltimo.murim.decor.LibraryBookcaseBlock.HAS_BOOK)
                        == !rack.manual().isEmpty(), "rack state disagrees with its manual at " + p);
                if (!rack.manual().isEmpty()) {
                    books++;
                    rackManuals++;
                    var slotProperty = io.github.verycooltimo.murim.decor.LibraryBookcaseBlock.bookSlotProperty(s);
                    boolean tall = s.hasProperty(io.github.verycooltimo.murim.decor.TallLibraryBookcaseBlock.PART);
                    int part = tall ? s.getValue(io.github.verycooltimo.murim.decor.TallLibraryBookcaseBlock.PART).ordinal()
                            : s.getValue(io.github.verycooltimo.murim.decor.StoneLanternBlock.HALF)
                                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? 1 : 0;
                    int slot = s.getValue(slotProperty);
                    helper.assertTrue(part == slot / 2, "manual is on the wrong shelf at " + p);
                    helper.assertTrue(slot == io.github.verycooltimo.murim.decor.LibraryBookcaseBlock.bookSlotAt(p.below(part), tall),
                            "manual slot does not match world position at " + p);
                    helper.assertTrue(rack.manual().is(ModItems.TECHNIQUE_MANUAL.get()), "rack holds unplanned junk");
                }
            }
            if (be instanceof BarrelBlockEntity barrel) {
                barrels++;
                helper.assertTrue(barrel.getLootTable() != null, "record barrel without loot table at " + p);
                ItemStack note = barrel.getItem(13);
                if (note.has(ModLibrary.JUNK.get()) && note.get(ModLibrary.JUNK.get()).kind() == JunkKind.CLUE) {
                    clue = true;
                    helper.assertTrue(note.get(ModLibrary.JUNK.get()).hint() == plan.proppedNumber(), "the note names another shelf");
                    helper.assertTrue(note.get(ModLibrary.JUNK.get()).manualTiers() == plan.deskClue().manualTiers(),
                            "entrance note lost its loose-manual tiers");
                }
            }
        }
        int[] c = plan.proppedCell();
        BlockPos proppedPos = piece.archive(c[0], 1, c[1]);
        helper.assertTrue(level.getBlockState(proppedPos).is(ModLibrary.PROPPED_SHELF.get()), "no propped shelf at " + proppedPos);
        BlockState proppedLower = level.getBlockState(proppedPos);
        BlockState proppedUpper = level.getBlockState(proppedPos.above());
        helper.assertTrue(proppedLower.getValue(ProppedShelfBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER
                && proppedUpper.is(ModLibrary.PROPPED_SHELF.get())
                && proppedUpper.getValue(ProppedShelfBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER
                && proppedUpper.getValue(ProppedShelfBlock.VARIANT).equals(proppedLower.getValue(ProppedShelfBlock.VARIANT)),
                "incomplete propped rack or mismatched filling");
        long plannedManuals = plan.shelves().stream().flatMap(shelf -> java.util.Arrays.stream(shelf.slots()))
                .filter(slot -> slot != null && slot.useful()).count();
        helper.assertTrue(rackManuals == plannedManuals, "planned manuals were not moved into the lit racks");
        for (LibraryPlan.RackManual manual : plan.rackManuals()) {
            LibraryPlan.Bookcase target = manual.target();
            BlockPos bottom = piece.archive(target.ax(), target.y(), target.az());
            BlockState state = level.getBlockState(bottom);
            int bookSlot = state.getValue(io.github.verycooltimo.murim.decor.LibraryBookcaseBlock.bookSlotProperty(state));
            BlockPos at = bottom.above(bookSlot / 2);
            var be = level.getBlockEntity(at);
            helper.assertTrue(be instanceof io.github.verycooltimo.murim.decor.BookcaseManualBlockEntity rack
                    && rack.manual().get(ModDataComponents.TECHNIQUE.get()).equals(ResourceLocation.parse(manual.genuine().technique()))
                    && rack.manual().get(ModDataComponents.MANUAL_DEPTH.get()) == manual.genuine().depth(),
                    "reward missing or changed at planned rack " + at);
            BlockPos front = piece.archive(target.ax() + (target.side() == 0 ? 1 : target.side() == 2 ? -1 : 0),
                    target.y(), target.az() + (target.side() == 3 ? 1 : target.side() == 1 ? -1 : 0));
            Direction expected = Direction.NORTH;
            for (Direction direction : Direction.Plane.HORIZONTAL) {
                if (bottom.relative(direction).equals(front)) expected = direction;
            }
            helper.assertTrue(state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING) == expected,
                    "rack faces away from gallery " + at);
            helper.assertTrue(!level.getBlockState(bottom.relative(expected)).is(io.github.verycooltimo.murim.decor.HallFurniture.LIBRARY_BOOKCASE.get())
                    && !level.getBlockState(bottom.relative(expected)).is(io.github.verycooltimo.murim.decor.HallFurniture.LIBRARY_BOOKCASE_TALL.get()),
                    "rack hidden behind another bookcase " + at);
            boolean lit = false;
            for (LibraryPlan.GalleryLight light : plan.galleryLights()) {
                if (light.tier() != target.tier() || light.side() != target.side()) continue;
                BlockPos lp = piece.archive(light.ax(), light.y(), light.az());
                BlockState lamp = level.getBlockState(lp);
                if (lp.distSqr(at) <= 36 && (lamp.is(Blocks.LANTERN)
                        || lamp.is(io.github.verycooltimo.murim.decor.HallLights.ROUND_SILK_LANTERN.get())
                        || lamp.is(io.github.verycooltimo.murim.decor.HallLights.SQUARE_WOOD_LANTERN.get()))) lit = true;
            }
            helper.assertTrue(lit, "manual has no nearby placed lantern " + at);
        }
        helper.assertTrue(clue, "no keeper's note in the top record barrel");
        helper.assertTrue(barrels == 4, "record barrels " + barrels + " instead of 4");
        helper.assertTrue(shelvesWithBooks > 50, "shelves with books " + shelvesWithBooks);
        helper.assertTrue(books == plan.books(), "volumes in the world " + books + " vs plan " + plan.books());
        helper.assertTrue(junk >= books * 0.98D, "junk " + junk + " of " + books);
        helper.assertTrue(lanterns >= 30, "lanterns " + lanterns);
        helper.assertTrue(railings > 100, "missing library railings: " + railings);
        helper.assertTrue(bookcaseParts >= 8 && tallBookcaseParts >= 12,
                "missing two/three-high library racks: " + bookcaseParts + "/" + tallBookcaseParts);
        for (int t = 0; t < LibraryPlan.TIERS.length; t++) {
            int x = (t & 1) == 0 ? 8 : 18;
            for (int dy = 0; dy < 3; dy++) {
                BlockState rack = level.getBlockState(piece.archive(x + 1, LibraryPlan.TIERS[t] + 1 + dy, 24));
                helper.assertTrue(rack.is(io.github.verycooltimo.murim.decor.HallFurniture.LIBRARY_BOOKCASE_TALL.get())
                        && rack.getValue(io.github.verycooltimo.murim.decor.TallLibraryBookcaseBlock.PART).ordinal() == dy,
                        "incomplete sorting rack at tier " + t);
            }
        }
        helper.assertTrue(level.getBlockState(piece.archive(12, 1, 15)).is(io.github.verycooltimo.murim.decor.HallFurniture.WRITING_DESK.get()),
                "keeper's writing desk missing");
        helper.assertTrue(level.getBlockState(piece.archive(14, 1, 14)).is(Blocks.LECTERN), "reader's lectern missing");
        helper.assertTrue(level.getBlockState(piece.archive(4, LibraryPlan.TOP, 13)).is(ModLibrary.LIBRARY_PARQUET.get()),
                "gallery parquet missing");
        helper.assertTrue(level.getBlockState(piece.archive(13, LibraryPlan.CEILING, 13)).is(ModLibrary.LIBRARY_COFFER.get()),
                "meander ceiling missing");
        helper.assertTrue(level.getBlockState(piece.archive(LibraryPlan.RAIL_LO, 10, LibraryPlan.RAIL_HI)).is(ModLibrary.LIBRARY_POST.get()),
                "slender corner post missing");
        MurimMod.LOGGER.info("GameTest archive: {} volumes ({} junk) on {} shelves, {} lanterns, propped shelf No. {} at {}",
                books, junk, shelvesWithBooks, lanterns, plan.proppedNumber(), proppedPos);
        helper.succeed();
    }

    /** Check the final world, after every decoration pass: both columns, three air blocks, real support,
     * half-block stair profile and both landings. Gallery bypasses are checked as well as every descent.
     */
    @GameTest(template = "library_floor", timeoutTicks = 200)
    public static void allPathsAreWideAndClear(GameTestHelper helper) {
        for (long seed : new long[]{SEED, SEED + 7, SEED + 31}) {
            LibraryPiece piece = build(helper, seed, LibraryPlan.ROCK_TOP + RuinedLibraryStructure.COVER + (int) Math.floorMod(seed, 3L));
            int checked = 0;
            for (LibraryPaths.Route route : LibraryPaths.routes(LibraryPlan.TOP + piece.steps())) {
                for (LibraryPaths.Tread tread : route.treads()) {
                    BlockPos p = piece.world(tread.x(), tread.y(), tread.z());
                    BlockState floor = helper.getLevel().getBlockState(p);
                    helper.assertTrue(!floor.getCollisionShape(helper.getLevel(), p).isEmpty(), route.name() + " has no floor at " + p);
                    if (tread.stair()) {
                        helper.assertTrue(floor.getBlock() instanceof net.minecraft.world.level.block.StairBlock,
                                route.name() + " missing stair at " + p);
                        // Local +z is world -z for the NORTH piece; stairs rise back toward the higher landing.
                        Direction expected = piece.world(tread.x(), tread.y(), tread.z() - tread.dz()).subtract(p).getZ() > 0
                                ? Direction.SOUTH : Direction.NORTH;
                        helper.assertTrue(floor.getValue(net.minecraft.world.level.block.StairBlock.FACING) == expected,
                                route.name() + " backwards stair at " + p);
                    }
                    for (int h = 1; h <= 3; h++) {
                        BlockPos air = p.above(h);
                        helper.assertTrue(helper.getLevel().getBlockState(air).isAir(),
                                route.name() + " blocked at " + air + " by " + helper.getLevel().getBlockState(air));
                    }
                    checked++;
                }
            }
            MurimMod.LOGGER.info("GameTest archive walkability: seed {}, {} treads, 2+ wide / 3 high", seed, checked);
        }
        helper.succeed();
    }

    /** Generated rack manuals remain retrievable exactly once; inspect the whole generated archive, not a fixture. */
    @GameTest(template = "library_floor", timeoutTicks = 200)
    public static void generatedRackManualsAreRetrievable(GameTestHelper helper) {
        long manualSeed = SEED;
        while (manualSeed < SEED + 64 && LibraryPlan.plan(manualSeed).genuineOnShelves() == 0) manualSeed++;
        helper.assertTrue(manualSeed < SEED + 64, "no generated manual test seed");
        LibraryPiece piece = build(helper, manualSeed);
        int found = 0;
        BoundingBox b = piece.getBoundingBox();
        for (BlockPos cursor : BlockPos.betweenClosed(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ())) {
            BlockPos pos = cursor.immutable();
            if (!(helper.getLevel().getBlockEntity(pos) instanceof io.github.verycooltimo.murim.decor.BookcaseManualBlockEntity rack)
                    || rack.manual().isEmpty()) continue;
            ItemStack expected = rack.manual().copy();
            BlockState state = helper.getLevel().getBlockState(pos);
            Vec3 hit = Vec3.atCenterOf(pos);
            state.useWithoutItem(helper.getLevel(), helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL),
                    new net.minecraft.world.phys.BlockHitResult(hit, state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING), pos, false));
            helper.assertTrue(rack.manual().isEmpty() && !helper.getLevel().getBlockState(pos)
                    .getValue(io.github.verycooltimo.murim.decor.LibraryBookcaseBlock.HAS_BOOK), "generated manual was not removed at " + pos);
            state.useWithoutItem(helper.getLevel(), helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL),
                    new net.minecraft.world.phys.BlockHitResult(hit, state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING), pos, false));
            var drops = helper.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(4));
            int count = drops.stream().filter(e -> ItemStack.isSameItemSameComponents(expected, e.getItem()))
                    .mapToInt(e -> e.getItem().getCount()).sum();
            helper.assertTrue(count == 1, "generated manual did not drop once at " + pos);
            drops.forEach(ItemEntity::discard);
            found++;
        }
        helper.assertTrue(found == piece.plan().genuineOnShelves(), "lost a planned hidden manual");
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
        helper.assertTrue(level.getBlockState(pos).is(ModLibrary.PROPPED_SHELF.get())
                && !level.getBlockState(pos).getValue(ProppedShelfBlock.HAS_BOOK), "the prop was not removed");
        ProppedShelfBlock.pull(level, pos, state); // A stale state must not allow a second reward.
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
