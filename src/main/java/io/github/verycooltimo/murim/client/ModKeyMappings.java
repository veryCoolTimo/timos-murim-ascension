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
     * Начать создание даньтяня.
     *
     * <p>Своя клавиша, а не предмет. Автор прямо забраковал запуск по свитку: церемония —
     * это действие персонажа, а не применение вещи, и привязка к предмету заставляла
     * искать его в инвентаре ради одного раза за игру.
     */
    public static final KeyMapping AWAKENING = new KeyMapping(
            "key." + MurimMod.MODID + ".awakening",
            KeyConflictContext.IN_GAME,
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    /**
     * Три клавиши выбора основания.
     *
     * <p>Клавиши, а не меню: сцена намеренно без интерфейса, полоски и окна превращают
     * её в мини-игру (план MVP 2). Названия и цена оснований показываются в сцене.
     */
    public static final KeyMapping FOUNDATION_BLOOD = foundationKey("blood", GLFW.GLFW_KEY_1);
    public static final KeyMapping FOUNDATION_VOID = foundationKey("void", GLFW.GLFW_KEY_2);
    public static final KeyMapping FOUNDATION_MOUNTAIN = foundationKey("mountain", GLFW.GLFW_KEY_3);

    private static KeyMapping foundationKey(String id, int code) {
        return new KeyMapping("key." + MurimMod.MODID + ".foundation." + id,
                KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM, code, CATEGORY);
    }

    @SubscribeEvent
    static void register(RegisterKeyMappingsEvent event) {
        event.register(TECHNIQUE);
        event.register(AWAKENING);
        event.register(FOUNDATION_BLOOD);
        event.register(FOUNDATION_VOID);
        event.register(FOUNDATION_MOUNTAIN);
    }

    private ModKeyMappings() {
    }
}
