package io.github.verycooltimo.murim.technique;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Порядок разворотов книги стиля и шаблоны секты — без клиента, чтобы проверять тестом.
 *
 * <p>Автор 04.10: в книге Тан шло «текст, текст, картинка» — разворот без картинки пропускался. Теперь порядок
 * всегда «картинка, текст» на титул стиля и на каждую форму (как у Хуашань). Нет своей картинки у формы —
 * ставится титульный разворот стиля. Шаблон текста и обложка — свои у каждой секты (у Тан не розовые сливы).
 */
public final class BookLayout {

    /** Разворот: картинка или текст; {@code form} — null для страниц всего стиля; {@code texture} — путь в assets/murim. */
    public record Page(boolean picture, ResourceLocation form, String texture) {
    }

    /** Секта книги — по ней шаблон текста и обложка. */
    public static String sect(Styles.Style style) {
        return style == Styles.TANG_DAGGERS ? "tang" : "huashan";
    }

    public static String textTemplate(Styles.Style style) {
        return "textures/gui/book/basic_" + sect(style) + ".png";
    }

    public static String cover(Styles.Style style) {
        return "textures/gui/book/cover_" + sect(style) + ".png";
    }

    public static String spread(String name) {
        return "textures/gui/book/spreads/" + name + ".png";
    }

    /** Титульный разворот стиля: у Семи Цветков — исторический {@code seven_plum.png}. */
    public static String title(Styles.Style style) {
        return style == Styles.SEVEN_PLUM ? "textures/gui/book/seven_plum.png" : spread(style.id().getPath() + "_title");
    }

    /**
     * Книга стиля: титул (картинка) → описание стиля (текст) → для каждой формы картинка → текст.
     * {@code exists} отвечает, есть ли текстура (путь в assets/murim).
     */
    public static List<Page> pages(Styles.Style style, Predicate<String> exists) {
        List<Page> out = new ArrayList<>();
        String title = title(style);
        String text = textTemplate(style);
        out.add(new Page(true, null, title));
        out.add(new Page(false, null, text));
        for (ResourceLocation f : style.forms()) {
            String pic = spread(f.getPath());
            out.add(new Page(true, f, exists.test(pic) ? pic : title));
            out.add(new Page(false, f, text));
        }
        return out;
    }

    /** Одиночная книга техники (не стиль): картинка, если есть, и текст на шаблоне Хуашань. */
    public static List<Page> single(ResourceLocation technique, Predicate<String> exists) {
        List<Page> out = new ArrayList<>();
        String pic = spread(technique.getPath());
        if (exists.test(pic)) {
            out.add(new Page(true, technique, pic));
        }
        out.add(new Page(false, technique, "textures/gui/book/basic_huashan.png"));
        return out;
    }

    private BookLayout() {
    }
}
