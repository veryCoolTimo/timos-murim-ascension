package io.github.verycooltimo.murim.technique;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.github.verycooltimo.murim.combat.TechniquePhase;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Техника целиком, описанная данными.
 *
 * <p>Формула из книги: техника — это <b>форма + намерение + дыхание</b>, три независимых слота.
 * Смена одного только дыхания при той же форме даёт другую технику; именно так в первоисточнике
 * выведен демонический стиль. Слоты хранятся строками и пока ни на что не влияют механически —
 * они нужны, чтобы схема не переписывалась, когда влияние появится.
 *
 * <p>Поля отмены и связок заведены заранее по той же причине. Даже если все техники первой
 * версии неотменяемые, прикручивать окна отмены и переходы задним числом означает переписать
 * исполнитель целиком.
 */
public record TechniqueDefinition(
        ResourceLocation id,
        Map<TechniquePhase, Integer> phaseTicks,
        String form,
        String intent,
        String breath,
        TechniqueBehavior behavior,
        TechniqueVfx vfx,
        ResourceLocation animation,
        Interruption interruption,
        String weaknessKey,
        int hitStopTicks,
        int cooldownTicks,
        int layers,
        io.github.verycooltimo.murim.mastery.TechniqueTier tier,
        List<io.github.verycooltimo.murim.mastery.TechniqueRequirement> requires
) {

    /**
     * Правила прерывания и связок.
     *
     * @param cancellableUntil  последняя фаза, из которой игрок может выйти сам;
     *                          {@code null} означает «отменить нельзя вообще»
     * @param breakOnDamage     сбивает ли технику полученный урон
     * @param damageThreshold   с какого урона сбивает; ноль означает «любой»
     * @param linkWindowTicks   сколько тиков в конце принимается ввод следующей техники
     * @param linksTo           какие техники допустимы как продолжение
     */
    public record Interruption(TechniquePhase cancellableUntil, boolean breakOnDamage,
                               float damageThreshold, int linkWindowTicks,
                               List<ResourceLocation> linksTo) {
        public static final Codec<Interruption> CODEC = RecordCodecBuilder.create(i -> i.group(
                TechniquePhase.CODEC.optionalFieldOf("cancellable_until")
                        .forGetter(v -> java.util.Optional.ofNullable(v.cancellableUntil())),
                Codec.BOOL.optionalFieldOf("break_on_damage", false).forGetter(Interruption::breakOnDamage),
                Codec.FLOAT.optionalFieldOf("damage_threshold", 0.0F).forGetter(Interruption::damageThreshold),
                Codec.INT.optionalFieldOf("link_window_ticks", 0).forGetter(Interruption::linkWindowTicks),
                ResourceLocation.CODEC.listOf().optionalFieldOf("links_to", List.of())
                        .forGetter(Interruption::linksTo)
        ).apply(i, (until, breakOn, threshold, window, links) ->
                new Interruption(until.orElse(null), breakOn, threshold, window, links)));

        public Interruption {
            if (!(damageThreshold >= 0.0F)) {
                throw new IllegalArgumentException("Отрицательный или нечисловой порог урона");
            }
            if (linkWindowTicks < 0) {
                throw new IllegalArgumentException("Отрицательное окно связки");
            }
            linksTo = List.copyOf(linksTo);
        }
    }

    private static final Codec<Map<TechniquePhase, Integer>> PHASES =
            Codec.unboundedMap(TechniquePhase.CODEC, Codec.INT);

    public static final Codec<TechniqueDefinition> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("id").forGetter(TechniqueDefinition::id),
            PHASES.fieldOf("phases").forGetter(TechniqueDefinition::phaseTicks),
            Codec.STRING.fieldOf("form").forGetter(TechniqueDefinition::form),
            Codec.STRING.fieldOf("intent").forGetter(TechniqueDefinition::intent),
            Codec.STRING.fieldOf("breath").forGetter(TechniqueDefinition::breath),
            TechniqueBehavior.CODEC.fieldOf("behavior").forGetter(TechniqueDefinition::behavior),
            TechniqueVfx.CODEC.fieldOf("vfx").forGetter(TechniqueDefinition::vfx),
            ResourceLocation.CODEC.fieldOf("animation").forGetter(TechniqueDefinition::animation),
            Interruption.CODEC.optionalFieldOf("interruption",
                            new Interruption(null, false, 0.0F, 0, List.of()))
                    .forGetter(TechniqueDefinition::interruption),
            Codec.STRING.optionalFieldOf("weakness", "").forGetter(TechniqueDefinition::weaknessKey),
            Codec.INT.optionalFieldOf("hit_stop_ticks", 0).forGetter(TechniqueDefinition::hitStopTicks),
            Codec.INT.fieldOf("cooldown_ticks").forGetter(TechniqueDefinition::cooldownTicks),
            // Освоение (docs/design/19 §3г): число слоёв, уровень и основы, без которых не выучить.
            Codec.INT.optionalFieldOf("layers", 3).forGetter(TechniqueDefinition::layers),
            io.github.verycooltimo.murim.mastery.TechniqueTier.CODEC
                    .optionalFieldOf("tier", io.github.verycooltimo.murim.mastery.TechniqueTier.BASIC)
                    .forGetter(TechniqueDefinition::tier),
            io.github.verycooltimo.murim.mastery.TechniqueRequirement.CODEC.listOf()
                    .optionalFieldOf("requires", List.of()).forGetter(TechniqueDefinition::requires)
    ).apply(i, TechniqueDefinition::new));

    public TechniqueDefinition {
        java.util.Objects.requireNonNull(id, "id");
        EnumMap<TechniquePhase, Integer> copy = new EnumMap<>(TechniquePhase.class);
        copy.putAll(phaseTicks);
        for (Map.Entry<TechniquePhase, Integer> entry : copy.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException(
                        "Длительность фазы " + entry.getKey() + " у техники " + id + " отрицательна");
            }
        }
        phaseTicks = java.util.Collections.unmodifiableMap(copy);
        if (layers < 1) {
            throw new IllegalArgumentException("У техники " + id + " должен быть хотя бы один слой");
        }
        requires = List.copyOf(requires);
        if (hitStopTicks < 0 || cooldownTicks < 0) {
            throw new IllegalArgumentException("Отрицательные тики у техники " + id);
        }
        int total = copy.values().stream().mapToInt(Integer::intValue).sum();
        if (total <= 0) {
            throw new IllegalArgumentException("Техника " + id + " не имеет ни одной фазы");
        }
        if (cooldownTicks < total) {
            // Кулдаун короче самой техники позволил бы запустить следующую посреди текущей:
            // состояние одно на игрока, и вторая молча затёрла бы первую.
            throw new IllegalArgumentException(
                    "Кулдаун " + cooldownTicks + " короче длительности техники " + total + " у " + id);
        }
    }

    public int ticksOf(TechniquePhase phase) {
        return phaseTicks.getOrDefault(phase, 0);
    }

    public int startTickOf(TechniquePhase phase) {
        int sum = 0;
        for (TechniquePhase p : TechniquePhase.values()) {
            if (p == phase) {
                return sum;
            }
            sum += ticksOf(p);
        }
        return sum;
    }

    public int totalTicks() {
        int sum = 0;
        for (TechniquePhase p : TechniquePhase.values()) {
            sum += ticksOf(p);
        }
        return sum;
    }

    /** Фаза на указанном тике от начала техники, или {@code null} если техника кончилась. */
    public TechniquePhase phaseAt(int tick) {
        if (tick < 0) {
            return null;
        }
        int sum = 0;
        for (TechniquePhase p : TechniquePhase.values()) {
            sum += ticksOf(p);
            if (tick < sum) {
                return p;
            }
        }
        return null;
    }
}
