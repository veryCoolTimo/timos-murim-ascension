package io.github.verycooltimo.murim.library;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import io.github.verycooltimo.murim.network.ChannelSafe;
import java.util.List;

/** One server-authoritative reader for notes, letters, scroll text and journal entries. */
public final class TextBooks {
    private TextBooks() { }

    public static void open(ServerPlayer player, Component title, List<Component> paragraphs) {
        // Reuse the negotiated-channel guard used by other notifications (ChannelSafe#send).
        ChannelSafe.send(player, new LibraryNetwork.OpenText(title, paragraphs));
    }

    public static void open(ServerPlayer player, String titleKey, String... textKeys) {
        open(player, Component.translatable(titleKey),
                java.util.Arrays.stream(textKeys).map(k -> (Component) Component.translatable(k)).toList());
    }
}
