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
     * Путь до даньтяня: выученный метод и пройденные такты создания.
     *
     * <p>Сохраняется и переживает смерть: метод и практика — результат игры
     * (docs/design/19-dantian-qi-meditation.md).
     */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.cultivation.CultivationState>> CULTIVATION =
            ATTACHMENT_TYPES.register("cultivation_state",
                    () -> AttachmentType.builder(() -> io.github.verycooltimo.murim.cultivation.CultivationState.NONE)
                            .serialize(io.github.verycooltimo.murim.cultivation.CultivationState.CODEC)
                            .copyOnDeath()
                            .build());

    /**
     * Выученные техники, их освоение и мудрость (docs/design/19 §3г). Сохраняется и
     * переживает смерть: выученное — результат игры.
     */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.mastery.MasteryState>> MASTERY =
            ATTACHMENT_TYPES.register("mastery",
                    () -> AttachmentType.builder(() -> io.github.verycooltimo.murim.mastery.MasteryState.EMPTY)
                            .serialize(io.github.verycooltimo.murim.mastery.MasteryState.CODEC)
                            .copyOnDeath()
                            .build());

    /** Техники по слотам и выбранная (решение автора 01.10). Сохраняется и переживает смерть. */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.mastery.Loadout>> LOADOUT =
            ATTACHMENT_TYPES.register("loadout",
                    () -> AttachmentType.builder(() -> io.github.verycooltimo.murim.mastery.Loadout.EMPTY)
                            .serialize(io.github.verycooltimo.murim.mastery.Loadout.CODEC)
                            .copyOnDeath()
                            .build());

    /** Идущая сессия медитации. Не сохраняется: сессия требует неподвижности здесь и сейчас. */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.cultivation.MeditationState>> MEDITATION =
            ATTACHMENT_TYPES.register("meditation_state",
                    () -> AttachmentType.<io.github.verycooltimo.murim.cultivation.MeditationState>builder(
                            () -> io.github.verycooltimo.murim.cultivation.MeditationState.IDLE).build());

    /**
     * Аура существа для давления (docs/design/19 §3ж). Сохраняется: манекен или моб с аурой
     * не должен терять её при перезаходе. Живёт на любом {@code LivingEntity}.
     */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.combat.AuraState>> AURA =
            ATTACHMENT_TYPES.register("aura",
                    () -> AttachmentType.builder(() -> io.github.verycooltimo.murim.combat.AuraState.NONE)
                            .serialize(io.github.verycooltimo.murim.combat.AuraState.CODEC)
                            .build());

    /** Давление ауры на игрока сейчас, 0..1. Не сохраняется: считается каждые два тика. */
    public static final Supplier<AttachmentType<Float>> PRESSURE =
            ATTACHMENT_TYPES.register("aura_pressure",
                    () -> AttachmentType.<Float>builder(() -> 0.0F).build());

    /**
     * Порывы давления: {тиков до толчка, тиков до обратной тяги, id источника}. Не сохраняется:
     * живёт только пока рядом сильный.
     */
    public static final Supplier<AttachmentType<int[]>> AURA_GUST =
            ATTACHMENT_TYPES.register("aura_gust",
                    () -> AttachmentType.<int[]>builder(() -> new int[] {0, -1, -1}).build());

    /**
     * Шаги (docs/design/21-footwork-families.md): {бег активен 0/1, слой, тиков бега осталось,
     * отталкиваний потрачено, тик последнего перелёта, поворот в воздухе потрачен 0/1, семейство
     * (ordinal), тик последней опоры, тиков без спринта подряд, оплаченных тиков бега, тиков на
     * опоре подряд, тик готовности Тени}. «Активен»: 1 — бег, 2 — Шаг Тени.
     * Не сохраняется: режим живёт секунды.
     */
    /** Захваченная цель игрока (id сущности, −1 — нет), 03.10. Не сохраняется. */
    public static final Supplier<AttachmentType<int[]>> LOCK =
            ATTACHMENT_TYPES.register("lock_on", () -> AttachmentType.<int[]>builder(() -> new int[] {-1}).build());

    /** Цель, замороженная в воздухе приёмом: {игровое время конца, 1 — гравитацию выключили мы}. */
    public static final Supplier<AttachmentType<long[]>> FROZEN =
            ATTACHMENT_TYPES.register("frozen", () -> AttachmentType.<long[]>builder(() -> new long[] {0L, 0L}).build());

    /** 1 — ИИ моба выключили мы на время оглушения техникой (вернуть после). Не сохраняется. */
    public static final Supplier<AttachmentType<int[]>> STUN_AI =
            ATTACHMENT_TYPES.register("stun_ai", () -> AttachmentType.<int[]>builder(() -> new int[] {0}).build());

    public static final Supplier<AttachmentType<int[]>> TRAVERSE =
            ATTACHMENT_TYPES.register("traverse",
                    () -> AttachmentType.<int[]>builder(() -> new int[] {0, 0, 0, 0, Integer.MIN_VALUE / 2, 0, 0,
                            Integer.MIN_VALUE / 2, 0, 0, 0, Integer.MIN_VALUE / 2, 0}).build());

    /** Техника шага, которая держит бег: туда идёт освоение за пробежку. Не сохраняется. */
    public static final Supplier<AttachmentType<String>> FOOTWORK_TECH =
            ATTACHMENT_TYPES.register("footwork_tech", () -> AttachmentType.builder(() -> "").build());

    /**
     * Следующий рывок шага, заданный контекстом ввода: {dx, dz, задано 0/1, дальность (0 — по
     * таблице Мига; иначе Шаг Смерти к цели)}.
     */
    public static final Supplier<AttachmentType<float[]>> FOOTWORK_DIR =
            ATTACHMENT_TYPES.register("footwork_dir",
                    () -> AttachmentType.<float[]>builder(() -> new float[] {0.0F, 0.0F, 0.0F, 0.0F}).build());

    /**
     * Вихрь Цветущей Сливы в работе: {центр x, y, z, yaw, слой, первое попадание 0/1, финал
     * попал 0/1, id цели или −1, точка цели x, y, z}. Не сохраняется — живёт одну технику.
     */
    public static final Supplier<AttachmentType<double[]>> WHIRL =
            ATTACHMENT_TYPES.register("plum_whirl",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, 0, -1, 0, 0, 0}).build());

    /**
     * Казнь Цветущей Сливы: {центр x, y, z, угол «цель → оригинал», слой, первое попадание 0/1,
     * id цели, меток (попавших клонов), исходная точка x, z}. Не сохраняется.
     */
    public static final Supplier<AttachmentType<double[]>> EXEC =
            ATTACHMENT_TYPES.register("plum_exec",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, -1, 0, 0, 0}).build());

    /**
     * Натиск Цветущей Сливы: {старт x, y, z, направление x, z, слой, тик обволакивания или −1,
     * id цели или −1, точка обволакивания x, y, z, первое попадание 0/1, укол 0/1, направление y,
     * id наводки или −1, тик отброса, число волочёных, их id…}. Не сохраняется.
     */
    public static final Supplier<AttachmentType<double[]>> RUSH =
            ATTACHMENT_TYPES.register("plum_rush",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, 0, 0}).build());

    /**
     * Ливень Цветущей Сливы: {старт x, y, z, направление x, z, слой, id цели или −1, метка 0/1,
     * первое попадание 0/1, точка остановки x, y, z}. Не сохраняется — живёт одну технику.
     */
    public static final Supplier<AttachmentType<double[]>> RAIN =
            ATTACHMENT_TYPES.register("plum_rain",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, -1, 0, 0, 0, 0, 0}).build());
    /**
     * Опадающие Лепестки, Перекрывающие Реку: {кисть x, y, z, прицел x, y, z, слой, id цели или −1,
     * тик контакта или −1, id цели контакта или −1, урон в руке, взрыв 0/1, дальность}. Не сохраняется.
     */
    public static final Supplier<AttachmentType<double[]>> RIVER =
            ATTACHMENT_TYPES.register("plum_river",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, 0, -1, -1, -1, 0, 0, 0}).build());
    /** Взрыв Цветущей Сливы: см. ExplosionExecutor. Не сохраняется — живёт одну технику. */
    public static final Supplier<AttachmentType<double[]>> EXPLOSION =
            ATTACHMENT_TYPES.register("plum_explosion",
                    () -> AttachmentType.<double[]>builder(() -> new double[40]).build());

    /** Ливень Цветов (Семь Цветков Сливы): см. ShowerExecutor. Не сохраняется — живёт одну технику. */
    public static final Supplier<AttachmentType<double[]>> SHOWER =
            ATTACHMENT_TYPES.register("plum_shower",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, 0, -1, -1, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0}).build());
    /** Рассеяние Цветущей Сливы: см. ScatterExecutor. Не сохраняется — живёт одну технику. */
    public static final Supplier<AttachmentType<double[]>> SCATTER =
            ATTACHMENT_TYPES.register("plum_scatter",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 0, 0, 0, 0, -1, 1, 1, 0, 0, 0, 0}).build());
    /** Меч Падающего Цветка: см. FallingPetalExecutor. Не сохраняется — живёт одну технику. */
    public static final Supplier<AttachmentType<double[]>> FALLING_PETAL =
            ATTACHMENT_TYPES.register("falling_petal",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 1, 0, -1, 0, 0, 1, 0, 0, 1}).build());
    /** Купол Цветущей Сливы: см. DomeExecutor. Не сохраняется — живёт одну технику. */
    public static final Supplier<AttachmentType<double[]>> DOME =
            ATTACHMENT_TYPES.register("plum_dome",
                    () -> AttachmentType.<double[]>builder(() -> new double[] {0, 0, 0, 0, 1, 0, 0, 0, 0, 1}).build());

    /** Последний взмах основы меча: {форма, тик}. Не сохраняется — живёт тики. */
    public static final Supplier<AttachmentType<int[]>> FOUNDATION_SWING =
            ATTACHMENT_TYPES.register("foundation_swing",
                    () -> AttachmentType.<int[]>builder(() -> new int[] {0, -1}).build());

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
    /**
     * Время последнего запуска каждой техники (автор 03.10: «кулдаун одной техники влияет на
     * все»): перезарядка теперь своя у каждой. Переживает смерть, как и состояние техники.
     */
    public static final Supplier<AttachmentType<java.util.Map<net.minecraft.resources.ResourceLocation, Long>>> COOLDOWNS =
            ATTACHMENT_TYPES.register("cooldowns",
                    () -> AttachmentType.<java.util.Map<net.minecraft.resources.ResourceLocation, Long>>builder(() -> java.util.Map.of())
                            .serialize(com.mojang.serialization.Codec.unboundedMap(net.minecraft.resources.ResourceLocation.CODEC, com.mojang.serialization.Codec.LONG))
                            .copyOnDeath()
                            .build());

    public static final Supplier<AttachmentType<TechniqueState>> TECHNIQUE_STATE =
            ATTACHMENT_TYPES.register("technique_state",
                    () -> AttachmentType.<TechniqueState>builder(() -> TechniqueState.IDLE)
                            .serialize(TechniqueState.CODEC)
                            .copyOnDeath()
                            .build());

    /** Пилюли: повторы, стойкость к ядам, окно «сразу» (docs/design/19b §1). Сохраняется, переживает смерть. */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.cultivation.PillState>> PILLS =
            ATTACHMENT_TYPES.register("pills",
                    () -> AttachmentType.builder(() -> io.github.verycooltimo.murim.cultivation.PillState.NONE)
                            .serialize(io.github.verycooltimo.murim.cultivation.PillState.CODEC)
                            .copyOnDeath()
                            .build());

    /** Идущая мини-игра поглощения пилюль. Не сохраняется: живёт только в сессии медитации. */
    public static final Supplier<AttachmentType<io.github.verycooltimo.murim.cultivation.PillService.Slot>> ABSORB =
            ATTACHMENT_TYPES.register("absorb",
                    () -> AttachmentType.builder(io.github.verycooltimo.murim.cultivation.PillService.Slot::new).build());

    private ModAttachments() {
    }
}
