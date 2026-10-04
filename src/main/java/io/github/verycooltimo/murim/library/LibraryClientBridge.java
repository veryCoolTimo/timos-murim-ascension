package io.github.verycooltimo.murim.library;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.loading.FMLEnvironment;

/**
 * The only door from common library code into {@code client/}: the client class is referenced inside the method
 * body, behind the side check, so a dedicated server never loads it (rules/03, same trick as ClientPayloadBridge).
 */
final class LibraryClientBridge {

    static void openJunk(JunkBook book, boolean mainHand) {
        if (FMLEnvironment.dist != Dist.CLIENT) {
            return;
        }
        io.github.verycooltimo.murim.client.library.JunkBookScreen.open(book, mainHand);
    }

    private LibraryClientBridge() {
    }
}
