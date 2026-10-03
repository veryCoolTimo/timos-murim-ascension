package io.github.verycooltimo.murim.client.bandit;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.bedrock.BedrockAnim;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.io.Reader;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Клипы врагов из {@code assets/murim/bedrock/<имя>.animation.json}. Перечитываются при
 * перезагрузке ресурсов (F3+T): автор правит анимацию в Blockbench, экспортирует Bedrock-файл
 * поверх этого и видит результат без перезапуска игры. Модель (geo) читается при старте.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BanditAnimations {

    /** Имена файлов анимаций врагов. */
    private static final String[] NAMES = {"bandit", "bandit_archer"};

    private static final Map<String, Map<String, AnimationDefinition>> CLIPS = new HashMap<>();

    public static Map<String, AnimationDefinition> get(String name) {
        return CLIPS.getOrDefault(name, Map.of());
    }

    @SubscribeEvent
    static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) BanditAnimations::reload);
    }

    private static void reload(ResourceManager resources) {
        for (String name : NAMES) {
            ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "bedrock/" + name + ".animation.json");
            Optional<Resource> res = resources.getResource(id);
            if (res.isEmpty()) {
                MurimMod.LOGGER.warn("Нет анимаций врага {}", id);
                continue;
            }
            try (Reader reader = res.get().openAsReader()) {
                Map<String, AnimationDefinition> clips = BedrockAnim.parse(reader);
                CLIPS.put(name, clips);
                MurimMod.LOGGER.info("Анимации {}: {} клипов {}", name, clips.size(), clips.keySet());
            } catch (Exception e) {
                MurimMod.LOGGER.error("Не прочитать анимации {}", id, e);
            }
        }
    }

    private BanditAnimations() {
    }
}
