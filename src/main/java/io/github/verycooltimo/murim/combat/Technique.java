package io.github.verycooltimo.murim.combat;

import net.minecraft.resources.ResourceLocation;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * Описание техники: последовательность движений с длительностями в тиках.
 *
 * <p>На этапе 0 техника собирается в коде. На этапе 1 из этой же структуры вынимается схема
 * данных, и описания переезжают в датапак — поэтому здесь сознательно нет ничего, что нельзя
 * выразить в JSON, и есть валидация: из датапака придут любые числа, включая отрицательные.
 *
 * @param id            идентификатор техники
 * @param phaseTicks    длительность каждой фазы в тиках; отсутствующая фаза = нулевая длительность
 * @param reach         дальность поражения в блоках от глаз применяющего
 * @param arcDegrees    ширина дуги поражения в градусах, полный угол
 * @param damage        урон в единицах здоровья
 * @param hitStopTicks  на сколько тиков замирает картинка при попадании
 * @param cooldownTicks минимальный промежуток между двумя запусками, от начала до начала
 */
public record Technique(
        ResourceLocation id,
        Map<TechniquePhase, Integer> phaseTicks,
        double reach,
        double arcDegrees,
        float damage,
        int hitStopTicks,
        int cooldownTicks
) {
    public Technique {
        java.util.Objects.requireNonNull(id, "id");
        java.util.Objects.requireNonNull(phaseTicks, "phaseTicks");

        EnumMap<TechniquePhase, Integer> copy = new EnumMap<>(TechniquePhase.class);
        copy.putAll(phaseTicks);
        for (Map.Entry<TechniquePhase, Integer> entry : copy.entrySet()) {
            if (entry.getValue() == null || entry.getValue() < 0) {
                throw new IllegalArgumentException(
                        "Длительность фазы " + entry.getKey() + " у техники " + id + " отрицательна");
            }
        }
        // Карта отдаётся наружу аксессором, поэтому наружу уходит неизменяемое представление.
        phaseTicks = Collections.unmodifiableMap(copy);

        // Проверки написаны в отрицательной форме намеренно: NaN проходит любое сравнение
        // «больше» и «меньше» как false, поэтому прямая форма (reach <= 0) его пропускает.
        // NaN в arcDegrees превращает косинус в NaN и полностью отключает угловой фильтр —
        // техника начинает бить на все 360 градусов.
        if (!(reach > 0.0D)) {
            throw new IllegalArgumentException("Недопустимая дальность " + reach + " у техники " + id);
        }
        if (!(arcDegrees > 0.0D && arcDegrees <= 360.0D)) {
            throw new IllegalArgumentException("Недопустимая дуга " + arcDegrees + " у техники " + id);
        }
        // Отрицательный урон у мобов не клампится и лечит цель вместо того, чтобы ранить.
        if (!(damage >= 0.0F)) {
            throw new IllegalArgumentException("Недопустимый урон " + damage + " у техники " + id);
        }
        if (hitStopTicks < 0 || cooldownTicks < 0) {
            throw new IllegalArgumentException("Отрицательные тики у техники " + id);
        }
    }

    public int ticksOf(TechniquePhase phase) {
        return phaseTicks.getOrDefault(phase, 0);
    }

    /** Тик, на котором начинается фаза, от начала техники. */
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

    /** Фаза, активная на указанном тике от начала техники, или {@code null} если техника кончилась. */
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
