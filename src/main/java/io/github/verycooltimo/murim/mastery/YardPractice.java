package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * The player's side of the training yard (docs/design/research/hua-main-1810.md section 6, T1): which forms the last hits
 * were, so that variety is told from monotony. The rules are
 * {@link YardRules}; the practice itself goes the normal way ({@link MasteryService#onHit}), so the caps hold.
 *
 * <p>State lives in the player's {@code persistentData} (like the daily fatigue): the last {@link YardRules#VARIETY_WINDOW}
 * form keys and the game time of the last hit. Nothing is synchronised.
 */
public final class YardPractice {

    private static final String TAG = "murim_yard";

    /** What one hit on a dummy was: the fatigue floor asks {@link #varied}. */
    public record Hit(boolean clean, boolean varied) {
    }

    private YardPractice() {
    }

    /** One hit of {@code form} ({@link YardRules#formKey}) on a dummy. */
    public static Hit onHit(ServerPlayer player, TrainingDummy dummy, String form) {
        CompoundTag tag = player.getPersistentData().getCompound(TAG);
        long now = player.level().getGameTime();
        List<String> next = YardRules.remember(recent(tag), form, tag.contains("last") ? now - tag.getLong("last") : Long.MAX_VALUE);
        boolean clean = dummy.strikeSpot(form);
        boolean varied = YardRules.varied(next);
        ListTag saved = new ListTag();
        next.forEach(k -> saved.add(StringTag.valueOf(k)));
        tag.put("recent", saved);
        tag.putLong("last", now);
        if (Boolean.getBoolean("murim.capture")) {
            // The stand's log: the film cannot show numbers, the log can (form, clean, varied, the run).
            io.github.verycooltimo.murim.MurimMod.LOGGER.info("Yard: {} hit {} on a dummy, clean={} varied={} run={}",
                    player.getGameProfile().getName(), form, clean, varied, next);
        }
        if (clean) {
            player.level().playSound(null, dummy.getX(), dummy.getY() + 1.2D, dummy.getZ(),
                    net.minecraft.sounds.SoundEvents.AMETHYST_BLOCK_RESONATE, net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.5F);
        }
        if (!tag.getBoolean("tip") && dummy.yardSlot() >= 0) {
            tag.putBoolean("tip", true);
            PlayerJournal.record(player, net.minecraft.network.chat.Component.translatable("murim.yard.tip"));
        }
        player.getPersistentData().put(TAG, tag);
        return new Hit(clean, varied);
    }

    /** The forms of the current run, oldest first; a long pause expires it without writing data. */
    public static List<String> recent(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData().getCompound(TAG);
        return player.level().getGameTime() - tag.getLong("last") > YardRules.RUN_BREAK_TICKS
                ? List.of() : recent(tag);
    }

    private static List<String> recent(CompoundTag tag) {
        List<String> out = new ArrayList<>();
        for (Tag t : tag.getList("recent", Tag.TAG_STRING)) {
            out.add(t.getAsString());
        }
        return out;
    }
}
