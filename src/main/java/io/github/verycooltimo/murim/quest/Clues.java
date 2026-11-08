package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomModelData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemEntityPickupEvent;


/**
 * Clue items: notes, scraps, rubbings and the fragments of the lost Twenty-Four Movements. A clue is an id; its title and
 * text are lang keys {@code murim.clue.<id>.title} and {@code .text} (ru_ru and en_us). Holding or reading a clue sets the
 * player's flag {@code clue.<id>}: dialogue options with that flag unlock, and stay unlocked if the item is lost.
 * Clue copies are free to get again (a quest piece is never gone for good); a reward is paid once, elsewhere.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Clues {

    /** Look of the item: an index into the item model overrides (the clue step's {@code model}). */
    public static final int NOTE = 2;

    private Clues() {
    }

    public static java.util.Set<String> ids() {
        return QuestLoader.clues().stream().map(l -> l.step().id()).collect(java.util.stream.Collectors.toSet());
    }

    public static boolean defined(String id) {
        return id != null && QuestLoader.clue(id) != null;
    }

    public static String titleKey(String id) {
        return "murim.clue." + id + ".title";
    }

    public static String textKey(String id) {
        return "murim.clue." + id + ".text";
    }

    static int modelOf(String id) {
        var c = QuestLoader.clue(id);
        return c == null ? NOTE : io.github.verycooltimo.murim.quest.def.QuestDef.modelIndex(c.step().model());
    }

    public static String flag(String id) {
        return "clue." + id;
    }

    public static ItemStack stack(String id) {
        ItemStack s = new ItemStack(ModQuests.CLUE_ITEM.get());
        s.set(ModQuests.CLUE.get(), id);
        s.set(DataComponents.CUSTOM_MODEL_DATA, new CustomModelData(modelOf(id)));
        return s;
    }

    /** The player carries this clue now. */
    public static boolean carries(ServerPlayer p, String id) {
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof ClueItem && id.equals(ClueItem.clueOf(s))) return true;
        }
        return false;
    }

    /** The player has ever held or read this clue (the flag): what dialogues ask. */
    public static boolean known(ServerPlayer p, String id) {
        return Quests.has(p, flag(id));
    }

    /**
     * Gives the clue once into the inventory (a second call gives a copy only if he no longer carries it) and marks it known.
     *
     * @return true if an item was handed over
     */
    public static boolean give(ServerPlayer p, String id) {
        if (!defined(id)) {
            MurimMod.LOGGER.warn("Unknown clue {}", id);
            return false;
        }
        learn(p, id);
        if (carries(p, id)) return false;
        ItemStack s = stack(id);
        if (!p.getInventory().add(s)) p.drop(s, false);
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 1.1F);
        return true;
    }

    /** Marks the clue known without an item (read in the world). */
    public static void learn(ServerPlayer p, String id) {
        Quests.set(p, flag(id));
        PlumRestoration.onClue(p, id);
    }

    /** Reads the clue to the player: title and text in the shared book reader. */
    public static void read(ServerPlayer p, String id) {
        learn(p, id);
        io.github.verycooltimo.murim.library.TextBooks.open(p, titleKey(id), textKey(id));
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8F, 1.0F);
    }

    /** A clue lying in the world (dropped copy) teaches on pickup too. */
    @SubscribeEvent
    static void onPickup(ItemEntityPickupEvent.Post event) {
        if (event.getPlayer() instanceof ServerPlayer p && event.getOriginalStack().getItem() instanceof ClueItem) {
            String id = ClueItem.clueOf(event.getOriginalStack());
            if (defined(id)) learn(p, id);
        }
    }
}
