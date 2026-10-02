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

    public record Style(ResourceLocation id, List<ResourceLocation> forms) {
        public String nameKey() {
            return "style." + id.getNamespace() + "." + id.getPath();
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    /** Меч Семи Цветков Сливы: Разрез·Частокол → Вихрь → Казнь → Натиск (порядок канона). */
    public static final Style SEVEN_PLUM = new Style(id("seven_plum"), List.of(
            id("seven_plum_blossoms"), id("seven_plum_whirlwind"), id("seven_plum_execution"), id("seven_plum_rush")));

    public static final List<Style> ALL = List.of(SEVEN_PLUM);

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
