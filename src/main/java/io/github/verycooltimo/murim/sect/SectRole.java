package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Кто из NPC секты (план §4.1, v1): наставник Ун Гём, глава Хён Чжон, старший ученик (спарринг)
 * и два ученика третьего поколения фоном. Модель у всех пока одна (бандит), различаются
 * текстурой; автор сделает свои.
 *
 * @param texture  текстура {@code textures/entity/<texture>.png}
 * @param dialogue диалог {@code murim_dialogues/<dialogue>.json}
 */
public enum SectRole {
    MENTOR("sect_mentor", "mentor"),
    LEADER("sect_leader", "leader"),
    /** Привратник у нижних ворот тропы: отправляет подниматься самому (автор 04.10). */
    GATEKEEPER("sect_disciple", "gatekeeper"),
    SENIOR("sect_disciple", "senior"),
    DISCIPLE_A("sect_disciple", "disciple"),
    DISCIPLE_B("sect_disciple", "disciple");

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

    public static SectRole of(String id) {
        for (SectRole r : values()) {
            if (r.id().equals(id)) {
                return r;
            }
        }
        return SENIOR;
    }
}
