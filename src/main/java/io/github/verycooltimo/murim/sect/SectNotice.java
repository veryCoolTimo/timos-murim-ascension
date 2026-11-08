package io.github.verycooltimo.murim.sect;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** Compatibility entry points: sect feedback is read in the tablet journal (author 30.10).
 * Both legacy names record history only; wall guidance uses its own action bar and meditation HUD.
 */
public final class SectNotice {
    private SectNotice() { }

    public static void chat(ServerPlayer p, Component line) {
        PlayerJournal.record(p, line);
    }

    public static void bar(ServerPlayer p, Component line) {
        PlayerJournal.record(p, line);
    }
}
