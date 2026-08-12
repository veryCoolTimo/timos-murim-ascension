package io.github.verycooltimo.murim.registry;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.function.Supplier;

/**
 * Data Attachments мода. В 1.21.1 это штатная замена capabilities.
 */
public final class ModAttachments {

    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, MurimMod.MODID);

    /**
     * Состояние применяемой техники. Без сериализации и без синхронизации: техника короче двух
     * секунд, клиент узнаёт о ней отдельным пакетом, а состояние, пережившее перезаход, вернуло бы
     * игрока в мир посреди замаха.
     */
    /**
     * Профиль даньтяня. В отличие от состояния техники он ПЕРЕЖИВАЕТ смерть и перезаход:
     * это результат всей культивации персонажа, терять его при гибели было бы наказанием
     * несоразмерным ошибке.
     */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.profile.DantianProfile>> PROFILE =
            ATTACHMENT_TYPES.register("dantian_profile",
                    () -> AttachmentType.builder(() -> io.github.verycooltimo.murim.profile.DantianProfile.INITIAL)
                            .serialize(io.github.verycooltimo.murim.profile.DantianProfile.CODEC)
                            .copyOnDeath()
                            .build());

    /**
     * Ритуал НЕ сохраняется и не переживает смерть: медитация прерывается любым выходом
     * из мира, и продолжать её после перезахода было бы способом обойти риск.
     */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.profile.RitualState>> RITUAL =
            ATTACHMENT_TYPES.register("ritual_state",
                    () -> AttachmentType.<io.github.verycooltimo.murim.profile.RitualState>builder(
                            () -> io.github.verycooltimo.murim.profile.RitualState.IDLE).build());

    /**
     * Церемония создания даньтяня.
     *
     * <p>НЕ сохраняется намеренно, как и циркуляция: сцена длится секунды и требует
     * неподвижности, а продолжать её после перезахода означало бы обходить риск срыва.
     * Сам созданный даньтянь сохраняется — он живёт в профиле.
     */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.profile.AwakeningState>> AWAKENING =
            ATTACHMENT_TYPES.register("awakening_state",
                    () -> AttachmentType.<io.github.verycooltimo.murim.profile.AwakeningState>builder(
                            () -> io.github.verycooltimo.murim.profile.AwakeningState.IDLE).build());

    /** Серия обычных ударов. Не сохраняется: связка живёт секунды и через сейв не тянется. */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.combat.SchoolStyle.ComboState>> COMBO =
            ATTACHMENT_TYPES.register("combo_state",
                    () -> AttachmentType.<io.github.verycooltimo.murim.combat.SchoolStyle.ComboState>builder(
                            () -> io.github.verycooltimo.murim.combat.SchoolStyle.ComboState.IDLE).build());

    /**
     * Состояние текущей техники и время последнего запуска.
     *
     * <p>Не переживает смерть намеренно: незаконченная техника после респавна — это
     * зависшее состояние. Но кулдаун сбрасывать нельзя, иначе смерть становится способом
     * мгновенно перезарядить приём; поэтому раньше здесь была дыра, найденная ревью.
     * Решение — сериализовать состояние, но обнулять активную часть при клоне игрока
     * (см. {@code CombatEvents}).
     */
    public static final Supplier<AttachmentType<TechniqueState>> TECHNIQUE_STATE =
            ATTACHMENT_TYPES.register("technique_state",
                    () -> AttachmentType.<TechniqueState>builder(() -> TechniqueState.IDLE)
                            .serialize(TechniqueState.CODEC)
                            .copyOnDeath()
                            .build());

    private ModAttachments() {
    }
}
