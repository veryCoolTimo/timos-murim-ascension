package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.decor.BookStackBlock;
import io.github.verycooltimo.murim.decor.BookcaseManualBlockEntity;
import io.github.verycooltimo.murim.decor.HallFurniture;
import io.github.verycooltimo.murim.decor.HallLights;
import io.github.verycooltimo.murim.decor.HangingLanternBlock;
import io.github.verycooltimo.murim.decor.PlumShelfBlock;
import io.github.verycooltimo.murim.decor.LibraryBookcaseBlock;
import io.github.verycooltimo.murim.decor.ScrollShelfBlock;
import io.github.verycooltimo.murim.decor.TallLibraryBookcaseBlock;
import io.github.verycooltimo.murim.decor.WritingDeskBlock;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.NonNullList;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.RandomizableContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CandleBlock;
import net.minecraft.world.level.block.ChiseledBookShelfBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.PipeBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceSerializationContext;

/**
 * The one piece of the abandoned archive (docs/design/25-ruined-library.md §3): rock shell, four tiers of galleries
 * round a void, shelving packed with clone junk, the propped shelf, the tunnel and the rock mouth on the surface.
 * Block choices follow codex's breakdown of the author's refs (docs/design/reference/library/codex-description.md).
 *
 * <p>Every decision comes from {@link LibraryPlan} or {@link LibraryPlan#noise} keyed by position, so the archive is
 * the same whichever chunk builds which part. Local frame (StructurePiece): x across, z from the mouth inwards,
 * y = 0 the archive's bottom floor; NORTH = +z. The tunnel occupies z 0..TUNNEL-1, the archive {@code az = z - TUNNEL}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/levelgen/structure/StructurePiece.java (#placeBlock applies
 * the piece rotation/mirror to states, #getWorldPos), .../WorldGenRegion.java#getBlockEntity (block entities exist in
 * proto chunks), reference/minecraft-src/net/minecraft/world/RandomizableContainer.java#setBlockEntityLootTable,
 * reference/minecraft-src/net/minecraft/world/level/block/entity/BlockEntity.java#loadCustomOnly.
 */
public class LibraryPiece extends StructurePiece {

    /** Saved so unfinished old pieces keep their original archive offset. */
    private final int T;
    private static final int S = LibraryPlan.SIZE;

    /** Two blocks of natural stone seal neighbouring caves; the unpadded frame keeps local coordinates stable. */
    public static final int ROCK_PAD = 2;
    private final BoundingBox frame;
    private final long seed;
    /** Local y of the tunnel mouth's floor (the surface where the rock mouth stands). */
    private final int mouthY;
    private LibraryPlan plan;

    public LibraryPiece(long seed, int x, int baseY, int z, Direction orientation, int mouthY) {
        super(ModLibrary.PIECE.get(), 0, makeBoundingBox(x, baseY, z, orientation, LibraryPlan.WIDTH,
                Math.max(LibraryPlan.ROCK_TOP + 1, mouthY + 13), LibraryPlan.DEPTH));
        this.frame = boundingBox;
        boundingBox = boundingBox.inflatedBy(ROCK_PAD, 0, ROCK_PAD);
        setOrientation(orientation);
        this.T = LibraryPlan.TUNNEL;
        this.seed = seed;
        this.mouthY = mouthY;
    }

    public LibraryPiece(CompoundTag tag) {
        super(ModLibrary.PIECE.get(), tag);
        this.frame = tag.contains("Frame") ? new BoundingBox(tag.getInt("FX0"), boundingBox.minY(), tag.getInt("FZ0"),
                tag.getInt("FX1"), boundingBox.maxY(), tag.getInt("FZ1")) : boundingBox;
        this.T = tag.contains("Tunnel") ? tag.getInt("Tunnel") : 18;
        this.seed = tag.getLong("Seed");
        this.mouthY = tag.getInt("MouthY");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
        tag.putBoolean("Frame", true);
        tag.putInt("FX0", frame.minX()); tag.putInt("FZ0", frame.minZ());
        tag.putInt("FX1", frame.maxX()); tag.putInt("FZ1", frame.maxZ());
        tag.putInt("Tunnel", T);
        tag.putLong("Seed", seed);
        tag.putInt("MouthY", mouthY);
    }

    public LibraryPlan plan() {
        if (plan == null) {
            plan = LibraryPlan.plan(seed);
        }
        return plan;
    }

    public long seed() {
        return seed;
    }

    @Override
    protected int getWorldX(int x, int z) {
        Direction dir = getOrientation();
        if (dir == null) return x;
        return switch (dir) {
            case WEST -> frame.maxX() - z;
            case EAST -> frame.minX() + z;
            default -> frame.minX() + x;
        };
    }

    @Override
    protected int getWorldZ(int x, int z) {
        Direction dir = getOrientation();
        if (dir == null) return z;
        return switch (dir) {
            case NORTH -> frame.maxZ() - z;
            case WEST, EAST -> frame.minZ() + x;
            default -> frame.minZ() + z;
        };
    }

    /** World position of a local point (for the structure, tests and the capture stand). */
    public BlockPos world(int x, int y, int z) {
        return getWorldPos(x, y, z).immutable();
    }

