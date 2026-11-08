package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.sounds.SoundSource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;

/**
 * The world goes quiet for a breakthrough (hua-main-1810 section 4, author 18.10: «стихают птицы и ветер»): from the first
 * tick of the omen to the impact the ambient sounds, the mountain's wind and bird calls among them, are stopped and no new one
 * starts; they come back on their own afterwards (the biome's ambient handler asks for them again).
 *
 * <p>Client only: it touches the player's own sound engine, never the world. The scene's own layered sound
 * ({@code BreakthroughWorld} on the server, played at the player) has the {@code PLAYERS} source and is not touched.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BreakthroughQuiet {

    private static boolean stopped;

    private BreakthroughQuiet() {
    }

    @SubscribeEvent
    static void onPlaySound(PlaySoundEvent event) {
        if (!ClientMeditationState.quiet()) {
            return;
        }
        SoundInstance sound = event.getSound();
        if (sound != null && sound.getSource() == SoundSource.AMBIENT) {
            event.setSound(null);
        }
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (ClientMeditationState.quiet()) {
            if (!stopped) {
                // What already plays (a wind loop) is stopped once, at the start of the omen.
                Minecraft.getInstance().getSoundManager().stop(null, SoundSource.AMBIENT);
                stopped = true;
            }
        } else {
            stopped = false;
        }
    }
}
