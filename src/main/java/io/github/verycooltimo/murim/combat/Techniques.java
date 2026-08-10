package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.EnumMap;
import java.util.Map;

/**
 * Техники этапа 0. Захардкожены сознательно: схема данных выносится на этапе 1 из уже работающей
 * техники, а не придумывается заранее — см. docs/design/09-mvp-plan.md.
 */
public final class Techniques {

    /**
     * Церемониальный выхват. Медленный тихий замах, одна широкая дуга, затухание.
     *
     * <p>Тайминги — первое приближение и подлежат правке по живой картинке. Пропорция взята
     * из разбора референсных панелей: замах заметно длиннее удара, рассеивание длиннее
     * восстановления. При 20 тиках в секунду весь приём занимает 1.6 секунды.
     *
     * <p>Кулдаун 110 тиков считается от начала до начала. Техника целиком занимает 92 тика
     * вместе с трёхсекундным ритуалом, поэтому прежние 40 позволяли начать следующую
     * прямо посреди предыдущей.
     *
     * <p>Старый комментарий про 0.4 секунды паузы
     * после завершения. Он нужен не для баланса, а чтобы модифицированный клиент не мог
     * запускать технику каждый тик.
     */
    public static final Technique CEREMONIAL_DRAW = new Technique(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "ceremonial_draw"),
            phases(60, 14, 2, 6, 10),
            4.0D,
            140.0D,
            6.0F,
            3,
            110
    );

    private static Map<TechniquePhase, Integer> phases(int ritual, int windup, int impact,
                                                       int recovery, int dissipation) {
        Map<TechniquePhase, Integer> map = new EnumMap<>(TechniquePhase.class);
        map.put(TechniquePhase.RITUAL, ritual);
        map.put(TechniquePhase.WINDUP, windup);
        map.put(TechniquePhase.IMPACT, impact);
        map.put(TechniquePhase.RECOVERY, recovery);
        map.put(TechniquePhase.DISSIPATION, dissipation);
        return map;
    }

    private Techniques() {
    }
}
