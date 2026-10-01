package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/**
 * Иконки техник: {@code assets/<ns>/textures/gui/technique/<path>.png}, 64×64 (нарезка из листа gpt-image-2,
 * docs/design/reference/ui/). Нет файла —
 * общая заглушка: новая техника из датапака не должна ломать интерфейс.
 */
public final class TechniqueIcons {

    private static final ResourceLocation UNKNOWN =
            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "textures/gui/technique/unknown.png");

    private static final Map<ResourceLocation, ResourceLocation> CACHE = new HashMap<>();

    public static ResourceLocation of(ResourceLocation technique) {
        return CACHE.computeIfAbsent(technique, id -> {
            ResourceLocation file = ResourceLocation.fromNamespaceAndPath(id.getNamespace(),
                    "textures/gui/technique/" + id.getPath() + ".png");
            return Minecraft.getInstance().getResourceManager().getResource(file).isPresent() ? file : UNKNOWN;
        });
    }

    public static void reset() {
        CACHE.clear();
    }

    private TechniqueIcons() {
    }
}
