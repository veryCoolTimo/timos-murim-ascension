package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;

import java.util.Locale;

/**
 * Кто из NPC секты по функции (план §4.1): наставник Гён Пхиль, глава Тэ Хви, старейшины, старший
 * ученик (спарринг-урок), ученики второго и третьего поколения, привратник. Имя и облик конкретного
 * человека — в {@link SectRoster}; роль задаёт диалог по умолчанию и то, что NPC умеет.
 *
 * <p>{@code DISCIPLE_A}/{@code DISCIPLE_B} — роли первой версии (два ученика фоном); старые сохранения
 * их помнят, новые NPC получают {@link #DISCIPLE}. Модель и текстуру по роли выбирает клиентский DiscipleRenderer.
 *
 * @param dialogue диалог {@code murim_dialogues/<dialogue>.json}
 */
public enum SectRole {
    MENTOR("mentor"),
    LEADER("leader"),
    /** Привратник у нижних ворот тропы: отправляет подниматься самому (автор 04.10). */
    GATEKEEPER("gatekeeper"),
    SENIOR("senior"),
    DISCIPLE_A("disciple"),
    DISCIPLE_B("disciple"),
    /** Старейшина (поколение Тэ) или старший первого поколения (Гён): у каждого свой диалог по имени. */
    ELDER("elder"),
    /** Ученик второго поколения (Со): спарринги, медитация, в строю — первый ряд. */
    SECOND("second"),
    /** Ученик третьего поколения (Юль): строй, спарринги, столбы, хозяйство. */
    DISCIPLE("disciple"),
    /**
     * Охрана: ученик второго поколения на посту у входа в зал (С3, часть 2): предупреждает, встаёт на пути,
     * отталкивает; если лезут силой — поединок или за ворота ({@link SectWatch}).
     */
    GUARD("guard"),
    /**
     * Управляющий хозяйством секты: припасы, земли и лавки Хуаиня (владелец имущества по запросу автора —
     * «владелец» понят как управляющий; казной по канону ведает старейшина Тэ Гюн). Мирянин, не боец.
     */
    STEWARD("steward"),
    /** Слуги-миряне при секте: повар, носильщик, травник, метельщик, водонос. Не бойцы, не в строю. */
    COOK("cook"),
    PORTER("porter"),
    GARDENER("gardener"),
    SWEEPER("sweeper"),
    WATER_CARRIER("water_carrier"),
    /**
     * Рядовой ученик первого поколения (Гён, решение автора 06.10): старше второго поколения, занимается отдельно,
     * по очереди помогает наставнику на занятиях и поправляет младших; ест и спит по распорядку секты.
     */
    FIRST("first");

    private final String dialogue;

    SectRole(String dialogue) {
        this.dialogue = dialogue;
    }

    public String id() {
        return name().toLowerCase(Locale.ROOT);
    }

    public ResourceLocation dialogue() {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, dialogue);
    }

    /** Ключ имени: {@code npc.murim.<роль>}. */
    public String nameKey() {
        return "npc.murim." + id();
    }

    /** Принимает вызов игрока на спарринг: старший, ученики обоих поколений и охрана. */
    public boolean spars() {
        return this == SENIOR || this == SECOND || this == DISCIPLE || this == DISCIPLE_A || this == DISCIPLE_B || this == GUARD;
    }

    /** Слуга или управляющий: мирянин при секте, без меча, не встаёт на защиту и не в строю. */
    public boolean lay() {
        return this == STEWARD || this == COOK || this == PORTER || this == GARDENER || this == SWEEPER || this == WATER_CARRIER;
    }

    /** Может перехватить младшего на пути к главе или старейшине и встать на пути в закрытый зал. */
    public boolean intercepts() {
        return this == GUARD || this == SENIOR || this == SECOND || this == FIRST;
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
