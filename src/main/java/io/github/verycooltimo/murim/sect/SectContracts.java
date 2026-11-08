package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.SectMissionBoard;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.trade.ModTrade;
import io.github.verycooltimo.murim.trade.Peddler;
import io.github.verycooltimo.murim.trade.PeddlerSpawns;
import io.github.verycooltimo.murim.world.camp.BanditCamp;
import io.github.verycooltimo.murim.world.camp.BanditCampData;
import io.github.verycooltimo.murim.world.camp.BanditCampPiece;
import io.github.verycooltimo.murim.world.camp.BanditCamps;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import net.minecraft.world.level.levelgen.structure.placement.StructurePlacement;
import net.minecraft.world.level.saveddata.SavedData;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * Contract board of the sect (docs/design/39-player-path-v2.md item 3): three contracts every sect day, taken at the
 * board and handed in to the NPC who named them. Repeatable, never punishing, gone at the end of the sect day.
 *
 * <ul>
 *   <li>CAMP: clear a bandit camp near the mountain; the clearing drops a proof token to the group, hand it in.</li>
 *   <li>ESCORT: walk a merchant from the sect gate to the stall at the foot of the mountain.</li>
 *   <li>HERBS: bring herbs to the alchemist.</li>
 * </ul>
 *
 * Up to {@link #MAX_GROUP} players share one run; members near the giver at hand-in all receive the reward.
 * Offers of a day are generated once from (world seed, day), stored, and stay stable while camps change.
 * The board is a screen ({@code SectBoardScreen}, opened by the block) and the tablet's contracts tab; their buttons send
 * {@code SectTabletPayload.Contract}, handled by {@link #act}. The {@code /contract} commands remain for admins and tests.
 * Feedback goes to the tablet journal ({@link SectNotice}), without chat or action-bar messages (author 30.10).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectContracts {
    public enum Kind { CAMP, ESCORT, HERBS }

    /** The board lists this many contracts a day: a camp (or a second herb order), an escort and a herb order ({@link #generate}). */
    public static final int OFFERS_PER_DAY = 3;
    public static final int MAX_GROUP = 3;
    /** A group is at least this many members (the tablet tells «2-3» from these two numbers). */
    public static final int MIN_GROUP = 2;
    /** Members this close to the giver at hand-in share the reward and pool items. */
    public static final double SHARE_RANGE = 48.0D;
    /** Members this close to a cleared camp receive its proof token. */
    public static final double CAMP_RANGE = 160.0D;
    static final double ARRIVE_RANGE = 8.0D;
    static final List<String> DIRECTIONS = List.of("north", "north_east", "east", "south_east", "south", "south_west", "west", "north_west");
    static final int PLACES = 8;
    /** Each named habitat appears once on the board. */
    public static List<String> habitats(Offer o) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        out.add(io.github.verycooltimo.murim.world.herb.HerbCatalog.habitat(o.itemA()));
        if (o.countB() > 0 || !o.itemB().isEmpty()) out.add(io.github.verycooltimo.murim.world.herb.HerbCatalog.habitat(o.itemB()));
        return List.copyOf(out);
    }

    private static final List<ResourceLocation> PAGES = List.of(SectService.SIX, SectJourney.PETAL,
            Styles.SEVEN_PLUM.basic().orElseThrow(), Styles.SEVEN_PLUM.forms().getFirst()); // never the Twenty-Four Movements: the art is lost (author 23.10)
    /** Test hook: players that are not in the server list (fake players). */
    static Function<UUID, ServerPlayer> resolver;

    private SectContracts() { }

    // ------------------------------------------------------------------ data

    public record CampRef(long key, BlockPos centre) { }

    public record Offer(int slot, Kind kind, String giver, long campKey, BlockPos site,
                        String itemA, int countA, String itemB, int countB) {
        CompoundTag save() {
            CompoundTag t = new CompoundTag();
            t.putInt("slot", slot);
            t.putString("kind", kind.name());
            t.putString("giver", giver);
            t.putLong("camp", campKey);
            t.putLong("site", site.asLong());
            t.putString("a", itemA);
            t.putInt("na", countA);
            t.putString("b", itemB);
            t.putInt("nb", countB);
            return t;
        }

        static Offer load(CompoundTag t) {
            Kind kind = io.github.verycooltimo.murim.compat.SavedEnums.read(Kind.class, t.getString("kind")).orElse(null);
            if (kind == null) return null;
            return new Offer(t.getInt("slot"), kind, t.getString("giver"), t.getLong("camp"),
                    BlockPos.of(t.getLong("site")), t.getString("a"), t.getInt("na"), t.getString("b"), t.getInt("nb"));
        }
    }

    public static final class Run {
        public final UUID id;
        public final int slot;
        public final long day;
        public final Kind kind;
        public final List<UUID> members = new ArrayList<>();
        public boolean arrived;
        public boolean turnedIn;
        /** The group already holds the camp's proof token (cleared by itself, or found in a camp cleared by others). */
        public boolean searched;
        /** Accepted offer stays stable when the public board replaces a razed camp. */
        public Offer accepted;
        public UUID escort;
        /** Escort: how many of the merchant's lines on the way are said (the start line, then one at each talking point). Saved. */
        public int leg;
        /** Escort: game time of his last line, and the talking points of the route (made once, not saved). */
        long lastLine = Long.MIN_VALUE / 2;
        List<net.minecraft.world.phys.Vec3> talks;

        Run(UUID id, int slot, long day, Kind kind) {
            this.id = id;
            this.slot = slot;
            this.day = day;
            this.kind = kind;
        }

        public UUID taker() { return members.isEmpty() ? null : members.getFirst(); }
    }

    public static final class Data extends SavedData {
        static final String NAME = "murim_sect_contracts";
        static final SavedData.Factory<Data> FACTORY = new SavedData.Factory<>(Data::new, Data::load);
        public long day = -1;
        public final List<Offer> offers = new ArrayList<>();
        public final Map<UUID, Run> runs = new LinkedHashMap<>();
        /** Players told on their next board visit that a merchant died (no penalty). Saved. */
        public final Set<UUID> lost = new LinkedHashSet<>();
        /** Escort merchants that were not loaded when their run ended: removed when they load. Saved, newest last. */
        public final Set<UUID> doomed = new LinkedHashSet<>();
        /** Open group invitations (invited player to run). This server session only: an invitation is not kept over a restart. */
        final Map<UUID, UUID> invites = new HashMap<>();

        static Data load(CompoundTag tag, HolderLookup.Provider registries) {
            Data d = new Data();
            d.day = tag.contains("day") ? tag.getLong("day") : -1;
            ListTag offers = tag.getList("offers", Tag.TAG_COMPOUND);
            for (int i = 0; i < offers.size(); i++) {
                Offer offer = Offer.load(offers.getCompound(i));
                if (offer != null) d.offers.add(offer);
            }
            ListTag runs = tag.getList("runs", Tag.TAG_COMPOUND);
            for (int i = 0; i < runs.size(); i++) {
                CompoundTag t = runs.getCompound(i);
                Kind kind = io.github.verycooltimo.murim.compat.SavedEnums.read(Kind.class, t.getString("kind")).orElse(null);
                if (kind == null) continue;
                Run r = new Run(t.getUUID("id"), t.getInt("slot"), t.getLong("day"), kind);
                ListTag m = t.getList("members", Tag.TAG_COMPOUND);
                for (int j = 0; j < m.size(); j++) r.members.add(m.getCompound(j).getUUID("p"));
                r.arrived = t.getBoolean("arrived");
                r.turnedIn = t.getBoolean("done");
                r.searched = t.getBoolean("searched");
                r.accepted = t.contains("accepted") ? Offer.load(t.getCompound("accepted")) : runOffer(d, r);
                if (t.contains("accepted") && r.accepted == null) continue;
                if (t.hasUUID("escort")) r.escort = t.getUUID("escort");
                r.leg = t.getInt("leg");
                d.runs.put(r.id, r);
            }
            for (Tag t : tag.getList("lost", Tag.TAG_INT_ARRAY)) d.lost.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
            for (Tag t : tag.getList("doomed", Tag.TAG_INT_ARRAY)) d.doomed.add(net.minecraft.nbt.NbtUtils.loadUUID(t));
            return d;
        }

        @Override
        public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
            tag.putLong("day", day);
            ListTag offers = new ListTag();
            for (Offer o : this.offers) offers.add(o.save());
            tag.put("offers", offers);
            ListTag runs = new ListTag();
            for (Run r : this.runs.values()) {
                CompoundTag t = new CompoundTag();
                t.putUUID("id", r.id);
                t.putInt("slot", r.slot);
                t.putLong("day", r.day);
                t.putString("kind", r.kind.name());
                if (r.accepted != null) t.put("accepted", r.accepted.save());
                ListTag m = new ListTag();
                for (UUID u : r.members) {
                    CompoundTag e = new CompoundTag();
                    e.putUUID("p", u);
                    m.add(e);
                }
                t.put("members", m);
                t.putBoolean("arrived", r.arrived);
                t.putBoolean("done", r.turnedIn);
                t.putBoolean("searched", r.searched);
                if (r.escort != null) t.putUUID("escort", r.escort);
                t.putInt("leg", r.leg);
                runs.add(t);
            }
            tag.put("runs", runs);
            ListTag lost = new ListTag();
            for (UUID u : this.lost) lost.add(net.minecraft.nbt.NbtUtils.createUUID(u));
            tag.put("lost", lost);
            ListTag doomed = new ListTag();
            for (UUID u : this.doomed) doomed.add(net.minecraft.nbt.NbtUtils.createUUID(u));
            tag.put("doomed", doomed);
            return tag;
        }
    }

    public static Data data(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(Data.FACTORY, Data.NAME);
    }

    public static long today(MinecraftServer server) {
        return SectSchedule.day(server.overworld().getDayTime());
    }

    // ------------------------------------------------------------------ generation

    /** An escort route as the board needs it: its id ({@link EscortRoutes}) and where it ends. */
    public record RouteRef(String id, BlockPos end) { }

    /** One route only (the stall at the foot of the mountain): the contract before the three routes, and the tests that want one. */
    public static List<Offer> generate(long seed, long day, List<CampRef> camps, BlockPos escortEnd, BlockPos gate) {
        return generate(seed, day, camps, List.of(new RouteRef(EscortRoutes.FOOT, escortEnd)), gate);
    }

    /**
     * Pure and deterministic: the same (seed, day, camps, routes) always gives the same three contracts. The escort of the day
     * takes the routes in turn, so that all of them come round (the order starts differently in every world).
     */
    public static List<Offer> generate(long seed, long day, List<CampRef> camps, List<RouteRef> routes, BlockPos gate) {
        Random rng = new Random(seed * 31L + day * 0x9E3779B97F4A7C15L);
        // Weighted sampling without replacement: distinct names, common plants dominate.
        // API: Minecraft 1.21.1 Registry#containsKey, ResourceLocation#parse.
        var available = new ArrayList<>(io.github.verycooltimo.murim.world.herb.HerbCatalog.all());
        available.removeIf(e -> !BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(e.item()))
                || !BuiltInRegistries.BLOCK.containsKey(ResourceLocation.parse(e.block())));
        List<String> pool = new ArrayList<>();
        while (!available.isEmpty()) {
            int roll = rng.nextInt(available.stream().mapToInt(e -> e.weight()).sum());
            int index = 0;
            while (roll >= available.get(index).weight()) roll -= available.get(index++).weight();
            pool.add(available.remove(index).item());
        }
        int na = 2 + rng.nextInt(3), nb = 2 + rng.nextInt(3);
        Offer herbs = new Offer(2, Kind.HERBS, "gyeong_cho", 0, BlockPos.ZERO, pool.get(0), na, pool.get(1), nb);
        Offer camp;
        if (camps.isEmpty()) {
            // No camp left to clear nearby: the alchemist also asks for a second, different herb order.
            camp = new Offer(0, Kind.HERBS, "gyeong_cho", 0, BlockPos.ZERO, pool.get(2), 2 + rng.nextInt(3), pool.get(3 % pool.size()), 2 + rng.nextInt(3));
        } else {
            CampRef c = camps.get(rng.nextInt(Math.min(4, camps.size())));
            // A camp is described by compass direction and rounded distance from the gate, plus a place name (never a marker).
            double dx = c.centre().getX() - gate.getX(), dz = c.centre().getZ() - gate.getZ();
            int sector = Math.floorMod((int) Math.round(Math.toDegrees(Math.atan2(dx, -dz)) / 45.0D), 8);
            int dist = (int) (Math.round(Math.sqrt(dx * dx + dz * dz) / 50.0D) * 50L);
            camp = new Offer(0, Kind.CAMP, "gyeong_tak", c.key(), c.centre(), DIRECTIONS.get(sector), dist, "",
                    (int) Math.floorMod(c.key() * 31L + day, (long) PLACES));
        }
        RouteRef route = routes.get((int) Math.floorMod(day + Math.floorMod(seed, 3L), (long) routes.size()));
        Offer escort = new Offer(1, Kind.ESCORT, "tae_gyun", 0, route.end(), route.id(), 0, "", 0);
        List<Offer> day3 = List.of(camp, escort, herbs);
        assert day3.size() == OFFERS_PER_DAY;
        return day3;
    }

    /** Camps near the gate, found by the structure placement maths (no chunk is generated for the search itself). */
    static List<CampRef> findCamps(ServerLevel level, BlockPos origin) {
        Holder<Structure> holder = level.registryAccess().registryOrThrow(Registries.STRUCTURE).getHolder(BanditCamp.KEY).orElse(null);
        if (holder == null) return List.of();
        var state = level.getChunkSource().getGeneratorState();
        BanditCampData known = BanditCamps.data(level);
        List<CampRef> found = new ArrayList<>();
        for (StructurePlacement placement : state.getPlacementsForStructure(holder)) {
            if (!(placement instanceof RandomSpreadStructurePlacement spread)) continue;
            int rx = Math.floorDiv(origin.getX() >> 4, spread.spacing()), rz = Math.floorDiv(origin.getZ() >> 4, spread.spacing());
            for (int dx = -2; dx <= 2; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    ChunkPos c = spread.getPotentialStructureChunk(state.getLevelSeed(), rx + dx, rz + dz);
                    if (!placement.isStructureChunk(state, c.x, c.z)) continue;
                    BanditCampData.Camp camp = known.get(c.toLong());
                    if (camp != null && camp.cleared) continue;
                    BanditCampPiece piece = BanditCamps.piece(level, holder.value(), c.toLong());
                    if (piece != null) found.add(new CampRef(c.toLong(), new BlockPos(piece.centreX(), piece.height(0), piece.centreZ())));
                }
            }
        }
        for (BanditCampData.Camp camp : known.all()) {
            if (!camp.cleared && found.stream().noneMatch(c -> c.key() == camp.key))
                found.add(new CampRef(camp.key, camp.centre));
        }
        if (found.isEmpty()) {
            BanditCampData.Camp fresh = io.github.verycooltimo.murim.world.camp.CampLife.create(level, origin,
                    level.getSeed() ^ level.getGameTime());
            if (fresh != null) found.add(new CampRef(fresh.key, fresh.centre));
        }
        found.sort(Comparator.<CampRef>comparingDouble(c -> c.centre().distSqr(origin)).thenComparingLong(CampRef::key));
        return found.size() > 6 ? new ArrayList<>(found.subList(0, 6)) : found;
    }

    static BlockPos gate(ServerLevel level) {
        MountHuaSite site = MountHuaSites.get(level.getServer());
        int[] zone = site == null ? null : SectService.zone(site, "sect_gate");
        return zone == null ? level.getSharedSpawnPos() : new BlockPos(zone[0], zone[1], zone[2]);
    }

    /** Test hook: replaces the stall position. */
    static BlockPos stallOverride;
    /** Test hook: replaces the talking points of the escort route. */
    static List<net.minecraft.world.phys.Vec3> talkingOverride;
    /** Test hook: the routes of the board (the game test world has a mountain, so the board would rotate through all of them). */
    static List<RouteRef> routesOverride;

    static BlockPos stall(ServerLevel level) {
        if (stallOverride != null) return stallOverride;
        MountHuaSite site = MountHuaSites.get(level.getServer());
        if (site == null) return level.getSharedSpawnPos().offset(60, 0, 0);
        int[] xz = PeddlerSpawns.stallXZ(site);
        return new BlockPos(xz[0], level.getSharedSpawnPos().getY(), xz[1]);
    }

    /** The routes of the escort contract on this mountain; the foot route alone where there is none (test yards, no mountain). */
    static List<RouteRef> routeRefs(ServerLevel level) {
        if (routesOverride != null) {
            return routesOverride;
        }
        BlockPos stall = stall(level);
        MountHuaSite site = stallOverride != null ? null : MountHuaSites.get(level.getServer());
        List<RouteRef> out = new ArrayList<>();
        for (EscortRoutes.Route r : EscortRoutes.all(site, site == null ? null : SectLayout.hua(site).file(), stall)) {
            out.add(new RouteRef(r.id(), r.endCell()));
        }
        return out.isEmpty() ? List.of(new RouteRef(EscortRoutes.FOOT, stall)) : out;
    }

    /** The route of an escort offer; an offer made before the three routes (no id) is the foot route. */
    static String routeId(Offer o) {
        return o.itemA().isEmpty() ? EscortRoutes.FOOT : o.itemA();
    }

    /** New sect day: drop yesterday's runs (escort merchants leave) and generate today's board. */
    public static void refresh(MinecraftServer server) {
        long day = today(server);
        if (data(server).day == day) {
            repairCampOffer(server);
            return;
        }
        ServerLevel level = server.overworld();
        List<CampRef> camps = campsFor(level);
        refresh(server, day, camps);
        if (camps.isEmpty() && level.getDifficulty() != net.minecraft.world.Difficulty.PEACEFUL) {
            data(server).offers.removeIf(o -> o.slot() == 0);
            data(server).setDirty();
        }
    }

    /** Public offers follow live camps; accepted runs keep their old location and proof token. */
    private static void repairCampOffer(MinecraftServer server) {
        if (server.overworld().getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL) return;
        Data d = data(server);
        Offer old = offer(d, 0);
        BanditCampData.Camp camp = old == null ? null : BanditCamps.data(server.overworld()).get(old.campKey());
        if (old != null && old.kind() == Kind.CAMP && (camp == null || !camp.cleared)) return;
        repairCampOffer(server, campsFor(server.overworld()));
    }

    static void repairCampOffer(MinecraftServer server, List<CampRef> candidates) {
        Data d = data(server);
        List<CampRef> live = candidates.stream().filter(c -> {
            var camp = BanditCamps.data(server.overworld()).get(c.key());
            return camp == null || !camp.cleared;
        }).toList();
        if (live.isEmpty()) {
            // With no safe land, defer this slot instead of advertising a ruin or substituting herbs.
            d.offers.removeIf(o -> o.slot() == 0);
            d.setDirty();
            return;
        }
        for (Run run : d.runs.values()) if (run.accepted == null) run.accepted = runOffer(d, run);
        Offer fresh = generate(server.overworld().getSeed(), d.day, live, routeRefs(server.overworld()), gate(server.overworld())).get(0);
        d.offers.removeIf(o -> o.slot() == 0);
        d.offers.add(0, fresh);
        d.setDirty();
    }

    /** The camps the board may offer: none on Peaceful (no bandit is placed there, so a camp could never be cleared). */
    static List<CampRef> campsFor(ServerLevel level) {
        return level.getDifficulty() == net.minecraft.world.Difficulty.PEACEFUL ? List.of() : findCamps(level, gate(level));
    }

    /** Acceptance day and the following two sect days; expiry has no penalty. */
    static boolean valid(Run run, long day) {
        return day >= run.day && day - run.day < 3;
    }

    static void refresh(MinecraftServer server, long day, List<CampRef> camps) {
        Data d = data(server);
        if (d.day == day) return;
        var runs = d.runs.values().iterator();
        while (runs.hasNext()) {
            Run r = runs.next();
            if (r.accepted == null) r.accepted = runOffer(d, r);
            if (!r.turnedIn && valid(r, day)) continue;
            if (!r.turnedIn) for (ServerPlayer member : online(server, r)) msg(member, "expired");
            discardEscort(server, r);
            runs.remove();
        }
        d.invites.clear();
        d.offers.clear();
        d.offers.addAll(generate(server.overworld().getSeed(), day, camps, routeRefs(server.overworld()), gate(server.overworld())));
        d.day = day;
        d.setDirty();
    }

    // ------------------------------------------------------------------ runs

    static ServerPlayer player(MinecraftServer server, UUID id) {
        if (resolver != null) {
            ServerPlayer p = resolver.apply(id);
            if (p != null) return p;
        }
        return server.getPlayerList().getPlayer(id);
    }

    public static Run active(Data d, UUID player) {
        for (Run r : d.runs.values()) if (!r.turnedIn && r.members.contains(player)) return r;
        return null;
    }

    static boolean doneToday(Data d, UUID player, int slot) {
        return d.runs.values().stream().anyMatch(r -> r.turnedIn && r.slot == slot && r.members.contains(player));
    }

    static Offer offer(Data d, int slot) {
        return d.offers.stream().filter(o -> o.slot() == slot).findFirst().orElse(null);
    }

    static Offer runOffer(Data d, Run run) {
        return run.accepted == null ? offer(d, run.slot) : run.accepted;
    }

    static List<ServerPlayer> online(MinecraftServer server, Run run) {
        List<ServerPlayer> list = new ArrayList<>();
        for (UUID u : run.members) {
            ServerPlayer p = player(server, u);
            if (p != null) list.add(p);
        }
        return list;
    }

    private static void msg(ServerPlayer p, String key, Object... args) {
        PlayerJournal.refuse(p, Component.translatable("murim.contract." + key, args).withStyle(ChatFormatting.GRAY));
    }

    /** The board is open from the standing of a disciple (author 18.10): a novice is not yet trusted with the sect's errands. */
    public static final SectStanding MIN_STANDING = SectStanding.DISCIPLE;

    static boolean trusted(ServerPlayer p) {
        return SectService.standing(p).atLeast(MIN_STANDING);
    }

    public static boolean take(ServerPlayer p, int slot) {
        MinecraftServer server = p.getServer();
        if (server == null || !SectService.state(p).member() || SectPositions.disciplined(SectService.state(p))) {
            msg(p, "members_only");
            return false;
        }
        if (!trusted(p)) {
            msg(p, "low_standing");
            return false;
        }
        refresh(server);
        Data d = data(server);
        Offer o = offer(d, slot);
        if (o == null) { msg(p, "no_such"); return false; }
        if (o.kind() == Kind.CAMP && SectRequests.peaceful(p)) { msg(p, "no_such"); return false; }
        if (active(d, p.getUUID()) != null) { msg(p, "already"); return false; }
        if (doneToday(d, p.getUUID(), slot)) { msg(p, "done_today"); return false; }
        Run run = new Run(UUID.randomUUID(), slot, d.day, o.kind());
        run.members.add(p.getUUID());
        if (o.kind() == Kind.ESCORT) {
            Peddler merchant = spawnMerchant(p.serverLevel(), p.blockPosition());
            if (merchant == null) { msg(p, "no_merchant"); return false; }
            run.escort = merchant.getUUID();
        }
        run.accepted = o;
        d.runs.put(run.id, run);
        d.setDirty();
        SectNotice.bar(p, Component.translatable("murim.contract.taken", shortTitle(o)).withStyle(ChatFormatting.GOLD));
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8F, 1.0F);
        d.lost.remove(p.getUUID());
        SectTablet.touch(p);
        return true;
    }

    static Peddler spawnMerchant(ServerLevel level, BlockPos near) {
        BlockPos g = gate(level);
        // The gate chunk may be unloaded (no mountain yet, tests): then the merchant waits beside the player.
        BlockPos at = level.isLoaded(g) ? g : near;
        Peddler merchant = ModTrade.PEDDLER.get().create(level);
        if (merchant == null) return null;
        merchant.moveTo(at.getX() + 2.5D, at.getY(), at.getZ() + 0.5D, 0.0F, 0.0F);
        merchant.finalizeSpawn(level, level.getCurrentDifficultyAt(at), MobSpawnType.EVENT, null);
        merchant.settle(at, 36000);
        merchant.escort();
        level.addFreshEntity(merchant);
        return merchant;
    }

    /** How many unloaded merchants are remembered for removal (oldest forgotten first). */
    static final int MAX_DOOMED = 64;

    /**
     * Sends the escort merchant away. One that is not loaded (the chunk is out of range) cannot be reached now: it is
     * remembered by uuid and removed the moment it loads ({@link #escortJoined}).
     */
    static void discardEscort(MinecraftServer server, Run run) {
        if (run.escort == null) return;
        boolean reached = false;
        for (ServerLevel level : server.getAllLevels()) {
            Entity e = level.getEntity(run.escort);
            if (e != null) {
                e.discard();
                reached = true;
            }
        }
        if (!reached) {
            Data d = data(server);
            d.doomed.add(run.escort);
            while (d.doomed.size() > MAX_DOOMED) d.doomed.remove(d.doomed.iterator().next());
            d.setDirty();
        }
    }

    /** A merchant of an ended run that was out of reach comes back with its chunk: it leaves at once. */
    @SubscribeEvent
    static void escortJoined(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !(event.getEntity() instanceof Peddler)) return;
        MinecraftServer server = event.getLevel().getServer();
        if (server == null || server.overworld() == null) return;
        Data d = data(server);
        if (d.doomed.remove(event.getEntity().getUUID())) {
            d.setDirty();
            event.setCanceled(true);
        }
    }

    public static boolean invite(ServerPlayer taker, ServerPlayer target) {
        MinecraftServer server = taker.getServer();
        Data d = data(server);
        Run run = active(d, taker.getUUID());
        if (run == null || !taker.getUUID().equals(run.taker())) { msg(taker, "not_taker"); return false; }
        if (run.members.size() >= MAX_GROUP) { msg(taker, "full"); return false; }
        if (!trusted(taker)) { msg(taker, "low_standing"); return false; }
        if (target == taker || !SectService.state(target).member() || !trusted(target) || active(d, target.getUUID()) != null
                || doneToday(d, target.getUUID(), run.slot) || target.level() != taker.level()
                || target.distanceToSqr(taker) > 64.0D * 64.0D) { msg(taker, "cannot_invite", target.getDisplayName()); return false; }
        d.invites.put(target.getUUID(), run.id);
        // The invitation waits on the tablet and the board (buttons there); the line over the hotbar only says it came.
        SectNotice.bar(target, Component.translatable("murim.contract.invited", taker.getDisplayName()).withStyle(ChatFormatting.GOLD));
        target.level().playSound(null, target.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.8F, 1.2F);
        msg(taker, "invite_sent", target.getDisplayName());
        SectTablet.touch(target);
        return true;
    }

    /** Turns an invitation down: it is gone, nothing else happens. */
    public static boolean decline(ServerPlayer p) {
        Data d = data(p.getServer());
        if (d.invites.remove(p.getUUID()) == null) { msg(p, "no_invite"); return false; }
        msg(p, "declined");
        SectTablet.touch(p);
        return true;
    }

    public static boolean accept(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        Data d = data(server);
        UUID id = d.invites.remove(p.getUUID());
        Run run = id == null ? null : d.runs.get(id);
        if (run == null || run.turnedIn || !valid(run, d.day)) { msg(p, "no_invite"); return false; }
        if (!trusted(p)) { msg(p, "low_standing"); return false; }
        if (run.members.size() >= MAX_GROUP || active(d, p.getUUID()) != null || doneToday(d, p.getUUID(), run.slot)) {
            msg(p, "cannot_join");
            return false;
        }
        run.members.add(p.getUUID());
        d.setDirty();
        Offer o = runOffer(d, run);
        SectNotice.bar(p, Component.translatable("murim.contract.joined", shortTitle(o)).withStyle(ChatFormatting.GOLD));
        for (ServerPlayer m : online(server, run)) if (m != p) msg(m, "member_joined", p.getDisplayName());
        for (ServerPlayer m : online(server, run)) SectTablet.touch(m);
        return true;
    }

    /** Leaving never costs anything. The last member leaving frees the slot again. */
    public static boolean leave(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        Data d = data(server);
        Run run = active(d, p.getUUID());
        if (run == null) { msg(p, "none"); return false; }
        run.members.remove(p.getUUID());
        if (run.members.isEmpty()) {
            discardEscort(server, run);
            d.runs.remove(run.id);
        }
        d.setDirty();
        msg(p, "left");
        p.level().playSound(null, p.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 0.6F, 0.7F);
        SectTablet.touch(p);
        for (ServerPlayer m : online(server, run)) SectTablet.touch(m);
        return true;
    }

    // ------------------------------------------------------------------ progress

    static ItemStack token(long campKey, long day) {
        ItemStack stack = new ItemStack(ModItems.CONTRACT_TOKEN.get());
        CompoundTag tag = new CompoundTag();
        tag.putLong("camp", campKey);
        tag.putLong("day", day);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return stack;
    }

    static boolean isToken(ItemStack s, long campKey, long day) {
        if (!s.is(ModItems.CONTRACT_TOKEN.get())) return false;
        CompoundTag t = s.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return t.getLong("camp") == campKey && t.getLong("day") == day;
    }

    /** Called when a camp is cleared: every run member near it gets the proof token. */
    public static void campCleared(ServerLevel level, BanditCampData.Camp camp) {
        MinecraftServer server = level.getServer();
        Data d = data(server);
        for (Run r : d.runs.values()) {
            Offer o = runOffer(d, r);
            if (r.turnedIn || !valid(r, d.day) || o == null || o.kind() != Kind.CAMP || o.campKey() != camp.key) continue;
            for (ServerPlayer m : online(server, r)) {
                if (m.level() != level || m.distanceToSqr(camp.centre.getX(), m.getY(), camp.centre.getZ()) > CAMP_RANGE * CAMP_RANGE) continue;
                ItemStack t = token(camp.key, r.day);
                if (!m.getInventory().add(t)) m.drop(t, false);
                // Marked only now that a token really changed hands: a group nobody of whom was near keeps the search open.
                r.searched = true;
                d.setDirty();
                SectNotice.bar(m, Component.translatable("murim.contract.token_found").withStyle(ChatFormatting.GOLD));
                SectTablet.touch(m);
            }
        }
    }

    private static Item herb(String id) {
        return BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
    }

    private static int count(List<ServerPlayer> who, java.util.function.Predicate<ItemStack> match) {
        int n = 0;
        for (ServerPlayer p : who) {
            for (ItemStack s : p.getInventory().items) if (match.test(s)) n += s.getCount();
            for (ItemStack s : p.getInventory().offhand) if (match.test(s)) n += s.getCount();
        }
        return n;
    }

    private static void take(List<ServerPlayer> who, java.util.function.Predicate<ItemStack> match, int amount) {
        for (ServerPlayer p : who) {
            for (var list : List.of(p.getInventory().items, p.getInventory().offhand)) {
                for (ItemStack s : list) {
                    if (amount <= 0 || !match.test(s)) continue;
                    int n = Math.min(amount, s.getCount());
                    s.shrink(n);
                    amount -= n;
                }
            }
        }
    }

    static boolean ready(Run run, Offer o, long day, List<ServerPlayer> group) {
        return switch (o.kind()) {
            case CAMP -> count(group, s -> isToken(s, o.campKey(), run.day)) >= 1;
            case ESCORT -> run.arrived;
            case HERBS -> count(group, s -> s.is(herb(o.itemA()))) >= o.countA() && count(group, s -> s.is(herb(o.itemB()))) >= o.countB();
        };
    }

    private static List<ServerPlayer> near(MinecraftServer server, Run run, ServerPlayer hander, Entity centre) {
        List<ServerPlayer> list = new ArrayList<>();
        list.add(hander);
        for (ServerPlayer m : online(server, run)) {
            if (m != hander && m.level() == centre.level() && m.distanceToSqr(centre) <= SHARE_RANGE * SHARE_RANGE) list.add(m);
        }
        return list;
    }

    // ------------------------------------------------------------------ hand-in and rewards

    public record Reward(int merit, List<ItemStack> items) { }

    /** The people who hand out contracts and have their own words of thanks (the others use the common pool). */
    static final java.util.Set<String> THANKS_GIVERS = java.util.Set.of("gyeong_cho", "gyeong_tak", "tae_gyun");

    /** Scaled to standing: the higher the standing, the better the merit and the items. */
    public static Reward reward(Kind kind, SectStanding standing, long day, int slot) {
        int t = Math.max(0, standing.ordinal());
        List<ItemStack> items = new ArrayList<>();
        int merit;
        switch (kind) {
            case CAMP -> {
                merit = 10 + 3 * t;
                if (t >= 2) items.add(new ItemStack(ModItems.PILL_SNOW_PLUM.get()));
                items.add(page(day, slot, t, t >= 3 ? 2 : 1));
            }
            case ESCORT -> {
                merit = 6 + 2 * t;
                items.add(new ItemStack(ModItems.PILL_SNOW_PLUM.get()));
                if (t >= 3) items.add(page(day, slot, t, 1));
            }
            default -> {
                merit = 4 + 2 * t;
                if (t >= 2) items.add(new ItemStack(ModItems.PILL_SNOW_PLUM.get(), t >= 4 ? 2 : 1));
            }
        }
        return new Reward(merit, items);
    }

    static ItemStack page(long day, int slot, int tier, int n) {
        ItemStack page = new ItemStack(ModItems.MANUAL_PAGE.get(), n);
        page.set(ModDataComponents.TECHNIQUE.get(), PAGES.get((int) Math.floorMod(day + slot, (long) Math.min(PAGES.size(), Math.max(2, tier + 1)))));
        return page;
    }

    /** The giver accepts the hand-in. Returns true when the contract was completed (the normal dialogue is skipped). */
    public static boolean handIn(ServerPlayer p, SectDisciple npc) {
        MinecraftServer server = p.getServer();
        if (server == null || npc == null || !npc.isAlive()) return false;
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day) || !o.giver().equals(npc.memberKey()) || npc.distanceToSqr(p) > 144.0D) return false;
        List<ServerPlayer> group = near(server, run, p, npc);
        if (!ready(run, o, d.day, group)) return false;
        complete(server, d, run, o, group, npc);
        return true;
    }

    /**
     * Does the giver exist in the world the player is in? Standing on the mountain or within reach of the player, or
     * marked as placed (a living member whose chunk is not loaded is "far away", not "gone"). Killed (the mark is
     * dropped on death) or never built: he does not exist and the board may take the hand-in in his place.
     */
    static boolean giverExists(ServerPlayer p, String giver) {
        ServerLevel level = p.serverLevel();
        java.util.function.Predicate<SectDisciple> match = n -> n.isAlive() && giver.equals(n.memberKey());
        if (!level.getEntitiesOfClass(SectDisciple.class, SectLife.columnBox(level, p.blockPosition(), SectLife.WHERE_RANGE), match).isEmpty()) {
            return true;
        }
        net.minecraft.world.phys.AABB sect = SectLife.sectBox(level);
        if (sect != null && !level.getEntitiesOfClass(SectDisciple.class, sect, match).isEmpty()) {
            return true;
        }
        return SectLife.data(level).placed("npc:" + giver);
    }

    /**
     * The board accepts the hand-in only at the board itself, and only when the named giver is gone (killed, or the
     * mountain not built). A giver who is merely far away must be walked to.
     */
    public static boolean handInAtBoard(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null || !nearBoard(p)) return false;
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day)) return false;
        if (giverExists(p, o.giver())) return false;
        List<ServerPlayer> group = near(server, run, p, p);
        if (!ready(run, o, d.day, group)) return false;
        complete(server, d, run, o, group, null);
        return true;
    }

    private static void complete(MinecraftServer server, Data d, Run run, Offer o, List<ServerPlayer> group, SectDisciple npc) {
        switch (o.kind()) {
            case CAMP -> take(group, s -> isToken(s, o.campKey(), run.day), 99);
            case HERBS -> {
                take(group, s -> s.is(herb(o.itemA())), o.countA());
                take(group, s -> s.is(herb(o.itemB())), o.countB());
            }
            default -> { }
        }
        run.turnedIn = true;
        discardEscort(server, run);
        d.setDirty();
        // the client's fee is the sect's income (treasury, doc 39 «Ремонт секты и казна»)
        io.github.verycooltimo.murim.sect.repair.SectTreasury.contractDone(server, o.kind().name());
        for (ServerPlayer m : group) {
            Reward r = reward(o.kind(), SectService.standing(m), d.day, o.slot());
            int paid = r.merit() + SectPositions.incomeBonus(SectService.state(m), r.merit());
            SectService.contribute(m, r.merit());
            for (ItemStack s : r.items()) {
                if (!m.getInventory().add(s)) m.drop(s, false);
            }
            // One line, sent last so that it is the one left over the hotbar (the merit line of contribute is replaced).
            SectNotice.bar(m, Component.translatable(r.items().isEmpty() ? "murim.contract.completed" : "murim.contract.completed_items",
                    shortTitle(o), paid).withStyle(ChatFormatting.GOLD));
            m.level().playSound(null, m.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 1.4F);
            SectTablet.touch(m);
        }
        for (ServerPlayer m : group) {
            SectEvents.record(m, SectEvents.Event.CONTRACT);
        }
        if (npc != null) {
            // The giver thanks in his own words; the rest of the mountain gossips about it once (SectEvents).
            String stem = THANKS_GIVERS.contains(npc.memberKey()) ? "murim.bubble.contract.done." + npc.memberKey() : "murim.bubble.contract.done.any";
            ServerPlayer first = group.isEmpty() ? null : group.get(0);
            String line = first != null ? SectLines.pick(first, stem, SectLines.MIN_VARIANTS, npc.getRandom())
                    : stem + "." + (1 + npc.getRandom().nextInt(SectLines.MIN_VARIANTS));
            SectChatter.send(npc, line, Component.translatable(line));
            for (ServerPlayer m : group) {
                SectEvents.markReacted(m, SectEvents.Event.CONTRACT, npc);
            }
        }
    }

    // ------------------------------------------------------------------ text

    static Component giverName(Offer o) {
        return SectNames.of(o.giver());
    }

    static Component herbs(Offer o) {
        return Component.literal(o.countA() + " ").append(herb(o.itemA()).getDescription())
                .append(", " + o.countB() + " ").append(herb(o.itemB()).getDescription());
    }

    /** The short form of an offer's title, as the tablet and the board draw it (and as the action bar says it). */
    static Component shortTitle(Offer o) {
        return switch (o.kind()) {
            case CAMP -> Component.translatable("murim.tablet.contract.camp", Component.translatable("murim.contract.place." + o.countB()));
            case ESCORT -> Component.translatable("murim.tablet.contract.escort");
            case HERBS -> Component.translatable("murim.tablet.contract.herbs", herbs(o));
        };
    }

    /** Where the escort goes, in words: the tablet's second line. */
    static Component escortWhere(Offer o) {
        return Component.translatable("murim.tablet.contract.escort.where." + routeId(o));
    }

    static Component title(Offer o) {
        return switch (o.kind()) {
            case CAMP -> Component.translatable("murim.contract.kind.camp",
                    Component.translatable("murim.contract.place." + o.countB()),
                    Component.translatable("murim.contract.dir." + o.itemA()), o.countA());
            case ESCORT -> Component.translatable("murim.contract.kind.escort." + routeId(o));
            case HERBS -> Component.translatable("murim.contract.kind.herbs", herbs(o));
        };
    }

    private static MutableComponent button(String key, String command) {
        return Component.translatable(key).withStyle(Style.EMPTY.withColor(ChatFormatting.AQUA).withUnderlined(true)
                .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, command)));
    }

    /** One line for the sect journal (the HUD goal line and /murim dev sect log); null without an active contract. */
    public static Component journal(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return null;
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day)) return null;
        return Component.translatable("murim.contract.journal", title(o), giverName(o), status(p));
    }

    /** The active contract as the guidance and the giver's dialogue need it: the offer, whether it can be handed in now, its title. */
    public record Guide(Offer offer, boolean ready, Component title) {
        public String giver() { return offer.giver(); }
    }

    /** The player's contract for today, or null. */
    public static Guide guide(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return null;
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day)) return null;
        return new Guide(o, ready(run, o, d.day, near(server, run, p, p)), shortTitle(o));
    }

    /** The active contract's state as a phrase (ready to hand in, walking, clearing, gathering); null without a contract. */
    public static Component status(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return null;
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day)) return null;
        List<ServerPlayer> group = near(server, run, p, p);
        String status = ready(run, o, d.day, group) ? "ready" : o.kind() == Kind.ESCORT ? "walking." + routeId(o) : o.kind() == Kind.CAMP ? "camp" : "herbs";
        return Component.translatable("murim.contract.status." + status);
    }

    /** What the group has gathered for the active contract ("4/6, 1/4" herbs, the camp's tally); empty when there is nothing to count. */
    public static Component progress(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return Component.empty();
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day)) return Component.empty();
        List<ServerPlayer> group = near(server, run, p, p);
        return switch (o.kind()) {
            case HERBS -> Component.translatable("murim.contract.progress.herbs",
                    Math.min(o.countA(), count(group, s -> s.is(herb(o.itemA())))), o.countA(),
                    Math.min(o.countB(), count(group, s -> s.is(herb(o.itemB())))), o.countB());
            case CAMP -> Component.translatable("murim.contract.progress.camp", Math.min(1, count(group, s -> isToken(s, o.campKey(), run.day))));
            case ESCORT -> Component.empty();
        };
    }

    /** Structured counters keep item identity on the wire; the client never parses a translated sentence. */
    static List<SectTabletData.Counter> counters(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return List.of();
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day) || o.kind() != Kind.HERBS) return List.of();
        List<ServerPlayer> group = near(server, run, p, p);
        return List.of(new SectTabletData.Counter(o.itemA(), Math.min(o.countA(), count(group, s -> s.is(herb(o.itemA())))), o.countA()),
                new SectTabletData.Counter(o.itemB(), Math.min(o.countB(), count(group, s -> s.is(herb(o.itemB())))), o.countB()));
    }

    /** The board: three lines with a Take button, plus the active contract with group and leave buttons. */
    public static void showBoard(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        if (!SectService.state(p).member()) { msg(p, "members_only"); return; }
        refresh(server);
        Data d = data(server);
        Run mine = active(d, p.getUUID());
        if (d.lost.remove(p.getUUID())) {
            d.setDirty();
            SectNotice.chat(p, Component.translatable("murim.contract.board_lost").withStyle(ChatFormatting.RED));
        }
        SectNotice.chat(p, Component.translatable("murim.contract.board", d.day + 1).withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD));
        for (Offer o : d.offers.stream().sorted(Comparator.comparingInt(Offer::slot)).toList()) {
            MutableComponent line = Component.literal(" " + (o.slot() + 1) + ". ").append(title(o))
                    .append(o.kind() == Kind.HERBS ? Component.translatable("murim.contract.habitat",
                            Component.translatable("murim.contract.habitat." + io.github.verycooltimo.murim.world.herb.HerbCatalog.habitat(o.itemA())),
                            Component.translatable("murim.contract.habitat." + io.github.verycooltimo.murim.world.herb.HerbCatalog.habitat(o.itemB()))) : Component.empty())
                    .append(o.kind() == Kind.CAMP ? Component.translatable("murim.contract.group_hint") : Component.empty())
                    .append(Component.literal(" - ")).append(giverName(o)).withStyle(ChatFormatting.WHITE);
            Reward r = reward(o.kind(), SectService.standing(p), d.day, o.slot());
            line.append(Component.translatable("murim.contract.merit", r.merit()).withStyle(ChatFormatting.YELLOW));
            if (doneToday(d, p.getUUID(), o.slot())) line.append(Component.translatable("murim.contract.done_mark").withStyle(ChatFormatting.GREEN));
            else if (mine == null) line.append(" ").append(button("murim.contract.take", "/contract take " + (o.slot() + 1)));
            SectNotice.chat(p, line);
        }
        if (mine != null) {
            Component j = journal(p);
            if (j != null) SectNotice.chat(p, j);
            SectNotice.chat(p, Component.translatable("murim.contract.group", mine.members.size(), MAX_GROUP).withStyle(ChatFormatting.GRAY)
                    .append(" ").append(Component.translatable("murim.contract.invite_hint").withStyle(ChatFormatting.AQUA)
                            .withStyle(Style.EMPTY.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/contract invite "))))
                    .append(" ").append(button("murim.contract.leave", "/contract leave")));
            if (handInAtBoard(p)) return;
        }
        SectNotice.chat(p, Component.translatable("murim.contract.footer").withStyle(ChatFormatting.DARK_GRAY));
    }

    static boolean nearBoard(ServerPlayer p) {
        BlockPos c = p.blockPosition();
        for (BlockPos pos : BlockPos.betweenClosed(c.offset(-8, -4, -8), c.offset(8, 4, 8))) {
            if (p.level().getBlockState(pos).is(SectMissionBoard.BOARD.get())) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the screens (board and tablet)

    /** Right-click on the board block: the board screen with a fresh snapshot (the chat listing is gone, author 18.10). */
    public static void openBoard(ServerPlayer p) {
        if (p.getServer() == null) return;
        if (!SectService.state(p).member()) { msg(p, "members_only"); return; }
        SectTablet.openBoard(p);
    }

    /**
     * A button of the board or of the tablet's contracts tab ({@code SectTabletPayload.Contract}). The same server logic
     * as the commands: take, hand in, invite, accept, decline and leave. Everything is checked again here: the client only
     * asks. Take, hand in, invite and leave need the board within reach; an invitation can be answered anywhere.
     */
    public static void act(ServerPlayer p, String op, int slot, String target) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        if (!SectService.state(p).member()) { msg(p, "members_only"); return; }
        switch (op) {
            case "accept" -> accept(p);
            case "decline" -> decline(p);
            case "take", "handin", "invite", "leave" -> {
                if (!nearBoard(p)) { msg(p, "far_from_board"); return; }
                switch (op) {
                    case "take" -> take(p, slot);
                    case "leave" -> leave(p);
                    case "invite" -> {
                        ServerPlayer who = null;
                        try {
                            who = player(server, UUID.fromString(target));
                        } catch (IllegalArgumentException ignored) {
                            // not an id: nobody to invite
                        }
                        if (who == null) msg(p, "cannot_invite", Component.literal(target));
                        else invite(p, who);
                    }
                    default -> handInFromBoard(p);
                }
            }
            default -> { }
        }
    }

    /**
     * The "hand in" button on the board: the giver takes the contract when he stands within reach of the player (as if he
     * were talked to), the board takes it in his place when he is gone ({@link #handInAtBoard}); otherwise the player is
     * told whom to walk to.
     */
    static boolean handInFromBoard(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        Data d = data(server);
        Run run = active(d, p.getUUID());
        Offer o = run == null ? null : runOffer(d, run);
        if (o == null || !valid(run, d.day)) { msg(p, "none"); return false; }
        if (!ready(run, o, d.day, near(server, run, p, p))) { msg(p, "not_ready"); return false; }
        for (SectDisciple npc : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(12.0D),
                n -> n.isAlive() && o.giver().equals(n.memberKey()))) {
            if (handIn(p, npc)) return true;
        }
        if (handInAtBoard(p)) return true;
        msg(p, "hand_to", giverName(o));
        return false;
    }

    /** An open invitation to this player, or null: the run must still exist, be of today, have room and fit him. */
    static SectTabletData.Invite pendingInvite(MinecraftServer server, Data d, ServerPlayer p) {
        UUID id = d.invites.get(p.getUUID());
        Run run = id == null ? null : d.runs.get(id);
        if (run == null || run.turnedIn || !valid(run, d.day) || run.members.size() >= MAX_GROUP || active(d, p.getUUID()) != null
                || doneToday(d, p.getUUID(), run.slot)) return null;
        Offer o = runOffer(d, run);
        ServerPlayer from = run.taker() == null ? null : player(server, run.taker());
        if (o == null || from == null) return null;
        return new SectTabletData.Invite(from.getGameProfile().getName(), shortTitle(o));
    }

    /** Members who can be invited into the player's group now: the same conditions as {@link #invite}. */
    static List<SectTabletData.Candidate> invitable(MinecraftServer server, Data d, ServerPlayer taker, Run run) {
        List<SectTabletData.Candidate> list = new ArrayList<>();
        if (run == null || !taker.getUUID().equals(run.taker()) || run.members.size() >= MAX_GROUP) return list;
        for (ServerPlayer t : server.getPlayerList().getPlayers()) {
            if (t == taker || !SectService.state(t).member() || !trusted(t) || active(d, t.getUUID()) != null
                    || doneToday(d, t.getUUID(), run.slot) || t.level() != taker.level()
                    || t.distanceToSqr(taker) > 64.0D * 64.0D) continue;
            list.add(new SectTabletData.Candidate(t.getUUID().toString(), t.getGameProfile().getName()));
        }
        return list;
    }

    /** What the contract buttons of the board and of the tablet need (see {@link SectTabletData.Board}). */
    static SectTabletData.Board boardState(ServerPlayer p, MinecraftServer server, Data d) {
        Run run = active(d, p.getUUID());
        Offer o = run == null || !valid(run, d.day) ? null : runOffer(d, run);
        boolean reach = nearBoard(p);
        boolean ready = o != null && ready(run, o, d.day, near(server, run, p, p));
        boolean absent = o != null && !giverExists(p, o.giver());
        return new SectTabletData.Board(reach, trusted(p) && !SectPositions.disciplined(SectService.state(p)), ready,
                o == null ? Component.empty() : giverName(o), reach ? invitable(server, d, p, run) : List.of(),
                pendingInvite(server, d, p), d.lost.contains(p.getUUID()),
                o != null && reach && ready && (absent
                        || !p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(12.0D),
                        n -> n.isAlive() && o.giver().equals(n.memberKey()) && n.distanceToSqr(p) <= 144.0D
                                && ready(run, o, d.day, near(server, run, p, n))).isEmpty()),
                absent);
    }

    /** The "merchant lost" line of the board is told once: it is cleared when the player takes a contract or closes the board. */
    public static void clearLost(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server != null && data(server).lost.remove(p.getUUID())) data(server).setDirty();
    }

    // ------------------------------------------------------------------ events and commands

    @SubscribeEvent(priority = EventPriority.HIGH)
    static void interact(PlayerInteractEvent.EntityInteract event) {
        if (event.getLevel().isClientSide || event.getHand() != InteractionHand.MAIN_HAND
                || !(event.getEntity() instanceof ServerPlayer p) || !(event.getTarget() instanceof SectDisciple npc)) return;
        if (handIn(p, npc)) {
            event.setCancellationResult(InteractionResult.SUCCESS);
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    static void tick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD || level.getGameTime() % 20L != 5L) return;
        refresh(level.getServer());
        escortStep(level);
        campSearchStep(level);
    }

    /** Horizontal reach from the camp centre that counts as being in the camp. */
    static final double SEARCH_RANGE = 14.0D;

    /**
     * A camp somebody else already cleared: a run member who arrives at the contract's camp searches it and finds the
     * proof token (one token per contract per group; the whole group may hand it in).
     */
    static void campSearchStep(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Data d = data(server);
        BanditCampData camps = BanditCamps.data(level);
        for (Run run : new ArrayList<>(d.runs.values())) {
            Offer o = run.kind == Kind.CAMP && !run.turnedIn && !run.searched && valid(run, d.day) ? runOffer(d, run) : null;
            if (o == null || o.kind() != Kind.CAMP) continue;
            BanditCampData.Camp camp = camps.get(o.campKey());
            if (camp == null || !camp.cleared) continue;
            for (ServerPlayer m : online(server, run)) {
                double dx = m.getX() - (o.site().getX() + 0.5D), dz = m.getZ() - (o.site().getZ() + 0.5D);
                if (m.level() != level || dx * dx + dz * dz > SEARCH_RANGE * SEARCH_RANGE) continue;
                run.searched = true;
                d.setDirty();
                ItemStack t = token(o.campKey(), d.day);
                if (!m.getInventory().add(t)) m.drop(t, false);
                SectNotice.bar(m, Component.translatable("murim.contract.token_searched").withStyle(ChatFormatting.GOLD));
                SectTablet.touch(m);
                for (ServerPlayer other : online(server, run)) if (other != m) msg(other, "token_searched_other", m.getDisplayName());
                break;
            }
        }
    }


    /**
     * Escort upkeep: the merchant follows the nearest member, dies = contract void, reaching the end of his route = arrived;
     * on the way he says a short line at the start, at the talking points of the route and at the end.
     */
    static void escortStep(ServerLevel level) {
        MinecraftServer server = level.getServer();
        Data d = data(server);
        for (Run run : new ArrayList<>(d.runs.values())) {
            if (run.kind != Kind.ESCORT || run.turnedIn || run.escort == null) continue;
            Offer offer = runOffer(d, run);
            if (offer == null) continue;
            // The foot route ends at the stall as it is now (a test moves it); the other routes end where their offer says.
            BlockPos end = EscortRoutes.FOOT.equals(routeId(offer)) ? stall(level) : offer.site();
            Entity e = level.getEntity(run.escort);
            if (!(e instanceof Peddler merchant)) continue;
            if (!merchant.isAlive()) {
                merchantLost(server, d, run);
                continue;
            }
            ServerPlayer lead = null;
            for (ServerPlayer m : online(server, run)) {
                if (m.level() == level && (lead == null || m.distanceToSqr(merchant) < lead.distanceToSqr(merchant))) lead = m;
            }
            merchant.setEscortLeader(run.arrived || lead == null ? null : lead.getUUID());
            double dx = merchant.getX() - (end.getX() + 0.5D), dz = merchant.getZ() - (end.getZ() + 0.5D);
            if (!run.arrived && dx * dx + dz * dz <= ARRIVE_RANGE * ARRIVE_RANGE) {
                run.arrived = true;
                d.setDirty();
                for (ServerPlayer m : online(server, run)) {
                    SectNotice.bar(m, Component.translatable("murim.contract.arrived." + routeId(offer)).withStyle(ChatFormatting.GOLD));
                    SectTablet.touch(m);
                }
            }
            if (run.arrived) {
                endLine(level, d, run, merchant, lead);
            } else {
                onTheWay(level, d, run, offer, merchant, lead);
            }
        }
    }

    /** The pools of the merchant's short lines (at least {@link SectLines#MIN_VARIANTS} each, a bubble over his head). */
    static final String ESCORT_START = "murim.bubble.escort.start";
    static final String ESCORT_WAY = "murim.bubble.escort.way";
    static final String ESCORT_END = "murim.bubble.escort.end";
    /** Not two lines of the merchant closer than this (ticks). */
    static final long LINE_GAP = 400L;

    /**
     * The merchant's words on the way: the start line when somebody of the group stands by him, then one at every talking point
     * of the route that he reaches in order (never two within {@link #LINE_GAP} ticks, and only with a member near).
     */
    static void onTheWay(ServerLevel level, Data d, Run run, Offer offer, Peddler merchant, ServerPlayer lead) {
        if (lead == null || lead.distanceToSqr(merchant) > 14.0D * 14.0D) return;
        // Progress along the route does not wait for the pause between two lines: a point passed in the pause is passed.
        boolean quiet = level.getGameTime() - run.lastLine < LINE_GAP;
        if (run.leg == 0) {
            if (quiet) return;
            run.leg = 1;
            d.setDirty();
            merchantSays(level, merchant, lead, ESCORT_START);
            run.lastLine = level.getGameTime();
            return;
        }
        if (run.talks == null && talkingOverride != null) {
            run.talks = talkingOverride;
        }
        if (run.talks == null) {
            MountHuaSite site = stallOverride != null ? null : MountHuaSites.get(level.getServer());
            EscortRoutes.Route route = site == null ? null
                    : EscortRoutes.build(routeId(offer), site, SectLayout.hua(site).file(), offer.site());
            run.talks = route == null ? List.of() : route.talkingPoints();
        }
        // The farthest point he stands at among those not passed yet; points he missed on the way are passed with it.
        int reached = -1;
        for (int i = run.talks.size() - 1; i >= run.leg - 1; i--) {
            net.minecraft.world.phys.Vec3 at = run.talks.get(i);
            if (Math.hypot(merchant.getX() - at.x, merchant.getZ() - at.z) <= 14.0D) {
                reached = i;
                break;
            }
        }
        if (reached < 0) return;
        run.leg = reached + 2;
        d.setDirty();
        if (quiet) return;
        merchantSays(level, merchant, lead, ESCORT_WAY);
        run.lastLine = level.getGameTime();
    }

    /**
     * The words at the end of the walk: once (the run remembers it in {@code leg = -1}), to somebody of the group, and not
     * within the pause after another line: the merchant waits it out beside the player.
     */
    static void endLine(ServerLevel level, Data d, Run run, Peddler merchant, ServerPlayer lead) {
        if (run.leg < 0 || lead == null || lead.distanceToSqr(merchant) > 14.0D * 14.0D
                || level.getGameTime() - run.lastLine < LINE_GAP) return;
        run.leg = -1;
        d.setDirty();
        merchantSays(level, merchant, lead, ESCORT_END);
        run.lastLine = level.getGameTime();
    }

    /** One line of the pool over the merchant's head, one the listener has not heard today ({@link SectLines}). */
    static void merchantSays(ServerLevel level, Peddler merchant, ServerPlayer lead, String stem) {
        String key = lead != null ? SectLines.pick(lead, stem, SectLines.MIN_VARIANTS, merchant.getRandom())
                : stem + "." + (1 + merchant.getRandom().nextInt(SectLines.MIN_VARIANTS));
        merchant.markBubble(key);
        SectChatter.sendAbove(merchant, key, Component.translatable(key));
    }

    /** A dead escort merchant voids the contract with no penalty; members hear it at once and again at the board. */
    static void merchantLost(MinecraftServer server, Data d, Run run) {
        for (UUID u : run.members) {
            d.lost.add(u);
            ServerPlayer m = player(server, u);
            if (m != null) {
                msg(m, "merchant_lost");
                SectTablet.touch(m);
            }
        }
        d.runs.remove(run.id);
        d.setDirty();
    }

    @SubscribeEvent
    static void merchantDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Peddler merchant) || !merchant.escorted() || merchant.level().isClientSide) return;
        MinecraftServer server = merchant.getServer();
        Data d = data(server);
        for (Run run : new ArrayList<>(d.runs.values())) {
            if (!run.turnedIn && merchant.getUUID().equals(run.escort)) merchantLost(server, d, run);
        }
    }

    @SubscribeEvent
    static void commands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("contract")
                .then(Commands.literal("show").executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!nearBoard(p)) { msg(p, "far_from_board"); return 0; }
                    showBoard(p);
                    return 1;
                }))
                .then(Commands.literal("take").then(Commands.argument("slot", IntegerArgumentType.integer(1, 3)).executes(c -> {
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    if (!nearBoard(p)) { msg(p, "far_from_board"); return 0; }
                    return take(p, IntegerArgumentType.getInteger(c, "slot") - 1) ? 1 : 0;
                })))
                .then(Commands.literal("invite").then(Commands.argument("player", EntityArgument.player()).executes(c ->
                        invite(c.getSource().getPlayerOrException(), EntityArgument.getPlayer(c, "player")) ? 1 : 0)))
                .then(Commands.literal("accept").executes(c -> accept(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("decline").executes(c -> decline(c.getSource().getPlayerOrException()) ? 1 : 0))
                .then(Commands.literal("leave").executes(c -> leave(c.getSource().getPlayerOrException()) ? 1 : 0)));
    }
}
