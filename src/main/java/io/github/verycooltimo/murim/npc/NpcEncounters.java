package io.github.verycooltimo.murim.npc;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.trade.PeddlerSpawns;
import io.github.verycooltimo.murim.world.camp.BanditCamps;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

/** One saved encounter per named person, at most three active. Unloaded UUIDs reserve their slots.
 * Never infer death from ServerLevel.getEntity returning null: the chunk might simply be unloaded. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class NpcEncounters {
    public static final class Entry {
        public final UUID uuid;
        public boolean completed;
        public boolean defeated;
        public long expiresAt;
        public long campKey = io.github.verycooltimo.murim.entity.Bandit.NO_CAMP;
        public Entry(UUID uuid) { this.uuid = uuid; }
    }

    public static final class Data extends SavedData {
        public static final String NAME = "murim_guest_encounters";
        public static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load);
        public final Map<NpcProfile, Entry> entries = new EnumMap<>(NpcProfile.class);

        public int active() { return (int) entries.values().stream().filter(e -> !e.completed).count(); }

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            for (NpcProfile p : NpcProfile.values()) {
                CompoundTag t = tag.getCompound(p.id);
                if (t.hasUUID("uuid")) {
                    Entry e = new Entry(t.getUUID("uuid"));
                    e.completed = t.getBoolean("completed");
                    e.defeated = t.getBoolean("defeated");
                    e.expiresAt = t.getLong("expires_at");
                    e.campKey = t.contains("camp") ? t.getLong("camp") : io.github.verycooltimo.murim.entity.Bandit.NO_CAMP;
                    d.entries.put(p, e);
                }
            }
            return d;
        }

        @Override public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            entries.forEach((p, e) -> {
                CompoundTag t = new CompoundTag();
                t.putUUID("uuid", e.uuid);
                t.putBoolean("completed", e.completed);
                t.putBoolean("defeated", e.defeated);
                t.putLong("expires_at", e.expiresAt);
                t.putLong("camp", e.campKey);
                tag.put(p.id, t);
            });
            return tag;
        }
    }

    public static Data data(ServerLevel level) { return level.getDataStorage().computeIfAbsent(Data.FACTORY, Data.NAME); }

    /** Called only on explicit departure or death, never on chunk unload. */
    public static void complete(ServerLevel level, NpcProfile profile, UUID uuid) {
        Data d = data(level);
        Entry e = d.entries.get(profile);
        if (e != null && e.uuid.equals(uuid)) { e.completed = true; d.setDirty(); }
    }

    /** Common spawning path, also exercised by GameTests. Returns null if an encounter already exists. */
    public static Mob arrive(ServerLevel level, NpcProfile profile, BlockPos feet, boolean timed) {
        Data d = data(level);
        if (d.entries.containsKey(profile) || d.active() >= 3 || !level.isPositionEntityTicking(feet)) return null;
        Mob mob = profile == NpcProfile.BAE_DO ? ModNpcs.INFORMANT.get().create(level) : ModNpcs.FIGHTERS.get(profile).get().create(level);
        if (mob == null) return null;
        mob.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        mob.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
        mob.setPersistenceRequired();
        mob.restrictTo(feet, 12);
        if (!level.noCollision(mob) || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                || !level.getFluidState(feet).isEmpty()) return null;
        if (mob instanceof GuestFighter f && timed) f.visitUntil(level.getGameTime() + 24000);
        if (mob instanceof Informant i) i.settle(feet, 0);
        if (!level.addFreshEntity(mob)) return null;
        Entry entry = new Entry(mob.getUUID());
        entry.expiresAt = timed ? level.getGameTime() + 24000 : 0;
        d.entries.put(profile, entry);
        d.setDirty();
        return mob;
    }

    /** Search only already ticking terrain; no chunk loads or terrain edits. */
    private static BlockPos surface(ServerLevel level, BlockPos at) {
        // A known terrace height takes precedence over the roof in the surface heightmap.
        if (at.getY() > level.getMinBuildHeight() && level.isPositionEntityTicking(at)
                && level.getBlockState(at).isAir() && level.getBlockState(at.above()).isAir()
                && level.getBlockState(at.below()).isFaceSturdy(level, at.below(), net.minecraft.core.Direction.UP)) return at;
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            BlockPos xz = at.offset(dx, 0, dz);
            if (!level.hasChunkAt(xz)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, xz.getX(), xz.getZ());
            BlockPos feet = new BlockPos(xz.getX(), y, xz.getZ());
            if (level.isPositionEntityTicking(feet) && level.getBlockState(feet).isAir()
                    && level.getBlockState(feet.above()).isAir()
                    && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)
                    && level.getFluidState(feet).isEmpty()) return feet;
        }
        return null;
    }

    private static void tryArrive(ServerLevel level, NpcProfile profile, BlockPos at, boolean timed) {
        if (data(level).entries.containsKey(profile) || data(level).active() >= 3) return;
        boolean near = level.players().stream().anyMatch(p -> !p.isSpectator()
                && p.distanceToSqr(at.getX() + 0.5, p.getY(), at.getZ() + 0.5) < 48 * 48);
        if (!near) return;
        BlockPos feet = surface(level, at);
        if (feet != null) arrive(level, profile, feet, timed);
    }

    @SubscribeEvent static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % 100 != 63 || level.players().isEmpty()) return;
        long day = Math.floorDiv(level.getDayTime(), 24000);
        long hour = Math.floorMod(level.getDayTime(), 24000);
        Data state = data(level);
        for (Entry e : state.entries.values()) {
            // An explicit visit deadline frees an unloaded guest's capacity, while preserving their UUID forever.
            if (!e.completed && e.expiresAt > 0 && level.getGameTime() >= e.expiresAt && level.getEntity(e.uuid) == null) {
                e.completed = true;
                state.setDirty();
            }
        }
        var site = MountHuaSites.get(level.getServer());
        // Existing road/stall at the foot of Mount Hua, or the road at the village edge.
        if (hour >= 4000 && hour < 12000) {
            NpcProfile[] road = {NpcProfile.BAE_DO, NpcProfile.HA_YUN, NpcProfile.CHO_GAN};
            NpcProfile profile = road[(int) Math.floorMod(day, 3)];
            if (site != null) {
                if (profile == NpcProfile.CHO_GAN) {
                    // The visiting sparring partner waits at the training square, where his duel takes place.
                    var layout = io.github.verycooltimo.murim.sect.SectLayout.hua(site);
                    Vec3 at = layout.at("training", 16, -10);
                    tryArrive(level, profile, BlockPos.containing(at), true);
                } else {
                    int[] xz = site.toWorld(-87, -426);
                    tryArrive(level, profile, new BlockPos(xz[0], 0, xz[1]), profile != NpcProfile.BAE_DO);
                }
            }
            for (ServerPlayer player : level.players()) {
                if (player.isSpectator() || site != null && profile == NpcProfile.CHO_GAN) continue;
                var village = PeddlerSpawns.village(level, player.blockPosition());
                if (village != null) {
                    var box = village.getBoundingBox();
                    tryArrive(level, profile, new BlockPos(box.minX() + 3, 0, box.getCenter().getZ()), profile != NpcProfile.BAE_DO);
                }
            }
        }
        // First eligible third-day inspection window, at the mountain gate; does not join SectRoster.
        if (site != null && Math.floorMod(day, 3) == 0 && hour >= 6000 && hour < 10000) {
            var gate = io.github.verycooltimo.murim.world.hua.MountHuaPlan.ZONES.stream()
                    .filter(z -> "sect_gate".equals(z.id())).findFirst().orElseThrow();
            int[] xz = site.toWorld(gate.u(), gate.v() - 7);
            tryArrive(level, NpcProfile.TAE_HYEON, new BlockPos(xz[0], (int) Math.round(site.worldY(gate.y())) + 1, xz[1]), true);
        }
        if (level.getDifficulty() == Difficulty.PEACEFUL) return;
        for (var camp : BanditCamps.data(level).all()) {
            if (!camp.populated) continue;
            Entry lieutenant = data(level).entries.get(NpcProfile.HAN_DOK);
            if (lieutenant == null && !camp.cleared) {
                tryArrive(level, NpcProfile.HAN_DOK, camp.centre.offset(5, 0, 0), false);
                lieutenant = data(level).entries.get(NpcProfile.HAN_DOK);
                if (lieutenant != null) { lieutenant.campKey = camp.key; data(level).setDirty(); }
            }
            boolean contract = level.players().stream().anyMatch(p -> p.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT)
                    .has("guest.ha_yun.contract") || p.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT).has("guest.bae_do.tip"));
            if (lieutenant != null && lieutenant.campKey == camp.key && lieutenant.defeated && contract) {
                tryArrive(level, NpcProfile.KWON_YEON, camp.centre.offset(-5, 0, 0), false);
            }
        }
    }

    @SubscribeEvent static void death(LivingDeathEvent event) {
        if (event.getEntity() instanceof DialogueActor n && n.actor().level() instanceof ServerLevel level) {
            complete(level, n.profile(), n.actor().getUUID());
            Entry e = data(level).entries.get(n.profile());
            if (e != null && e.uuid.equals(n.actor().getUUID())) { e.defeated = true; data(level).setDirty(); }
            // Defeating this mini-boss never sets BossRegistry.BOSS_DEFEATED.
        }
    }

    private NpcEncounters() { }
}
