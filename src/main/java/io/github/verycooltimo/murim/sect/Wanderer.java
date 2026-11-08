package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The wandering disciple of Mount Hua (docs/adr/0020): a second-generation disciple on the road who tells a player who is not
 * in the sect yet which way the mountain lies, with his arm, a few words, and a vanilla explorer map.
 *
 * <p>One per player at a time. The saved entry holds his UUID, so a chunk that is not loaded never means "gone" and he is never
 * made twice; an entity that is not in any entry (a crash between spawn and save) is dropped when it joins the level.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Wanderer {
    /** Mist shows the mountain inside this many blocks; the wanderer is for players beyond it. */
    public static final double FAR_FROM_MOUNTAIN = 1200.0D;
    /** How long he stays, ticks (15 minutes). */
    public static final long STAY = 18000L;
    /** Gap before the next visit after he left: one to two days. */
    static final long GAP_MIN = 24000L;
    static final long GAP_SPAN = 24000L;
    /** A player further than this from him has left him behind. */
    static final double LEFT_BEHIND = 160.0D;
    /**
     * Recruiters stand near the foot of the mountain, not along a long road (author 22.10: «не надо сильно навязывать»): the
     * n-th one comes when the player is within RECRUIT_AT[n] blocks of the mountain's centre (the foot of the trail is ~420 out).
     */
    static final double[] RECRUIT_AT = {900.0D, 700.0D, 540.0D};
    /** A recruiter stands at least this far from the centre (the sect lies inside it). */
    static final double RECRUIT_KEEP_OUT = 380.0D;
    static final int MAX_EXTRA = RECRUIT_AT.length;
    /** Recruiters are made only inside the mist, but not at the very foot. */
    static final double MIN_RECRUIT_DIST = 430.0D;
    static final int MIN_RING = 22;
    static final int MAX_RING = 46;
    /** First meeting: wait this many ticks after joining so the chunks around the player are ticking. */
    static final int FIRST_DELAY = 60;
    /**
     * The very first wanderer comes only after 5 to 7 minutes of play in this world (author 24.10): not at the spawn point in
     * the first seconds, and quietly, since more sects will invite the player later. Per player, fixed by the UUID.
     */
    static final int FIRST_MEET_MIN = 6000;
    static final int FIRST_MEET_SPAN = 2400;

    /** GameTests only: a mountain that is not the world's. */
    static volatile MountHuaSite siteOverride;
    /** GameTests only: a mock player has no chunk tickets, so the ground round him is not entity-ticking. */
    static volatile boolean skipTickingCheck;

    private Wanderer() {
    }

    public static final class Entry {
        public UUID entity;
        public long expiresAt;
        public long nextAt;
        public int visits;
        /** Distance to the mountain at the last visit (0 = none yet); recruiters wait only for a first visit. */
        public double lastDist;
        /** Recruiters met after the first wanderer. */
        public int extra;
        /** Server-only delivery receipt; the map itself uses vanilla inventory synchronization. */
        public boolean mapGiven;
    }

    public static final class Data extends SavedData {
        public static final String NAME = "murim_wanderers";
        public static final Factory<Data> FACTORY = new Factory<>(Data::new, Data::load);
        public final Map<UUID, Entry> byPlayer = new HashMap<>();

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            for (Tag t : tag.getList("entries", Tag.TAG_COMPOUND)) {
                CompoundTag c = (CompoundTag) t;
                Entry e = new Entry();
                e.entity = c.hasUUID("entity") ? c.getUUID("entity") : null;
                e.expiresAt = c.getLong("expires_at");
                e.nextAt = c.getLong("next_at");
                e.visits = c.getInt("visits");
                e.lastDist = c.getDouble("last_dist");
                e.extra = c.getInt("extra");
                e.mapGiven = c.getBoolean("map_given");
                d.byPlayer.put(c.getUUID("player"), e);
            }
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            ListTag list = new ListTag();
            byPlayer.forEach((p, e) -> {
                CompoundTag c = new CompoundTag();
                c.putUUID("player", p);
                if (e.entity != null) {
                    c.putUUID("entity", e.entity);
                }
                c.putLong("expires_at", e.expiresAt);
                c.putLong("next_at", e.nextAt);
                c.putInt("visits", e.visits);
                c.putDouble("last_dist", e.lastDist);
                c.putInt("extra", e.extra);
                c.putBoolean("map_given", e.mapGiven);
                list.add(c);
            });
            tag.put("entries", list);
            return tag;
        }

        boolean owns(UUID entity) {
            return byPlayer.values().stream().anyMatch(e -> entity.equals(e.entity));
        }
    }

    public static Data data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(Data.FACTORY, Data.NAME);
    }

    // ------------------------------------------------------------------ pointing

    /** Yaw (Minecraft degrees) from a spot to the mountain's centre: where the massif stands, not a door on it. */
    public static float yawToMountain(double x, double z, MountHuaSite site) {
        return (float) (Mth.atan2(site.centerZ() + 0.5D - z, site.centerX() + 0.5D - x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    /** Dialogue action {@code wanderer_point}: turn toward the mountain and point. The direction is taken from where he stands now. */
    public static void point(ServerPlayer player, SectDisciple npc) {
        MountHuaSite site = siteOverride != null ? siteOverride : MountHuaSites.get(player.getServer());
        if (site == null || !npc.wanderer() || SectService.state(player).member()) {
            return;
        }
        noticed(player, npc);
        point(npc, site);
        giveMap(player, site);
    }

    /** Shared policy for every road invitation and replacement map; evaluated again at delivery. */
    public static boolean needsDirections(ServerPlayer player) {
        if (SectService.state(player).member()) return false;
        MountHuaSite site = siteOverride != null ? siteOverride : MountHuaSites.get(player.getServer());
        if (site == null) return true;
        // API: minecraft-src Entity#getX/getZ/level, Level#dimension (1.21.1).
        if (player.level().dimension() != Level.OVERWORLD) return true;
        if (site.near((int) Math.floor(player.getX()), (int) Math.floor(player.getZ()), 0)) return false;
        int[] gate = SectService.zone(site, "sect_gate");
        if (gate == null) return true;
        double dx = player.getX() - gate[0], dz = player.getZ() - gate[2];
        return dx * dx + dz * dz > 48.0D * 48.0D;
    }

    public static boolean giveMap(ServerPlayer player) {
        MountHuaSite site = siteOverride != null ? siteOverride : MountHuaSites.get(player.getServer());
        return site != null && needsDirections(player) && giveMap(player, site);
    }

    static boolean giveMap(ServerPlayer player, MountHuaSite site) {
        // Explicit wanderer questions remain available nearby; unsolicited offers use needsDirections.
        if (SectService.state(player).member()) return false;
        ServerLevel overworld = player.getServer().overworld();
        Data data = data(overworld);
        Entry entry = data.byPlayer.computeIfAbsent(player.getUUID(), id -> new Entry());
        long now = overworld.getDayTime();
        if (!mapDue(player, now)) return false;
        int[] gate = SectService.zone(site, "sect_gate");
        if (gate == null) {
            return false;
        }
        ItemStack map = createMap(player, site);
        if (map.isEmpty()) return false;
        if (!player.getInventory().add(map)) {
            player.drop(map, false);
        }
        entry.mapGiven = true;
        player.getPersistentData().putLong("murim_gate_map_at", now);
        data.setDirty();
        return true;
    }

    /** Shared explorer-map factory for gifts and per-customer merchant offers. No gift receipt or cooldown. */
    public static ItemStack createMap(ServerPlayer player, MountHuaSite site) {
        int[] gate = SectService.zone(site, "sect_gate");
        if (gate == null) return ItemStack.EMPTY;
        ServerLevel overworld = player.getServer().overworld();
        // API: reference/minecraft-src/net/minecraft/world/item/MapItem.java#create/renderBiomePreviewMap.
        // Only player markers clamp to the edge; an off-map TARGET_X would disappear.
        ItemStack map = MapItem.create(overworld, gate[0], gate[2], (byte) 2, true, true);
        // Vanilla createFresh snaps to a grid whose outside four-block strip cannot display TARGET_X.
        // Explorer maps may have arbitrary centres: preserve scale 2 and centre on the actual gate,
        // instead of moving the target marker away from the place the player is looking for.
        // API: Mojang 1.21.1 MapItemSavedData#save/load, ServerLevel#setMapData.
        MapItemSavedData savedMap = MapItem.getSavedData(map, overworld);
        if (savedMap == null) return ItemStack.EMPTY;
        CompoundTag centred = savedMap.save(new CompoundTag(), overworld.registryAccess());
        centred.putInt("xCenter", gate[0]);
        centred.putInt("zCenter", gate[2]);
        overworld.setMapData(map.get(DataComponents.MAP_ID), MapItemSavedData.load(centred, overworld.registryAccess()));
        MapItem.renderBiomePreviewMap(overworld, map);
        MapItemSavedData.addTargetDecoration(map, new BlockPos(gate[0], gate[1], gate[2]), "+", MapDecorationTypes.TARGET_X);
        map.set(DataComponents.CUSTOM_NAME, Component.translatable("murim.wanderer.map"));
        // API: reference/minecraft-src/net/minecraft/world/item/component/ItemLore.java#ItemLore(List<Component>).
        map.set(DataComponents.LORE, new net.minecraft.world.item.component.ItemLore(java.util.List.of(
                Component.translatable("murim.hua.map.distance", distanceBlocks(player.getX(), player.getZ(), gate[0], gate[2]),
                        Component.translatable("murim.hua.direction." + direction(player.getX(), player.getZ(), gate[0], gate[2]))))));
        return map;
    }

    public static ItemStack createMap(ServerPlayer player) {
        MountHuaSite site = siteOverride != null ? siteOverride : MountHuaSites.get(player.getServer());
        return site == null ? ItemStack.EMPTY : createMap(player, site);
    }

    static long distanceBlocks(double x, double z, double tx, double tz) {
        return Math.round(Math.hypot(tx - x, tz - z) / 100) * 100;
    }

    static String direction(double x, double z, double tx, double tz) {
        String[] directions = {"east", "south_east", "south", "south_west", "west", "north_west", "north", "north_east"};
        return directions[Math.floorMod((int) Math.round(Math.atan2(tz - z, tx - x) / (Math.PI / 4)), 8)];
    }

    /** Server-only receipt on the existing entity persistent tag; no new saved-data schema. */
    public static void noticed(ServerPlayer player, SectDisciple npc) {
        String receipt = "murim_noticed_" + player.getUUID();
        if (!npc.wanderer() || npc.getPersistentData().getBoolean(receipt)) return;
        npc.getPersistentData().putBoolean(receipt, true);
        approached(player);
        Data data = data(player.serverLevel());
        Entry entry = data.byPlayer.get(player.getUUID());
        if (entry != null && npc.getUUID().equals(entry.entity)) {
            entry.visits++;
            data.setDirty();
        }
    }

    static void observe(ServerPlayer player, SectDisciple npc, long now) {
        // API: Entity#getEyePosition/getLookAngle/distanceToSqr; LivingEntity#hasLineOfSight;
        // Vec3#subtract/normalize/dot. A continuous one-second look, sampled every 10 ticks.
        String key = "murim_look_" + player.getUUID();
        var tag = npc.getPersistentData();
        double distance = npc.distanceToSqr(player);
        if (distance <= 36) {
            noticed(player, npc);
        } else if (distance <= 256 && player.hasLineOfSight(npc)
                && player.getLookAngle().dot(npc.getEyePosition().subtract(player.getEyePosition()).normalize()) >= 0.97) {
            long last = tag.getLong(key + "_last");
            if (!tag.contains(key) || now < last || now - last > 10) tag.putLong(key, now);
            else if (now - tag.getLong(key) >= 20) noticed(player, npc);
            tag.putLong(key + "_last", now);
        } else {
            tag.remove(key);
        }
    }

    static boolean mapDue(ServerPlayer p, long now) {
        var tag = p.getPersistentData();
        return !tag.contains("murim_gate_map_at") || now < tag.getLong("murim_gate_map_at")
                || Math.floorDiv(now, 24000L) > Math.floorDiv(tag.getLong("murim_gate_map_at"), 24000L);
    }

    public static boolean mayApproach(ServerPlayer p) {
        var tag = p.getPersistentData();
        return needsDirections(p) && (tag.getBoolean("murim_wander_requested")
                || tag.getInt("murim_wander_declines") < 2 && tag.getInt("murim_wander_approaches") < 3);
    }

    public static void request(ServerPlayer p) {
        p.getPersistentData().putBoolean("murim_wander_requested", true);
        Entry e = data(p.serverLevel()).byPlayer.get(p.getUUID());
        if (e != null) {
            e.nextAt = 0;
            data(p.serverLevel()).setDirty();
        }
    }

    public static void approached(ServerPlayer p) {
        var tag = p.getPersistentData();
        tag.putInt("murim_wander_approaches", tag.getInt("murim_wander_approaches") + 1);
        tag.remove("murim_wander_requested");
    }

    public static void decline(ServerPlayer p) {
        var tag = p.getPersistentData();
        tag.putInt("murim_wander_declines", Math.min(2, tag.getInt("murim_wander_declines") + 1));
        tag.remove("murim_wander_requested");
    }

    public static void point(SectDisciple npc, MountHuaSite site) {
        npc.pointAlong(yawToMountain(npc.getX(), npc.getZ(), site));
    }

    // ------------------------------------------------------------------ appearing and leaving

    /** Never a sect member, never in the overworld's sect, never where the mist already shows the mountain. */
    static boolean eligible(ServerPlayer p, MountHuaSite site) {
        if (p.isSpectator() || !p.isAlive() || p.level().dimension() != Level.OVERWORLD || SectService.state(p).member()) {
            return false;
        }
        double dx = site.centerX() + 0.5D - p.getX();
        double dz = site.centerZ() + 0.5D - p.getZ();
        return dx * dx + dz * dz > FAR_FROM_MOUNTAIN * FAR_FROM_MOUNTAIN;
    }

    /**
     * One look at one player (every half second from the level tick, and from GameTests): drop a finished visit, or start a new
     * one when it is due.
     *
     * @return the wanderer if this call put him in the world
     */
    public static SectDisciple step(ServerLevel level, ServerPlayer p, MountHuaSite site) {
        return step(level, p, site, level.random);
    }

    /** The same spawn path with an explicit random stream for reproducible fixtures. */
    static SectDisciple step(ServerLevel level, ServerPlayer p, MountHuaSite site, net.minecraft.util.RandomSource random) {
        Data data = data(level);
        long now = level.getGameTime();
        Entry e = data.byPlayer.get(p.getUUID());
        if (e != null && e.entity != null) {
            Entity who = level.getEntity(e.entity);
            if (who instanceof SectDisciple d && d.isAlive()) {
                observe(p, d, now);
                boolean left = d.distanceTo(p) > LEFT_BEHIND;
                boolean over = now >= e.expiresAt && d.talkingTo() < 0;
                boolean inSect = d.wandererIndex() > 0 ? farFromCentre(d.blockPosition().getX(), d.blockPosition().getZ(), site, RECRUIT_KEEP_OUT - 40)
                        : site.near(d.blockPosition().getX(), d.blockPosition().getZ(), 0);
                if (inSect || (left || over) && d.talkingTo() < 0) {
                    finish(data, e, now, d);
                }
            } else if (who != null || now >= e.expiresAt) {
                // Dead or gone, or an unloaded one past its stay (it is dropped when it loads: see join()).
                finish(data, e, now, null);
            }
            return null;
        }
        // The cap includes recruiters; manual conversation remains available after it.
        if (!mayApproach(p) || e != null && e.visits >= 3 && !p.getPersistentData().getBoolean("murim_wander_requested")) return null;
        double dist = Math.hypot(site.centerX() + 0.5D - p.getX(), site.centerZ() + 0.5D - p.getZ());
        boolean recruiter = e != null && e.lastDist > 0 && e.extra < MAX_EXTRA && dist <= RECRUIT_AT[e.extra] && dist > MIN_RECRUIT_DIST
                && !p.isSpectator() && p.isAlive() && p.level().dimension() == Level.OVERWORLD && !SectService.state(p).member();
        if (!recruiter && !eligible(p, site) || p.tickCount < FIRST_DELAY) {
            return null;
        }
        if (e != null && now < e.nextAt && !recruiter) {
            return null;
        }
        BlockPos at = pick(level, p, site, recruiter, random);
        if (at == null) {
            return null;
        }
        SectDisciple d = create(level, at, site, recruiter ? Math.min(SectDisciple.WANDERERS.size() - 1, e.extra + 1) : 0);
        Entry made = e != null ? e : new Entry();
        // The entry comes first: join() drops a wanderer that no entry knows.
        Entry before = e;
        made.entity = d.getUUID();
        made.expiresAt = now + STAY;
        double beforeDist = made.lastDist;
        int beforeExtra = made.extra;
        made.lastDist = dist;
        if (recruiter) {
            made.extra++;
        } else {
            made.extra = 0;
        }
        data.byPlayer.put(p.getUUID(), made);
        if (!level.noCollision(d) || !level.addFreshEntity(d)) {
            made.entity = null;
            made.lastDist = beforeDist;
            made.extra = beforeExtra;
            if (before == null) {
                data.byPlayer.remove(p.getUUID());
            }
            return null;
        }
        data.setDirty();
        MurimMod.LOGGER.info("Wanderer: visit {} for {} at {}", made.visits, p.getGameProfile().getName(), at.toShortString());
        return d;
    }

    private static void finish(Data data, Entry e, long now, SectDisciple d) {
        if (d != null) {
            d.discard();
        }
        e.entity = null;
        e.nextAt = now + GAP_MIN + (long) (Math.abs(e.visits * 7919L % 997) / 997.0D * GAP_SPAN);
        data.setDirty();
    }

    static SectDisciple create(ServerLevel level, BlockPos feet, MountHuaSite site) {
        return create(level, feet, site, 0);
    }

    static SectDisciple create(ServerLevel level, BlockPos feet, MountHuaSite site, int index) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
        d.makeWanderer(index);
        float yaw = yawToMountain(feet.getX() + 0.5D, feet.getZ() + 0.5D, site);
        d.moveTo(feet.getX() + 0.5D, feet.getY(), feet.getZ() + 0.5D, yaw, 0.0F);
        d.setYBodyRot(yaw);
        d.setYHeadRot(yaw);
        d.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
        d.restrictTo(feet, 16);
        d.setKeepAwake(true);
        return d;
    }

    /**
     * A standing spot 22 to 46 blocks from the player, in loaded and ticking terrain, mostly on the mountain's side so that he is
     * ahead of a player who walks the right way. No chunk loads, no terrain edits.
     */
    static BlockPos pick(ServerLevel level, ServerPlayer p, MountHuaSite site) {
        return pick(level, p, site, false);
    }

    /** @param recruiter he waits in the approach to the foot of the trail (inside the footprint, but 380+ blocks from the centre) */
    static BlockPos pick(ServerLevel level, ServerPlayer p, MountHuaSite site, boolean recruiter) {
        return pick(level, p, site, recruiter, level.random);
    }

    private static BlockPos pick(ServerLevel level, ServerPlayer p, MountHuaSite site, boolean recruiter,
                                 net.minecraft.util.RandomSource rnd) {
        float toward = yawToMountain(p.getX(), p.getZ(), site);
        for (int i = 0; i < 40; i++) {
            float yaw = toward + (i < 24 ? 1.0F : 3.0F) * (rnd.nextFloat() - 0.5F) * 120.0F;
            double r = MIN_RING + rnd.nextInt(MAX_RING - MIN_RING + 1);
            int x = Mth.floor(p.getX() - Mth.sin(yaw * Mth.DEG_TO_RAD) * r);
            int z = Mth.floor(p.getZ() + Mth.cos(yaw * Mth.DEG_TO_RAD) * r);
            if ((recruiter ? farFromCentre(x, z, site, RECRUIT_KEEP_OUT) : site.near(x, z, 800)) || !level.hasChunkAt(new BlockPos(x, 64, z))) {
                continue;
            }
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
            BlockPos feet = new BlockPos(x, y, z);
            if (standable(level, feet)) {
                return feet;
            }
        }
        return null;
    }

    /** True when (x, z) is nearer to the mountain's centre than {@code r}: the sect itself, where nobody is met on the road. */
    static boolean farFromCentre(int x, int z, MountHuaSite site, double r) {
        return Math.hypot(x - site.centerX(), z - site.centerZ()) < r;
    }

    static boolean standable(ServerLevel level, BlockPos feet) {
        return (skipTickingCheck || level.isPositionEntityTicking(feet)) && level.getBlockState(feet).isAir() && level.getBlockState(feet.above()).isAir()
                && level.getFluidState(feet).isEmpty() && level.getFluidState(feet.below()).isEmpty()
                && level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), Direction.UP)
                && !level.getBlockState(feet.below()).is(net.minecraft.tags.BlockTags.LEAVES);
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD
                || level.getGameTime() % 10 != 1 || level.players().isEmpty()) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(level.getServer());
        if (site == null) {
            return;
        }
        Data data = data(level);
        for (ServerPlayer p : level.players()) {
            // First visits wait for play time; later visits use the saved encounter state.
            if (data.byPlayer.containsKey(p.getUUID()) || firstMeetingDue(p)) {
                step(level, p, site);
            }
        }
    }

    /** Ticks of play in this world after which this player's first wanderer may come (5 to 7 minutes). */
    static int firstMeetAt(ServerPlayer p) {
        return FIRST_MEET_MIN + (int) Math.floorMod(p.getUUID().getLeastSignificantBits(), (long) FIRST_MEET_SPAN + 1L);
    }

    /** True once the player has played long enough in this world for the first meeting (the play-time stat is per world). */
    // API: reference/minecraft-src/net/minecraft/stats/Stats.java#PLAY_TIME, StatsCounter#getValue(Stat)
    static boolean firstMeetingDue(ServerPlayer p) {
        return p.getStats().getValue(Stats.CUSTOM.get(Stats.PLAY_TIME)) >= firstMeetAt(p);
    }

    /** A wanderer no entry knows (a crash between spawn and save, a copied entity) is dropped as it joins. */
    @SubscribeEvent
    static void join(EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && !level.isClientSide && event.getEntity() instanceof SectDisciple d
                && d.wanderer() && !data(level).owns(d.getUUID())) {
            event.setCanceled(true);
        }
    }
}
