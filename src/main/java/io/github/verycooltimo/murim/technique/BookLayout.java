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

    /**
     * Секта книги — по ней шаблон текста и обложка. Хуашань и Тан — свои; книга любой другой школы (Шаги Бога Ветра,
     * демонические искусства) — нейтральный {@code plain}: та же бумага и раскладка без слив Хуашань (автор 18.10:
     * «в книге Шагов Бога Ветра страничка со сливой»).
     */
    public static String sect(Styles.Style style) {
        if (style == Styles.TANG_DAGGERS) {
            return "tang";
        }
        boolean hua = style == Styles.SIX_HARMONIES || style == Styles.SEVEN_PLUM || style == Styles.TWENTY_FOUR_PLUM || style == Styles.DARK_FRAGRANCE || style == Styles.SEVEN_STARS || style == Styles.BAMBOO_HAND;
        return hua ? "huashan" : "plain";
    }

    /**
     * Секта одиночной книги: у техники стиля — секта стиля; иначе по школе искусства (автор 19.10): школа «demon»
     * (Ладонь Разрушения Демонов, Теневой Шаг, демонические искусства) — нейтральный {@code plain}, остальные
     * (Хуашань, уличные) — {@code huashan}, как было.
     */
    public static String sect(ResourceLocation technique) {
        java.util.Optional<Styles.Style> style = Styles.of(technique);
        if (style.isPresent()) {
            return sect(style.get());
        }
        return "demon".equals(io.github.verycooltimo.murim.mastery.Arts.school(technique)) ? "plain" : "huashan";
    }

    public static String textTemplate(ResourceLocation technique) {
        return "textures/gui/book/basic_" + sect(technique) + ".png";
    }

    public static String cover(ResourceLocation technique) {
        return "textures/gui/book/cover_" + sect(technique) + ".png";
    }

    /** Титульный разворот одиночной книги большого искусства; у малых (бандитских, уличных, шуточных) его нет. */
    public static String title(ResourceLocation technique) {
        return spread(technique.getPath() + "_title");
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
     * Книга стиля. Стиль-книга с формами по очереди (Семь Цветков, 24 Движения, Тан): титул (картинка) → описание стиля
     * (текст) → для каждой формы картинка → текст. Шаги учатся целиком, но книга та же (автор 18.10: «первая страница —
     * просто красивые картинки, что это Wind God Steps, потом overall, потом картинка и текст, как в семи сливах»):
     * титульная картинка {@code <стиль>_title} → описание стиля (текст, на нём единственная печать «Изучить») →
     * для каждой формы картинка → текст. Нет титульной картинки — книга открывается описанием.
     * {@code exists} отвечает, есть ли текстура (путь в assets/murim).
     */
    public static List<Page> pages(Styles.Style style, Predicate<String> exists) {
        List<Page> out = new ArrayList<>();
        if (Styles.starGated(style)) {
            // The foundation manual teaches this art; these are reference pages, never form books.
            for (ResourceLocation f : style.forms()) out.add(new Page(false, f, textTemplate(style)));
            return out;
        }
        if (Styles.learnsWhole(style)) {
            String text = textTemplate(style);
            String title = title(style);
            if (exists.test(title)) {
                out.add(new Page(true, null, title));
            }
            out.add(new Page(false, null, text));
            for (ResourceLocation f : style.forms()) {
                String pic = spread(f.getPath());
                if (exists.test(pic)) {
                    out.add(new Page(true, f, pic));
                }
                out.add(new Page(false, f, text));
            }
            return out;
        }
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

    /**
     * Одиночная книга. Танцы — только простой текст. Большое искусство (автор 19.10: «для каждой большой техники
     * титульную») открывается титульным разворотом {@code <техника>_title}, затем картинка техники, если она есть,
     * затем текст на шаблоне секты ({@link #sect(ResourceLocation)}). Малые искусства — как раньше: картинка, если есть, и текст.
     */
    public static List<Page> single(ResourceLocation technique, Predicate<String> exists) {
        List<Page> out = new ArrayList<>();
        if (JunkArts.FUNNY.contains(technique)) {
            return List.of(new Page(false, technique, "textures/gui/book/junk_text.png"));
        }
        // form = technique, не null: на странице формы нет логики «страницы стиля» (подпись, печать).
        String title = title(technique);
        if (exists.test(title)) {
            out.add(new Page(true, technique, title));
        }
        String pic = spread(technique.getPath());
        if (exists.test(pic)) {
            out.add(new Page(true, technique, pic));
        }
        out.add(new Page(false, technique, textTemplate(technique)));
        return out;
    }

    private BookLayout() {
    }
}
