package io.github.verycooltimo.murim.client;

import com.mojang.blaze3d.platform.InputConstants;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.KeyMapping;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;

/**
 * Клавиши мода.
 */
@EventBusSubscriber(modid = MurimMod.MODID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ModKeyMappings {

    public static final String CATEGORY = "key.categories." + MurimMod.MODID;

    /**
     * Применить технику. Контекст {@code IN_GAME} нужен, чтобы клавиша не срабатывала в открытых
     * экранах и не конфликтовала с чужими биндами вне игры.
     */
    public static final KeyMapping TECHNIQUE = new KeyMapping(
            "key." + MurimMod.MODID + ".technique",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_R,
            CATEGORY);

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.register(TECHNIQUE);
    }

    private ModKeyMappings() {
    }
}
