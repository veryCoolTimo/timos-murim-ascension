package io.github.verycooltimo.murim.library;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class ProppedShelfGameTests {
    private static final BlockPos POS = new BlockPos(1, 2, 1);
    private ProppedShelfGameTests() { }

    private static BlockState lower(Direction facing, boolean secret) {
        return ModLibrary.PROPPED_SHELF.get().defaultBlockState().setValue(ProppedShelfBlock.FACING, facing)
                .setValue(ProppedShelfBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(ProppedShelfBlock.VARIANT, 6).setValue(ProppedShelfBlock.HAS_BOOK, secret);
    }

    private static void clearDrops(GameTestHelper h) {
        h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(h.absolutePos(POS)).inflate(5)).forEach(ItemEntity::discard);
    }

    @GameTest(template = "small_floor", batch = "library")
    public static void legacySingleMigratesOnlyIntoAir(GameTestHelper h) {
        BlockPos pos = h.absolutePos(POS);
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            for (boolean secret : new boolean[]{false, true}) {
                h.setBlock(POS, Blocks.AIR);
                h.setBlock(POS.above(), Blocks.STONE);
                h.setBlock(POS, lower(facing, secret));
                var chunk = h.getLevel().getChunkAt(pos);
                ProppedShelfMigration.migrate(chunk);
                h.assertTrue(h.getBlockState(POS.above()).is(Blocks.STONE), "migration overwrote occupied space");
                h.assertTrue(h.getBlockState(POS).equals(lower(facing, secret)), "legacy latch/state changed");
                h.setBlock(POS.above(), Blocks.AIR);
                ProppedShelfMigration.migrate(chunk);
                BlockState upper = h.getBlockState(POS.above());
                h.assertTrue(upper.is(ModLibrary.PROPPED_SHELF.get()) && upper.getValue(ProppedShelfBlock.HALF) == DoubleBlockHalf.UPPER
                        && upper.getValue(ProppedShelfBlock.FACING) == facing && upper.getValue(ProppedShelfBlock.VARIANT) == 6,
                        "missing/mismatched migrated upper");
                h.assertTrue(h.getBlockState(POS).getValue(ProppedShelfBlock.HAS_BOOK) == secret, "migration reset claim latch");
                ProppedShelfMigration.migrate(chunk);
                h.assertTrue(h.getBlockState(POS.above()).equals(upper), "migration is not idempotent");
            }
        }
        clearDrops(h);
        h.assertTrue(ModLibrary.PROPPED_SHELF.get().getStateDefinition().getPossibleStates().size() == 112, "state explosion");
        h.succeed();
    }

    @GameTest(template = "small_floor", batch = "library")
    public static void breakingEitherHalfDropsOnceAndCreativeDropsNothing(GameTestHelper h) {
        BlockPos pos = h.absolutePos(POS);
        for (DoubleBlockHalf broken : DoubleBlockHalf.values()) {
            for (boolean secret : new boolean[]{false, true}) {
                h.setBlock(POS, lower(Direction.NORTH, secret));
                h.setBlock(POS.above(), lower(Direction.NORTH, false).setValue(ProppedShelfBlock.HALF, DoubleBlockHalf.UPPER));
                clearDrops(h);
                h.getLevel().destroyBlock(broken == DoubleBlockHalf.LOWER ? pos : pos.above(), true);
                h.assertTrue(h.getBlockState(POS).isAir() && h.getBlockState(POS.above()).isAir(), "orphan half after break");
                var drops = h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(5));
                int racks = drops.stream().filter(e -> e.getItem().is(ModLibrary.PROPPED_SHELF_ITEM.get()))
                        .mapToInt(e -> e.getItem().getCount()).sum();
                int manuals = drops.stream().filter(e -> e.getItem().is(ModItems.TECHNIQUE_MANUAL.get()))
                        .mapToInt(e -> e.getItem().getCount()).sum();
                h.assertTrue(racks == 1 && manuals == (secret ? 1 : 0), "duplicate/missing drop " + broken + ": " + racks + "/" + manuals);
                clearDrops(h);
            }
            h.setBlock(POS, lower(Direction.NORTH, true));
            h.setBlock(POS.above(), lower(Direction.NORTH, false).setValue(ProppedShelfBlock.HALF, DoubleBlockHalf.UPPER));
            clearDrops(h);
            BlockPos target = broken == DoubleBlockHalf.LOWER ? pos : pos.above();
            BlockState state = h.getLevel().getBlockState(target);
            state.getBlock().playerWillDestroy(h.getLevel(), target, state, h.makeMockPlayer(GameType.CREATIVE));
            h.getLevel().setBlock(target, Blocks.AIR.defaultBlockState(), 3);
            h.assertTrue(h.getBlockState(POS).isAir() && h.getBlockState(POS.above()).isAir(), "creative orphan");
            h.assertTrue(h.getLevel().getEntitiesOfClass(ItemEntity.class, new AABB(pos).inflate(5)).isEmpty(), "creative secret/drop");
        }
        h.succeed();
    }
}
