package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.library.TextBooks;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import java.util.ArrayList;
import java.util.List;

/** Saved, per-player reading history for feedback formerly sent to chat or the action bar.
 * Latest value per translation key, at most 64 entries. Synced only when explicitly opened from the tablet.
 * Quest flags and service counters remain the source of truth; this is their readable history.
 */
public final class PlayerJournal {
    public static final int LIMIT = 64;
    public static final com.mojang.serialization.Codec<List<Component>> CODEC =
            net.minecraft.network.chat.ComponentSerialization.CODEC.listOf()
                    .xmap(lines -> List.copyOf(lines.subList(0, Math.min(lines.size(), LIMIT))), List::copyOf);
    private static final String LAST_SOUND = "murim_journal_refusal_sound";
    private PlayerJournal() { }

    public static List<Component> entries(ServerPlayer player) {
        return player.getData(ModAttachments.PLAYER_JOURNAL);
    }

    private static String key(Component line) {
        return line.getContents() instanceof TranslatableContents t ? t.getKey() : line.getString();
    }

    public static void record(Player player, Component line) {
        if (!(player instanceof ServerPlayer serverPlayer)) return;
        List<Component> before = entries(serverPlayer);
        // Repeated polling of the same condition neither moves the entry nor allocates a new history.
        if (before.stream().anyMatch(line::equals)) return;
        List<Component> next = new ArrayList<>(before);
        next.removeIf(old -> key(old).equals(key(line)));
        next.add(0, line.copy());
        if (next.size() > LIMIT) next.subList(LIMIT, next.size()).clear();
        serverPlayer.setData(ModAttachments.PLAYER_JOURNAL, List.copyOf(next));
    }

    public static void refuse(Player player, Component reason) {
        record(player, reason);
        if (!(player instanceof ServerPlayer p)) return;
        long now = p.level().getGameTime();
        if (p.getPersistentData().contains(LAST_SOUND) && now - p.getPersistentData().getLong(LAST_SOUND) < 10) return;
        p.getPersistentData().putLong(LAST_SOUND, now);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.25F, 0.6F);
    }

    public static void open(ServerPlayer player) {
        List<Component> lines = new ArrayList<>(entries(player));
        for (var observation : io.github.verycooltimo.murim.quest.QuestLoader.observations()) {
            if (io.github.verycooltimo.murim.quest.Quests.done(player, observation.quest(), "observed." + observation.step().id())) {
                Component fact = Component.translatable("murim.observation." + observation.step().id());
                if (!lines.contains(fact)) lines.add(fact);
            }
        }
        for (var herb : io.github.verycooltimo.murim.quest.QuestLoader.herbs()) {
            if (io.github.verycooltimo.murim.quest.Quests.has(player, "herb." + herb.step().id() + ".wild")) {
                Component fact = Component.translatable("murim.herb." + herb.step().id() + ".sample");
                if (!lines.contains(fact)) lines.add(fact);
            }
        }
        TextBooks.open(player, Component.translatable("murim.tablet.journal.title"),
                lines.isEmpty() ? List.of(Component.translatable("murim.tablet.journal.empty")) : lines);
    }
}
