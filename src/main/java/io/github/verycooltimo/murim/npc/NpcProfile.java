package io.github.verycooltimo.murim.npc;

import io.github.verycooltimo.murim.cultivation.Realm;
import net.minecraft.resources.ResourceLocation;

/** Guests have their own identity; none belongs to the permanent Mount Hua roster. */
public enum NpcProfile {
    HA_YUN("ha_yun", 68, Realm.PEAK, "guest_ha_yun", false),
    CHO_GAN("cho_gan", 72, Realm.PEAK, "guest_cho_gan", false),
    BAE_DO("bae_do", 20, Realm.NONE, "peddler", false),
    TAE_HYEON("tae_hyeon", 48, Realm.FIRST, "guest_tae_hyeon", false),
    HAN_DOK("han_dok", 54, Realm.SECOND, "guest_han_dok", true),
    KWON_YEON("kwon_yeon", 96, Realm.PEAK, "guest_kwon_yeon", false),
    // Side-quest folk (docs/design/45-quest-format.md): they stand where their quest is and are placed by QuestSites, never roam.
    SUPPLIER("supplier", 20, Realm.NONE, "guest_supplier", false),
    IMPOSTOR("impostor", 30, Realm.NONE, "guest_impostor", false),
    TRADER("trader", 20, Realm.NONE, "guest_trader", false);

    public final String id;
    public final double health;
    public final int rank;
    public final String texture;
    public final boolean hostile;

    /** Placed by QuestSites while their quest runs; the sect never takes them for enemies and they never turn hostile. */
    public boolean questFolk() {
        return this == SUPPLIER || this == IMPOSTOR || this == TRADER;
    }

    NpcProfile(String id, double health, int rank, String texture, boolean hostile) {
        this.id = id;
        this.health = health;
        this.rank = rank;
        this.texture = texture;
        this.hostile = hostile;
    }

    public ResourceLocation dialogue() {
        return ResourceLocation.fromNamespaceAndPath("murim", "guest_" + id);
    }
}
