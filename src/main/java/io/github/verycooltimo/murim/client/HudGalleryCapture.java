package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.network.SyncMeditationPayload;
import io.github.verycooltimo.murim.client.training.ClientTraining;
import io.github.verycooltimo.murim.training.TrainingPayloads;
import java.util.List;
import net.minecraft.client.CameraType;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/**
 * Dev stand: a gallery of every HUD line and book/dialogue screen at GUI scale 2 and 3, one frame each
 * ({@code screenshots/gallery_<scale>_<scene>.png}). Language and window size come from {@code run/options.txt}
 * ({@code lang}, {@code overrideWidth/Height}) — {@code tools/capture/hud_gallery.sh} runs the four combinations.
 *
 * <p>Client states are fed with the same payload records the server sends, so the HUD code under test is the real one;
 * nothing here changes server state. Only active with {@code -Dmurim.capture=true} and subject {@code hudgallery}.
 *
 * <p>API: reference/minecraft-src/net/minecraft/client/Minecraft.java#resizeDisplay (GUI scale change applies at once),
 * net/minecraft/client/Screenshot.java#grab.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class HudGalleryCapture {

    public static final String SUBJECT = "hudgallery";
    private static final boolean ON = Boolean.getBoolean("murim.capture")
            && SUBJECT.equals(System.getProperty("murim.capture.technique"));

    private static final List<String> ALL_SCENES = List.of("hud", "training", "meditation", "loadout", "six_forms_wheel", "six_forms_loadout", "manual", "junk", "dialogue",
            "contract_tracker", "tablet_today", "tablet_goal", "tablet_contracts", "tablet_standing", "tablet_goal_elder",
            "tablet_standing_elder", "board_open", "board_taken", "board_newcomer", "board_invite", "board_ready", "board_tablet_free",
            "board_tablet_near", "board_tablet_far", "board_tablet_held",
            // The REAL tablet in a generated world (docs/adr/0016): joins the sect, walks to the site given by MURIM_TABLET_AT and
            // MURIM_TABLET_BOARD ("x y z"), lets the server chart the map and opens the tablet by the server's own command. No demo data.
            "tablet_real_prep1", "tablet_real_prep2", "tablet_real_prep3", "tablet_real_prep4",
            "tablet_real_today", "tablet_real_goal", "tablet_real_contracts_far", "tablet_real_contracts_board",
            "tablet_real_standing_live", "tablet_real_map", "tablet_real_map_live",
            "guide", "guide_plate", "tablet_today_drill", "tooltip_pills", "note_head", "note_clue", "note_junk", "tablet_journal",
            "scroll_six_harmonies", "scroll_mountain_root", "scroll_reverse_breathing",
            "scroll_holy_fire_breath", "scroll_tang_poison_breath", "scroll_street_strength",
            "scroll_six_harmonies_opening",
            // Every Murim achievement on one sheet (FilmAdvancements): the vanilla tab shows only a few without scrolling.
            "advancements");
    /** {@code MURIM_GALLERY_ONLY=tablet} keeps only the scenes whose name starts with it (a quick tablet pass). */
    private static final List<String> SCENES = ALL_SCENES.stream()
            .flatMap(scene -> "manual".equals(scene) ? manualScenes() : java.util.stream.Stream.of(scene))
            .filter(scene -> java.util.Arrays.stream(System.getenv().getOrDefault("MURIM_GALLERY_ONLY", "").split(","))
                    .anyMatch(scene::startsWith)).toList();
    /** Ticks per scene: set up at 0, frame at {@link #SHOT}, tear down at the end. */
    private static final int SPAN = 170;
    private static final int SHOT = 160;
    private static final int WARMUP = 120;

    /**
     * {@code MURIM_CAPTURE_MANUAL_PAGES=0-8} (or {@code 0,3,5}) films the book page by page: scenes {@code manual_p0}..
     * (book of {@code MURIM_CAPTURE_MANUAL}); without it the single {@code manual} scene shows the first spread.
     * {@code MURIM_CAPTURE_MANUAL_BOOKS=six_harmonies,demon_palm} films several books in one client run: scenes
     * {@code manual_<book>__p<n>} (the pages are then {@code MURIM_CAPTURE_MANUAL_PAGES}, default 0-1).
     */
    private static java.util.stream.Stream<String> manualScenes() {
        String pages = System.getenv().getOrDefault("MURIM_CAPTURE_MANUAL_PAGES", "").trim();
        String books = System.getenv().getOrDefault("MURIM_CAPTURE_MANUAL_BOOKS", "").trim();
        if (pages.isEmpty() && books.isEmpty()) {
            return java.util.stream.Stream.of("manual");
        }
        if (pages.isEmpty()) {
            pages = "0-1";
        }
        List<String> out = new java.util.ArrayList<>();
        for (String book : books.isEmpty() ? new String[] {""} : books.split(",")) {
            for (String part : pages.split(",")) {
                String[] range = part.trim().split("-");
                int from = Integer.parseInt(range[0].trim());
                int to = range.length > 1 ? Integer.parseInt(range[1].trim()) : from;
                for (int n = from; n <= to; n++) {
                    out.add(book.isBlank() ? "manual_p" + n : "manual_" + book.trim() + "__p" + n);
                }
            }
        }
        return out.stream();
    }

    private static int tick = -WARMUP;
    /** The client-only person of the {@code guide_plate} scene. */
    private static io.github.verycooltimo.murim.entity.SectDisciple plateNpc;
    private static boolean done;
    private static int realWait;
    private static volatile String realFailure;

    private HudGalleryCapture() {
    }

    public static boolean active() {
        return ON;
    }

    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (!ON || done) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (tick < 0) {
            tick++;
            if (tick == -WARMUP + 2) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
                // The early loading window ignores options.txt overrideWidth; resize here (MURIM_GALLERY_SIZE=1920x1080).
                // API: com/mojang/blaze3d/platform/Window.java#setWindowed (neoforge sources jar).
                String[] size = System.getenv().getOrDefault("MURIM_GALLERY_SIZE", "").split("x");
                if (size.length == 2) {
                    mc.getWindow().setWindowed(Integer.parseInt(size[0].trim()), Integer.parseInt(size[1].trim()));
                }
            }
            return;
        }
        // The first scene of the first pass used to start one tick late (post-increment) and skip its setUp.
        final int now = tick++;
        int[] scales = mc.getWindow().getWidth() >= 1280 ? new int[] {2, 3, 4} : new int[] {2};
        if ("2".equals(System.getenv("MURIM_GALLERY_SCALE"))) scales = new int[] {2};
        int perScale = SCENES.size() * SPAN;
        int si = now / perScale;
        if (si >= scales.length) {
            done = true;
            MurimMod.LOGGER.info("HUD gallery done");
            mc.stop();
            return;
        }
        int local = now % perScale;
        String scene = SCENES.get(local / SPAN);
        int t = local % SPAN;
        if (local == 0) {
            mc.options.guiScale().set(scales[si]);
            mc.resizeDisplay();
        }
        if (t == 0) {
            realWait = 0;
            setUp(mc, scene);
        }
        if (scene.startsWith("six_forms_") && t <= SHOT) {
            // Reassert the client fixture against periodic server syncs; never write it to the server.
            sixFormsState();
            if (scene.equals("six_forms_wheel")) {
                ModKeyMappings.WHEEL.setDown(true);
                if (t > 8) TechniqueWheel.setCursor(0, -29);
            }
        }
        if (scene.startsWith("manual") && t < SHOT && !ClientProfileState.profile().isAwakened()) {
            // The stand player has no dantian (the server re-syncs that profile): the book shows its "Learn" seal.
            ClientProfileState.setProfile(io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("calm", "none")
                    .withPool(12.4D).withCirculating(3.1D));
        }
        if ("meditation".equals(scene) && t > 0) {
            // Seated widget is the game HUD, not the dantian ceremony: keep the play camera and the GUI.
            mc.options.hideGui = false;
            mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            // The server re-syncs the stand player's (unawakened) profile; the seated widget needs a dantian.
            if (!ClientProfileState.profile().isAwakened()) {
                ClientProfileState.setProfile(io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("calm", "none")
                        .withPool(12.4D).withCirculating(3.1D));
            }
        }
        if (scene.startsWith("tablet_real")) {
            realTabletStep(mc, scene, t);
        }
        if (t > 0 && t < SHOT && "training".equals(scene) && t % 12 == 0) {
            beat(mc, t);
        }
        if (t == SHOT && "meditation".equals(scene)) {
            MurimMod.LOGGER.info("HUD gallery meditation: active={} beats={} awakened={} absorbing={} break={} rank={} warn={} hide={}",
                    ClientMeditationState.state().active(), ClientMeditationState.state().beats(),
                    ClientProfileState.profile().isAwakened(), ClientPillState.absorbing(), ClientMeditationState.breakthroughAge(),
                    ClientMeditationState.rankUpAge(), ClientMeditationState.warningAge(), mc.options.hideGui);
        }
        if (t == SHOT && scene.startsWith("tablet_real")) {
            if (realFailure != null) throw new IllegalStateException(realFailure);
            int expected = realTab(scene);
            boolean ready = mc.screen instanceof io.github.verycooltimo.murim.client.sect.SectTabletScreen tablet
                    && tablet.captureReady(expected);
            if (!ready) {
                if (++realWait > 600) throw new IllegalStateException("Tablet gallery timed out: " + scene);
                tick--; // Pause the scenario clock, not the client/server: wait for data and the map texture.
                if (realWait % 40 == 0) command(mc, "murim dev sect tablet");
                return;
            }
        }
        // Park before SHOT, leaving at least one rendered frame before grabbing the framebuffer.
        // This includes real-data tablet scenes.
        // Window handle and cursor coordinates follow MouseHandler#setup/onMove (1.21.1 sources).
        if (t > 0 && t < SHOT && (scene.startsWith("tablet") || scene.startsWith("board")) && mc.screen != null) {
            org.lwjgl.glfw.GLFW.glfwSetCursorPos(mc.getWindow().getWindow(), 1 + (t % 2), 1);
            // Alternate by one pixel so GLFW emits a move even after Screen recenters the mouse.
            // API: minecraft-src/.../gui/components/events/GuiEventListener.java#mouseMoved.
            mc.screen.mouseMoved(0, 0);
        }
        if (t == SHOT && scene.startsWith("scroll_") && !scene.endsWith("_opening")
                && mc.screen instanceof io.github.verycooltimo.murim.client.library.MethodScrollScreen scroll
                && !scroll.unrolled()) {
            tick--; // Wall-clock animation can outlast the scene ticks on a fast capture client.
            return;
        }
        if (t == SHOT) {
            String name = String.format("gallery_%d_%s.png", scales[si], scene);
            Screenshot.grab(mc.gameDirectory, name, mc.getMainRenderTarget(), message -> {
            });
        }
        if (t == SPAN - 1) {
            tearDown(mc, scene);
        }
    }

    /** {@code MURIM_TABLET_AT} / {@code MURIM_TABLET_BOARD} as "x y z" (blocks): where the sect stands and where its board does. */
    private static String where(String variable) {
        String value = System.getenv(variable);
        if (value == null || !value.trim().matches("-?\\d+\\s+-?\\d+\\s+-?\\d+"))
            throw new IllegalArgumentException("Set " + variable + " to the real sect coordinates: x y z");
        return value.trim();
    }

    /** Stands the player on the board's spot ({@code MURIM_TABLET_BOARD}); the server has placed the board there by itself. */
    private static void toBoard(Minecraft mc) {
        String[] c = where("MURIM_TABLET_BOARD").trim().split("\\s+");
        command(mc, "tp @s " + c[0] + " " + (Integer.parseInt(c[1]) + 1) + " " + c[2]);
    }

    private static void command(Minecraft mc, String command) {
        // Capture is opt-in and local only. A copied world's cheats flag must not decide whether fixtures work.
        // API: reference/minecraft-src/net/minecraft/client/Minecraft.java#getSingleplayerServer;
        // util/thread/BlockableEventLoop.java#execute, commands/CommandSourceStack.java#withPermission,
        // commands/Commands.java#getDispatcher; server/players/PlayerList.java#getPlayer.
        var server = mc.getSingleplayerServer();
        if (server == null) throw new IllegalStateException("Real tablet capture requires an integrated server");
        var playerId = mc.player.getUUID();
        server.execute(() -> {
            var player = server.getPlayerList().getPlayer(playerId);
            if (player == null) { realFailure = "Capture player disconnected"; return; }
            if (command.equals("murim dev sect join") && io.github.verycooltimo.murim.sect.SectService.state(player).member()) return;
            try {
                var source = player.createCommandSourceStack().withPermission(4);
                var parsed = server.getCommands().getDispatcher().parse(command, source);
                if (parsed.getReader().canRead() || !parsed.getExceptions().isEmpty())
                    throw new IllegalArgumentException("Command did not parse: " + command);
                server.getCommands().performPrefixedCommand(source.withCallback((success, result) -> {
                    if (!success && !command.endsWith(" spawn") && !command.endsWith(" settle")) realFailure = "Capture command returned failure: " + command;
                }), command);
            } catch (IllegalArgumentException failure) {
                realFailure = "Capture command failed: " + command + ": " + failure.getMessage();
            }
        });
    }

    /**
     * Steps of the real-data tablet scenes. The prep scenes join the sect, walk to it and let the server chart the map; the other
     * scenes open the tablet with the server's command (the same snapshot the item sends) and look at one tab; the live scenes
     * change the world while the screen is open: merit is granted, the player is moved.
     */
    private static void realTabletStep(Minecraft mc, String scene, int t) {
        var screen = mc.screen;
        if (t >= 120) {
            io.github.verycooltimo.murim.client.sect.SectTabletScreen.showTab(screen, realTab(scene));
            if (!(screen instanceof io.github.verycooltimo.murim.client.sect.SectTabletScreen) && t % 20 == 0)
                command(mc, "murim dev sect tablet");
        }
        switch (scene) {
            case "tablet_real_prep1" -> {
                if (t == 2) {
                    command(mc, "gamemode creative");
                    command(mc, "gamerule doDaylightCycle false");
                    command(mc, "weather clear");
                    command(mc, "time set night");
                    command(mc, "murim dev sect join");
                    command(mc, "tp @s " + where("MURIM_TABLET_AT"));
                }
                if (t == 40) {
                    // the board is open to a disciple: the contracts tab beside it lists the day's offers
                    command(mc, "murim dev sect standing disciple");
                }
                if (t == 60) {
                    // the roster stands in the loaded chunks, so the goal's "where" line has a real person to read
                    command(mc, "murim dev sched spawn");
                }
                if (t == 80) {
                    command(mc, "murim dev sched settle");
                }
            }
            case "tablet_real_prep2", "tablet_real_prep3", "tablet_real_prep4" -> {
                if (t == 5 && scene.equals("tablet_real_prep2")) command(mc, "murim dev map rebuild");
            }
            case "tablet_real_contracts_board", "tablet_real_map" -> {
                if (t == 1) {
                    toBoard(mc);
                }
                if (t == 40) {
                    command(mc, "murim dev map rebuild");
                }
                if (t == 60) {
                    command(mc, "murim dev sect tablet");
                }
                if (t == 70) {
                    io.github.verycooltimo.murim.client.sect.SectTabletScreen.showTab(screen,
                            scene.endsWith("map") ? io.github.verycooltimo.murim.network.SectTabletView.Tab.MAP.ordinal()
                                    : io.github.verycooltimo.murim.network.SectTabletView.Tab.CONTRACTS.ordinal());
                    mc.gui.getChat().clearMessages(false);
                }
            }
            case "tablet_real_standing_live" -> {
                if (t == 5) {
                    command(mc, "murim dev sect tablet");
                }
                if (t == 15) {
                    io.github.verycooltimo.murim.client.sect.SectTabletScreen.showTab(screen, io.github.verycooltimo.murim.network.SectTabletView.Tab.STANDING.ordinal());
                    mc.gui.getChat().clearMessages(false);
                }
                if (t == 60) {
                    // the screen stays open: the server pushes the new balance
                    command(mc, "murim dev sect merit 37");
                }
            }
            case "tablet_real_map_live" -> {
                if (t == 1) {
                    toBoard(mc);
                }
                if (t == 30) {
                    command(mc, "murim dev sect tablet");
                }
                if (t == 40) {
                    io.github.verycooltimo.murim.client.sect.SectTabletScreen.showTab(screen, io.github.verycooltimo.murim.network.SectTabletView.Tab.MAP.ordinal());
                    mc.gui.getChat().clearMessages(false);
                }
                if (t == 80) {
                    // walk away with the tablet open: the marker moves without reopening it
                    command(mc, "tp @s " + where("MURIM_TABLET_AT"));
                }
            }
            default -> {
                // today, goal, contracts_far: the tablet opens where the player stands (the prep spot, far from the board)
                if (t == 5) {
                    command(mc, "murim dev sect tablet");
                }
                if (t == 15) {
                    var tab = scene.endsWith("today") ? io.github.verycooltimo.murim.network.SectTabletView.Tab.TODAY
                            : scene.endsWith("goal") ? io.github.verycooltimo.murim.network.SectTabletView.Tab.GOAL
                            : io.github.verycooltimo.murim.network.SectTabletView.Tab.CONTRACTS;
                    io.github.verycooltimo.murim.client.sect.SectTabletScreen.showTab(screen, tab.ordinal());
                    mc.gui.getChat().clearMessages(false);
                }
            }
        }
    }

    private static int realTab(String scene) {
        if (scene.contains("map") || scene.startsWith("tablet_real_prep")) return io.github.verycooltimo.murim.network.SectTabletView.Tab.MAP.ordinal();
        if (scene.endsWith("standing_live")) return io.github.verycooltimo.murim.network.SectTabletView.Tab.STANDING.ordinal();
        if (scene.endsWith("goal")) return io.github.verycooltimo.murim.network.SectTabletView.Tab.GOAL.ordinal();
        if (scene.endsWith("today")) return io.github.verycooltimo.murim.network.SectTabletView.Tab.TODAY.ordinal();
        return io.github.verycooltimo.murim.network.SectTabletView.Tab.CONTRACTS.ordinal();
    }

    private static void setUp(Minecraft mc, String scene) {
        if (scene.startsWith("scroll_")) {
            boolean opening = scene.endsWith("_opening");
            String method = scene.substring("scroll_".length());
            if (opening) method = method.substring(0, method.length() - "_opening".length());
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/resources/ResourceLocation.java#fromNamespaceAndPath.
            // Same title/cost payload as MethodScrollItem.use; no method learning or server mutation.
            String key = "method.murim." + method;
            var payload = new io.github.verycooltimo.murim.library.LibraryNetwork.OpenMethodScroll(
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, method), key, List.of(key + ".cost"),
                    new io.github.verycooltimo.murim.library.LibraryNetwork.MethodState(
                            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, method), "can", "", ""));
            if (opening) {
                io.github.verycooltimo.murim.client.library.MethodScrollScreen.openForCapture(payload, 300_000_000L);
            } else {
                io.github.verycooltimo.murim.client.library.MethodScrollScreen.open(payload);
            }
            return;
        }
        switch (scene) {
            case "contract_tracker" -> io.github.verycooltimo.murim.client.sect.ContractTracker.accept(io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo());
            case "tooltip_pills" -> mc.setScreen(new FilmPillTooltips());
            case "advancements" -> mc.setScreen(new FilmAdvancements());
            case "note_head" -> io.github.verycooltimo.murim.library.LibraryClientBridge.openText(
                    new io.github.verycooltimo.murim.library.LibraryNetwork.OpenText(
                            Component.translatable(io.github.verycooltimo.murim.library.HeadNoteItem.TITLE),
                            io.github.verycooltimo.murim.library.HeadNoteItem.PAGES.stream().map(k -> (Component) Component.translatable(k)).toList()));
            case "note_clue" -> io.github.verycooltimo.murim.library.LibraryClientBridge.openText(
                    new io.github.verycooltimo.murim.library.LibraryNetwork.OpenText(
                            Component.translatable(io.github.verycooltimo.murim.quest.Clues.titleKey("belt_note")),
                            List.of(Component.translatable(io.github.verycooltimo.murim.quest.Clues.textKey("belt_note")))));
            case "note_junk" -> {
                var payload = new io.github.verycooltimo.murim.library.LibraryNetwork.OpenJunk(
                        new io.github.verycooltimo.murim.library.JunkBook(io.github.verycooltimo.murim.library.JunkKind.LOVE, 1030), true);
                io.github.verycooltimo.murim.library.LibraryClientBridge.openJunk(payload.book(), payload.mainHand());
            }
            case "tablet_journal" -> io.github.verycooltimo.murim.library.LibraryClientBridge.openText(
                    new io.github.verycooltimo.murim.library.LibraryNetwork.OpenText(
                            Component.translatable("murim.tablet.journal.title"), List.of(
                            Component.translatable("murim.vault.rite.silent"),
                            Component.translatable("murim.clue.have", Component.translatable(io.github.verycooltimo.murim.quest.Clues.titleKey("belt_note"))),
                            Component.translatable("murim.meditation.held"),
                            Component.translatable("murim.training.end.carry", 13, "4.2"))));
            case "hud" -> {
                CombatMode.engage();
                // Chat sits over the lower-left HUD; two typical lines show the overlap.
                mc.player.displayClientMessage(Component.translatable("murim.training.end.carry", 13, "4.2"), false);
                mc.player.displayClientMessage(Component.translatable("murim.meditation.held"), false);
            }
            case "training" -> {
                ClientTraining.onBody(new TrainingPayloads.Body(3, 0.62F, 0, 0));
                beat(mc, 0);
            }
            case "meditation" -> {
                ClientProfileState.setProfile(ClientProfileState.profile().isAwakened() ? ClientProfileState.profile()
                        : io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("calm", "none")
                        .withPool(12.4D).withCirculating(3.1D));
                ClientMeditationState.accept(new SyncMeditationPayload(true, 3, 400, SyncMeditationPayload.Event.NONE,
                        SyncMeditationPayload.Ring.NONE, -1, ClientProfileState.profile().rank()));
                ClientPlaceState.forceForCapture(new io.github.verycooltimo.murim.world.Place(mc.player.blockPosition(),
                        io.github.verycooltimo.murim.world.PlaceKind.values()[0], false));
            }
            case "six_forms_wheel", "six_forms_loadout" -> {
                sixFormsState();
                if (scene.equals("six_forms_loadout")) mc.setScreen(new LoadoutScreen());
                else {
                    mc.setScreen(null);
                    ModKeyMappings.WHEEL.setDown(true);
                }
            }
            case "loadout" -> {
                // MURIM_GALLERY_FRESH=1: a fresh player who knows only the Six Elements Sword (K screen: owned kinds only, 22.10).
                if ("1".equals(System.getenv("MURIM_GALLERY_FRESH"))) {
                    ClientLoadoutState.reset();
                    ClientMasteryState.accept(new io.github.verycooltimo.murim.network.SyncMasteryPayload(java.util.List.of(
                            new io.github.verycooltimo.murim.network.SyncMasteryPayload.Entry(
                                    io.github.verycooltimo.murim.mastery.ArtSlots.SIX, 1, 12, 0.3F, 0.0F))));
                }
                mc.setScreen(new LoadoutScreen());
            }
            case "guide_plate" -> {
                // A person of the sect three blocks ahead, the player looking at him: the name plate, and the mote of the goal.
                var npc = new io.github.verycooltimo.murim.entity.SectDisciple(io.github.verycooltimo.murim.registry.ModEntities.SECT_DISCIPLE.get(), mc.level);
                npc.setMember(io.github.verycooltimo.murim.sect.SectRoster.of("gyeong_pil").orElseThrow());
                npc.moveTo(mc.player.getX(), mc.player.getY(), mc.player.getZ() + 5.0D, 180.0F, 0.0F);
                mc.player.setYRot(0.0F);
                mc.player.setXRot(8.0F);
                mc.level.addEntity(npc);
                plateNpc = npc;
                io.github.verycooltimo.murim.client.sect.SectGuideHud.accept(new io.github.verycooltimo.murim.network.SectGuidePayload(List.of(
                        new io.github.verycooltimo.murim.network.SectGuidePayload.Entry(true, "take_six", Component.translatable("murim.sect.journey.goal.take_six"),
                                Component.translatable("npc.murim.gyeong_pil"), Component.translatable("murim.role.person.gyeong_pil"),
                                Component.translatable("murim.sect.place.lecture_hall"), npc.getX(), npc.getY(), npc.getZ(), npc.getId()))));
            }
            case "guide" -> {
                // The goal's person ahead and to the right, a daily task's person to the left: the same record the server sends.
                net.minecraft.world.phys.Vec3 p = mc.player.position();
                mc.player.setYRot(0.0F);
                io.github.verycooltimo.murim.client.sect.SectGuideHud.accept(new io.github.verycooltimo.murim.network.SectGuidePayload(List.of(
                        new io.github.verycooltimo.murim.network.SectGuidePayload.Entry(true, "take_six", Component.translatable("murim.sect.journey.goal.take_six"),
                                Component.translatable("npc.murim.gyeong_pil"), Component.translatable("murim.role.person.gyeong_pil"),
                                Component.translatable("murim.sect.place.lecture_hall"), p.x + 22.0D, p.y, p.z + 6.0D, -1),
                        new io.github.verycooltimo.murim.network.SectGuidePayload.Entry(false, "contract", Component.translatable("murim.guide.task.contract", "Herbs"),
                                Component.translatable("npc.murim.tae_gyun"), Component.translatable("murim.role.person.tae_gyun"),
                                Component.empty(), p.x - 40.0D, p.y, p.z + 30.0D, -1))));
            }
            case "manual" -> ManualScreen.open(new io.github.verycooltimo.murim.network.ManualPayloads.Open(
                    ResourceLocation.fromNamespaceAndPath(MurimMod.MODID,
                            System.getenv().getOrDefault("MURIM_CAPTURE_MANUAL", "six_harmonies")), 0, "basic", true));
            case "junk" -> io.github.verycooltimo.murim.client.library.JunkBookScreen.open(
                    io.github.verycooltimo.murim.library.JunkFactory.roll(net.minecraft.util.RandomSource.create(7L)), true);
            case "dialogue" -> io.github.verycooltimo.murim.client.sect.DialogueScreen.open(
                    new io.github.verycooltimo.murim.network.DialoguePayloads.Open(mc.player.getId(),
                            Component.translatable("murim.gallery.npc"), Component.translatable("murim.gallery.npc_title"),
                            Component.translatable("murim.gallery.line"),
                            List.of(Component.translatable("murim.gallery.option1"), Component.translatable("murim.gallery.option2"),
                                    Component.translatable("murim.gallery.option3")), ""));
            case "tablet_today" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo(), 0);
            case "tablet_today_drill" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoDrill(), 0);
            case "tablet_goal" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo(), 1);
            case "tablet_contracts" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo(), 2);
            case "tablet_standing" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo(), 3);
            case "tablet_goal_elder" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo(2), 1);
            case "tablet_standing_elder" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demo(2), 3);
            case "board_open" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openBoardForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(0));
            case "board_taken" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openBoardForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(1));
            case "board_newcomer" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openBoardForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(2));
            case "board_invite" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openBoardForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(3));
            case "board_ready" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openBoardForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(4));
            case "board_tablet_free" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(0), 2);
            case "board_tablet_near" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(1), 2);
            case "board_tablet_far" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(5), 2);
            case "board_tablet_held" -> io.github.verycooltimo.murim.client.sect.SectTabletScreen.openForCapture(
                    io.github.verycooltimo.murim.client.sect.SectTabletDemo.demoBoard(6), 2);
            default -> {
                if (scene.startsWith("manual_")) {
                    int sep = scene.indexOf("__p");
                    String id = sep < 0 ? System.getenv().getOrDefault("MURIM_CAPTURE_MANUAL", "six_harmonies")
                            : scene.substring("manual_".length(), sep);
                    int pageNo = Integer.parseInt(scene.substring(sep < 0 ? "manual_p".length() : sep + 3));
                    ManualScreen.open(new io.github.verycooltimo.murim.network.ManualPayloads.Open(
                            ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, id), 0, "basic", true));
                    if (mc.screen instanceof ManualScreen book) {
                        book.showPage(pageNo);
                    }
                }
            }
        }
    }

    /** The stone carried uphill: the block count grows. */
    private static void beat(Minecraft mc, int t) {
        int reps = 4 + t / 12;
        ClientTraining.onState(new TrainingPayloads.State(mc.player.getId(), 0, TrainingPayloads.Beat.GAIN, reps, 0,
                mc.level.getGameTime() - 3, 0.0F));
    }

    /** tools/capture/hud_gallery.sh runs these scenes in both ru_ru and en_us.
     * MURIM_GALLERY_ONLY=six_forms captures the complete V/K input path at both GUI scales. */
    private static void sixFormsState() {
        var style = io.github.verycooltimo.murim.technique.Styles.SIX_HARMONIES;
        var entries = style.forms().stream().map(id ->
                new io.github.verycooltimo.murim.network.SyncMasteryPayload.Entry(id, 10, 12, .3F, 0F)).toList();
        ClientMasteryState.accept(new io.github.verycooltimo.murim.network.SyncMasteryPayload(entries));
        ClientLoadoutState.accept(new io.github.verycooltimo.murim.network.LoadoutPayloads.Sync(
                List.of(java.util.Optional.of(style.forms().getFirst()), java.util.Optional.empty()),
                0, 2, java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()));
    }

    private static void tearDown(Minecraft mc, String scene) {
        io.github.verycooltimo.murim.client.sect.ContractTracker.clearForCapture();
        switch (scene) {
            case "six_forms_wheel", "six_forms_loadout" -> {
                // Cancel before releasing V, so gallery teardown cannot send a SelectForm packet.
                TechniqueWheel.cancel();
                ModKeyMappings.WHEEL.setDown(false);
                ClientLoadoutState.reset();
                ClientMasteryState.reset();
            }
            case "advancements" -> mc.setScreen(null);
            case "training" -> ClientTraining.onState(new TrainingPayloads.State(mc.player.getId(), -1,
                    TrainingPayloads.Beat.VOID, 0, 0, 0L, 0.0F));
            case "guide", "guide_plate" -> {
                io.github.verycooltimo.murim.client.sect.SectGuideHud.accept(new io.github.verycooltimo.murim.network.SectGuidePayload(List.of()));
                if (plateNpc != null) {
                    plateNpc.discard();
                    plateNpc = null;
                }
            }
            case "meditation" -> {
                ClientPlaceState.forceForCapture(null);
                ClientMeditationState.accept(new SyncMeditationPayload(false, 0, 0, SyncMeditationPayload.Event.NONE,
                        SyncMeditationPayload.Ring.NONE, -1, ClientProfileState.profile().rank()));
            }
            default -> {
            }
        }
        // onClose, not setScreen(null): the dialogue screen ends its camera there; otherwise the next pass is shot
        // from inside the player (gallery 05.10, GUI 3 frames).
        if (mc.screen != null) {
            mc.screen.onClose();
        }
        mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
    }
}
