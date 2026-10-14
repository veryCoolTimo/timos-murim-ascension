package io.github.verycooltimo.murim.npc;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;

/** Server dialogue contract shared by outsiders, including the existing merchant base. */
public interface DialogueActor {
    Mob actor();
    NpcProfile profile();
    boolean available();
    int talkingTo();
    void talkingTo(ServerPlayer player);
    boolean dialogueAction(ServerPlayer player, String action);
}