    /** World position of an archive point. */
    public BlockPos archive(int ax, int y, int az) {
        return world(ax, y, az + T);
    }

    /** Steps of the entrance stairs (from the mouth floor down to the top tier). */
    public int steps() {
        return Math.max(0, mouthY - LibraryPlan.TOP);
    }

    /** Local z of the tunnel mouth (first outdoor block in front of the top stair). */
    public int mouthZ() {
        return LibraryPaths.mouthZ(mouthY, T);
    }

    @Override
    public void postProcess(WorldGenLevel level, StructureManager structures, ChunkGenerator generator, RandomSource random,
                            BoundingBox box, ChunkPos chunkPos, BlockPos pivot) {
        new Builder(level, box, random).build();
    }

    /** Places everything that falls inside {@code box} (one chunk column per call). */
    private final class Builder {
        private final WorldGenLevel level;
        private final BoundingBox box;
        private final RandomSource random;
        private final LibraryPlan plan = plan();
        private final java.util.Set<BlockPos> walkingAir = new java.util.HashSet<>();
        private final java.util.Map<BlockPos, LibraryPlan.Genuine> rackManuals = new java.util.LinkedHashMap<>();
        private final java.util.Map<BlockPos, java.util.Set<Integer>> movedSlots = new java.util.HashMap<>();

        Builder(WorldGenLevel level, BoundingBox box, RandomSource random) {
            this.level = level;
            this.box = box;
            this.random = random;
            for (LibraryPaths.Route route : LibraryPaths.routes(mouthY, T)) {
                for (LibraryPaths.Tread tread : route.treads()) {
                    for (int h = 1; h <= 3; h++) walkingAir.add(new BlockPos(tread.x(), tread.y() + h, tread.z()));
                }
            }
            planRackManuals();
        }

        /** Allocate from the existing plan, before chunk clipping. Re-running for another chunk is identical. */
        void planRackManuals() {
            for (LibraryPlan.RackManual manual : plan.rackManuals()) {
                LibraryPlan.Bookcase target = manual.target();
                rackManuals.put(new BlockPos(target.ax(), target.y(), target.az()), manual.genuine());
                LibraryPlan.Shelf source = manual.source();
                movedSlots.computeIfAbsent(new BlockPos(source.ax(), source.y(), source.az()),
                        key -> new java.util.HashSet<>()).add(manual.sourceSlot());
            }
        }

        void rackPart(int ax, int y, int az, int bottomY, BlockState state) {
            int slot = state.getValue(LibraryBookcaseBlock.bookSlotProperty(state));
            LibraryPlan.Genuine manual = y - bottomY == slot / 2
                    ? rackManuals.get(new BlockPos(ax, bottomY, az)) : null;
            a(ax, y, az, state.setValue(LibraryBookcaseBlock.HAS_BOOK, manual != null));
            BlockPos pos = getWorldPos(ax, y, az + T);
            if (box.isInside(pos) && level.getBlockEntity(pos) instanceof
                    BookcaseManualBlockEntity be) {
                be.setManual(manual == null ? ItemStack.EMPTY : genuine(manual));
            }
        }

        double n(int x, int y, int z) {
            return LibraryPlan.noise(seed, x, y, z);
        }

        void put(int x, int y, int z, BlockState state) {
            // All passes (including lights, roots and collapse debris) respect the same reserved volume.
            placeBlock(level, walkingAir.contains(new BlockPos(x, y, z)) ? Blocks.AIR.defaultBlockState() : state, x, y, z, box);
        }

        /** Archive coordinates. */
        void a(int ax, int y, int az, BlockState state) {
            put(ax, y, az + T, state);
        }

        /** Archive coordinates, only where nothing has been built yet (dust, webs, roots). */
        void soft(int ax, int y, int az, BlockState state) {
            if (box.isInside(getWorldPos(ax, y, az + T)) && getBlock(level, ax, y, az + T, box).isAir()) {
                a(ax, y, az, state);
            }
        }

        void build() {
            shell();
            for (int t = 0; t < LibraryPlan.TIERS.length; t++) {
                tier(t);
            }
            posts();
            stairs();
            collapse();
            bottom();
            lights();
            decay();
            tunnel();
            mouth();
            // Restore landing surfaces at the entrance after cutting through the shell.
            for (LibraryPaths.Tread tread : LibraryPaths.routes(mouthY, T).get(0).treads()) {
                if (tread.z() >= T) put(tread.x(), tread.y(), tread.z(), ModLibrary.LIBRARY_PARQUET.get().defaultBlockState());
            }
        }

        // ---- rock shell, air inside --------------------------------------------------------------------------

        BlockState rock(int x, int y, int z) {
            double v = n(x >> 1, y >> 1, z >> 1);
            if (v < 0.58D) {
                return Blocks.STONE.defaultBlockState();
            }
            if (v < 0.78D) {
                return Blocks.ANDESITE.defaultBlockState();
            }
            if (v < 0.94D) {
                return Blocks.TUFF.defaultBlockState();
            }
            return Blocks.DEEPSLATE.defaultBlockState();
        }

