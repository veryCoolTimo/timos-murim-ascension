package io.github.verycooltimo.murim.world.hua;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.LadderBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A climbable mat or handhold on a rock face of Mount Hua (author 06.10: «свои блоки, по которым можно
 * карабкаться… выглядит очень ненатурально и ванильно»): {@code murim:climbing_moss} — a hanging moss mat,
 * {@code murim:rock_handhold} — granite knobs and foot-holds jutting from the face. Placement, shape and support
 * are the ladder's (a 3-pixel layer against a sturdy face, {@code facing} away from the wall); climbing comes
 * from {@link #isLadder} instead of the vanilla {@code #minecraft:climbable} tag, so no vanilla tag is overridden.
 *
 * <p>The codec stays the ladder's: {@code LadderBlock#codec} returns {@code MapCodec<LadderBlock>}, which a
 * subclass cannot narrow; it only serialises block types and is never used by the world.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/block/LadderBlock.java,
 * reference/neoforge-src/net/neoforged/neoforge/common/extensions/IBlockExtension.java#isLadder
 */
public class ClimbingAidBlock extends LadderBlock {

    public ClimbingAidBlock(BlockBehaviour.Properties properties) {
        super(properties);
    }

    @Override
    public boolean isLadder(BlockState state, LevelReader level, BlockPos pos, LivingEntity entity) {
        return true;
    }
}
