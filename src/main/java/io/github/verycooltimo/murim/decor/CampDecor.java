package io.github.verycooltimo.murim.decor;

import com.mojang.serialization.MapCodec;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModCreativeTabs;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.level.material.PushReaction;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

/** Pieces for the disciples' camp buildings (build/camp): an herb drying rack hung under the eaves and a chimney cap
 * that lets smoke out. Models and resources: tools/art/camp_blocks.py. */
public final class CampDecor {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(MurimMod.MODID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MurimMod.MODID);
    public static final DeferredBlock<HerbRack> CAMP_HERB_RACK = BLOCKS.registerBlock("camp_herb_rack", HerbRack::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.TERRACOTTA_BROWN).strength(0.5F).sound(SoundType.GRASS)
                    .noOcclusion().noCollission().ignitedByLava().pushReaction(PushReaction.DESTROY));
    public static final DeferredBlock<SmokeVent> CAMP_SMOKE_VENT = BLOCKS.registerBlock("camp_smoke_vent", SmokeVent::new,
            BlockBehaviour.Properties.of().mapColor(MapColor.STONE).strength(1.5F, 6).sound(SoundType.STONE)
                    .requiresCorrectToolForDrops().noOcclusion());
    private static final List<DeferredItem<net.minecraft.world.item.BlockItem>> ALL_ITEMS =
            List.of(ITEMS.registerSimpleBlockItem(CAMP_HERB_RACK), ITEMS.registerSimpleBlockItem(CAMP_SMOKE_VENT));

    private CampDecor() {}

    /** A pole with hanging herb bundles; runs along X or Z by the way the player faces. */
    public static final class HerbRack extends Block {
        public static final MapCodec<HerbRack> CODEC = simpleCodec(HerbRack::new);
        public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.HORIZONTAL_AXIS;
        private static final VoxelShape X = Block.box(0, 3, 6, 16, 16, 10);
        private static final VoxelShape Z = Block.box(6, 3, 0, 10, 16, 16);

        public HerbRack(Properties properties) {
            super(properties);
            registerDefaultState(stateDefinition.any().setValue(AXIS, Direction.Axis.X));
        }

        @Override protected MapCodec<? extends Block> codec() { return CODEC; }
        @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) { b.add(AXIS); }
        @Override public BlockState getStateForPlacement(BlockPlaceContext c) {
            return defaultBlockState().setValue(AXIS, c.getHorizontalDirection().getAxis());
        }
        @Override protected BlockState rotate(BlockState state, Rotation rotation) {
            return rotation == Rotation.CLOCKWISE_90 || rotation == Rotation.COUNTERCLOCKWISE_90
                    ? state.setValue(AXIS, state.getValue(AXIS) == Direction.Axis.X ? Direction.Axis.Z : Direction.Axis.X) : state;
        }
        @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) {
            return state.getValue(AXIS) == Direction.Axis.X ? X : Z;
        }
    }

    /** A small tiled chimney cap; wisps of smoke rise from under the hat. */
    public static final class SmokeVent extends Block {
        public static final MapCodec<SmokeVent> CODEC = simpleCodec(SmokeVent::new);
        private static final VoxelShape SHAPE = Block.box(1, 0, 1, 15, 14, 15);

        public SmokeVent(Properties properties) { super(properties); }

        @Override protected MapCodec<? extends Block> codec() { return CODEC; }
        @Override protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext ctx) { return SHAPE; }
        @Override public void animateTick(BlockState state, Level level, BlockPos pos, RandomSource random) {
            if (random.nextInt(4) == 0) {
                level.addAlwaysVisibleParticle(ParticleTypes.CAMPFIRE_COSY_SMOKE, true,
                        pos.getX() + 0.5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1), pos.getY() + 0.85,
                        pos.getZ() + 0.5 + random.nextDouble() / 3 * (random.nextBoolean() ? 1 : -1), 0.0, 0.07, 0.0);
            }
        }
    }

    private static void tabs(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey().equals(ModCreativeTabs.MURIM.getKey())) ALL_ITEMS.forEach(i -> event.accept(i.get()));
    }

    public static void register(IEventBus bus) { BLOCKS.register(bus); ITEMS.register(bus); bus.addListener(CampDecor::tabs); }
}