        void shell() {
            // Fill neighbouring cave gaps with natural stone, keeping existing solid terrain.
            // The padded piece box includes the whole rind, even across chunk boundaries.
            for (int ax = -ROCK_PAD; ax < S + ROCK_PAD; ax++) {
                for (int az = -ROCK_PAD; az < S + ROCK_PAD; az++) {
                    if (ax >= 0 && ax < S && az >= 0 && az < S) continue;
                    for (int y = -1; y <= LibraryPlan.ROCK_TOP; y++) {
                        BlockPos pos = getWorldPos(ax, y, az + T);
                        if (box.isInside(pos)) {
                            BlockState existing = level.getBlockState(pos);
                            if (existing.isAir() || !existing.getFluidState().isEmpty()) a(ax, y, az, rock(ax, y, az + T));
                        }
                    }
                }
            }
            for (int ax = 0; ax < S; ax++) {
                for (int az = 0; az < S; az++) {
                    for (int y = -1; y <= LibraryPlan.ROCK_TOP; y++) {
                        boolean wall = ax == 0 || ax == S - 1 || az == 0 || az == S - 1 || y < 0 || y >= LibraryPlan.CEILING;
                        a(ax, y, az, wall ? (y == LibraryPlan.CEILING && ax > 0 && ax < S - 1 && az > 0 && az < S - 1
                                ? ModLibrary.LIBRARY_COFFER.get().defaultBlockState() : rock(ax, y, az + T)) : Blocks.AIR.defaultBlockState());
                    }
                }
            }
            // The bottom floor: stone worn into broad paths (codex: stone 55, andesite 25, polished 15, gravel 5).
            for (int ax = 1; ax < S - 1; ax++) {
                for (int az = 1; az < S - 1; az++) {
                    double v = n(ax / 3, 7, az / 3) * 0.7D + n(ax, 8, az) * 0.3D;
                    BlockState floor = v < 0.52D ? Blocks.STONE.defaultBlockState() : v < 0.76D ? Blocks.ANDESITE.defaultBlockState()
                            : v < 0.93D ? Blocks.POLISHED_ANDESITE.defaultBlockState() : Blocks.GRAVEL.defaultBlockState();
                    a(ax, 0, az, floor);
                }
            }
        }

        // ---- tiers: decks, shelf walls, railings --------------------------------------------------------------

        boolean ring(int ax, int az) {
            return ax <= LibraryPlan.RAIL_LO || ax >= LibraryPlan.RAIL_HI || az <= LibraryPlan.RAIL_LO || az >= LibraryPlan.RAIL_HI;
        }

        boolean railLine(int ax, int az) {
            boolean inBand = ax >= LibraryPlan.RAIL_LO && ax <= LibraryPlan.RAIL_HI && az >= LibraryPlan.RAIL_LO && az <= LibraryPlan.RAIL_HI;
            return inBand && (ax == LibraryPlan.RAIL_LO || ax == LibraryPlan.RAIL_HI || az == LibraryPlan.RAIL_LO || az == LibraryPlan.RAIL_HI);
        }

        /** True where the deck of tier {@code t} is missing: stair wells and the collapse. */
        boolean hole(int t, int ax, int az) {
            return plan.hole(t, ax, az);
        }

        boolean collapsed(int t, int ax, int az) {
            return plan.collapsed(t, ax, az);
        }

        void tier(int t) {
            int y = LibraryPlan.TIERS[t];
            if (y > 0) {
                for (int ax = 1; ax < S - 1; ax++) {
                    for (int az = 1; az < S - 1; az++) {
                        if (!ring(ax, az) || hole(t, ax, az)) {
                            continue;
                        }
                        if (railLine(ax, az)) {
                            // Ring beam on the railing line, along its span.
                            boolean alongX = az == LibraryPlan.RAIL_LO || az == LibraryPlan.RAIL_HI;
                            a(ax, y, az, Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS,
                                    alongX ? Direction.Axis.X : Direction.Axis.Z));
                        } else {
                            boolean border = ax == LibraryPlan.RAIL_LO - 1 || ax == LibraryPlan.RAIL_HI + 1
                                    || az == LibraryPlan.RAIL_LO - 1 || az == LibraryPlan.RAIL_HI + 1;
                            a(ax, y, az, (border ? ModLibrary.LIBRARY_PARQUET_BORDER.get()
                                    : ModLibrary.LIBRARY_PARQUET.get()).defaultBlockState());
                        }
                    }
                }
            }
            shelfWalls(t, y);
            railing(t, y);
        }

