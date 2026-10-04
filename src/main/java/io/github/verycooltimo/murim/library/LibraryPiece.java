package io.github.verycooltimo.murim.library;

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
import net.minecraft.world.level.block.LanternBlock;
import net.minecraft.world.level.block.LecternBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Half;
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

    private static final int T = LibraryPlan.TUNNEL;
    private static final int S = LibraryPlan.SIZE;

    private final long seed;
    /** Local y of the tunnel mouth's floor (the surface where the rock mouth stands). */
    private final int mouthY;
    private LibraryPlan plan;

    public LibraryPiece(long seed, int x, int baseY, int z, Direction orientation, int mouthY) {
        super(ModLibrary.PIECE.get(), 0, makeBoundingBox(x, baseY, z, orientation, LibraryPlan.WIDTH,
                Math.max(LibraryPlan.CEILING + 3, mouthY + 9), LibraryPlan.DEPTH));
        setOrientation(orientation);
        this.seed = seed;
        this.mouthY = mouthY;
    }

    public LibraryPiece(CompoundTag tag) {
        super(ModLibrary.PIECE.get(), tag);
        this.seed = tag.getLong("Seed");
        this.mouthY = tag.getInt("MouthY");
    }

    @Override
    protected void addAdditionalSaveData(StructurePieceSerializationContext context, CompoundTag tag) {
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
        return Math.max(1, T - LibraryPlan.LANDING - steps());
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

        Builder(WorldGenLevel level, BoundingBox box, RandomSource random) {
            this.level = level;
            this.box = box;
            this.random = random;
        }

        double n(int x, int y, int z) {
            return LibraryPlan.noise(seed, x, y, z);
        }

        void put(int x, int y, int z, BlockState state) {
            placeBlock(level, state, x, y, z, box);
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
            for (int ax = 0; ax < S; ax++) {
                for (int az = 0; az < S; az++) {
                    for (int y = -1; y <= LibraryPlan.CEILING + 2; y++) {
                        boolean wall = ax == 0 || ax == S - 1 || az == 0 || az == S - 1 || y < 0 || y >= LibraryPlan.CEILING;
                        a(ax, y, az, wall ? rock(ax, y, az + T) : Blocks.AIR.defaultBlockState());
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
            int y = LibraryPlan.TIERS[t];
            if ((y == 15 || y == 5) && ax >= LibraryPlan.STAIR_WEST_X && ax <= LibraryPlan.STAIR_WEST_X + 1
                    && az >= LibraryPlan.STAIR_WEST_Z0 && az <= LibraryPlan.STAIR_WEST_Z0 + 2) {
                return true;
            }
            if (y == 10 && ax >= LibraryPlan.STAIR_EAST_X && ax <= LibraryPlan.STAIR_EAST_X + 1
                    && az <= LibraryPlan.STAIR_EAST_Z0 && az >= LibraryPlan.STAIR_EAST_Z0 - 2) {
                return true;
            }
            return collapsed(t, ax, az);
        }

        /** The middle tier's south gallery fell over x 12..16; the deck below cracked under it (smaller hole). */
        boolean collapsed(int t, int ax, int az) {
            if (az > LibraryPlan.RAIL_LO) {
                return false;
            }
            double jag = n(ax, t, az);
            if (t == LibraryPlan.COLLAPSE_TIER) {
                return ax >= LibraryPlan.COLLAPSE_X0 && ax <= LibraryPlan.COLLAPSE_X1 && az >= 2
                        || (ax == LibraryPlan.COLLAPSE_X0 - 1 || ax == LibraryPlan.COLLAPSE_X1 + 1) && az >= 3 && jag < 0.5D;
            }
            if (t == LibraryPlan.COLLAPSE_TIER - 1) {
                return ax >= LibraryPlan.COLLAPSE_X0 + 1 && ax <= LibraryPlan.COLLAPSE_X1 - 1 && az >= 2 && az <= 4 && jag < 0.8D;
            }
            return false;
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
                            a(ax, y, az, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
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
                        for (int dy = 1; dy <= 4; dy++) {
                            a(c[0], y + dy, c[1], Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                        }
                        continue;
                    }
                    for (int row = 0; row < 3; row++) {
                        cell(t, side, p, row, c[0], y + 1 + row, c[1], facing);
                    }
                    // Frieze under the next deck: a beam along the wall (the doorway keeps it as a lintel).
                    Direction.Axis along = side == 0 || side == 2 ? Direction.Axis.Z : Direction.Axis.X;
                    a(c[0], y + 4, c[1], Blocks.DARK_OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, along));
                }
            }
        }

        void cell(int t, int side, int p, int row, int ax, int y, int az, Direction facing) {
            LibraryPlan.Cell kind = plan.cell(t, side, p, row);
            switch (kind) {
                case CHISELED -> chiseled(ax, y, az, facing);
                case PLAIN -> a(ax, y, az, Blocks.BOOKSHELF.defaultBlockState());
                case BUNDLE -> a(ax, y, az, Blocks.BIRCH_SLAB.defaultBlockState());
                case EMPTY -> a(ax, y, az, Blocks.DARK_OAK_SLAB.defaultBlockState());
                case PROPPED -> a(ax, y, az, ModLibrary.PROPPED_SHELF.get().defaultBlockState().setValue(ProppedShelfBlock.FACING, facing));
                case BARREL -> barrel(t, ax, y, az, facing);
                default -> a(ax, y, az, Blocks.AIR.defaultBlockState());
            }
        }

        void chiseled(int ax, int y, int az, Direction facing) {
            LibraryPlan.Shelf shelf = plan.shelfAt(ax, y, az);
            BlockState state = Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, facing);
            NonNullList<ItemStack> items = NonNullList.withSize(6, ItemStack.EMPTY);
            if (shelf != null) {
                for (int i = 0; i < 6; i++) {
                    LibraryPlan.Slot slot = shelf.slots()[i];
                    if (slot != null) {
                        items.set(i, slot.useful() ? genuine(slot.genuine()) : JunkFactory.stack(slot.junk()));
                        state = state.setValue(ChiseledBookShelfBlock.SLOT_OCCUPIED_PROPERTIES.get(i), true);
                    }
                }
            }
            a(ax, y, az, state);
            BlockPos pos = getWorldPos(ax, y, az + T);
            if (box.isInside(pos) && level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity be) {
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
                    a(ax, y + 1, az, Blocks.DARK_OAK_FENCE.defaultBlockState());
                    // Lattice panels on the void side of the fence line: open mangrove trapdoors (pierced pattern).
                    if ((ax + az) % 2 == 0) {
                        int[] out = voidward(ax, az);
                        Direction f = facingInto(ax, az);
                        a(out[0], y + 1, out[1], Blocks.MANGROVE_TRAPDOOR.defaultBlockState()
                                .setValue(TrapDoorBlock.FACING, f).setValue(TrapDoorBlock.OPEN, true).setValue(TrapDoorBlock.HALF, Half.BOTTOM));
                    }
                }
            }
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

        /** The void-side neighbour of a railing-line cell (corners lean diagonally inwards). */
        int[] voidward(int ax, int az) {
            int dx = ax == LibraryPlan.RAIL_LO ? 1 : ax == LibraryPlan.RAIL_HI ? -1 : 0;
            int dz = az == LibraryPlan.RAIL_LO ? 1 : az == LibraryPlan.RAIL_HI ? -1 : 0;
            if (dx != 0 && dz != 0) {
                dz = 0;
            }
            return new int[]{ax + dx, az + dz};
        }

        /** Trapdoor facing so its open panel lies against the fence line (TrapDoorBlock: open NORTH = south face). */
        Direction facingInto(int ax, int az) {
            if (ax == LibraryPlan.RAIL_LO) {
                return Direction.EAST;
            }
            if (ax == LibraryPlan.RAIL_HI) {
                return Direction.WEST;
            }
            return az == LibraryPlan.RAIL_LO ? Direction.NORTH : Direction.SOUTH;
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
                        boolean broken = ax >= LibraryPlan.COLLAPSE_X0 && ax <= LibraryPlan.COLLAPSE_X1 && az == LibraryPlan.RAIL_LO && y > 8 && y < 14;
                        a(ax, y, az, broken ? Blocks.AIR.defaultBlockState() : Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState());
                    }
                }
            }
        }

        void stairs() {
            flight(15, LibraryPlan.STAIR_WEST_X, LibraryPlan.STAIR_WEST_Z0, 1);
            flight(10, LibraryPlan.STAIR_EAST_X, LibraryPlan.STAIR_EAST_Z0, -1);
            flight(5, LibraryPlan.STAIR_WEST_X, LibraryPlan.STAIR_WEST_Z0, 1);
        }

        /** Four steps from deck {@code top} down to the next one, two wide, walking towards {@code dir} (±z). */
        void flight(int top, int x, int z0, int dir) {
            Direction up = dir > 0 ? Direction.SOUTH : Direction.NORTH;
            for (int i = 0; i < 4; i++) {
                int z = z0 + dir * i;
                for (int dx = 0; dx < 2; dx++) {
                    a(x + dx, top - 1 - i, z, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, up));
                    for (int h = top - i; h <= top - i + 2 && h < top + 3; h++) {
                        if (h != top || i < 3) {
                            a(x + dx, h, z, Blocks.AIR.defaultBlockState());
                        }
                    }
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
                            : v < 0.40D ? Blocks.BIRCH_SLAB.defaultBlockState()
                            : v < 0.52D ? Blocks.DARK_OAK_SLAB.defaultBlockState()
                            : v < 0.60D ? Blocks.CHISELED_BOOKSHELF.defaultBlockState().setValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING, Direction.NORTH)
                            : v < 0.70D ? Blocks.COBWEB.defaultBlockState()
                            : v < 0.82D ? Blocks.LIGHT_GRAY_CARPET.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    if (!cracked && az > 5) {
                        continue;
                    }
                    a(ax, y, az, debris);
                }
            }
        }

        // ---- bottom: reading spot ----------------------------------------------------------------------------

        void bottom() {
            // Parquet patch under the keeper's reading spot (HDA floors), dark border.
            for (int ax = 10; ax <= 16; ax++) {
                for (int az = 10; az <= 16; az++) {
                    boolean border = ax == 10 || ax == 16 || az == 10 || az == 16;
                    a(ax, 0, az, border ? Blocks.DARK_OAK_PLANKS.defaultBlockState()
                            : ((ax / 2 + az / 2) % 2 == 0 ? Blocks.SPRUCE_PLANKS.defaultBlockState()
                            : Blocks.STRIPPED_SPRUCE_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X)));
                }
            }
            for (int ax = 12; ax <= 13; ax++) {
                for (int az = 12; az <= 13; az++) {
                    a(ax, 1, az, Blocks.GRAY_CARPET.defaultBlockState());
                }
            }
            a(13, 1, 15, Blocks.LECTERN.defaultBlockState().setValue(LecternBlock.FACING, Direction.SOUTH));
            a(11, 1, 13, Blocks.DARK_OAK_FENCE.defaultBlockState());
            a(11, 2, 13, Blocks.SPRUCE_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.TOP));
            a(11, 3, 13, Blocks.CANDLE.defaultBlockState().setValue(CandleBlock.CANDLES, 3).setValue(CandleBlock.LIT, true));
            a(15, 1, 12, Blocks.POTTED_DEAD_BUSH.defaultBlockState());
            a(14, 1, 11, Blocks.SPRUCE_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.WEST));
        }

        // ---- lanterns ----------------------------------------------------------------------------------------

        void lights() {
            // Under each deck (and under the rock for the top tier), on the railing line, every 8 blocks,
            // offset by 4 from tier to tier so the shaft reads as interleaved points (codex synthesis).
            int[] ceilings = {5, 10, 15, LibraryPlan.CEILING};
            for (int t = 0; t < 4; t++) {
                int above = ceilings[t];
                int chain = t == 3 ? 2 : 1;
                for (int ax = LibraryPlan.RAIL_LO; ax <= LibraryPlan.RAIL_HI; ax++) {
                    for (int az = LibraryPlan.RAIL_LO; az <= LibraryPlan.RAIL_HI; az++) {
                        if (!railLine(ax, az) || isRailPost(ax, az)) {
                            continue;
                        }
                        int along = ax == LibraryPlan.RAIL_LO || ax == LibraryPlan.RAIL_HI ? az : ax;
                        if (Math.floorMod(along + 4 * t, 8) != 3) {
                            continue;
                        }
                        if (t < 3 && hole(t + 1, ax, az)) {
                            continue;
                        }
                        // Hung from the ring beam (or the rock) right above the railing of the tier below.
                        int[] in = {ax, az};
                        for (int c = 1; c <= chain; c++) {
                            a(in[0], above - c, in[1], Blocks.CHAIN.defaultBlockState());
                        }
                        // One snapped chain: the lantern is gone (codex: a ruined fixture, vanilla lanterns cannot be unlit).
                        boolean snapped = t == 2 && ax == LibraryPlan.RAIL_HI && n(ax, 77, az) < 0.5D;
                        if (!snapped) {
                            a(in[0], above - chain - 1, in[1], Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                        }
                    }
                }
            }
            // Two long chains from the rock into the void, at different depths — not a chandelier.
            hang(10, 15, 9);
            hang(16, 11, 13);
            // Both ends of the broken span get a lamp; the reading spot gets one more.
            int deck = LibraryPlan.TIERS[LibraryPlan.COLLAPSE_TIER];
            a(LibraryPlan.COLLAPSE_X0 - 2, deck + 1, 3, Blocks.LANTERN.defaultBlockState());
            a(LibraryPlan.COLLAPSE_X1 + 2, deck + 1, 3, Blocks.LANTERN.defaultBlockState());
            hang(13, 14, 5);
        }

        void hang(int ax, int az, int lowest) {
            for (int y = LibraryPlan.CEILING - 1; y > lowest; y--) {
                a(ax, y, az, Blocks.CHAIN.defaultBlockState());
            }
            a(ax, lowest, az, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        }

        // ---- decay -------------------------------------------------------------------------------------------

        void decay() {
            // Cobwebs under beam corners and in empty bays, clear of the walking lane.
            for (int t = 0; t < 4; t++) {
                int y = LibraryPlan.TIERS[t] + 4;
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
                    if (v < 0.035D) {
                        soft(ax, 1, az, Blocks.MOSS_CARPET.defaultBlockState());
                    } else if (v < 0.07D) {
                        soft(ax, 1, az, Blocks.BROWN_CARPET.defaultBlockState());
                    } else if (v < 0.09D) {
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
            int landing = T - LibraryPlan.LANDING;
            if (z >= landing) {
                return LibraryPlan.TOP;
            }
            return Math.min(mouthY, LibraryPlan.TOP + (landing - z));
        }

        void tunnel() {
            int doorway = 10;
            for (int z = mouthZ(); z < T + 2; z++) {
                int f = z >= T ? LibraryPlan.TOP : floorAt(z);
                boolean stair = z < T - LibraryPlan.LANDING && f > LibraryPlan.TOP && f < mouthY;
                for (int x = doorway - 1; x <= doorway + 3; x++) {
                    boolean side = x == doorway - 1 || x == doorway + 3;
                    for (int y = f - 2; y <= f + 5; y++) {
                        BlockState s;
                        if (z >= T) {
                            // Through the shell and the shelf wall: just the opening.
                            if (!side && y > f && y <= f + 3) {
                                put(x, y, z, Blocks.AIR.defaultBlockState());
                            }
                            continue;
                        }
                        if (side || y <= f - 1 || y >= f + 4) {
                            s = lining(x, y, z);
                        } else if (y == f) {
                            s = stair && z < T - LibraryPlan.LANDING ? Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.SOUTH)
                                    : Blocks.STONE_BRICKS.defaultBlockState();
                        } else if (y == f + 3 && (x == doorway || x == doorway + 2)) {
                            // Arch haunches: upside-down stairs in the upper corners.
                            s = Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.HALF, Half.TOP)
                                    .setValue(StairBlock.FACING, x == doorway ? Direction.EAST : Direction.WEST);
                        } else {
                            s = Blocks.AIR.defaultBlockState();
                        }
                        put(x, y, z, s);
                    }
                }
                if (z == T - 2 || z == T - LibraryPlan.LANDING - Math.max(1, steps() / 2)) {
                    put(doorway + 1, f + 3, z, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
                }
            }
        }

        BlockState lining(int x, int y, int z) {
            double v = n(x, y, z);
            boolean outer = z < mouthZ() + 3;
            if (v < 0.12D) {
                return Blocks.CRACKED_STONE_BRICKS.defaultBlockState();
            }
            if (outer && v < 0.35D) {
                return Blocks.MOSSY_STONE_BRICKS.defaultBlockState();
            }
            return v > 0.93D ? Blocks.POLISHED_DEEPSLATE.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState();
        }

        /** A rough rock mound round the mouth (lfc-06), its rim irregular, on whatever terrain is there. */
        void mouth() {
            int zm = mouthZ();
            for (int x = 3; x <= 19; x++) {
                for (int z = Math.max(0, zm - 3); z <= Math.min(T - 2, zm + 7); z++) {
                    double d = Math.sqrt((x - 11.0D) * (x - 11.0D) * 0.5D + (z - zm - 2.0D) * (z - zm - 2.0D));
                    int top = mouthY + 6 - (int) Math.round(d * 0.9D + n(x, 55, z) * 2.0D);
                    if (top <= mouthY - 1) {
                        continue;
                    }
                    BlockPos column = getWorldPos(x, 0, z);
                    if (!box.isInside(new BlockPos(column.getX(), box.minY(), column.getZ()))) {
                        continue;
                    }
                    int ground = level.getHeight(Heightmap.Types.WORLD_SURFACE_WG, column.getX(), column.getZ()) - 1 - boundingBox.minY();
                    for (int y = Math.min(ground, mouthY - 1); y <= top; y++) {
                        boolean opening = x >= 10 && x <= 12 && y > floorAt(z) && y <= floorAt(z) + 3 && z >= zm - 3;
                        boolean lined = x >= 9 && x <= 13 && z >= zm && y >= floorAt(z) - 1 && y <= floorAt(z) + 4;
                        if (opening || lined) {
                            continue;
                        }
                        BlockState s = n(x, y, z + 300) < 0.2D ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : rock(x, y, z);
                        put(x, y, z, s);
                    }
                    // Moss and roots only at the outer seam.
                    if (z == zm - 1 && x >= 8 && x <= 14 && n(x, 66, z) < 0.5D) {
                        put(x, top + 1, z, Blocks.MOSS_CARPET.defaultBlockState());
                    }
                }
            }
            // A worn path out of the mouth.
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
