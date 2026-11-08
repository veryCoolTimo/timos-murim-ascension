package io.github.verycooltimo.murim.quest.world;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.MobSpawnType;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.FinalizeSpawnEvent;

/** The story places are safe: nothing hostile spawns by itself inside Dry Stream Cave or on the stele ledge. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QuestSafeZones {

    private QuestSafeZones() {
    }

    @SubscribeEvent
    static void onSpawn(FinalizeSpawnEvent event) {
        if (event.getEntity().getType().getCategory() != MobCategory.MONSTER) return;
        MobSpawnType t = event.getSpawnType();
        if (t != MobSpawnType.NATURAL && t != MobSpawnType.CHUNK_GENERATION && t != MobSpawnType.PATROL && t != MobSpawnType.REINFORCEMENT) return;
        if (!(event.getLevel().getLevel() instanceof ServerLevel level) || level.dimension() != net.minecraft.world.level.Level.OVERWORLD) return;
        if (safe(level, event.getEntity().blockPosition())) event.setSpawnCancelled(true);
    }

    /** True inside a story place where hostile mobs do not appear on their own. */
    public static boolean safe(ServerLevel level, net.minecraft.core.BlockPos pos) {
        var site = MountHuaSites.get(level.getServer());
        if (site == null) return false;
        var cave = HuaDryStreamCave.ENABLED ? HuaDryStreamCave.Frame.of(site) : null;
        if (cave != null && cave.box().inflatedBy(4).isInside(pos)) return true;
        var stele = HuaStele.ENABLED ? HuaStele.Frame.of(site) : null;
        return stele != null && stele.box().inflatedBy(6).isInside(pos);
    }
}
