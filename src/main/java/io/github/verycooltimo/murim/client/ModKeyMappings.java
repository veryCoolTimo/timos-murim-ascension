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

    /**
     * Медитация: сесть или встать. На корточках — «брать всё», без отсева примесей
     * (docs/design/19 §3: выбор делается один раз, перед тем как сесть).
     */
    public static final KeyMapping MEDITATION = new KeyMapping(
            "key." + MurimMod.MODID + ".meditation",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    /** Кольцо выбора техник: зажал — кольцо, повёл мышь — выбрал, отпустил (автор 01.10). */
    public static final KeyMapping WHEEL = new KeyMapping(
            "key." + MurimMod.MODID + ".wheel",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_V,
            CATEGORY);

    /** Экран раскладки техник по слотам. */
    public static final KeyMapping LOADOUT = new KeyMapping(
            "key." + MurimMod.MODID + ".loadout",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            CATEGORY);

    /** Захват цели как в Devil May Cry (автор 03.10): нажал — захват, ещё раз — снять. */
    public static final KeyMapping LOCK = new KeyMapping(
            "key." + MurimMod.MODID + ".lock",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_Z,
            CATEGORY);

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.register(LOCK);
        event.register(TECHNIQUE);
        event.register(MEDITATION);
        event.register(WHEEL);
        event.register(LOADOUT);
    }

    private ModKeyMappings() {
    }
}
