package io.github.verycooltimo.murim.world.hua;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * The Mount Hua biomes (author 05.10: «Хуашань сделать биомом — это не просто структура, это ещё и биом»):
 * {@code murim:mount_hua} over the massif and {@code murim:mount_hua_foothills} over the foothill belt. Data:
 * {@code data/murim/worldgen/biome/*.json} — misty grey-green colours after the reference palette
 * (docs/design/reference/mount-hua/DESCRIPTIONS.md: sea of mist {@code #c8d2dc}, deciduous belt {@code #4f5e46}),
 * temperate (no snow even at the summit), wind and distant birds, goats and rabbits on the massif.
 *
 * <p><b>How the biome gets into the world.</b> Not through the biome source: replacing or wrapping the overworld's
 * multi-noise source needs a mixin (ADR-05 ladder) and breaks terrain mods, the same reason the mountain itself
 * is a feature (docs/adr/0001-mount-hua-terrain-feature.md). Instead the cleanup pass of the mountain feature
 * (top_layer_modification, after every vanilla feature) paints the chunk's biome storage above y = {@link #PAINT_FROM_Y}
 * column by column: F3, sky/fog/grass colours, ambience, mob spawning and weather all read the stored biome.
 * Features and structures are still planned from the vanilla biomes underneath (the mountain writes its own
 * vegetation anyway). {@code /locate biome} asks the biome source, so {@link HuaLocate} answers for these two.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/level/chunk/ChunkAccess.java#fillBiomesFromNoise/#getNoiseBiome
 * (quart coordinates; the old section palette stays readable until the section's new one is assigned —
 * LevelChunkSection#fillBiomesFromNoise), reference/minecraft-src/net/minecraft/sounds/SoundEvent.java.
 */
public final class HuaBiomes {

    public static final ResourceKey<Biome> MOUNT_HUA = key("mount_hua");
    public static final ResourceKey<Biome> FOOTHILLS = key("mount_hua_foothills");

    /** Below this the vanilla underground biomes (lush caves, dripstone, deep dark) stay. */
    public static final int PAINT_FROM_Y = 48;

    /** Massif strength above which the column is the mountain proper (the scarp, peaks, sect shelf, pillars). */
    static final double MASSIF = 0.35;
    /** Mountain weight above which a column belongs to the foothill belt. */
    static final double BELT = 0.5;

    private static final DeferredRegister<SoundEvent> SOUNDS = DeferredRegister.create(Registries.SOUND_EVENT, MurimMod.MODID);
    /** Wind through pines over the gorges (loop, ElevenLabs; art/sounds-src/hua_wind.txt). */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_WIND = sound("ambient.mount_hua.wind");
    /** Rare distant bird calls echoing between the walls. */
    public static final DeferredHolder<SoundEvent, SoundEvent> AMBIENT_BIRDS = sound("ambient.mount_hua.birds");

    public enum Zone { NONE, FOOTHILLS, MASSIF }

    private HuaBiomes() {
    }

    public static void register(IEventBus modBus) {
        SOUNDS.register(modBus);
    }

    /** Which biome a column at local (u, v) gets. */
    public static Zone classify(MountHuaShape shape, double u, double v) {
        if (!MountHuaShape.inBounds(u, v) || shape.blend(u, v) < BELT) {
            return Zone.NONE;
        }
        return shape.massif(u, v) > MASSIF ? Zone.MASSIF : Zone.FOOTHILLS;
    }

    /** Biome zone of a world column (block centre). */
    public static Zone zoneAt(MountHuaSite site, int x, int z) {
        return classify(site.shape(), site.localU(x + 0.5, z + 0.5), site.localV(x + 0.5, z + 0.5));
    }

    /**
     * Paints the chunk's biomes (one 4×4 column per quart, sampled at its centre) above {@link #PAINT_FROM_Y}.
     * Called once per chunk from the mountain's cleanup pass.
     */
    public static void paint(WorldGenLevel level, ChunkAccess chunk, MountHuaSite site) {
        Registry<Biome> reg = level.registryAccess().registryOrThrow(Registries.BIOME);
        Holder<Biome> hua = reg.getHolder(MOUNT_HUA).orElse(null);
        Holder<Biome> foot = reg.getHolder(FOOTHILLS).orElse(null);
        if (hua == null || foot == null) {
            return;
        }
        int x0 = chunk.getPos().getMinBlockX();
        int z0 = chunk.getPos().getMinBlockZ();
        Zone[] zones = new Zone[16];
        boolean any = false;
        for (int qz = 0; qz < 4; qz++) {
            for (int qx = 0; qx < 4; qx++) {
                Zone zone = zoneAt(site, x0 + qx * 4 + 2, z0 + qz * 4 + 2);
                zones[qz * 4 + qx] = zone;
                any |= zone != Zone.NONE;
            }
        }
        if (!any) {
            return;
        }
        int qx0 = QuartPos.fromBlock(x0);
        int qz0 = QuartPos.fromBlock(z0);
        int fromQ = QuartPos.fromBlock(PAINT_FROM_Y);
        chunk.fillBiomesFromNoise((qx, qy, qz, sampler) -> {
            Zone zone = zones[(qz - qz0) * 4 + (qx - qx0)];
            if (zone == Zone.NONE || qy < fromQ) {
                return chunk.getNoiseBiome(qx, qy, qz);
            }
            return zone == Zone.MASSIF ? hua : foot;
        }, level.getLevel().getChunkSource().randomState().sampler());
        chunk.setUnsaved(true);
    }

    private static ResourceKey<Biome> key(String id) {
        return ResourceKey.create(Registries.BIOME, ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, id));
    }

    private static DeferredHolder<SoundEvent, SoundEvent> sound(String id) {
        return SOUNDS.register(id, () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, id)));
    }
}
