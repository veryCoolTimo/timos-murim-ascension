package io.github.verycooltimo.murim.library;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * The only door from common library code into {@code client/}: the client class is referenced inside the method
 * body, behind the side check, so a dedicated server never loads it (rules/03, same trick as ClientPayloadBridge).
 */
public final class LibraryClientBridge {

    public static void openJunk(JunkBook book, boolean mainHand) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        io.github.verycooltimo.murim.client.library.JunkBookScreen.open(book, mainHand);
    }

    public static void openText(LibraryNetwork.OpenText text) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            io.github.verycooltimo.murim.client.library.JunkBookScreen.openText(text.title(), text.paragraphs());
        }
    }

    public static void openMethodScroll(LibraryNetwork.OpenMethodScroll scroll) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            io.github.verycooltimo.murim.client.library.MethodScrollScreen.open(scroll);
        }
    }

    public static void methodState(LibraryNetwork.MethodState state) {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            io.github.verycooltimo.murim.client.library.MethodScrollScreen.updateState(state);
        }
    }

    private LibraryClientBridge() {
    }
}
