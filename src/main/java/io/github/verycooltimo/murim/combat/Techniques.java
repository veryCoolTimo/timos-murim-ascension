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
     * <p>Кулдаун 40 тиков считается от начала до начала, то есть даёт около 0.4 секунды паузы
     * после завершения. Он нужен не для баланса, а чтобы модифицированный клиент не мог
     * запускать технику каждый тик.
     */
    public static final Technique CEREMONIAL_DRAW = new Technique(
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "ceremonial_draw"),
            phases(14, 2, 6, 10),
            4.0D,
            140.0D,
            6.0F,
            3,
            40
    );

    private static Map<TechniquePhase, Integer> phases(int windup, int impact, int recovery, int dissipation) {
        Map<TechniquePhase, Integer> map = new EnumMap<>(TechniquePhase.class);
        map.put(TechniquePhase.WINDUP, windup);
        map.put(TechniquePhase.IMPACT, impact);
        map.put(TechniquePhase.RECOVERY, recovery);
        map.put(TechniquePhase.DISSIPATION, dissipation);
        return map;
    }

    private Techniques() {
    }
}
