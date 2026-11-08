package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.SectProgression;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Wall-mounted board. Right-click: the contract board screen (the day's three contracts); sneak + right-click: the old duties dialogue. */
public final class SectMissionBoard extends HorizontalDirectionalBlock {
    public static final MapCodec<SectMissionBoard> CODEC = simpleCodec(SectMissionBoard::new);
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    public static final DeferredBlock<SectMissionBoard> BOARD = BLOCKS.registerBlock("sect_mission_board", SectMissionBoard::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(1.5F).sound(SoundType.WOOD).noOcclusion().ignitedByLava());
    private static final net.neoforged.neoforge.registries.DeferredItem<net.minecraft.world.item.BlockItem> ITEM = ITEMS.registerSimpleBlockItem(BOARD);
    /** The board is 3 wide x 2 high: six blocks, each drawing its slice of one picture. COL 0 is the viewer's left, ROW 0 the bottom. */
    public static final IntegerProperty COL = IntegerProperty.create("col", 0, 2);
    public static final IntegerProperty ROW = IntegerProperty.create("row", 0, 1);
    public SectMissionBoard(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COL, 1).setValue(ROW, 0));
    }

    /** The cell of part (col, row) of the board whose bottom-middle block is {@code base}. */
    public static BlockPos part(BlockPos base, Direction facing, int col, int row) {
        // the viewer looks against FACING; his left is FACING.getClockWise()
        return base.relative(facing.getClockWise(), 1 - col).above(row);
    }

    /** True when all six cells are free (or already board parts) and each has a sturdy block behind it. */
    public static boolean fits(LevelReader level, BlockPos base, Direction facing) {
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 2; r++) {
                BlockPos p = part(base, facing, c, r);
                BlockState there = level.getBlockState(p);
                BlockPos support = p.relative(facing.getOpposite());
                if (!(there.canBeReplaced() || there.is(BOARD.get())) || !level.getBlockState(support).isFaceSturdy(level, support, facing)) return false;
            }
        }
        return true;
    }

    /** Sets all six parts (call {@link #fits} first). */
    public static void placeWhole(LevelAccessor level, BlockPos base, Direction facing) {
        for (int c = 0; c < 3; c++) {
            for (int r = 0; r < 2; r++) {
                level.setBlock(part(base, facing, c, r), BOARD.get().defaultBlockState().setValue(FACING, facing).setValue(COL, c).setValue(ROW, r), 3);
            }
        }
    }

    /** A board placed by hand grows to the whole 3x2 board when the room is there. */
    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.LivingEntity who, net.minecraft.world.item.ItemStack stack) {
        super.setPlacedBy(level, pos, state, who, stack);
        if (level.isClientSide || state.getValue(COL) != 1 || state.getValue(ROW) != 0) return;
        if (fits(level, pos, state.getValue(FACING))) placeWhole(level, pos, state.getValue(FACING));
    }
    @Override protected MapCodec<? extends HorizontalDirectionalBlock> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(FACING, COL, ROW); }
    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        if (!facing.getAxis().isHorizontal()) return null;
        BlockState state = defaultBlockState().setValue(FACING, facing);
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }
    @Override protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(FACING);
        BlockPos support = pos.relative(facing.getOpposite());
        return level.getBlockState(support).isFaceSturdy(level, support, facing);
    }
    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbour,
                                               LevelAccessor level, BlockPos pos, BlockPos neighbourPos) {
        return !state.canSurvive(level, pos) ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }
    @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return HallFurniture.turn(0, 0, 14, 16, 16, 16, state.getValue(FACING).getOpposite());
    }
    @Override protected BlockState rotate(BlockState state, Rotation rotation) { return state.setValue(FACING, rotation.rotate(state.getValue(FACING))); }
    @Override protected BlockState mirror(BlockState state, Mirror mirror) { return state.rotate(mirror.getRotation(state.getValue(FACING))); }
    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player instanceof ServerPlayer p && !p.isShiftKeyDown()) {
            io.github.verycooltimo.murim.sect.SectContracts.openBoard(p);
        } else if (player instanceof ServerPlayer p) {
            SectDisciple reader = level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(6),
                    npc -> SectProgression.matches(p, npc, "menu") && npc.layout() != null).stream()
                    .min(java.util.Comparator.comparingDouble(npc -> npc.distanceToSqr(p))).orElse(null);
            if (reader == null || !DialogueService.openDialogue(p, reader, SectProgression.DIALOGUE, "duties")) {
                io.github.verycooltimo.murim.sect.SectNotice.bar(p, Component.translatable("murim.sect.progression.board.reader"));
            }
        }
        return InteractionResult.CONSUME;
    }
    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(io.github.verycooltimo.murim.registry.ModCreativeTabs.MURIM.getKey())) event.accept(ITEM.get());
    }
    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); bus.addListener(SectMissionBoard::tabs); }
}
