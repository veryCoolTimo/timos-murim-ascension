package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.List;
import java.util.Optional;

/**
 * Стили — группы форм (docs/design/techniques/styles-and-forms.md, решение codex 02.10): стиль
 * занимает одну ячейку раскладки, его формы выбираются на кольце V, у каждой формы свои
 * анимация, урон и кулдаун; звёзды освоения общие для всего искусства. Встроенный каталог — одинаковый у клиента и сервера.
 */
public final class Styles {

    /** {@code basic} — основа стиля на ЛКМ (серия обычных ударов), пусто — у стиля её нет. */
    public record Style(ResourceLocation id, List<ResourceLocation> forms, Optional<ResourceLocation> basic) {
        public String nameKey() {
            return (this == BAMBOO_HAND ? "art." : "style.") + id.getNamespace() + "." + id.getPath();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** Меч Семи Цветков Сливы по звёздам искусства (автор 22.10): Натиск ★1 → Вихрь ★3 → Казнь ★5 → Ливень ★7 → Взрыв ★9 → Разрез·Частокол ★11. */
    public static final Style SEVEN_PLUM = new Style(id("seven_plum"), List.of(
            id("seven_plum_rush"), id("seven_plum_whirlwind"), id("seven_plum_execution"), id("seven_plum_shower"), id("seven_plum_explosion"), id("seven_plum_blossoms")),
            Optional.of(id("seven_plum_basic")));

    /**
     * Меч 24 Движений Цветущей Сливы: секретные формы — Ливень, Рассеяние. Обычные движения
     * стиля добавятся формами сюда же, своей основы ЛКМ у стиля пока нет.
     */
    public static final Style TWENTY_FOUR_PLUM = new Style(id("twenty_four_plum"), List.of(
            id("twenty_four_plum_rainfall"), id("twenty_four_plum_full_bloom"), id("twenty_four_plum_scatter"), id("twenty_four_plum_river"), id("twenty_four_plum_sea")), Optional.empty());

    /**
     * Шаги — тоже стили форм (автор 03.10: «техника шагов — то же, что 7 цветков: как ты
     * шагаешь»), docs/design/techniques/footwork-styles.md. Первая форма — уклонение: его же
     * вызывает мгновенное двойное нажатие A/D/S. Основы ЛКМ у шагов нет — стойка меча остаётся.
     */
    public static final Style DARK_FRAGRANCE = new Style(id("dark_fragrance"), List.of(
            id("dark_fragrance_step"), id("dark_fragrance_trail"), id("dark_fragrance_behind"), id("dark_fragrance_rising"),
            id("dark_fragrance_floating"), id("dark_fragrance_rain")), Optional.empty());

    public static final Style SEVEN_STARS = new Style(id("seven_stars"), List.of(
            id("seven_stars_shift"), id("seven_stars_follow"), id("seven_stars_dipper")), Optional.empty());

    public static final Style WIND_GOD = new Style(id("wind_god"), List.of(
            id("wind_god_steps"), id("wind_god_shadow"), id("wind_god_death"), id("wind_god_lightning")), Optional.empty());

    /**
     * Скрытое Оружие Клана Тан (docs/design/techniques/tang-daggers-spec.md): первая не-хуашаньская школа,
     * метательные кинжалы без меча. Формы по очереди — попытки Тан Гунака, гл. 194–196 (3 → 5 → 6-й → 7 →
     * возврат → 11-й): Три Мгновения → Пять Молний → Похищение Жизни → Семь Звёзд → Возврат Лезвий →
     * Тёмный Взрыв → Монеты Семьи Тан (гл. 210, «только главе», 04.10) → Двенадцать (автор 03.10: финальная, обсуждается отдельно).
     */
    public static final Style TANG_DAGGERS = new Style(id("tang_daggers"), List.of(
            id("tang_three_instant"), id("tang_five_thunders"), id("tang_flash_life"), id("tang_seven_stars"),
            id("tang_sleeve_return"), id("tang_dark_burst"), id("tang_family_coins"), id("tang_twelve_daggers")), Optional.empty());

    /** R-key forms; deliberately no LMB basic. Author 24–25.10. */
    public static final Style SIX_HARMONIES = new Style(id("six_harmonies_forms"), List.of(
            id("six_harmonies_strike"), id("six_harmonies_chain"), id("six_harmonies_wave"),
            id("six_harmonies_fear"), id("six_harmonies_counter")), Optional.empty());

    public static final Style BAMBOO_HAND = new Style(id("bamboo_leaf_hand"), List.of(
            id("bamboo_leaf_hand"), id("bamboo_iron_wall"), id("bamboo_sword_fingers"), id("bamboo_bending"), id("bamboo_wind_leaf"), id("bamboo_rustling_grove")), Optional.empty());

    public static boolean starGated(Style s) { return s == SIX_HARMONIES || s == BAMBOO_HAND; }

    public static final List<Style> ALL = List.of(BAMBOO_HAND, SIX_HARMONIES, SEVEN_PLUM, TWENTY_FOUR_PLUM, SEVEN_STARS, DARK_FRAGRANCE, WIND_GOD, TANG_DAGGERS);

    /** Форма бега стиля шагов — её включает автобег (спринт 0,5 с), 03.10. */
    public static Optional<ResourceLocation> footworkRun(Style s) {
        return s == DARK_FRAGRANCE ? Optional.of(id("dark_fragrance_trail"))
                : s == WIND_GOD ? Optional.of(id("wind_god_lightning")) : Optional.empty();
    }

    /** Форма тени — её включает автотень (присед на месте 0,7 с); у Хуашань пока нет. */
    public static Optional<ResourceLocation> footworkShadow(Style s) {
        return s == WIND_GOD ? Optional.of(id("wind_god_shadow")) : Optional.empty();
    }

    /**
     * Стили-книги с формами по очереди (автор 03.10: «все в одной книге, каждую надо по очереди
     * изучать, и каждая сложнее прошлой»): Семь Цветков и 24 Движения. Шаги учатся целиком.
     */
    public static boolean sequential(Style s) {
        return s == SEVEN_PLUM || s == TWENTY_FOUR_PLUM || s == TANG_DAGGERS;
    }

    /**
     * Книга учит искусство целиком одной печатью (шаги: Бог Ветров, Тёмный Аромат): формы не по очереди, как у
     * {@link #sequential}. Совпадает с тем, что делает {@code MasteryService.grant}, когда рекурсивно выдаёт все формы стиля.
     */
    public static boolean learnsWhole(Style s) {
        return !sequential(s) && !starGated(s);
    }

    /** Предыдущая форма в стиле-книге или пусто. */
    public static Optional<ResourceLocation> previous(ResourceLocation technique) {
        Optional<Style> s = of(technique);
        if (s.isEmpty() || !sequential(s.get())) {
            return Optional.empty();
        }
        int i = s.get().forms().indexOf(technique);
        // Первая форма — после основы стиля (автор 03.10: «сначала изучаешь сам стиль»).
        return i > 0 ? Optional.of(s.get().forms().get(i - 1)) : s.get().basic();
    }

    /** Стили шагов: их первая форма — мгновенное уклонение. */
    public static final List<Style> FOOTWORK = List.of(SEVEN_STARS, DARK_FRAGRANCE, WIND_GOD);

    /** Стиль, которому принадлежит форма; техника вне стилей — пусто. */
    public static Optional<Style> of(ResourceLocation technique) {
        for (Style s : ALL) {
            if (s.forms().contains(technique)) {
                return Optional.of(s);
            }
        }
        return Optional.empty();
    }

    /** Стиль, основой ЛКМ которого служит {@code technique}. */
    public static Optional<Style> ofBasic(ResourceLocation technique) {
        for (Style s : ALL) {
            if (s.basic().isPresent() && s.basic().get().equals(technique)) {
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
