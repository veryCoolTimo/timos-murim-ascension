package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.SectMapPayload;
import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.network.SectTabletPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * The jade sect tablet (doc 39, «Приход новичка»): the journal item given at joining. The server collects a snapshot of
 * real sect data ({@link SectTabletData}) and the client only draws it. One tablet per member; a lost one is replaced by
 * the mentor for a little merit (see {@link #replace}).
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectTablet {
    /** Set once the first tablet was handed over; losing the item never clears it. */
    public static final String GIVEN = "tablet.given";
    /** Merit the mentor takes for a replacement. If the member has less, he takes what there is (never a negative balance). */
    public static final int REPLACE_COST = 5;

    /** The parts of a day the tablet lists, in order; which of them count towards a full day is the sect's roll call. */
    private static final List<SectAttendance.Activity> CORE = List.of(SectAttendance.Activity.FORMATION,
            SectAttendance.Activity.MEAL, SectAttendance.Activity.LESSON);

    private SectTablet() { }

    // ------------------------------------------------------------------ the item

    public static boolean has(ServerPlayer p) {
        for (ItemStack s : p.getInventory().items) {
            if (s.is(ModItems.JADE_SECT_TABLET.get())) return true;
        }
        for (ItemStack s : p.getInventory().offhand) {
            if (s.is(ModItems.JADE_SECT_TABLET.get())) return true;
        }
        return false;
    }

    private static void hand(ServerPlayer p) {
        ItemStack stack = new ItemStack(ModItems.JADE_SECT_TABLET.get());
        if (!p.getInventory().add(stack)) p.drop(stack, false);
    }

    /** At joining and once for older members: the first tablet. Idempotent through {@link #GIVEN}. */
    public static boolean ensure(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member() || s.has(GIVEN)) return false;
        p.setData(ModAttachments.SECT, s.with(GIVEN));
        if (!has(p)) hand(p);
        SectNotice.chat(p, Component.translatable("murim.tablet.given").withStyle(ChatFormatting.GOLD));
        return true;
    }

    /**
     * The mentor's replacement for a lost tablet. Refused while the player still carries one. The cost is
     * {@link #REPLACE_COST} merit; with less merit he scolds and gives it anyway, taking what the member has
     * (friendlier than a refusal, and the balance never goes below zero).
     */
    public static boolean replace(ServerPlayer p) {
        SectState s = SectService.state(p);
        if (!s.member() || has(p)) return false;
        int cost = Math.min(REPLACE_COST, s.merit());
        p.setData(ModAttachments.SECT, s.spend(cost).with(GIVEN));
        hand(p);
        SectNotice.bar(p, Component.translatable(cost >= REPLACE_COST ? "murim.tablet.replaced" : "murim.tablet.replaced_part", cost)
                .withStyle(ChatFormatting.GOLD));
        return true;
    }

    /** Dialogue condition: a member of the sect who has no tablet on them. */
    static boolean lost(ServerPlayer p) {
        return SectService.state(p).member() && !has(p);
    }

    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isSpectator()) return;
        if (p.tickCount % 100 == 17) ensure(p);
        if (pollDue(p.tickCount)) pushIfChanged(p);
    }

    /**
     * Every event that changes what the tablet shows calls {@link #touch}, so the timed poll is only the net for changes
     * without a hook (inventory, positions): once a second is enough, and it is a quarter of the old collect-and-encode cost.
     */
    static final int POLL_TICKS = 20;

    static boolean pollDue(int tickCount) {
        return tickCount % POLL_TICKS == 3;
    }

    // ------------------------------------------------------------------ screen

    public static void open(ServerPlayer p) {
        if (!SectService.state(p).member()) {
            PlayerJournal.open(p);
            return;
        }
        SectTabletData data = collect(p);
        sendMap(p);
        PacketDistributor.sendToPlayer(p, new SectTabletPayload.Open(data));
        watch(p, true);
        p.getPersistentData().putLong(DIGEST, digest(p, data));
    }

    /** The contract board block was used: the board screen, kept up to date like the tablet (same snapshot, same pushes). */
    public static void openBoard(ServerPlayer p) {
        SectTabletData data = collect(p);
        PacketDistributor.sendToPlayer(p, new SectTabletPayload.OpenBoard(data));
        watch(p, true);
        p.getPersistentData().putLong(DIGEST, digest(p, data));
    }

    // ------------------------------------------------------------------ live updates

    private static final String OPEN = "murim_tablet_open";
    private static final String DIGEST = "murim_tablet_digest";
    private static final String PUSHES = "murim_tablet_pushes";
    private static final String BUSY = "murim_tablet_busy";

    /** The screen of this player is open: the server pushes every change until it is closed. */
    public static void watch(ServerPlayer p, boolean on) {
        if (on) p.getPersistentData().putBoolean(OPEN, true);
        else {
            p.getPersistentData().remove(OPEN);
            p.getPersistentData().remove(DIGEST);
        }
    }

    public static boolean watching(ServerPlayer p) {
        return p.getPersistentData().getBoolean(OPEN);
    }

    /** How many updates went to this player since he opened the tablet (for tests and the log). */
    public static int pushes(ServerPlayer p) {
        return p.getPersistentData().getInt(PUSHES);
    }

    private static long digest(ServerPlayer p, SectTabletData data) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), p.registryAccess());
        SectTabletData.CODEC.encode(buf, data);
        byte[] bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        buf.release();
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(bytes);
        return crc.getValue() << 20 ^ bytes.length;
    }

    /**
     * Sends the tablet's data when anything it shows has changed since the last push, to a player whose screen is open.
     * Answers whether a push was due. Every event that changes what the tablet shows calls {@link #touch}; this is also
     * run every few ticks for open screens, so changes without a hook (inventory, positions) arrive within a quarter second.
     */
    public static boolean pushIfChanged(ServerPlayer p) {
        if (!watching(p) || !SectService.state(p).member() || p.getPersistentData().getBoolean(BUSY)) return false;
        // collecting settles the day's log, which can award merit and call touch again: do not nest
        p.getPersistentData().putBoolean(BUSY, true);
        SectTabletData data;
        try {
            data = collect(p);
        } finally {
            p.getPersistentData().remove(BUSY);
        }
        long now = digest(p, data);
        if (p.getPersistentData().contains(DIGEST) && p.getPersistentData().getLong(DIGEST) == now) return false;
        p.getPersistentData().putLong(DIGEST, now);
        p.getPersistentData().putInt(PUSHES, pushes(p) + 1);
        sendMap(p);
        SectTabletPayload.Update update = new SectTabletPayload.Update(data);
        if (p.connection != null && p.connection.hasChannel(update)) PacketDistributor.sendToPlayer(p, update);
        return true;
    }

    private static final String MAP_SENT = "murim_map_sent";

    /** The map picture, when this player's copy is older than the server's. Reset at login, so every session gets it once. */
    static void sendMap(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        if (server == null) return;
        SectMapData map = SectMap.data(server);
        if (!map.hasArea() || map.version() == 0 || p.getPersistentData().getInt(MAP_SENT) == map.version()) return;
        SectMapPayload payload = new SectMapPayload(map.version(), map.area().x0(), map.area().z0(), map.area().w(), map.area().h(), map.zipped());
        if (p.connection != null && p.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayer(p, payload);
            p.getPersistentData().putInt(MAP_SENT, map.version());
        }
    }

    /** How many map pictures went to this player in this session's bookkeeping: the version he holds (0 = none). */
    public static int mapHeld(ServerPlayer p) {
        return p.getPersistentData().getInt(MAP_SENT);
    }

    /** Something this player sees on the tablet or the goal reminder just changed: send both at once. */
    public static void touch(ServerPlayer p) {
        SectJourney.syncHud(p);
        SectGuide.sync(p);
        pushIfChanged(p);
    }

    /** Admin commands and other direct changes. */
    public static void push(ServerPlayer p) {
        touch(p);
    }

    @SubscribeEvent
    static void loggedOut(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            watch(p, false);
            MENTOR_CACHE.remove(p.getUUID());
        }
    }

    @SubscribeEvent
    static void loggedIn(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            watch(p, false);
            p.getPersistentData().remove(MAP_SENT);
        }
    }

    /** Client button presses. Only the review sign-up needs the server; it then refreshes the open screen. */
    public static void act(ServerPlayer p, String action) {
        if ("close".equals(action)) {
            watch(p, false);
            SectContracts.clearLost(p);
            return;
        }
        if ("journal".equals(action) && has(p)) {
            PlayerJournal.open(p);
            return;
        }
        if ("track".equals(action)) {
            // Closed-screen HUD refreshes do not subscribe the tablet or request map textures.
            if (!SectService.state(p).member()) return;
            if (refreshAllowed(p)) io.github.verycooltimo.murim.network.ChannelSafe.send(p, new SectTabletPayload.Update(collect(p)));
            return;
        }
        if (!SectService.state(p).member()) return;
        if ("review_signup".equals(action) && has(p)) {
            SectReview.signUp(p);
            touch(p);
        } else if ("refresh".equals(action)) {
            // The open screen asks every couple of seconds. That is the safety net (the current data is sent whether or not
            // anything seems to have changed) and also the proof that the screen is still open, so a lost "open" flag comes
            // back with the next request. Collecting is not cheap: a client that asks often is answered at most once per
            // REFRESH_COOLDOWN ticks.
            if (!refreshAllowed(p)) return;
            watch(p, true);
            p.getPersistentData().remove(DIGEST);
            pushIfChanged(p);
        }
    }

    /** The least ticks between two answered "refresh" requests of one player. */
    public static final int REFRESH_COOLDOWN = 15;
    private static final String REFRESH_AT = "murim_tablet_refresh_at";

    /** True (and the time noted) when this player's last answered refresh is at least {@link #REFRESH_COOLDOWN} ticks old. */
    static boolean refreshAllowed(ServerPlayer p) {
        long now = p.level().getGameTime();
        var data = p.getPersistentData();
        if (data.contains(REFRESH_AT) && now >= data.getLong(REFRESH_AT) && now - data.getLong(REFRESH_AT) < REFRESH_COOLDOWN) {
            return false;
        }
        data.putLong(REFRESH_AT, now);
        return true;
    }

    /** A point of the world on the map: world x and z (the map is in world axes, north up). */
    /** Mentor found by the big area scan, per player: the next snapshots look the entity up by id instead of scanning. */
    private static final java.util.Map<java.util.UUID, java.util.UUID> MENTOR_CACHE = new java.util.concurrent.ConcurrentHashMap<>();

    /** Server stopped: forget cross-world state. */
    public static void clearMentorCache() { MENTOR_CACHE.clear(); }
    /** Area scans done for the mentor since start (a debug counter for tests). */
    public static int mentorScans;

    private static SectDisciple mentorOf(ServerPlayer p) {
        java.util.UUID id = MENTOR_CACHE.get(p.getUUID());
        if (id != null && p.serverLevel().getEntity(id) instanceof SectDisciple d && d.isAlive() && d.role() == SectRole.MENTOR
                && Math.abs(d.getX() - p.getX()) <= 300 && Math.abs(d.getY() - p.getY()) <= 300 && Math.abs(d.getZ() - p.getZ()) <= 300) {
            return d;
        }
        mentorScans++;
        SectDisciple found = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(300),
                        d -> d.isAlive() && d.role() == SectRole.MENTOR).stream()
                .min(Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (found != null) MENTOR_CACHE.put(p.getUUID(), found.getUUID());
        else MENTOR_CACHE.remove(p.getUUID());
        return found;
    }

    static float[] at(Vec3 pos) {
        return pos == null ? null : new float[] {(float) pos.x, (float) pos.z};
    }

    /** The snapshot the screen draws. */
    public static SectTabletData collect(ServerPlayer p) {
        MinecraftServer server = p.getServer();
        long time = p.level().getDayTime();
        SectState s = SectService.state(p);
        var profile = p.getData(ModAttachments.PROFILE);
        SectStanding standing = SectStanding.of(s, profile.rank());
        // Today's marks and the day's merit rule: formation and a lesson make a full day; a meal is listed but never required (author 21.10).
        SectAttendance.Log log = SectAttendance.current(p);
        SectAttendance.Day today = log.today();
        List<SectTabletData.Mark> marks = new ArrayList<>();
        for (SectAttendance.Activity a : CORE) {
            marks.add(new SectTabletData.Mark(a.id(), today.did(a), SectAttendance.required(today, a, time)));
        }
        // The drill the teacher gave after a lost bout is a task of the day, with how far it is (playtest 19.10: the tablet never
        // said whether the day's training was done).
        SectDefeat.Drill drill = SectDefeat.given(p);
        if (drill != null) {
            SectDefeat.Open open = SectDefeat.open(p);
            Component name = Component.translatable("murim.sect.defeat.name." + drill.id());
            marks.add(new SectTabletData.Mark("drill", open == null, false, open == null
                    ? Component.translatable("murim.tablet.mark.drill_done", name)
                    : Component.translatable("murim.tablet.mark.drill", name, open.progress(), open.need())));
        }
        Component gateBearing = gateBearing(p);
        if (!gateBearing.getString().isEmpty()) marks.add(new SectTabletData.Mark("gate", false, false, gateBearing));
        int streak = log.streak();
        int bonus = SectAttendance.dayBonus(streak + 1);
        // The goal and the chain of milestones around it, both from the journey itself.
        SectJourney.Goal goal = SectJourney.next(p);
        SectJourney.Chain path = SectJourney.chain(p);
        List<Component> chain = new ArrayList<>();
        for (String slot : path.slots()) chain.add(Component.translatable("murim.tablet.step." + slot));
        int done = 0;
        int total = 0;
        if (goal.progress().contains("/")) {
            String[] parts = goal.progress().split("/");
            try {
                done = Integer.parseInt(parts[0]);
                total = Integer.parseInt(parts[1]);
            } catch (NumberFormatException ignored) {
                // no numeric progress
            }
        }
        int position = s.has(SectPositions.ELDER) ? 2 : s.has(SectPositions.FIRST) ? 1 : 0;
        // Contracts.
        List<SectTabletData.Offer> offers = new ArrayList<>();
        SectTabletData.Active active = null;
        SectTabletData.Board contracts = SectTabletData.Board.NONE;
        if (server != null) {
            SectContracts.refresh(server);
            SectContracts.Data d = SectContracts.data(server);
            SectContracts.Run run = SectContracts.active(d, p.getUUID());
            Component status = SectContracts.status(p);
            if (run != null && status != null) {
                List<String> names = new ArrayList<>();
                for (UUID u : run.members) {
                    ServerPlayer m = SectContracts.player(server, u);
                    if (m != null) names.add(m.getGameProfile().getName());
                }
                active = new SectTabletData.Active(run.slot, status, SectContracts.progress(p), names, SectContracts.MAX_GROUP, SectContracts.counters(p));
            }
            contracts = SectContracts.boardState(p, server, d);
            // The day's offers hang on the board, not in the player's pocket: the snapshot lists one only beside a board (the board
            // screen and the tablet's tab held there), or when it is his own, taken or handed in today. Everywhere else a player who
            // has taken nothing sees an empty tab (author 19.10: «I have taken no quests, but they are already in my cards»).
            for (SectContracts.Offer o : d.offers.stream().sorted(Comparator.comparingInt(SectContracts.Offer::slot)).toList()) {
                SectTabletData.Offer line = offer(p, d, o, standing, s);
                if (contracts.reach() || line.done() || (active != null && active.slot() == o.slot())) offers.add(line);
            }
        }
        // Standing: challenges and the beaten seniors.
        List<Component> beaten = new ArrayList<>();
        for (SectRoster m : SectBouts.strongSecond()) {
            if (s.entry(SectBouts.BEAT_PREFIX + m.key(), 0) > 0) beaten.add(SectNames.of(m.key()));
        }
        long daysToReview = SectReview.daysUntil(time);
        Component teacher = s.has(SectProgression.DIRECT) ? SectNames.of(SectProgression.MENTOR) : Component.empty();
        // Map: the markers in world coordinates, the buildings' names from the layout, the version of the picture.
        SectLayout layout = server == null ? null : SectLife.layout(p.serverLevel());
        SectLayoutFile file = SectMap.fileOf(layout);
        boolean overworld = p.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
        float[] you = overworld ? at(p.position()) : null;
        float[] bed = null;
        net.minecraft.core.BlockPos bedPos = SectRooms.bed(p);
        if (bedPos != null) bed = at(Vec3.atCenterOf(bedPos));
        float[] mentorAt = null;
        SectDisciple mentor = mentorOf(p);
        if (mentor != null) mentorAt = at(mentor.position());
        List<float[]> boards = boardMarks(file);
        List<SectTabletData.MapLabel> labels = mapLabels(file);
        // No built sect in this world (old, non-canonical): no stored or baked map either.
        SectMapData map = server == null || file == null ? null : SectMap.data(server);
        SectMap.Area area = map != null && map.hasArea() ? map.area() : SectMap.areaFor(file);
        int mapVersion = map != null && map.hasArea() ? map.version() : 0;
        Component mentorName = SectNames.of(SectRoster.legacy(SectRole.MENTOR).map(SectRoster::key).orElse("gyeong_pil"));
        return new SectTabletData((int) SectSchedule.day(time) + 1, SectSchedule.at(time).ordinal(), marks,
                streak, SectReview.APPROVAL_STREAK, s.has(SectStanding.APPROVAL), bonus, standing.ordinal(), position,
                Component.translatable(SectPositions.titleKey(s)), profile.rank(), Component.translatable(Realm.nameKey(profile.rank())),
                Component.translatable("murim.sect.journey.goal." + goal.id()), goal.blocker(),
                SectNames.of(goal.person()), done, total, chain, path.current(), path.more(), offers, active,
                s.merit(), s.contribution(), SectBouts.wins(s), SectBouts.NEEDED,
                standing.ordinal() < SectBouts.CAP.ordinal(), (int) s.entry(SectBouts.TOTAL, 0), beaten,
                (int) daysToReview, standing.atLeast(SectStanding.DISCIPLE), SectReview.signupOpen(p), s.has(SectReview.SIGNED),
                mentorName, teacher, you, mentorAt, bed, boards,
                mapVersion, area == null ? 0 : area.x0(), area == null ? 0 : area.z0(), area == null ? 0 : area.w(),
                area == null ? 0 : area.h(), labels, contracts,
                server == null ? -1 : io.github.verycooltimo.murim.sect.repair.SectTreasury.liang(server),
                server == null ? List.of() : io.github.verycooltimo.murim.sect.repair.SectTreasury.chronicle(server, 3),
                MeritShop.tabletLines(p, standing));
    }

    static Component gateBearing(ServerPlayer p) {
        if (p.level().dimension() != net.minecraft.world.level.Level.OVERWORLD) return Component.translatable("murim.guidance.gate.overworld");
        var site = Wanderer.siteOverride != null ? Wanderer.siteOverride : io.github.verycooltimo.murim.world.hua.MountHuaSites.get(p.getServer());
        int[] gate = site == null ? null : SectService.zone(site, "sect_gate");
        if (gate == null) return Component.empty();
        double dx = gate[0] + 0.5D - p.getX(), dz = gate[2] + 0.5D - p.getZ();
        String[] directions = {"e", "se", "s", "sw", "w", "nw", "n", "ne"};
        int octant = Math.floorMod((int) Math.round(Math.atan2(dz, dx) / (Math.PI / 4)), 8);
        return Component.translatable("murim.guidance.gate", Component.translatable("murim.guidance.dir." + directions[octant]),
                (int) Math.round(Math.hypot(dx, dz)));
    }

    /** The contract boards of the built sect, where they stand in the world (the layout file is anchored to the site's rotation). */
    static List<float[]> boardMarks(SectLayoutFile file) {
        List<float[]> boards = new ArrayList<>();
        if (file != null) {
            for (net.minecraft.core.BlockPos board : file.boards()) boards.add(at(Vec3.atCenterOf(board)));
        }
        return boards;
    }

    /** The named buildings of the built sect with their real footprints, in world blocks. */
    static List<SectTabletData.MapLabel> mapLabels(SectLayoutFile file) {
        List<SectTabletData.MapLabel> labels = new ArrayList<>();
        for (SectMap.Label l : SectMap.labels(file)) labels.add(label(l));
        return labels;
    }

    /** A building of the layout as the tablet's map draws it: its box and the real boxes of its parts. */
    static SectTabletData.MapLabel label(SectMap.Label l) {
        List<SectTabletData.Box> parts = new ArrayList<>();
        for (SectLayoutFile.Rect r : l.parts()) parts.add(new SectTabletData.Box(r.x0(), r.z0(), r.x1(), r.z1()));
        return new SectTabletData.MapLabel(l.key(), l.x0(), l.z0(), l.x1(), l.z1(), parts);
    }

    /** The sprite of a contract type's picture (an exhaustive switch: a new type does not compile until it has one). */
    static String icon(SectContracts.Kind kind) {
        return switch (kind) {
            case CAMP -> "big_mask";
            case ESCORT -> "big_cart";
            case HERBS -> "big_herb";
        };
    }

    static SectTabletData.Offer offer(ServerPlayer p, SectContracts.Data d, SectContracts.Offer o, SectStanding standing, SectState state) {
        Component title;
        Component where;
        switch (o.kind()) {
            case CAMP -> {
                title = Component.translatable("murim.tablet.contract.camp", Component.translatable("murim.contract.place." + o.countB()));
                where = Component.translatable("murim.tablet.contract.camp.where", Component.translatable("murim.contract.dir." + o.itemA()), o.countA());
            }
            case ESCORT -> {
                title = Component.translatable("murim.tablet.contract.escort");
                where = SectContracts.escortWhere(o);
            }
            default -> {
                title = Component.translatable("murim.tablet.contract.herbs", SectContracts.herbs(o));
                net.minecraft.network.chat.MutableComponent places = Component.empty();
                for (String habitat : SectContracts.habitats(o)) {
                    if (!places.getSiblings().isEmpty()) places.append(Component.translatable("murim.tablet.list_separator"));
                    places.append(Component.translatable("murim.tablet.habitat." + habitat));
                }
                where = Component.translatable("murim.tablet.contract.herbs.where", places);
            }
        }
        // What is paid out: the reward plus the office's bonus on merit income (SectPositions.incomeBonus).
        int merit = SectContracts.reward(o.kind(), standing, d.day, o.slot()).merit();
        merit += SectPositions.incomeBonus(state, merit);
        return new SectTabletData.Offer(o.slot(), o.kind().ordinal(), icon(o.kind()), title, where,
                o.kind() == SectContracts.Kind.CAMP ? SectContracts.MAX_GROUP : 0,
                merit, SectContracts.giverName(o),
                SectContracts.doneToday(d, p.getUUID(), o.slot()));
    }
}
