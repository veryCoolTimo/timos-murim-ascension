package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.MobSpawnEvent;

/**
 * Земля секты (план §5.4: «в зоне секты не спавнятся враждебные мобы»): площадки на полке перед
 * Южным пиком с запасом по краям. Здесь враждебные мобы сами не появляются — ни ночью, ни в темноте
 * построек автора. Спавнеры, яйца и команды не трогаем.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/event/entity/living/MobSpawnEvent.java#SpawnPlacementCheck
 * (единственное место вызова — {@code SpawnPlacements#checkSpawnRules}, через него идут естественный
 * спавн и спавн при генерации чанка).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectTerritory {

    /** Запас вокруг площадок по горизонтали, блоков. */
    public static final double MARGIN = 16.0D;
    /** Ниже площадки — столько блоков (склоны под полкой), выше — столько (крыши, колокольня). */
    public static final double BELOW = 14.0D;
    public static final double ABOVE = 40.0D;

    private SectTerritory() {
    }

    /** Точка мира на земле секты. */
    public static boolean contains(MountHuaSite site, double x, double y, double z) {
        double u = site.localU(x, z);
        double v = site.localV(x, z);
        for (MountHuaPlan.Zone zone : MountHuaPlan.ZONES) {
            if (!SectLayout.SECT_ZONES.contains(zone.id())) {
                continue;
            }
            if (Math.abs(u - zone.u()) <= zone.width() / 2.0D + MARGIN && Math.abs(v - zone.v()) <= zone.depth() / 2.0D + MARGIN) {
                double floor = site.worldY(zone.y());
                if (y >= floor - BELOW && y <= floor + ABOVE) {
                    return true;
                }
            }
        }
        return false;
    }

    @SubscribeEvent
    static void onSpawnCheck(MobSpawnEvent.SpawnPlacementCheck event) {
        if (event.getEntityType().getCategory() != MobCategory.MONSTER) {
            return;
        }
        MobSpawnType type = event.getSpawnType();
        if (type != MobSpawnType.NATURAL && type != MobSpawnType.CHUNK_GENERATION && type != MobSpawnType.PATROL
                && type != MobSpawnType.REINFORCEMENT && type != MobSpawnType.EVENT) {
            return;
        }
        MinecraftServer server = event.getLevel().getServer();
        if (server == null || event.getLevel().getLevel().dimension() != Level.OVERWORLD) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(server);
        if (site != null && contains(site, event.getPos().getX() + 0.5D, event.getPos().getY(), event.getPos().getZ() + 0.5D)) {
            event.setResult(MobSpawnEvent.SpawnPlacementCheck.Result.FAIL);
        }
    }
}
