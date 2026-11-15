package io.github.verycooltimo.murim.item;

import java.util.function.Predicate;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

/**
 * One way to look through what a player carries: the main inventory and the off hand (armour slots are not carried goods).
 * Dialogues, quests, contracts and clues all count and take items through here, so a thing held in the left hand
 * counts the same everywhere.
 */
public final class PlayerItems {
    private PlayerItems() {
    }

    /** Total count of stacks matching, main inventory and off hand. */
    public static int count(Player p, Predicate<ItemStack> match) {
        int n = 0;
        for (ItemStack s : p.getInventory().items) if (!s.isEmpty() && match.test(s)) n += s.getCount();
        for (ItemStack s : p.getInventory().offhand) if (!s.isEmpty() && match.test(s)) n += s.getCount();
        return n;
    }

    public static boolean has(Player p, Predicate<ItemStack> match) {
        return count(p, match) > 0;
    }

    /** Takes up to {@code amount} matching items, main inventory first; returns how many were taken. */
    public static int take(Player p, Predicate<ItemStack> match, int amount) {
        int left = amount;
        for (var list : java.util.List.of(p.getInventory().items, p.getInventory().offhand)) {
            for (ItemStack s : list) {
                if (left <= 0) return amount;
                if (s.isEmpty() || !match.test(s)) continue;
                int n = Math.min(left, s.getCount());
                s.shrink(n);
                left -= n;
            }
        }
        return amount - left;
    }
}
