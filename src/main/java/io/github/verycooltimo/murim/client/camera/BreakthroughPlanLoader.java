package io.github.verycooltimo.murim.client.camera;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.io.Reader;

/**
 * Loads {@code assets/murim/camera/breakthrough.json} on every resource reload (F3+T) into
 * {@link BreakthroughCamera}. A missing or broken file is logged and replaced by the static front shot, so a typo in
 * the data never leaves a scene without a camera.
 * API: reference/neoforge-src/net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class BreakthroughPlanLoader {

    static final ResourceLocation FILE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "camera/breakthrough.json");

    private BreakthroughPlanLoader() {
    }

    @SubscribeEvent
    static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) BreakthroughPlanLoader::reload);
    }

    private static void reload(ResourceManager resources) {
        BreakthroughPlan loaded;
        try (Reader reader = resources.getResourceOrThrow(FILE).openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            loaded = BreakthroughPlan.parse(root);
            MurimMod.LOGGER.info("Breakthrough camera: {} shots {}", loaded.shots().size(), loaded.shots().keySet());
        } catch (Exception e) {
            MurimMod.LOGGER.error("Breakthrough camera data {} is unusable, the static front shot is used: {}", FILE, e.toString());
            loaded = BreakthroughPlan.fallback();
        }
        BreakthroughCamera.setPlan(loaded);
    }
}
