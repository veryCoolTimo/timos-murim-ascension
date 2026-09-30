package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ArmorStand;

import java.util.List;
import java.util.Map;

/**
 * Изучение, освоение и мудрость на сервере (docs/design/19 §3г). Правила — в
 * {@link MasteryRules}; здесь только данные игрока, сообщения и звуки.
 */
public final class MasteryService {

    /** Итог попытки выучить технику. */
    public enum Learn { LEARNED, ALREADY, MISSING_BASICS, UNKNOWN }

    /**
     * Изучение из манускрипта.
     *
     * @param depth до какого слоя учит манускрипт; {@code <= 0} — полный
     */
    public static Learn learn(ServerPlayer player, ResourceLocation id, int depth) {
        TechniqueDefinition definition = TechniqueLoader.get(id);
        if (definition == null) {
            return Learn.UNKNOWN;
        }
        MasteryState state = player.getData(ModAttachments.MASTERY);
        int cap = depth <= 0 ? definition.layers() : Math.min(depth, definition.layers());
        if (state.knows(id)) {
            // Продолжение рваного манускрипта поднимает предел, но не сбрасывает освоенное.
            TechniqueProgress current = state.techniques().get(id);
            if (cap > current.cap()) {
                player.setData(ModAttachments.MASTERY, state.with(id, new TechniqueProgress(current.layer(),
                        current.progress(), current.unprocessed(), current.day(), cap)));
                message(player, "murim.mastery.deeper", ChatFormatting.GRAY, name(id));
                return Learn.LEARNED;
            }
            return Learn.ALREADY;
        }
        List<TechniqueRequirement> missing = MasteryRules.missing(definition.requires(), state.layers());
        if (!missing.isEmpty()) {
            // Жёсткий порог (автор 30.09): без основ слова не складываются в смысл.
            TechniqueRequirement first = missing.get(0);
            message(player, "murim.mastery.missing", ChatFormatting.GOLD, name(first.technique()), first.layer());
            return Learn.MISSING_BASICS;
        }
        int start = MasteryRules.startLayer(definition.tier(), cap, state.wisdom());
        MasteryState next = state.with(id, TechniqueProgress.learned(start, cap))
                .withWisdom(state.wisdom() + MasteryRules.wisdomForLearning(definition.tier()));
        player.setData(ModAttachments.MASTERY, next);
        message(player, start > 0 ? "murim.mastery.learned_skipped" : "murim.mastery.learned",
                ChatFormatting.GRAY, name(id), start);
        return Learn.LEARNED;
    }

    /** Может ли игрок применять технику: только выученную. */
    public static boolean knows(ServerPlayer player, ResourceLocation id) {
        return player.getData(ModAttachments.MASTERY).knows(id);
    }

    /** Слой техники у игрока, или -1, если не выучена. */
    public static int layer(ServerPlayer player, ResourceLocation id) {
        TechniqueProgress progress = player.getData(ModAttachments.MASTERY).techniques().get(id);
        return progress == null ? -1 : progress.layer();
    }

    /** Попадание техникой: живой противник — бой, манекен и стойка — тренировка. */
    public static void onHit(ServerPlayer player, ResourceLocation id, Entity target) {
        boolean training = target instanceof ArmorStand
                || target instanceof io.github.verycooltimo.murim.entity.TrainingDummy;
        experience(player, id, training ? MasteryRules.Source.TRAINING : MasteryRules.Source.FIGHT, 1.0D);
    }

    /** Техника доиграла без попадания — это тоже тренировка формы, но слабее. */
    public static void onMiss(ServerPlayer player, ResourceLocation id) {
        experience(player, id, MasteryRules.Source.TRAINING, 0.5D);
    }

    private static void experience(ServerPlayer player, ResourceLocation id, MasteryRules.Source source,
                                   double amount) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        TechniqueProgress progress = state.techniques().get(id);
        if (progress == null) {
            return;
        }
        MasteryRules.Gain gain = MasteryRules.experience(progress, source, amount, state.wisdom(), day(player));
        apply(player, id, gain, false);
    }

    /**
     * Тик медитации: осмысляется неосмысленное всех техник понемногу.
     *
     * @return есть ли что осмысливать
     */
    public static boolean meditate(ServerPlayer player) {
        MasteryState state = player.getData(ModAttachments.MASTERY);
        boolean any = false;
        long day = day(player);
        for (Map.Entry<ResourceLocation, TechniqueProgress> entry : state.techniques().entrySet()) {
            TechniqueProgress cooled = MasteryRules.cool(entry.getValue(), day);
            if (cooled.unprocessed() <= 0.0D) {
                continue;
            }
            any = true;
            MasteryState current = player.getData(ModAttachments.MASTERY);
            apply(player, entry.getKey(),
                    MasteryRules.meditate(current.techniques().get(entry.getKey()), current.wisdom(), day), true);
        }
        return any;
    }

    private static void apply(ServerPlayer player, ResourceLocation id, MasteryRules.Gain gain, boolean meditating) {
        MasteryState state = player.getData(ModAttachments.MASTERY).with(id, gain.progress());
        for (int layer : gain.layersReached()) {
            insight(player, id, layer, meditating);
        }
        TechniqueDefinition definition = TechniqueLoader.get(id);
        if (definition != null && !state.comprehended().contains(id)
                && MasteryRules.comprehends(definition.tier(), gain.progress().layer(), definition.layers())) {
            // Постижение крутой техники растит мудрость — косвенно, одной фразой.
            state = state.comprehend(id).withWisdom(state.wisdom() + MasteryRules.wisdomForComprehension());
            message(player, "murim.mastery.comprehended", ChatFormatting.AQUA, name(id));
        }
        player.setData(ModAttachments.MASTERY, state);
    }

    /** Озарение: новый слой. Визуал в бою и в медитации — следующим шагом (§3г, п. 4). */
    private static void insight(ServerPlayer player, ResourceLocation id, int layer, boolean meditating) {
        player.level().playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.AMETHYST_BLOCK_RESONATE, SoundSource.PLAYERS, 1.0F, 1.4F);
        player.displayClientMessage(Component.translatable("murim.mastery.layer", name(id), layer)
                .withStyle(ChatFormatting.AQUA), true);
    }

    private static long day(ServerPlayer player) {
        // Утро — граница дня: неосмысленное остывает, когда наступает новый день.
        return player.level().getDayTime() / 24000L;
    }

    public static Component name(ResourceLocation id) {
        return Component.translatable("technique." + id.getNamespace() + "." + id.getPath());
    }

    private static void message(ServerPlayer player, String key, ChatFormatting style, Object... args) {
        player.displayClientMessage(Component.translatable(key, args).withStyle(style), false);
    }

    private MasteryService() {
    }
}
