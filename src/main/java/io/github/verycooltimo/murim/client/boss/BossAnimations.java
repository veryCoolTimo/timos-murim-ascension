package io.github.verycooltimo.murim.client.boss;

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
import java.util.Map;
import java.util.Optional;

/**
 * Клипы хозяина крепости из {@code assets/murim/bedrock/fortress_master.animation.json}.
 * Перечитываются по F3+T, как у бандитов (client/bandit/BanditAnimations): автор правит позы в
 * Blockbench и видит их без перезапуска.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BossAnimations {

    private static Map<String, AnimationDefinition> clips = Map.of();

    public static Map<String, AnimationDefinition> get() {
        return clips;
    }

    @SubscribeEvent
    static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) BossAnimations::reload);
    }

    private static void reload(ResourceManager resources) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "bedrock/fortress_master.animation.json");
        Optional<Resource> res = resources.getResource(id);
        if (res.isEmpty()) {
            MurimMod.LOGGER.warn("Нет анимаций босса {}", id);
            return;
        }
        try (Reader reader = res.get().openAsReader()) {
            clips = BedrockAnim.parse(reader);
            MurimMod.LOGGER.info("Анимации хозяина крепости: {} клипов", clips.size());
        } catch (Exception e) {
            MurimMod.LOGGER.error("Не прочитать анимации {}", id, e);
        }
    }

    private BossAnimations() {
    }
}
