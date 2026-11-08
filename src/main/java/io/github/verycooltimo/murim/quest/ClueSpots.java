package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;


/**
 * The spots of the world that quests place as {@link ClueBlock}s: what each says (lang key {@code murim.spot.<id>}), which
 * clue it gives, whether the player must copy it for {@link #COPY_TICKS} first (a rubbing, a sketch: nothing is spent, an
 * interrupted copy just starts again), and what examining or taking it records. A spot never punishes and is never gone: a
 * clue taken from it can be taken again if the player no longer carries it, a reward is paid once per player.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ClueSpots {

    /** A personal sketch takes five seconds (design: side-quests-v3, quest 6). */
    public static final int COPY_TICKS = 100;
    private static final String COPY_TAG = "murim_clue_copy";
    private static final double COPY_RANGE = 4.0D;

    private ClueSpots() {
    }

    public static java.util.Set<String> ids() {
        return QuestLoader.spots().stream().map(l -> l.step().id()).collect(java.util.stream.Collectors.toSet());
    }

    public static String textKey(String id) {
        return "murim.spot." + id;
    }

    /** The right click on a spot (the engine is {@link QuestService}). */
    public static void use(ServerPlayer p, BlockPos pos, String id) {
        QuestService.use(p, pos, id);
    }

    // ------------------------------------------------------------------ copying (a rubbing or a sketch)

    static void startCopy(ServerPlayer p, BlockPos pos, String id) {
        CompoundTag t = p.getPersistentData();
        if (t.contains(COPY_TAG) && t.getCompound(COPY_TAG).getString("spot").equals(id)) return;
        CompoundTag c = new CompoundTag();
        c.putString("spot", id);
        c.putLong("pos", pos.asLong());
        c.putInt("t", 0);
        t.put(COPY_TAG, c);
        p.level().playSound(null, pos, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundSource.PLAYERS, 0.7F, 1.2F);
    }

    /** Copy in progress for the player, or null. */
    public static String copying(ServerPlayer p) {
        CompoundTag t = p.getPersistentData();
        return t.contains(COPY_TAG) ? t.getCompound(COPY_TAG).getString("spot") : null;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !p.getPersistentData().contains(COPY_TAG)) return;
        tickCopy(p, 1);
    }

    /** Advances the copy by {@code ticks} (tests step it); an interrupted copy is dropped without a cost. */
    public static void tickCopy(ServerPlayer p, int ticks) {
        CompoundTag t = p.getPersistentData();
        CompoundTag c = t.getCompound(COPY_TAG);
        var located = QuestLoader.spot(c.getString("spot"));
        QuestDef.Inspect s = located == null ? null : located.step();
        BlockPos pos = BlockPos.of(c.getLong("pos"));
        if (s == null || p.distanceToSqr(pos.getCenter()) > COPY_RANGE * COPY_RANGE || p.isSpectator() || !p.isAlive()) {
            t.remove(COPY_TAG);
            PlayerJournal.refuse(p, Component.translatable("murim.clue.copy_broken"));
            return;
        }
        int now = c.getInt("t") + ticks;
        c.putInt("t", now);
        if (now < s.copyTicks()) {
            if (now % 10 == 0) {
                PlayerJournal.record(p, Component.translatable("murim.notes.copying", 100 * now / s.copyTicks()));
            }
            return;
        }
        t.remove(COPY_TAG);
        QuestService.grant(p, s);
    }

}
