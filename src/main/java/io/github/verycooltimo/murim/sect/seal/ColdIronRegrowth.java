package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Broken cold iron is re-forged by the sect (decision 05.10): a block broken in survival comes back after
 * {@link ColdIronRules#REGROW_TICKS} where it stood, if the place is still free (air or replaceable). So forcing the
 * vault above Peak is a raid, not a door taken off for good; the trial ({@link VaultTrial}) is the lasting way in.
 * Doors come back whole (both halves) and shut.
 *
 * <p>Saved per level ({@code murim_cold_iron}); checked every two seconds, only for loaded positions.
 * API: reference/neoforge-src/net/neoforged/neoforge/event/level/BlockEvent.java#BreakEvent,
 * reference/minecraft-src/net/minecraft/world/level/saveddata/SavedData.java#Factory, NbtUtils#writeBlockState/readBlockState.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ColdIronRegrowth extends SavedData {

    public static final String NAME = "murim_cold_iron";
    public static final SavedData.Factory<ColdIronRegrowth> FACTORY = new SavedData.Factory<>(ColdIronRegrowth::new, ColdIronRegrowth::load);

    record Entry(BlockPos pos, BlockState state, long at) {
    }

    private final List<Entry> entries = new ArrayList<>();

    public ColdIronRegrowth() {
    }

    public static ColdIronRegrowth get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, NAME);
    }

    public int pending() {
        return entries.size();
    }

    private static ColdIronRegrowth load(CompoundTag tag, HolderLookup.Provider registries) {
        ColdIronRegrowth data = new ColdIronRegrowth();
        var blocks = registries.lookupOrThrow(Registries.BLOCK);
        for (Tag t : tag.getList("entries", Tag.TAG_COMPOUND)) {
            CompoundTag e = (CompoundTag) t;
            data.entries.add(new Entry(BlockPos.of(e.getLong("pos")), NbtUtils.readBlockState(blocks, e.getCompound("state")), e.getLong("at")));
        }
        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        for (Entry e : entries) {
            CompoundTag c = new CompoundTag();
            c.putLong("pos", e.pos().asLong());
            c.put("state", NbtUtils.writeBlockState(e.state()));
            c.putLong("at", e.at());
            list.add(c);
        }
        tag.put("entries", list);
        return tag;
    }

    /** Remember a broken block (a door — both halves, shut). */
    public void broken(ServerLevel level, BlockPos pos, BlockState state) {
        long at = level.getGameTime() + ColdIronRules.REGROW_TICKS;
        if (state.getBlock() instanceof ColdIronDoorBlock) {
            BlockPos lower = state.getValue(ColdIronDoorBlock.HALF) == DoubleBlockHalf.LOWER ? pos : pos.below();
            BlockState low = state.setValue(ColdIronDoorBlock.HALF, DoubleBlockHalf.LOWER).setValue(ColdIronDoorBlock.OPEN, false);
            entries.add(new Entry(lower.immutable(), low, at));
            entries.add(new Entry(lower.above().immutable(), low.setValue(ColdIronDoorBlock.HALF, DoubleBlockHalf.UPPER), at));
        } else {
            entries.add(new Entry(pos.immutable(), state, at));
        }
        setDirty();
    }

    /** Put back what is due. @return blocks restored */
    public int regrow(ServerLevel level, long now) {
        int n = 0;
        for (Iterator<Entry> it = entries.iterator(); it.hasNext(); ) {
            Entry e = it.next();
            if (e.at() > now || !level.isLoaded(e.pos())) {
                continue;
            }
            BlockState here = level.getBlockState(e.pos());
            if (here.isAir() || here.canBeReplaced()) {
                // Flag 2 without neighbour updates: the lower door half must not drop the upper one placed next.
                level.setBlock(e.pos(), e.state(), 2 | 16);
                n++;
            }
            it.remove();
            setDirty();
        }
        return n;
    }

    @SubscribeEvent
    static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !event.getPlayer().isCreative() && isColdIron(event.getState())) {
            get(level).broken(level, event.getPos(), event.getState());
            MurimMod.LOGGER.info("Холодное железо сломано: {} у {} — вернётся через {} тиков", event.getPlayer().getName().getString(),
                    event.getPos().toShortString(), ColdIronRules.REGROW_TICKS);
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level && level.getGameTime() % 40 == 0) {
            ColdIronRegrowth data = level.getDataStorage().get(FACTORY, NAME);
            if (data != null && data.pending() > 0) {
                data.regrow(level, level.getGameTime());
            }
        }
    }

    static boolean isColdIron(BlockState state) {
        return state.getBlock() instanceof ColdIronBlock || state.getBlock() instanceof ColdIronBarsBlock
                || state.getBlock() instanceof ColdIronDoorBlock;
    }
}
