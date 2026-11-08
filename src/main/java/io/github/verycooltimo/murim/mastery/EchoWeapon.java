package io.github.verycooltimo.murim.mastery;

import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * What the double of the Echo holds in its hands (author 22.10: it struck an empty hand with a sword art). Decided from
 * the forms it repeats: sword forms give a sword, hidden-weapon (throw) forms give daggers, palm and fist forms leave
 * the hand empty. The majority of the moves decides; a tie goes to the weapon.
 */
public final class EchoWeapon {

    public enum Kind { EMPTY, SWORD, DAGGERS }

    private EchoWeapon() {
    }

    /** The weapon of one form. */
    public static Kind of(ResourceLocation form) {
        var definition = TechniqueLoader.get(form);
        if (definition == null) {
            return Kind.EMPTY;
        }
        if (ArtSlots.kindOfForm(definition.form()) == ArtSlots.Kind.HIDDEN_WEAPON
                || io.github.verycooltimo.murim.technique.Styles.of(form)
                        .filter(s -> s == io.github.verycooltimo.murim.technique.Styles.TANG_DAGGERS).isPresent()) {
            return Kind.DAGGERS;
        }
        return QiSword.needsSword(definition) ? Kind.SWORD : Kind.EMPTY;
    }

    /** The weapon of a kata: what most of its forms need; sword over daggers over the empty hand on a tie. */
    public static Kind of(List<ResourceLocation> forms) {
        int sword = 0;
        int daggers = 0;
        int empty = 0;
        for (ResourceLocation form : forms) {
            switch (of(form)) {
                case SWORD -> sword++;
                case DAGGERS -> daggers++;
                default -> empty++;
            }
        }
        if (sword >= daggers && sword >= empty && sword > 0) {
            return Kind.SWORD;
        }
        if (daggers >= empty && daggers > 0) {
            return Kind.DAGGERS;
        }
        return Kind.EMPTY;
    }
}
