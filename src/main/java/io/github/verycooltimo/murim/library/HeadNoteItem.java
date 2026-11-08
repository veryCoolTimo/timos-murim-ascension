package io.github.verycooltimo.murim.library;

import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import java.util.List;

/** Fixed bilingual diary leaf; item identity selects its text, with no mutable or synced reading state.
 * Uses the same Item use/name API and side bridge as JunkBookItem.
 */
public final class HeadNoteItem extends Item {
    public static final String TITLE = "murim.vault.head_note.title";
    public static final List<String> PAGES = List.of("murim.vault.head_note.page_1", "murim.vault.head_note.page_2");

    private final String title;
    private final List<String> pages;
    public HeadNoteItem(Properties properties) { this(properties, TITLE, PAGES); }
    public HeadNoteItem(Properties properties, String title, List<String> pages) {
        super(properties);
        this.title = title;
        this.pages = List.copyOf(pages);
    }

    @Override
    public Component getName(ItemStack stack) {
        return Component.translatable(title);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
            TextBooks.open(serverPlayer, title, pages.toArray(String[]::new));
        }
        return InteractionResultHolder.consume(stack);
    }
}
