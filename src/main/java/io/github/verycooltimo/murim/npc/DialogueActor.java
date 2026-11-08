package io.github.verycooltimo.murim.npc;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/** Server dialogue contract shared by outsiders, including the existing merchant base. */
public interface DialogueActor {
    Mob actor();
    io.github.verycooltimo.murim.sect.SectSpeechGuard speechGuard();
    NpcProfile profile();
    boolean available();
    int talkingTo();
    void talkingTo(ServerPlayer player);
    boolean dialogueAction(ServerPlayer player, String action);
}
