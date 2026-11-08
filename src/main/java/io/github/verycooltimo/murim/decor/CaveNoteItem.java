package io.github.verycooltimo.murim.decor;

import io.github.verycooltimo.murim.library.TextBooks;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/** An original in-character note, read through the existing bilingual letters UI (HeadNoteItem pattern). */
public final class CaveNoteItem extends Item {
    public CaveNoteItem(Properties properties) { super(properties); }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        if (player instanceof ServerPlayer server) {
            TextBooks.open(server, "item.murim.chungmyung_note", "murim.cave.chungmyung.note");
        }
        return InteractionResultHolder.consume(player.getItemInHand(hand));
    }
}
