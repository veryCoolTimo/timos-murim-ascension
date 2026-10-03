package io.github.verycooltimo.murim.cultivation;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Пилюли игрока: сколько каких уже съедено (повтор даёт меньше), стойкость к ядам
 * и открытое окно «сразу» (docs/design/19b §1).
 *
 * <p>Сохраняется и переживает смерть: съеденное — результат игры. Окно сохраняется вместе
 * со всем, оно короткое и сверяется по игровому времени.
 *
 * @param taken          съедено пилюль каждого вида за всю игру (ключ — имя {@link PillKind})
 * @param poisonResistant поглотил Пилюлю Тысячи Ядов: яды действуют вдвое короче
 * @param pending        съеденные в открытом окне
 * @param first          тик первой пилюли окна
 * @param deadline       тик конца окна
 */
public record PillState(Map<String, Integer> taken, boolean poisonResistant, List<PillKind> pending,
                        long first, long deadline) {

    public static final PillState NONE = new PillState(Map.of(), false, List.of(), 0L, 0L);

    private static final Codec<PillKind> KIND_CODEC = Codec.STRING.xmap(PillKind::valueOf, PillKind::name);

    public static final Codec<PillState> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.INT).optionalFieldOf("taken", Map.of()).forGetter(PillState::taken),
            Codec.BOOL.optionalFieldOf("poison_resistant", false).forGetter(PillState::poisonResistant),
            KIND_CODEC.listOf().optionalFieldOf("pending", List.of()).forGetter(PillState::pending),
            Codec.LONG.optionalFieldOf("first", 0L).forGetter(PillState::first),
            Codec.LONG.optionalFieldOf("deadline", 0L).forGetter(PillState::deadline)
    ).apply(i, PillState::new));

    public PillState {
        taken = Map.copyOf(taken);
        pending = List.copyOf(pending);
    }

    public int taken(PillKind kind) {
        return taken.getOrDefault(kind.name(), 0);
    }

    /** Окно открыто прямо сейчас. */
    public boolean windowOpen(long now) {
        return !pending.isEmpty() && now < deadline;
    }

    public PillState withEaten(PillKind kind, long now) {
        boolean firstPill = pending.isEmpty();
        List<PillKind> next = new ArrayList<>(pending);
        next.add(kind);
        long start = firstPill ? now : first;
        return new PillState(taken, poisonResistant, next, start,
                PillRules.deadline(start, deadline, now, firstPill));
    }

    /** Окно закрыто (сел или истекло): съеденное засчитано в повторы. */
    public PillState closed() {
        Map<String, Integer> next = new HashMap<>(taken);
        for (PillKind k : pending) {
            next.merge(k.name(), 1, Integer::sum);
        }
        return new PillState(next, poisonResistant, List.of(), 0L, 0L);
    }

    public PillState withPoisonResistant() {
        return new PillState(taken, true, pending, first, deadline);
    }
}