        void shelfWalls(int t, int y) {
            for (int side = 0; side < 4; side++) {
                Direction facing = switch (side) {
                    case 0 -> Direction.EAST;
                    case 1 -> Direction.SOUTH;
                    case 2 -> Direction.WEST;
                    default -> Direction.NORTH;
                };
                for (int p = 1; p <= LibraryPlan.WALL_HI; p++) {
                    int[] c = LibraryPlan.wallCell(side, p);
                    boolean post = false;
                    for (int q : LibraryPlan.POSTS) {
                        post |= q == p;
                    }
                    if (post) {
                        for (int dy = 1; dy < LibraryPlan.TIER_HEIGHT; dy++) {
                            a(c[0], y + dy, c[1], Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                        }
                        continue;
                    }
                    // Preserve planned takeable volumes, barrels and the hidden propped manual.
                    // Whole decorative columns become real racks; damaged columns keep their gaps.
                    boolean lowerPlain = decorative(plan.cell(t, side, p, 0));
                    boolean middlePlain = decorative(plan.cell(t, side, p, 1));
                    boolean upperPlain = decorative(plan.cell(t, side, p, 2));
                    if (lowerPlain && middlePlain) {
                        bookcase(c[0], y + 1, c[1], facing, upperPlain);
                        if (!upperPlain) cell(t, side, p, 2, c[0], y + 3, c[1], facing);
                    } else if (middlePlain && upperPlain) {
                        cell(t, side, p, 0, c[0], y + 1, c[1], facing);
                        bookcase(c[0], y + 2, c[1], facing, false);
                    } else {
                        for (int row = 0; row < 3; row++) {
                            cell(t, side, p, row, c[0], y + 1 + row, c[1], facing);
                        }
                    }
                    // Additional tightly packed, pale manuals fill the taller band; no extra reward allocation.
                    boolean doorway = t == LibraryPlan.TIERS.length - 1 && side == 3 && p >= 10 && p <= 12;
                    if (!doorway) {
                        if (Math.floorMod(p + side + t, 6) == 0) {
                            scrollCase(c[0], y + 4, c[1], facing);
                        } else {
                            bookcase(c[0], y + 4, c[1], facing, false);
                        }
                    }
                    // Frieze under the next deck: a beam along the wall (the doorway keeps it as a lintel).
                    Direction.Axis along = side == 0 || side == 2 ? Direction.Axis.Z : Direction.Axis.X;
                    a(c[0], y + LibraryPlan.TIER_HEIGHT - 1, c[1], Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, along));
                }
            }
        }

        boolean decorative(LibraryPlan.Cell cell) {
            return LibraryPlan.decorative(cell);
        }

