package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Кто из NPC секты по функции (план §4.1): наставник Ун Гём, глава Хён Чжон, старейшины, старший
 * ученик (спарринг-урок), ученики второго и третьего поколения, привратник. Имя и облик конкретного
 * человека — в {@link SectRoster}; роль задаёт диалог по умолчанию и то, что NPC умеет.
 *
 * <p>{@code DISCIPLE_A}/{@code DISCIPLE_B} — роли первой версии (два ученика фоном); старые сохранения
 * их помнят, новые NPC получают {@link #DISCIPLE}.
 *
 * @param texture  текстура {@code textures/entity/<texture>.png} (если у человека нет своего облика)
 * @param dialogue диалог {@code murim_dialogues/<dialogue>.json}
 */
public enum SectRole {
    MENTOR("sect_mentor", "mentor"),
    LEADER("sect_leader", "leader"),
    /** Привратник у нижних ворот тропы: отправляет подниматься самому (автор 04.10). */
    GATEKEEPER("sect_disciple", "gatekeeper"),
    SENIOR("sect_disciple", "senior"),
    DISCIPLE_A("sect_disciple", "disciple"),
    DISCIPLE_B("sect_disciple", "disciple"),
    /** Старейшина (поколение Хён) или старший первого поколения (Ун): у каждого свой диалог по имени. */
    ELDER("sect_leader", "elder"),
    /** Ученик второго поколения (Пэк): спарринги, медитация, в строю — первый ряд. */
    SECOND("sect_disciple", "second"),
    /** Ученик третьего поколения (Чхон): строй, спарринги, столбы, хозяйство. */
    DISCIPLE("sect_disciple", "disciple");

    private final String texture;
    private final String dialogue;

    SectRole(String texture, String dialogue) {
        this.texture = texture;
        this.dialogue = dialogue;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ResourceLocation texture() {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/entity/" + texture + ".png");
    }

    public ResourceLocation dialogue() {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, dialogue);
    }

    /** Ключ имени: {@code npc.murim.<роль>}. */
    public String nameKey() {
        return "npc.murim." + id();
    }

    /** Принимает вызов игрока на спарринг: старший и ученики обоих поколений. */
    public boolean spars() {
        return this == SENIOR || this == SECOND || this == DISCIPLE || this == DISCIPLE_A || this == DISCIPLE_B;
    }

    /** Ученик третьего поколения (в том числе роли первой версии). */
    public boolean third() {
        return this == DISCIPLE || this == DISCIPLE_A || this == DISCIPLE_B || this == GATEKEEPER;
    }

    public static SectRole of(String id) {
        for (SectRole r : values()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return SENIOR;
    }
}
