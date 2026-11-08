package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The moves the double of the Echo repeats (hua-main-1810 §1: "the double repeats the moves of your last fight against
 * a shadow of the enemy"). The server remembers the forms the player used in the last fights, in order; the Echo of an
 * art takes the ones of THAT art. The list lives in the persistent data of the player (the same place as the daily
 * fatigue of the hits), so it needs no new attachment and no save format change.
 */
public final class EchoMoves {

    /** The tag in the player's persistent data. */
    static final String TAG = "murim_last_moves";

    /** How many moves the body remembers. */
    static final int KEEP = 12;

    /** How many moves the double shows in one round. */
    public static final int ROUND = 4;

    private EchoMoves() {
    }

    /** The player used this form (a swing or a cast, hit or miss). Equal moves in a row are one move. */
    public static void record(ServerPlayer player, ResourceLocation form) {
        if (!MasteryService.knows(player, form) || Arts.isMethod(form)) {
            return;
        }
        ListTag list = player.getPersistentData().getList(TAG, Tag.TAG_STRING);
        if (!list.isEmpty() && list.getString(list.size() - 1).equals(form.toString())) {
            return;
        }
        list.add(StringTag.valueOf(form.toString()));
        while (list.size() > KEEP) {
            list.remove(0);
        }
        player.getPersistentData().put(TAG, list);
    }

    /** The recorded moves, oldest first. */
    public static List<ResourceLocation> recorded(ServerPlayer player) {
        List<ResourceLocation> out = new ArrayList<>();
        ListTag list = player.getPersistentData().getList(TAG, Tag.TAG_STRING);
        for (int i = 0; i < list.size(); i++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(i));
            if (id != null) {
                out.add(id);
            }
        }
        return out;
    }

    /**
     * What the double repeats for the art: the last moves of THIS art from the fights (newest {@link #ROUND}, in the
     * order they happened); when the art has not been used yet, its learned forms (the base form first).
     */
    public static List<ResourceLocation> forArt(ServerPlayer player, ResourceLocation art) {
        ResourceLocation target = MasteryState.masteryId(art);
        List<ResourceLocation> moves = new ArrayList<>();
        for (ResourceLocation form : recorded(player)) {
            if (MasteryState.masteryId(form).equals(target) && TechniqueLoader.get(form) != null) {
                moves.add(form);
            }
        }
        if (moves.size() > ROUND) {
            moves = new ArrayList<>(moves.subList(moves.size() - ROUND, moves.size()));
        }
        if (!moves.isEmpty()) {
            return List.copyOf(moves);
        }
        return fallback(player.getData(ModAttachments.MASTERY), target);
    }

    /** The learned forms of an art, the foundation first: the double has something to show before any fight. */
    static List<ResourceLocation> fallback(MasteryState state, ResourceLocation art) {
        List<ResourceLocation> forms = new ArrayList<>();
        for (ResourceLocation form : state.techniques().keySet()) {
            if (MasteryState.masteryId(form).equals(art) && TechniqueLoader.get(form) != null) {
                forms.add(form);
            }
        }
        forms.sort(Comparator.comparing((ResourceLocation f) -> {
            TechniqueDefinition d = TechniqueLoader.get(f);
            return d != null && d.foundation() ? 0 : 1;
        }).thenComparing(ResourceLocation::toString));
        return List.copyOf(forms.size() > ROUND ? forms.subList(0, ROUND) : forms);
    }
}