        void bookcase(int ax, int y, int az, Direction facing, boolean tall) {
            BlockPos bottom = getWorldPos(ax, y, az + T);
            int variant = LibraryBookcaseBlock.variantAt(bottom);
            BlockState state = (tall ? HallFurniture.LIBRARY_BOOKCASE_TALL.get()
                    : HallFurniture.LIBRARY_BOOKCASE.get()).defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, facing).setValue(LibraryBookcaseBlock.VARIANT, variant);
            state = state.setValue(LibraryBookcaseBlock.bookSlotProperty(state), LibraryBookcaseBlock.bookSlotAt(bottom, tall));
            if (tall) {
                for (TallLibraryBookcaseBlock.Part part : TallLibraryBookcaseBlock.Part.values()) {
                    rackPart(ax, y + part.ordinal(), az, y, state.setValue(TallLibraryBookcaseBlock.PART, part));
                }
            } else {
                rackPart(ax, y, az, y, state.setValue(ScrollShelfBlock.HALF, DoubleBlockHalf.LOWER));
                rackPart(ax, y + 1, az, y, state.setValue(ScrollShelfBlock.HALF, DoubleBlockHalf.UPPER));
            }
        }

        void scrollCase(int ax, int y, int az, Direction facing) {
            BlockState state = HallFurniture.SCROLL_SHELF.get().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, facing)
                    .setValue(PlumShelfBlock.VARIANT, Math.floorMod(ax + az + y, 3));
            a(ax, y, az, state.setValue(ScrollShelfBlock.HALF, DoubleBlockHalf.LOWER));
            a(ax, y + 1, az, state.setValue(ScrollShelfBlock.HALF, DoubleBlockHalf.UPPER));
        }

        void cell(int t, int side, int p, int row, int ax, int y, int az, Direction facing) {
            LibraryPlan.Cell kind = plan.cell(t, side, p, row);
            switch (kind) {
                // Continuous flat shelf rows; most of the books are texture, only planned marked books are real.
                case CHISELED, PLAIN, BUNDLE -> chiseled(ax, y, az, facing);
                case EMPTY -> a(ax, y, az, Blocks.DARK_OAK_SLAB.defaultBlockState());
                case PROPPED, PROPPED_UPPER -> a(ax, y, az, ModLibrary.PROPPED_SHELF.get().defaultBlockState().setValue(ProppedShelfBlock.FACING, facing)
                        .setValue(ProppedShelfBlock.HALF, kind == LibraryPlan.Cell.PROPPED ? DoubleBlockHalf.LOWER : DoubleBlockHalf.UPPER)
                        .setValue(ProppedShelfBlock.HAS_BOOK, kind == LibraryPlan.Cell.PROPPED)
                        .setValue(ProppedShelfBlock.VARIANT, LibraryBookcaseBlock.variantAt(getWorldPos(ax,
                                kind == LibraryPlan.Cell.PROPPED ? y : y - 1, az + T))));
                case BARREL -> barrel(t, ax, y, az, facing);
                default -> a(ax, y, az, Blocks.AIR.defaultBlockState());
            }
        }

        void chiseled(int ax, int y, int az, Direction facing) {
            LibraryPlan.Shelf shelf = plan.shelfAt(ax, y, az);
            // Local archive position chooses the layout, as in LibraryPlan, independent of chunk order.
            BlockState state = ModLibrary.ARCHIVE_SHELF.get().defaultBlockState()
                    .setValue(HorizontalDirectionalBlock.FACING, facing)
                    .setValue(ArchiveShelfBlock.VARIANT, LibraryShelfLayout.variantAt(ax, y, az));
            NonNullList<ItemStack> items = NonNullList.withSize(6, ItemStack.EMPTY);
            if (shelf != null) {
                for (int i = 0; i < 6; i++) {
                    LibraryPlan.Slot slot = shelf.slots()[i];
                    if (slot != null && !movedSlots.getOrDefault(new BlockPos(ax, y, az), java.util.Set.of()).contains(i)
                            && LibraryShelfLayout.available(state.getValue(ArchiveShelfBlock.VARIANT), i)) {
                        items.set(i, slot.useful() ? genuine(slot.genuine()) : JunkFactory.stack(slot.junk()));
                        state = state.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i), true);
                    }
                }
            }
            a(ax, y, az, state);
            BlockPos pos = getWorldPos(ax, y, az + T);
            if (box.isInside(pos) && level.getBlockEntity(pos) instanceof ArchiveShelfBlockEntity be) {
                CompoundTag tag = new CompoundTag();
                ContainerHelper.saveAllItems(tag, items, true, level.registryAccess());
                be.loadCustomOnly(tag, level.registryAccess());
            }
        }

        ItemStack genuine(LibraryPlan.Genuine g) {
            ItemStack stack = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
            stack.set(ModDataComponents.TECHNIQUE.get(), ResourceLocation.parse(g.technique()));
            stack.set(ModDataComponents.MANUAL_DEPTH.get(), g.depth());
            return stack;
        }

        void barrel(int t, int ax, int y, int az, Direction facing) {
            a(ax, y, az, Blocks.BARREL.defaultBlockState().setValue(BarrelBlock.FACING, facing));
            BlockPos pos = getWorldPos(ax, y, az + T);
            if (!box.isInside(pos)) {
                return;
            }
            // The keeper's note goes in first (loot fills only empty slots), then the record table.
            if (t == LibraryPlan.TIERS.length - 1 && level.getBlockEntity(pos) instanceof BarrelBlockEntity be) {
                be.setItem(13, JunkFactory.stack(plan.deskClue()));
            }
            RandomizableContainer.setBlockEntityLootTable(level, random, pos, ModLibrary.DESK_LOOT);
        }

        void railing(int t, int y) {
            if (y == 0) {
                return;
            }
            for (int ax = LibraryPlan.RAIL_LO; ax <= LibraryPlan.RAIL_HI; ax++) {
                for (int az = LibraryPlan.RAIL_LO; az <= LibraryPlan.RAIL_HI; az++) {
                    if (!railLine(ax, az) || hole(t, ax, az) || isRailPost(ax, az)) {
                        continue;
                    }
                    // About a tenth of the rail is gone, mostly beside the collapse (codex: damage by cause).
                    boolean nearCollapse = t == LibraryPlan.COLLAPSE_TIER && az == LibraryPlan.RAIL_LO
                            && ax >= LibraryPlan.COLLAPSE_X0 - 2 && ax <= LibraryPlan.COLLAPSE_X1 + 2;
                    if (n(ax, y + 1, az) < (nearCollapse ? 0.6D : 0.04D)) {
                        continue;
                    }
                    // Set connections explicitly: chunk generation does not promise neighbour updates.
                    BlockState rail = ModLibrary.BOOKSHELF_RAILING.get().defaultBlockState();
                    for (Direction d : Direction.Plane.HORIZONTAL) {
                        int nx = ax + d.getStepX(), nz = az + d.getStepZ();
                        rail = rail.setValue(PipeBlock.PROPERTY_BY_DIRECTION.get(d), railExists(t, nx, nz));
                    }
                    a(ax, y + 1, az, rail);
                }
            }
        }

        boolean railExists(int t, int ax, int az) {
            if (!railLine(ax, az) || hole(t, ax, az)) return false;
            if (isRailPost(ax, az)) return true;
            boolean near = t == LibraryPlan.COLLAPSE_TIER && az == LibraryPlan.RAIL_LO
                    && ax >= LibraryPlan.COLLAPSE_X0 - 2 && ax <= LibraryPlan.COLLAPSE_X1 + 2;
            return n(ax, LibraryPlan.TIERS[t] + 1, az) >= (near ? 0.6D : 0.04D);
        }

        boolean isRailPost(int ax, int az) {
            if (!railLine(ax, az)) {
                return false;
            }
            int along = ax == LibraryPlan.RAIL_LO || ax == LibraryPlan.RAIL_HI ? az : ax;
            for (int p : LibraryPlan.RAIL_POSTS) {
                if (p == along) {
                    return true;
                }
            }
            return false;
        }

        // ---- posts, stairs, collapse ------------------------------------------------------------------------

        void posts() {
            for (int ax = LibraryPlan.RAIL_LO; ax <= LibraryPlan.RAIL_HI; ax++) {
                for (int az = LibraryPlan.RAIL_LO; az <= LibraryPlan.RAIL_HI; az++) {
                    if (!isRailPost(ax, az)) {
                        continue;
                    }
                    // One polished deepslate footing, then a dark post through all tiers to the rock.
                    a(ax, 0, az, Blocks.POLISHED_DEEPSLATE.defaultBlockState());
                    for (int y = 1; y < LibraryPlan.CEILING; y++) {
                        a(ax, y, az, ModLibrary.LIBRARY_POST.get().defaultBlockState());
                    }
                }
            }
        }

        void stairs() {
            for (LibraryPaths.Route route : LibraryPaths.routes(mouthY, T)) {
                if (!route.name().startsWith("flight-")) continue;
                for (LibraryPaths.Tread tread : route.treads()) {
                    BlockState state = ModLibrary.LIBRARY_PARQUET.get().defaultBlockState();
                    if (tread.stair()) {
                        state = ModLibrary.LIBRARY_PARQUET_STAIRS.get().defaultBlockState()
                                .setValue(StairBlock.FACING, tread.dz() > 0 ? Direction.SOUTH : Direction.NORTH);
                    }
                    put(tread.x(), tread.y(), tread.z(), state);
                    for (int h = 1; h <= 3; h++) put(tread.x(), tread.y() + h, tread.z(), Blocks.AIR.defaultBlockState());
                }
            }
        }

        void collapse() {
            // What fell: a broken ring beam, pale bundles and a toppled shelf on the cracked deck and the floor below.
            int y1 = LibraryPlan.TIERS[LibraryPlan.COLLAPSE_TIER - 1];
            for (int ax = LibraryPlan.COLLAPSE_X0 - 1; ax <= LibraryPlan.COLLAPSE_X1 + 1; ax++) {
                for (int az = 2; az <= 6; az++) {
                    double v = n(ax, 40, az);
                    boolean cracked = collapsed(LibraryPlan.COLLAPSE_TIER - 1, ax, az);
                    int y = cracked ? 1 : y1 + 1;
                    BlockState debris = v < 0.20D ? Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)
                            : v < 0.40D ? HallFurniture.BOOK_STACK.get().defaultBlockState().setValue(BookStackBlock.STACKS, 2)
                            : v < 0.52D ? Blocks.DARK_OAK_SLAB.defaultBlockState()
                            : v < 0.60D ? Blocks.DARK_OAK_SLAB.defaultBlockState()
                            : v < 0.70D ? Blocks.COBWEB.defaultBlockState()
                            : v < 0.82D ? Blocks.LIGHT_GRAY_CARPET.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (!cracked && az > 5) {
                        continue;
                    }
                    a(ax, y, az, debris);
                    if (v >= 0.52D && v < 0.60D) {
                        bookcase(ax, y, az, Direction.NORTH, false);
                    }
                }
            }
        }

        // ---- bottom: reading spot ----------------------------------------------------------------------------

        void bottom() {
            // Parquet patch under the keeper's reading spot (HDA floors), dark border.
            for (int ax = 10; ax <= 16; ax++) {
                for (int az = 10; az <= 16; az++) {
                    boolean border = ax == 10 || ax == 16 || az == 10 || az == 16;
                    // Missing and loose planks break the square (codex frame review: it read as a tidy centrepiece).
                    if (n(ax, 3, az) < (border ? 0.3D : 0.12D)) {
                        continue;
                    }
                    a(ax, 0, az, (border ? ModLibrary.LIBRARY_PARQUET_BORDER.get()
                            : ModLibrary.LIBRARY_PARQUET.get()).defaultBlockState());
                }
            }
            for (int ax = 12; ax <= 13; ax++) {
                for (int az = 12; az <= 13; az++) {
                    a(ax, 1, az, Blocks.GRAY_CARPET.defaultBlockState());
                }
            }
            a(11, 1, 13, Blocks.DARK_OAK_FENCE.defaultBlockState());
            a(11, 2, 13, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            a(11, 3, 13, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true));
            a(15, 1, 12, Blocks.POTTED_DEAD_BUSH.defaultBlockState());
            a(14, 1, 14, Blocks.LECTERN.defaultBlockState().setValue(HorizontalDirectionalBlock.FACING, Direction.SOUTH));
            // Scattered pale folios and rough broken boards below the collapsed span, on supported stone.
            for (int i = 0; i < 8; i++) {
                int ax = 8 + i, az = 7 + (i % 3);
                a(ax, 1, az, HallFurniture.BOOK_STACK.get().defaultBlockState().setValue(BookStackBlock.STACKS, 1 + i % 3));
            }
            a(14, 1, 11, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
            // Keeper's low writing desk, with books on supported floor cells beside it.
            a(12, 1, 15, HallFurniture.WRITING_DESK.get().defaultBlockState()
                    .setValue(WritingDeskBlock.FACING, Direction.SOUTH).setValue(WritingDeskBlock.PART, WritingDeskBlock.Part.LEFT));
            a(13, 1, 15, HallFurniture.WRITING_DESK.get().defaultBlockState()
                    .setValue(WritingDeskBlock.FACING, Direction.SOUTH).setValue(WritingDeskBlock.PART, WritingDeskBlock.Part.RIGHT));
            a(14, 1, 15, HallFurniture.BOOK_STACK.get().defaultBlockState().setValue(BookStackBlock.STACKS, 3));
            // Quiet sorting bays along the rear gallery; keep both stair passages clear.
            for (int t = 0; t < LibraryPlan.TIERS.length; t++) {
                int fy = LibraryPlan.TIERS[t] + 1;
                int x = (t & 1) == 0 ? 8 : 18;
                bookcase(x, fy, 24, Direction.SOUTH, false);
                bookcase(x + 1, fy, 24, Direction.SOUTH, true);
                a(x + 1, fy, 23, Blocks.LIGHT_GRAY_CARPET.defaultBlockState());
            }
        }

        // ---- lanterns ----------------------------------------------------------------------------------------

        void lights() {
            for (LibraryPlan.GalleryLight light : plan.galleryLights()) {
                if (light.wall()) {
                    a(light.ax(), light.y(), light.az(), Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                } else {
                    int above = light.tier() + 1 < LibraryPlan.TIERS.length
                            ? LibraryPlan.TIERS[light.tier() + 1] : LibraryPlan.CEILING;
                    for (int y = above - 1; y > light.y(); y--) a(light.ax(), y, light.az(), Blocks.CHAIN.defaultBlockState());
                    BlockState lamp = ((light.p() + light.side() + light.tier()) % 3 == 0
                            ? HallLights.ROUND_SILK_LANTERN.get() : HallLights.SQUARE_WOOD_LANTERN.get()).defaultBlockState()
                            .setValue(HangingLanternBlock.ATTACH, HangingLanternBlock.Attach.CEILING);
                    a(light.ax(), light.y(), light.az(), lamp);
                }
            }
            // Lower light points sit on supported rails, never on a tread or landing.
            for (int t = 1; t < LibraryPlan.TIERS.length; t++) {
                boolean west = (t & 1) != 0;
                int ax = west ? LibraryPlan.RAIL_LO : LibraryPlan.RAIL_HI;
                int z0 = west ? LibraryPlan.STAIR_WEST_Z0 : LibraryPlan.STAIR_EAST_Z0;
                int dir = west ? 1 : -1;
                a(ax, LibraryPlan.TIERS[t] + 2, z0, Blocks.LANTERN.defaultBlockState());
                if (t == 1) a(ax, 1, z0 + dir * 6, Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
                a(ax, LibraryPlan.TIERS[t - 1] + 2, z0 + dir * 6, Blocks.LANTERN.defaultBlockState());
            }
            // A warm floor lamp on a supported side table. The low desk already has its own modelled books.
            a(16, 1, 14, Blocks.DARK_OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            a(16, 2, 14, HallLights.SQUARE_WOOD_LANTERN.get().defaultBlockState()
                    .setValue(HangingLanternBlock.ATTACH, HangingLanternBlock.Attach.FLOOR));
            int[] c = plan.proppedCell();
            int side = plan.proppedSide();
            int ix = side == 0 ? 1 : side == 2 ? -1 : 0;
            int iz = side == 3 ? 1 : side == 1 ? -1 : 0;
            soft(c[0] + ix + (iz != 0 ? 2 : 0), 1, c[1] + iz + (ix != 0 ? 2 : 0), Blocks.LANTERN.defaultBlockState());
        }

        // ---- decay -------------------------------------------------------------------------------------------

        void decay() {
            // Cobwebs under beam corners and in empty bays, clear of the walking lane.
            for (int t = 0; t < 4; t++) {
                int y = LibraryPlan.TIERS[t] + LibraryPlan.TIER_HEIGHT - 1;
                int[][] corners = {{2, 2}, {2, 24}, {24, 2}, {24, 24}, {2, 13}, {24, 13}};
                for (int[] c : corners) {
                    if (n(c[0], y, c[1]) < 0.6D) {
                        soft(c[0], y, c[1], Blocks.COBWEB.defaultBlockState());
                    }
                }
            }
            // Dust and moss on the bottom floor.
            for (int ax = 2; ax < S - 2; ax++) {
                for (int az = 2; az < S - 2; az++) {
                    double v = n(ax, 1, az);
                    if (ax >= 10 && ax <= 16 && az >= 10 && az <= 16) {
                        continue;
                    }
                    // Dust is grey, moss green; brown carpet read as red rugs on the stand frames.
                    if (v < 0.03D) {
                        soft(ax, 1, az, Blocks.MOSS_CARPET.defaultBlockState());
                    } else if (v < 0.06D) {
                        soft(ax, 1, az, Blocks.LIGHT_GRAY_CARPET.defaultBlockState());
                    }
                }
            }
            // A root fissure above the north-east top gallery: rooted dirt in the rock, hanging roots, moss.
            for (int ax = 19; ax <= 24; ax++) {
                for (int az = 19; az <= 24; az++) {
                    double v = n(ax, 99, az);
                    if (v < 0.55D) {
                        a(ax, LibraryPlan.CEILING, az, v < 0.3D ? Blocks.ROOTED_DIRT.defaultBlockState() : Blocks.MOSS_BLOCK.defaultBlockState());
                        if (v < 0.3D) {
                            soft(ax, LibraryPlan.CEILING - 1, az, Blocks.HANGING_ROOTS.defaultBlockState());
                        }
                    }
                }
            }
        }

        // ---- tunnel and the rock mouth -----------------------------------------------------------------------

        int floorAt(int z) {
            return LibraryPaths.entranceFloor(mouthY, z, T);
        }

        void tunnel() {
            for (int z = mouthZ(); z < T + 2; z++) {
                int f = floorAt(z);
                boolean stair = f > LibraryPlan.TOP && f < mouthY;
                // Broad rough crack; the crown and side pockets vary outside the clear three-high passage.
                for (int x = 8; x <= 14; x++) {
                    boolean side = x < 10 || x > 12;
                    for (int y = f - 2; y <= f + 5; y++) {
                        if (z >= T) {
                            if (!side && y > f && y <= f + 3) put(x, y, z, Blocks.AIR.defaultBlockState());
                            continue;
                        }
                        BlockState state;
                        if (y == f && !side) {
                            state = stair ? Blocks.COBBLESTONE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH)
                                    : (n(x / 2, 4, z / 2) < 0.3D ? Blocks.TUFF : Blocks.DEEPSLATE).defaultBlockState();
                        } else if (!side && y > f && y <= f + 3) {
                            state = Blocks.AIR.defaultBlockState();
                        } else if (y == f + 4 && x >= 10 && x <= 12 && n(x, 2, z) < 0.6D) {
                            state = Blocks.AIR.defaultBlockState();
                        } else {
                            state = lining(x, y, z);
                        }
                        put(x, y, z, state);
                    }
                }
                // Side niche after the threshold. No lantern hangs in the headroom.
                if (z == mouthZ() + 5 || z == T - 3) {
                    put(9, f, z, Blocks.COBBLESTONE.defaultBlockState());
                    put(9, f + 1, z, Blocks.LANTERN.defaultBlockState());
                    put(9, f + 2, z, Blocks.AIR.defaultBlockState());
                }
            }
        }

        BlockState lining(int x, int y, int z) {
            double v = n(x >> 1, y >> 1, z >> 1);
            return v < 0.35D ? Blocks.DEEPSLATE.defaultBlockState()
                    : v < 0.55D ? Blocks.TUFF.defaultBlockState()
                    : v < 0.75D ? Blocks.COBBLESTONE.defaultBlockState() : rock(x, y, z);
        }

        /**
         * A rock outcrop round the mouth (lfc-06: broken rock rim, the opening in its flank): an elliptic mound up to
         * 11 blocks high, higher than the tunnel lining it hides, on whatever terrain is there. The opening is 3 wide and
         * 4 high at the crown.
         */
        void mouth() {
            int zm = mouthZ();
            for (int x = 0; x <= 22; x++) {
                for (int z = Math.max(0, zm - 3); z <= Math.min(T - 1, zm + 12); z++) {
                    double ex = (x - 11.0D) / (10.0D + n(z, 51, 0) * 2.0D);
                    double ez = (z - zm - 4.0D) / 8.0D;
                    double e = ex * ex + ez * ez;
                    if (e >= 1.0D) {
                        continue;
                    }
                    int top = mouthY + (int) Math.round(10.0D * (1.0D - e) + (n(x, 55, z) - 0.5D) * 3.0D);
                    BlockPos column = getWorldPos(x, 0, z);
                    if (!box.isInside(new BlockPos(column.getX(), box.minY(), column.getZ()))) {
                        continue;
                    }
                    int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, column.getX(), column.getZ()) - 1 - boundingBox.minY();
                    for (int y = Math.min(ground, mouthY) - 1; y <= top; y++) {
                        int f = floorAt(z);
                        boolean opening = x >= 10 && x <= 12 && y > f && y <= f + 3 || x == 11 && y == f + 4 && z <= zm + 1;
                        boolean lined = x >= 8 && x <= 14 && z >= zm && y >= f - 2 && y <= f + 5;
                        if (opening && z >= zm - 3 || lined) {
                            continue;
                        }
                        double v = n(x, y, z + 300);
                        BlockState state = v < 0.12D ? Blocks.MOSSY_COBBLESTONE.defaultBlockState()
                                : v < 0.18D ? Blocks.COBBLESTONE.defaultBlockState() : rock(x, y, z);
                        put(x, y, z, state);
                    }
                    // Moss and a little grass on the crown.
                    double g = n(x, 66, z);
                    if (g < 0.35D) {
                        put(x, top + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
                    } else if (g < 0.5D) {
                        put(x, top + 1, z, Blocks.SHORT_GRASS.defaultBlockState());
                    }
                }
            }
            // Bare crown and a worn approach; the three-high walking volume remains clear.
            for (int z = Math.max(0, zm - 3); z < zm; z++) {
                for (int x = 10; x <= 12; x++) {
                    put(x, mouthY, z, n(x, 3, z) < 0.4D ? Blocks.GRAVEL.defaultBlockState() : Blocks.COARSE_DIRT.defaultBlockState());
                    for (int y = mouthY + 1; y <= mouthY + 3; y++) {
                        put(x, y, z, Blocks.AIR.defaultBlockState());
                    }
                }
            }
        }
    }

}
