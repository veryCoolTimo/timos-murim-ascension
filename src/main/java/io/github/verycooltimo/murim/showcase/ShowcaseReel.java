package io.github.verycooltimo.murim.showcase;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.CultivationState;
import io.github.verycooltimo.murim.cultivation.MeditationService;
import io.github.verycooltimo.murim.cultivation.MeditationState;
import io.github.verycooltimo.murim.cultivation.MethodLoader;
import io.github.verycooltimo.murim.cultivation.PillService;
import io.github.verycooltimo.murim.cultivation.RankEffects;
import io.github.verycooltimo.murim.cultivation.Realm;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.LoadoutService;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.PillPayloads;
import io.github.verycooltimo.murim.network.ShowcasePayloads;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.MentorDemo;
import io.github.verycooltimo.murim.sect.SectArrival;
import io.github.verycooltimo.murim.sect.SectHeadIntro;
import io.github.verycooltimo.murim.sect.SectJourney;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.combat.TechniqueService;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * {@code /murim dev showcase}: an automatic reel for screenshots (author 14.11). The server plays the timeline of
 * {@link ShowcaseShots} from one tick counter, the client ({@code ShowcaseClient}) hides the HUD, flies the far camera and takes the
 * screenshots. Existing scenes only: the mentor's demonstration and talk, the head introduction, the rank breakthroughs and the
 * technique casts are started through the same entry points the game and its dev commands use.
 *
 * <p>Safety. A real world is changed by the scenes (rank, arts, items, flags), so before the first shot the whole player is saved as
 * NBT inside his own persistent data ({@link #KEY}); stop, the end of the reel and a logout put it back (attachments are cleared and
 * re-read, items, position, game mode, flags and the world's time, weather and game rules too). A crash mid-reel
 * leaves the backup in the player file; the next login restores it. The state lives in the player's persistent data, not in a
 * static field: the timeline is driven by {@link #onServerTick}, never by a self-rescheduling task.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class ShowcaseReel {

    /** Player persistent data: the running reel and the backup of the player. */
    public static final String KEY = "murim_showcase";
    /** Tag of every entity the reel spawns. */
    public static final String TAG = "murim_showcase";
    /** Ticks between the command and the first shot: the chat line (copy the world) stays readable before the HUD goes. */
    public static final int PRE_ROLL = 100;
    private static final int ARENA_ZONE_BT = 0;

    private ShowcaseReel() { }

    public static boolean running(ServerPlayer p) {
        return p.getPersistentData().contains(KEY, 10);
    }

    private static CompoundTag state(ServerPlayer p) {
        return p.getPersistentData().getCompound(KEY);
    }

    /** Some player in this level is playing the reel: the sect's alarm stays quiet. */
    public static boolean activeIn(ServerLevel level) {
        for (ServerPlayer p : level.players()) {
            if (running(p)) {
                return true;
            }
        }
        return false;
    }

    /** Shot the reel is on, -1 in the pre-roll; -2 when no reel runs. */
    public static int shotIndex(ServerPlayer p) {
        return running(p) ? state(p).getInt("shot") : -2;
    }

    // ---------------------------------------------------------------------------------------------------------- start / stop

    /** Starts the reel; false when one is already running. */
    public static boolean begin(ServerPlayer p, int holdTicks) {
        if (running(p)) {
            return false;
        }
        MinecraftServer server = p.getServer();
        ServerLevel level = p.serverLevel();
        if (p.getData(ModAttachments.MEDITATION).active()) {
            p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
        }
        p.stopRiding();
        CompoundTag backup = p.saveWithoutId(new CompoundTag());
        backup.remove("RootVehicle");
        CompoundTag st = new CompoundTag();
        st.put("backup", backup);
        st.putString("dim", level.dimension().location().toString());
        st.putInt("hold", Math.max(20, holdTicks));
        st.putInt("shot", -1);
        st.putInt("local", 0);
        st.putInt("pre", 0);
        st.putInt("seq", 0);
        st.putLong("time", level.getDayTime());
        st.putBoolean("daylight", level.getGameRules().getBoolean(GameRules.RULE_DAYLIGHT));
        st.putBoolean("mobs", level.getGameRules().getBoolean(GameRules.RULE_DOMOBSPAWNING));
        st.putBoolean("rain", level.isRaining());
        st.putBoolean("thunder", level.isThundering());
        p.getPersistentData().put(KEY, st);
        MurimMod.LOGGER.info("SHOWCASE-START hold={} ticks, {} shots, {} screenshots, about {} s", holdTicks,
                ShowcaseShots.plan().size(), ShowcaseShots.snapCount(), ShowcaseShots.totalTicks(holdTicks) / 20);
        p.setGameMode(GameType.SURVIVAL);
        p.setInvulnerable(true);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, server);
        level.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(false, server);
        level.setWeatherParameters(1_000_000, 0, false, false);
        return true;
    }

    /** Skips to the next shot. */
    public static boolean next(ServerPlayer p) {
        if (!running(p)) {
            return false;
        }
        CompoundTag st = state(p);
        int idx = st.getInt("shot");
        if (idx < 0) {
            enter(p, st, 0);
        } else {
            advance(p, st, idx);
        }
        return true;
    }

    /** Ends the reel: leaves the shot, removes what it spawned, puts the player and the world back. */
    public static boolean stop(ServerPlayer p, boolean finished) {
        if (!running(p)) {
            return false;
        }
        CompoundTag st = state(p);
        int idx = st.getInt("shot");
        List<ShowcaseShots.Shot> plan = ShowcaseShots.plan();
        if (idx >= 0 && idx < plan.size()) {
            try {
                exit(p, st, plan.get(idx));
            } catch (RuntimeException e) {
                MurimMod.LOGGER.warn("Showcase: leaving shot {} failed", idx, e);
            }
        }
        int snaps = st.getInt("seq");
        restore(p);
        send(p, ShowcasePayloads.Cue.of(ShowcasePayloads.Op.END, ""));
        ShowcaseCommand.say(p, finished ? "command.murim.dev.showcase.done" : "command.murim.dev.showcase.stopped", snaps);
        MurimMod.LOGGER.info("SHOWCASE-END {} screenshots, player restored", snaps);
        return true;
    }

    /** Puts everything back from the backup in the player's data. Also the crash recovery. */
    static void restore(ServerPlayer p) {
        CompoundTag st = state(p);
        MinecraftServer server = p.getServer();
        // The world first: what the reel spawned, then the clock, the weather, the rules.
        for (ServerLevel l : server.getAllLevels()) {
            java.util.List<Entity> all = new java.util.ArrayList<>();
            iterable(l).forEach(all::add);
            for (Entity e : all) {
                if (e.getTags().contains(TAG)) {
                    e.discard();
                }
            }
        }
        ServerLevel overworld = server.overworld();
        if (st.contains("time")) {
            run(p, "time set " + st.getLong("time"));
            overworld.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(st.getBoolean("daylight"), server);
            overworld.getGameRules().getRule(GameRules.RULE_DOMOBSPAWNING).set(st.getBoolean("mobs"), server);
            overworld.setWeatherParameters(st.getBoolean("rain") || st.getBoolean("thunder") ? 0 : 12000,
                    st.getBoolean("rain") || st.getBoolean("thunder") ? 12000 : 0, st.getBoolean("rain"), st.getBoolean("thunder"));
        }
        CompoundTag backup = st.getCompound("backup");
        p.removeAllEffects();
        // Attachments that were absent in the backup must go; the ones present are read back by the load below.
        for (var holder : ModAttachments.ATTACHMENT_TYPES.getEntries()) {
            p.removeData(holder.get());
        }
        p.load(backup);
        p.getPersistentData().remove(KEY);
        ListTag pos = backup.getList("Pos", 6);
        ListTag rot = backup.getList("Rotation", 5);
        ResourceLocation dim = ResourceLocation.tryParse(st.getString("dim"));
        ServerLevel target = dim == null ? p.serverLevel()
                : server.getLevel(ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION, dim));
        if (target == null) {
            target = p.serverLevel();
        }
        if (pos.size() == 3) {
            p.teleportTo(target, pos.getDouble(0), pos.getDouble(1), pos.getDouble(2), rot.getFloat(0), rot.getFloat(1));
        }
        p.setGameMode(GameType.byId(backup.getInt("playerGameType")));
        p.setInvulnerable(backup.getBoolean("Invulnerable"));
        RankEffects.apply(p);
        p.setHealth(Math.min(p.getMaxHealth(), backup.contains("Health") ? backup.getFloat("Health") : p.getMaxHealth()));
        p.onUpdateAbilities();
        p.inventoryMenu.broadcastFullState();
        p.containerMenu.broadcastFullState();
        ProfileNetwork.sync(p);
        MasteryService.sync(p);
        LoadoutService.sync(p);
        PillService.sync(p, PillPayloads.Event.NONE);
        MeditationService.sync(p, SyncMeditationPayload.Event.NONE);
        SectJourney.syncHud(p);
    }

    private static Iterable<Entity> iterable(ServerLevel level) {
        return level.getAllEntities();
    }

    /** Crash recovery: a reel that was active when the game closed is undone at the next login. */
    @SubscribeEvent
    static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            recover(p);
        }
    }

    /** Undoes a reel left in the player's data; false when there was none. */
    public static boolean recover(ServerPlayer p) {
        if (!running(p)) {
            return false;
        }
        restore(p);
        send(p, ShowcasePayloads.Cue.of(ShowcasePayloads.Op.END, ""));
        ShowcaseCommand.say(p, "command.murim.dev.showcase.recovered");
        MurimMod.LOGGER.info("SHOWCASE-RECOVER interrupted reel undone at login");
        return true;
    }

    /** Leaving the world ends the reel too (the player file is written after this event). */
    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && running(p)) {
            try {
                stop(p, false);
            } catch (RuntimeException e) {
                MurimMod.LOGGER.warn("Showcase: restore at logout failed, the backup stays in the player file", e);
            }
        }
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        for (ServerPlayer p : List.copyOf(event.getServer().getPlayerList().getPlayers())) {
            if (running(p)) {
                try {
                    tick(p);
                } catch (RuntimeException e) {
                    MurimMod.LOGGER.warn("Showcase: the reel failed, putting everything back", e);
                    stop(p, false);
                }
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- timeline

    /** One server tick of the reel. Public for the tests, which drive it by hand. */
    public static void tick(ServerPlayer p) {
        CompoundTag st = state(p);
        int idx = st.getInt("shot");
        if (idx < 0) {
            int pre = st.getInt("pre") + 1;
            st.putInt("pre", pre);
            if (pre >= PRE_ROLL) {
                enter(p, st, 0);
            }
            return;
        }
        List<ShowcaseShots.Shot> plan = ShowcaseShots.plan();
        if (idx >= plan.size()) {
            stop(p, true);
            return;
        }
        ShowcaseShots.Shot s = plan.get(idx);
        if (!st.getBoolean("ready")) {
            Readiness r = prepare(p, st, s);
            if (r == Readiness.SKIP) {
                MurimMod.LOGGER.info("SHOWCASE-SKIP {} (nothing to film here)", s.name());
                advance(p, st, idx);
            } else if (r == Readiness.READY) {
                st.putBoolean("ready", true);
                st.putInt("local", 0);
                ready(p, st, s);
            } else if (st.getInt("wait") + 1 > ShowcaseShots.WAIT_MAX) {
                MurimMod.LOGGER.info("SHOWCASE-SKIP {} (waited {} ticks for its people)", s.name(), ShowcaseShots.WAIT_MAX);
                advance(p, st, idx);
            } else {
                st.putInt("wait", st.getInt("wait") + 1);
            }
            return;
        }
        int local = st.getInt("local") + 1;
        st.putInt("local", local);
        run(p, st, s, local);
        if (!running(p)) {
            return;
        }
        if (local >= s.duration(st.getInt("hold"))) {
            advance(p, st, idx);
        }
    }

    private static void advance(ServerPlayer p, CompoundTag st, int idx) {
        List<ShowcaseShots.Shot> plan = ShowcaseShots.plan();
        if (idx >= 0 && idx < plan.size()) {
            exit(p, st, plan.get(idx));
        }
        if (idx + 1 >= plan.size()) {
            stop(p, true);
        } else {
            enter(p, st, idx + 1);
        }
    }

    private static void enter(ServerPlayer p, CompoundTag st, int idx) {
        ShowcaseShots.Shot s = ShowcaseShots.plan().get(idx);
        if (idx == 0) {
            send(p, ShowcasePayloads.Cue.of(ShowcasePayloads.Op.BEGIN, ""));
        }
        st.putInt("shot", idx);
        st.putInt("local", 0);
        st.putInt("wait", 0);
        st.putBoolean("ready", false);
        st.putBoolean("placed", false);
        st.putBoolean("done", false);
        st.remove("bandit");
        run(p, "time set " + s.dayTime());
        MurimMod.LOGGER.info("SHOWCASE-SHOT {}/{} {}", idx + 1, ShowcaseShots.plan().size(), s.name());
    }

    private static void exit(ServerPlayer p, CompoundTag st, ShowcaseShots.Shot s) {
        switch (s.kind()) {
            case MENTOR_TALK -> {
                SectDisciple m = find(p, "gyeong_pil");
                if (m != null) {
                    DialogueService.close(p, m);
                }
            }
            case HEAD_INTRO -> {
                SectLayout layout = SectLife.layout(p.serverLevel());
                if (SectHeadIntro.active(p)) {
                    SectHeadIntro.finish(p, layout);
                }
                SectArrival.cancelGuides(p);
            }
            case BREAKTHROUGH -> {
                if (p.getData(ModAttachments.MEDITATION).active()) {
                    p.setData(ModAttachments.MEDITATION, MeditationState.IDLE);
                    MeditationService.sync(p, SyncMeditationPayload.Event.NONE);
                }
            }
            case TECHNIQUE -> {
                p.setData(ModAttachments.TECHNIQUE_STATE, io.github.verycooltimo.murim.combat.TechniqueState.IDLE);
                discardBandit(p);
            }
            default -> { }
        }
        send(p, ShowcasePayloads.Cue.of(ShowcasePayloads.Op.FREE, ""));
    }

    private enum Readiness { WAIT, READY, SKIP }

    /** Puts the player and the people of the shot in place; READY when the camera may start. */
    private static Readiness prepare(ServerPlayer p, CompoundTag st, ShowcaseShots.Shot s) {
        SectLayout layout = SectLife.layout(p.serverLevel());
        ServerLevel level = p.serverLevel();
        switch (s.kind()) {
            case MENTOR_WIDE, MENTOR_DEMO, MENTOR_TALK -> {
                if (layout == null) {
                    return Readiness.SKIP;
                }
                SectDisciple m = find(p, "gyeong_pil");
                if (m == null) {
                    if (!st.getBoolean("placed")) {
                        teleport(p, spot(p, layout, "mentor", 0, 0), layout.yaw(0, 1));
                        st.putBoolean("placed", true);
                    }
                    return Readiness.WAIT;
                }
                if (!st.getBoolean("placed")) {
                    Vec3 fwd = forward(m.getYRot());
                    Vec3 guess = m.position().add(fwd.scale(2.6D));
                    Vec3 at = SectLife.stand(level, guess);
                    at = at == null ? guess : at;
                    teleport(p, at, yawTo(at, m.position()));
                    if (s.kind() == ShowcaseShots.Kind.MENTOR_DEMO) {
                        newcomer(p);
                    }
                    st.putBoolean("placed", true);
                    return Readiness.WAIT;
                }
                // a second of waiting lets the chunks and the people around the mentor settle before the camera looks
                return st.getInt("wait") >= 40 && grounded(p, st) ? Readiness.READY : Readiness.WAIT;
            }
            case HALL_WIDE -> {
                if (layout == null) {
                    return Readiness.SKIP;
                }
                if (!st.getBoolean("placed")) {
                    teleport(p, spot(p, layout, "main_hall", 0, -2), layout.yaw(0, 1));
                    st.putBoolean("placed", true);
                    return Readiness.WAIT;
                }
                return grounded(p, st) ? Readiness.READY : Readiness.WAIT;
            }
            case HEAD_INTRO -> {
                if (layout == null) {
                    return Readiness.SKIP;
                }
                // The dev command would spawn a stand-in for a missing person: wait until both live ones are loaded.
                SectDisciple head = find(p, "tae_hwi");
                if (find(p, "gyeong_pil") == null || head == null) {
                    if (!st.getBoolean("placed")) {
                        teleport(p, spot(p, layout, "main_hall", 0, -3), layout.yaw(0, 1));
                        st.putBoolean("placed", true);
                    }
                    return Readiness.WAIT;
                }
                // The hall must have been loaded and ticking with the player in it for a while: the head takes his seat only then
                // (the dev command stages him, the scene starts only when he sits at the seat).
                if (!st.getBoolean("placed")) {
                    teleport(p, spot(p, layout, "main_hall", 0, -3), layout.yaw(0, 1));
                    st.putBoolean("placed", true);
                    return Readiness.WAIT;
                }
                return st.getInt("wait") >= 100 ? Readiness.READY : Readiness.WAIT;
            }
            case BREAKTHROUGH, TECHNIQUE -> {
                if (!st.getBoolean("placed")) {
                    placeArena(p, st, s, layout);
                    st.putBoolean("placed", true);
                    return Readiness.WAIT;
                }
                return grounded(p, st) ? Readiness.READY : Readiness.WAIT;
            }
        }
        return Readiness.SKIP;
    }

    /** On the ground for one tick after the teleport (a sitting player must not "move"), or give up waiting after 30 ticks. */
    private static boolean grounded(ServerPlayer p, CompoundTag st) {
        int n = st.getInt("settle") + 1;
        st.putInt("settle", n);
        if (p.onGround() && n >= 2 || n >= 30) {
            st.putInt("settle", 0);
            return true;
        }
        return false;
    }

    /** The shot is ready: send the far camera (or give the camera to the cutscene). */
    private static void ready(ServerPlayer p, CompoundTag st, ShowcaseShots.Shot s) {
        SectLayout layout = SectLife.layout(p.serverLevel());
        if (s.own()) {
            send(p, ShowcasePayloads.Cue.of(ShowcasePayloads.Op.FREE, ""));
            return;
        }
        Vec3 focus;
        float base;
        switch (s.kind()) {
            case MENTOR_WIDE, MENTOR_DEMO -> {
                SectDisciple m = find(p, "gyeong_pil");
                Vec3 mid = m == null ? p.position() : p.position().add(m.position()).scale(0.5D);
                focus = mid.add(0.0D, 1.25D, 0.0D);
                base = m == null ? p.getYRot() : yawTo(p.position(), m.position());
            }
            case HALL_WIDE -> {
                Vec3 seat = layout == null ? null : layout.at("main_hall", 0, 2);
                focus = (seat == null ? p.position() : seat).add(0.0D, 1.6D, 0.0D);
                base = layout == null ? p.getYRot() : layout.yaw(0, 1);
            }
            default -> {
                // Breakthrough and technique arenas: the focus is the middle of the action, the camera looks along the arena's v axis.
                focus = st.contains("fx") ? new Vec3(st.getDouble("fx"), st.getDouble("fy"), st.getDouble("fz")) : p.position().add(0, 1, 0);
                base = st.getFloat("fyaw");
            }
        }
        send(p, new ShowcasePayloads.Cue(ShowcasePayloads.Op.SHOT, s.name(), focus.x, focus.y, focus.z, base + s.yawOff(), s.elev(),
                s.dist(), s.sweep(), s.dolly(), s.fov(), s.duration(st.getInt("hold"))));
    }

    /** Things that happen at ticks of a ready shot: the scene itself, then the screenshots. */
    private static void run(ServerPlayer p, CompoundTag st, ShowcaseShots.Shot s, int local) {
        switch (s.kind()) {
            case MENTOR_DEMO -> {
                if (local == 3) {
                    SectDisciple m = find(p, "gyeong_pil");
                    boolean ok = m != null && MentorDemo.start(p, m);
                    MurimMod.LOGGER.info("SHOWCASE-SCENE mentor demo started={}", ok);
                }
            }
            case MENTOR_TALK -> {
                if (local == 5) {
                    SectDisciple m = find(p, "gyeong_pil");
                    if (m != null) {
                        DialogueService.open(p, m);
                    }
                }
            }
            case HEAD_INTRO -> {
                if (local == 2 || (local == 8 && !SectHeadIntro.active(p))) {
                    run(p, "murim dev head intro");
                } else if (local == 14) {
                    MurimMod.LOGGER.info("SHOWCASE-SCENE head intro active={}", SectHeadIntro.active(p));
                }
            }
            case BREAKTHROUGH -> {
                if (local == ShowcaseShots.BT_PREP) {
                    startBreakthrough(p, s.target());
                }
            }
            case TECHNIQUE -> {
                if (local == ShowcaseShots.CAST_AT) {
                    ResourceLocation id = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, s.tech().form());
                    boolean ok = TechniqueService.tryStart(p, id);
                    MurimMod.LOGGER.info("SHOWCASE-SCENE cast {} accepted={}", s.tech().form(), ok);
                    if (!ok) {
                        st.putInt("local", s.duration(st.getInt("hold")));   // nothing to film: leave the shot
                    }
                }
            }
            default -> { }
        }
        int[] snaps = s.snaps();
        for (int i = 0; i < snaps.length; i++) {
            if (snaps[i] == local) {
                int seq = st.getInt("seq") + 1;
                st.putInt("seq", seq);
                String tag = s.snapTags().length > i ? "_" + s.snapTags()[i] : "";
                String label = String.format(java.util.Locale.ROOT, "%02d_%s%s", seq, s.name(), tag);
                int wait = s.kind() == ShowcaseShots.Kind.BREAKTHROUGH ? ShowcaseShots.BT_PEAK_AGES[i] : 0;
                send(p, new ShowcasePayloads.Cue(ShowcasePayloads.Op.SNAP, label, 0, 0, 0, 0, 0, 0, 0, 1, 1, wait));
                MurimMod.LOGGER.info("SHOWCASE-SHOT-SNAP {} -> screenshots/showcase_{}.png", s.name(), label);
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------- scenes

    /** A newcomer who holds the Six Harmonies method and has no dantian yet: the state the mentor's demonstration is offered in. */
    private static void newcomer(ServerPlayer p) {
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        p.setData(ModAttachments.CULTIVATION, CultivationState.NONE.withMethod(SectService.SIX));
        if (!SectService.state(p).member()) {
            SectService.join(p);
        }
    }

    /** The rank breakthrough into {@code target}: seeded dantian at the wall of the rank below, sitting, the scene started. */
    private static void startBreakthrough(ServerPlayer p, int target) {
        var method = MethodLoader.get(SectService.SIX);
        if (method == null) {
            MurimMod.LOGGER.warn("SHOWCASE-SCENE breakthrough: method {} missing", SectService.SIX);
            return;
        }
        p.setData(ModAttachments.PROFILE, DantianProfile.INITIAL);
        MeditationService.createSeed(p, method, 1.0D);
        DantianProfile ranked = p.getData(ModAttachments.PROFILE).withRank(target - 1).withStage(0);
        p.setData(ModAttachments.PROFILE, ranked.withPool(Realm.wall(ranked)).withCirculating(ranked.maxCirculating()));
        RankEffects.apply(p);
        p.setHealth(p.getMaxHealth());
        ProfileNetwork.sync(p);
        p.setData(ModAttachments.MEDITATION, MeditationState.started(false, CultivationState.SEEDED).withBreakthrough(0));
        MeditationService.sync(p, SyncMeditationPayload.Event.BREAKTHROUGH);
        MurimMod.LOGGER.info("SHOWCASE-SCENE breakthrough into rank {} started", target);
    }

    /** Other places to try when the shot's own zones have no safe ground. */
    private static final String[] FALLBACK_ZONES = {"training", "bell", "treasury", "poles", "arch", "mentor", "sect_gate", "dining"};

    /**
     * Is the ground flat, solid and open around {@code c}: at the centre, at half the radius and at the radius, a floor within a block
     * of the standing level with two free blocks above it, and nothing solid over the centre. A cliff edge, a stair shaft, a wall or a roof
     * fails (the player must not fall off or be pushed off while the casts and the knock-back play).
     */
    static boolean flatAround(ServerLevel level, Vec3 c, double radius) {
        for (double r : new double[] {0.0D, radius / 2.0D, radius}) {
            int n = r == 0.0D ? 1 : 12;
            for (int i = 0; i < n; i++) {
                double a = i * Math.PI * 2.0D / n;
                int x = Mth.floor(c.x + r * Math.cos(a));
                int z = Mth.floor(c.z + r * Math.sin(a));
                level.getChunk(x >> 4, z >> 4);   // loads the chunk: the zone may be far from where the player stands
                boolean floor = false;
                for (int dy = 1; dy >= -2 && !floor; dy--) {
                    net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(x, Mth.floor(c.y) + dy, z);
                    var shape = level.getBlockState(pos).getCollisionShape(level, pos);
                    if (!shape.isEmpty() && level.getBlockState(pos.above()).getCollisionShape(level, pos.above()).isEmpty()
                            && level.getBlockState(pos.above(2)).getCollisionShape(level, pos.above(2)).isEmpty()) {
                        double top = pos.getY() + shape.max(net.minecraft.core.Direction.Axis.Y);
                        floor = Math.abs(top - c.y) <= 1.0D;
                        break;
                    }
                }
                if (!floor) {
                    return false;
                }
            }
        }
        for (int dy = 2; dy <= 7; dy++) {
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(c.x, c.y + dy, c.z);
            if (!level.getBlockState(pos).getCollisionShape(level, pos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    /** The first safe arena: the shot's zones, then the fallbacks, each tried on a grid of places; null when nothing is safe. */
    private static Vec3 findArena(ServerPlayer p, SectLayout layout, ShowcaseShots.Shot s, double radius) {
        ServerLevel level = p.serverLevel();
        java.util.LinkedHashSet<String> zones = new java.util.LinkedHashSet<>(java.util.List.of(s.zones()));
        zones.addAll(java.util.List.of(FALLBACK_ZONES));
        for (String zone : zones) {
            double[] half = layout.half(zone);
            if (half == null || layout.at(zone, 0, 0) == null) {
                continue;
            }
            for (double fu : new double[] {0.0D, 0.5D, -0.5D}) {
                for (double fv : new double[] {0.0D, 0.5D, -0.5D}) {
                    Vec3 at = layout.at(zone, half[0] * fu, half[1] * fv);
                    Vec3 stand = at == null ? null : SectLife.stand(level, at);
                    if (stand != null && flatAround(level, stand, radius)) {
                        MurimMod.LOGGER.info("SHOWCASE-ARENA {} at zone {} ({}, {})", s.name(), zone, fu, fv);
                        return stand;
                    }
                }
            }
        }
        return null;
    }

    /** Where a breakthrough or a technique is filmed and where the camera looks; spawns the target of a technique. */
    private static void placeArena(ServerPlayer p, CompoundTag st, ShowcaseShots.Shot s, SectLayout layout) {
        boolean bt = s.kind() == ShowcaseShots.Kind.BREAKTHROUGH;
        double gap = bt ? 0.0D : s.tech().gap();
        double radius = bt && s.target() == Realm.PEAK ? 9.0D : 7.0D;
        Vec3 centre = layout == null ? null : findArena(p, layout, s, radius);
        if (centre == null) {
            // No sect here (a test) or no safe ground anywhere: stay where the reel started.
            centre = spot(p, layout, "training", 0, 0);
            MurimMod.LOGGER.info("SHOWCASE-ARENA {} no safe ground found, using the start place", s.name());
        }
        float faceU = yawOf(layout, 1, 0);
        Vec3 along = forward(faceU);
        Vec3 player = centre.subtract(along.scale(gap / 2.0D));
        Vec3 stand = SectLife.stand(p.serverLevel(), player);
        player = stand == null ? player : stand;
        teleport(p, player, faceU);
        Vec3 mid = player;
        if (!bt) {
            Vec3 enemy = centre.add(along.scale(gap / 2.0D));
            Vec3 es = SectLife.stand(p.serverLevel(), enemy);
            enemy = es == null ? enemy : es;
            mid = player.add(enemy).scale(0.5D);
            prepareTechnique(p, s);
            spawnBandit(p, st, enemy, faceU + 180.0F);
        } else {
            mid = player.add(0.0D, -0.2D, 0.0D);
        }
        st.putDouble("fx", mid.x);
        st.putDouble("fy", mid.y + (bt ? 1.35D : 1.25D));
        st.putDouble("fz", mid.z);
        // the camera yaw is relative to the way the player faces
        st.putFloat("fyaw", faceU);
    }

    private static void prepareTechnique(ServerPlayer p, ShowcaseShots.Shot s) {
        ShowcaseShots.Tech t = s.tech();
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE));
        run(p, "murim dev art stars set murim:" + t.art() + " 12");
        run(p, "murim dev art cooldown");
        run(p, "murim dev body qi");
        p.getInventory().setItem(p.getInventory().selected,
                t.sword() ? new ItemStack(ModItems.HUASHAN_SWORD.get()) : ItemStack.EMPTY);
        p.setHealth(p.getMaxHealth());
    }

    private static void spawnBandit(ServerPlayer p, CompoundTag st, Vec3 at, float yaw) {
        ServerLevel level = p.serverLevel();
        // A training dummy, not a bandit: the sect's guards run to fight a bandit in their yard and walk into the frame.
        var b = new io.github.verycooltimo.murim.entity.TrainingDummy(ModEntities.DUMMY.get(), level);
        b.addTag(TAG);
        b.moveTo(at.x, at.y, at.z, yaw, 0.0F);
        boolean added = level.addFreshEntity(b);
        MurimMod.LOGGER.info("SHOWCASE-SCENE target dummy spawned={}", added);
        if (added) {
            st.putUUID("bandit", b.getUUID());
        }
    }

    private static void discardBandit(ServerPlayer p) {
        CompoundTag st = state(p);
        if (st.hasUUID("bandit") && p.serverLevel().getEntity(st.getUUID("bandit")) instanceof Entity e) {
            e.discard();
        }
        st.remove("bandit");
    }

    // ---------------------------------------------------------------------------------------------------------- helpers

    private static SectDisciple find(ServerPlayer p, String key) {
        SectDisciple best = null;
        for (SectDisciple d : p.serverLevel().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(96.0D),
                d -> d.isAlive() && key.equals(d.memberKey()))) {
            if (best == null || d.distanceToSqr(p) < best.distanceToSqr(p)) {
                best = d;
            }
        }
        return best;
    }

    /** A place of the sect, or (no sect here, as in a test) the same offset from where the reel started. */
    private static Vec3 spot(ServerPlayer p, SectLayout layout, String zone, double du, double dv) {
        Vec3 at = layout == null ? null : layout.at(zone, du, dv);
        if (at == null) {
            CompoundTag pos = state(p).getCompound("backup");
            ListTag l = pos.getList("Pos", 6);
            Vec3 start = l.size() == 3 ? new Vec3(l.getDouble(0), l.getDouble(1), l.getDouble(2)) : p.position();
            return start.add(du, 0.0D, dv);
        }
        Vec3 stand = SectLife.stand(p.serverLevel(), at);
        return stand == null ? at : stand;
    }

    private static float yawOf(SectLayout layout, double du, double dv) {
        return layout != null ? layout.yaw(du, dv) : (float) Math.toDegrees(Math.atan2(-du, dv));
    }

    private static Vec3 forward(float yaw) {
        double a = Math.toRadians(yaw);
        return new Vec3(-Math.sin(a), 0.0D, Math.cos(a));
    }

    private static float yawTo(Vec3 from, Vec3 to) {
        return (float) (Mth.atan2(to.z - from.z, to.x - from.x) * Mth.RAD_TO_DEG) - 90.0F;
    }

    private static void teleport(ServerPlayer p, Vec3 at, float yaw) {
        p.teleportTo(p.serverLevel(), at.x, at.y, at.z, yaw, 0.0F);
        p.setOnGround(false);
    }

    private static void run(ServerPlayer p, String command) {
        MinecraftServer server = p.getServer();
        CommandSourceStack source = p.createCommandSourceStack().withPermission(4).withSuppressedOutput();
        server.getCommands().performPrefixedCommand(source, command);
    }

    private static void send(ServerPlayer p, ShowcasePayloads.Cue cue) {
        io.github.verycooltimo.murim.network.ChannelSafe.send(p, cue);
    }
}
