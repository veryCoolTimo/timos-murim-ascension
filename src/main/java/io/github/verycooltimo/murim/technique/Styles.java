package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Стили — группы форм (docs/design/techniques/styles-and-forms.md, решение codex 02.10): стиль
 * занимает одну ячейку раскладки, его формы выбираются на кольце V, у каждой формы свои
 * анимация, урон, кулдаун и слои освоения. Встроенный каталог — одинаковый у клиента и сервера.
 */
public final class Styles {

    /** {@code basic} — основа стиля на ЛКМ (серия обычных ударов), пусто — у стиля её нет. */
    public record Style(ResourceLocation id, List<ResourceLocation> forms, Optional<ResourceLocation> basic) {
        public String nameKey() {
            return "style." + id.getNamespace() + "." + id.getPath();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** Меч Семи Цветков Сливы: Разрез·Частокол → Вихрь → Казнь → Натиск (порядок канона). */
    public static final Style SEVEN_PLUM = new Style(id("seven_plum"), List.of(
            id("seven_plum_blossoms"), id("seven_plum_whirlwind"), id("seven_plum_execution"), id("seven_plum_rush")),
            Optional.of(id("seven_plum_basic")));

    /**
     * Меч 24 Движений Цветущей Сливы: пока одна секретная форма — Ливень. Обычные движения
     * стиля добавятся формами сюда же, своей основы ЛКМ у стиля пока нет.
     */
    public static final Style TWENTY_FOUR_PLUM = new Style(id("twenty_four_plum"), List.of(
            id("twenty_four_plum_rainfall")), Optional.empty());

    /**
     * Шаги — тоже стили форм (автор 03.10: «техника шагов — то же, что 7 цветков: как ты
     * шагаешь»), docs/design/techniques/footwork-styles.md. Первая форма — уклонение: его же
     * вызывает мгновенное двойное нажатие A/D/S. Основы ЛКМ у шагов нет — стойка меча остаётся.
     */
    public static final Style DARK_FRAGRANCE = new Style(id("dark_fragrance"), List.of(
            id("dark_fragrance_step"), id("dark_fragrance_trail"), id("dark_fragrance_behind")), Optional.empty());

    public static final Style WIND_GOD = new Style(id("wind_god"), List.of(
            id("wind_god_steps"), id("wind_god_shadow"), id("wind_god_death"), id("wind_god_lightning")), Optional.empty());

    public static final List<Style> ALL = List.of(SEVEN_PLUM, TWENTY_FOUR_PLUM, DARK_FRAGRANCE, WIND_GOD);

    /** Стили шагов: их первая форма — мгновенное уклонение. */
    public static final List<Style> FOOTWORK = List.of(DARK_FRAGRANCE, WIND_GOD);

    /** Стиль, которому принадлежит форма; техника вне стилей — пусто. */
    public static Optional<Style> of(ResourceLocation technique) {
        for (Style s : ALL) {
            if (s.forms().contains(technique)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    public static boolean sameStyle(ResourceLocation a, ResourceLocation b) {
        Optional<Style> sa = of(a);
        return sa.isPresent() && sa.equals(of(b));
    }

    private Styles() {
    }
}
