package io.github.verycooltimo.murim.quest;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

/** H06/H11, age 100 only in this slice. Shearing leaves a root; a broken root yields nothing.
 * Numbers/habitats/harvest items live in the quest's herb step. No chat or text-on-click.
 * API: library/ArchiveShelfBlock#useItemOn, sect/vault/VaultDoorBlock#tick and #scheduleTick.
 */
public final class MedicinalPlant extends Block {
    public static final BooleanProperty CUT = BooleanProperty.create("cut");
    public static final MapCodec<MedicinalPlant> CODEC = simpleCodec(MedicinalPlant::new);
    public MedicinalPlant(Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(CUT, false));
    }
    @Override protected MapCodec<? extends Block> codec() { return CODEC; }
    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) { builder.add(CUT); }
    @Override protected VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter level, BlockPos pos, CollisionContext context) {
        return state.getValue(CUT) ? box(5, 0, 5, 11, 3, 11) : box(2, 0, 2, 14, 12, 14);
    }
    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                       Player player, InteractionHand hand, BlockHitResult hit) {
        if (!stack.is(Items.SHEARS)) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        if (!level.isClientSide && player instanceof ServerPlayer p) harvest(p, pos);
        return ItemInteractionResult.sidedSuccess(level.isClientSide);
    }
    public static boolean harvest(ServerPlayer p, BlockPos pos) {
        var level = p.serverLevel();
        var state = level.getBlockState(pos);
        if (p.isSpectator() || !(state.getBlock() instanceof MedicinalPlant) || state.getValue(CUT)
                || !p.getMainHandItem().is(Items.SHEARS) && !p.getOffhandItem().is(Items.SHEARS)) return false;
        var herb = definition(state);
        if (herb == null) return false;
        ItemStack sample = QuestService.stack(herb.item());
        if (sample == null) return false;
        // Full inventory leaves the plant untouched; a sample is never counted before delivery.
        if (!p.getInventory().add(sample)) return false;
        level.setBlock(pos, state.setValue(CUT, true), 3);
        level.scheduleTick(pos, state.getBlock(), herb.regrowDays() * 24000);
        Quests.set(p, "herb." + herb.id() + ".wild");
        io.github.verycooltimo.murim.sect.PlayerJournal.record(p,
                net.minecraft.network.chat.Component.translatable("murim.herb." + herb.id() + ".sample"));
        return true;
    }
    static io.github.verycooltimo.murim.quest.def.QuestDef.Herb definition(BlockState state) {
        String block = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        return QuestLoader.herbs().stream().map(QuestLoader.Located::step).filter(h -> h.block().equals(block)).findFirst().orElse(null);
    }
    @Override protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        var herb = definition(state);
        if (herb == null || !state.getValue(CUT)) return;
        if (HerbHabitats.suitable(level, pos, herb)) level.setBlock(pos, state.setValue(CUT, false), 3);
        else level.scheduleTick(pos, this, 24000);
    }
}
