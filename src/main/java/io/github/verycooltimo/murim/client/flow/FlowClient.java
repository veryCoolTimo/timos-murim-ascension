package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientPillState;
import io.github.verycooltimo.murim.client.vfx.fx.FxTune;
import io.github.verycooltimo.murim.client.vfx.fx.FxTuneScene;
import io.github.verycooltimo.murim.cultivation.PillKind;
import io.github.verycooltimo.murim.cultivation.PillTier;
import io.github.verycooltimo.murim.cultivation.PillService;
import io.github.verycooltimo.murim.cultivation.flow.FlowCharacter;
import io.github.verycooltimo.murim.cultivation.flow.FlowGame;
import io.github.verycooltimo.murim.network.PillPayloads;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * The client side of «Провести поток»: plays the game from the server's seed and pills, reads the player's hands and reports how it
 * ended. The body picture is {@link FlowBodyRenderer}; the minimal HUD is {@link FlowOverlay}; the rules are {@link FlowGame}.
 *
 * <p>Hands (no new keybinds, the mod may sit in a modpack): hold the attack key (LMB) or Space to restrain the stream; A / D (the
 * strafe keys) to choose the left or the right branch at a fork. The seated body does not move, so these keys are free.
 *
 * <p>The game runs by frames (smooth, like the mockup), not by ticks; a paused game does not run. The server never trusts the
 * result as a quality: it takes the outcome, the progress and the lost share and works the rest out itself.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class FlowClient {

    /** Seconds the picture stays after the end of the stream (the finale of a clean run is about two seconds). */
    private static final double TAIL_DONE = 2.5D;
    private static final double TAIL_OTHER = 1.4D;
    private static final double TAIL_BURST = 2.8D;
    private static final double TAIL_LIFT = 1.9D;
    /** The longest step of the stream: the mockup's feel is tuned at 60 frames a second and a frame of 50 ms at most. */
    private static final double STEP = 1.0D / 30.0D;
    private static final double FADE_IN = 0.5D;
    private static final double FADE_OUT = 0.5D;

    /** Dev switch: false = the old hard release of the game camera (for the film that shows the difference). */
    public static final boolean CAM_GLIDE = !"0".equals(System.getenv("MURIM_FLOW_GLIDE"));

    private static long tuneLast;
    private static FlowGame game;
    private static boolean bodyPresentation;
    private static boolean automatic;
    private static boolean sceneRan;
    private static int id;
    private static boolean reportedEnd;
    private static long lastNanos;
    private static double shown;
    private static int tickAge;
    private static List<PillKind> kinds = List.of();
    private static int pillColour = 0xE8F4EC;
    /** Live tuning (dev): the last start, replayed when the tune loop ends; the clock of the tune loop. */
    private static PillPayloads.FlowStart lastStart;
    private static double tuneClock;

    private FlowClient() {
    }

    /** The server sat the player with pills in the window: start the stream (or the torn picture). */
    public static void start(PillPayloads.FlowStart payload) {
        lastStart = payload;
        tuneClock = 0;
        List<PillKind> list = new ArrayList<>();
        for (int o : payload.kinds()) {
            list.add(PillKind.byId(o));
        }
        kinds = list;
        automatic = payload.automatic();
        bodyPresentation = true;
        FlowCharacter c = PillService.character(list);
        pillColour = PillScene.hue(c.colour());
        game = new FlowGame(c, payload.seed(), payload.hard(), payload.torn(), payload.poisonNature());
        id = payload.id();
        reportedEnd = false;
        shown = 0.0D;
        tickAge = 0;
        lastNanos = System.nanoTime();
        game.setAuto(automatic || "auto".equals(System.getenv("MURIM_CAPTURE_FLOW")) || FlowFireStand.ON);
        // The cinematic scene: ordinary and middle pills only (never a manual game, a torn body or a guided high pill).
        boolean calm = automatic && !payload.torn() && list.stream().noneMatch(k -> k.tier() == PillTier.HIGH);
        PillCalmFx.reset();
        sceneRan = !calm && PillScene.begin(payload, c.colour());
        // common and middle pills: the calm absorption (belly light, floor vortex, curtains, flakes, body lamp), no mini-game, no board
        if (calm) PillCalmFx.begin(list.stream().anyMatch(k -> k.tier() == PillTier.MIDDLE) ? 1 : 0, PillScene.hue(c.colour()));
        PillScene.setPlum(list.stream().anyMatch(k -> k.name().contains("PLUM")));
    }

    /** Owns the body through the server's lift/recoil tail, so legacy pill effects do not reappear after our fade. */
    public static boolean bodyPresentation() {
        return bodyPresentation;
    }

    public static boolean automatic() { return automatic; }

    public static int pillColour() { return pillColour; }

    /** True for the ordinary (common) pill: no cinematic scene and no game, a short warm flash on the chest, a push of dust and a couple of seconds of haze. */
    public static boolean commonQuick() {
        return game != null && automatic && !sceneRan && !PillCalmFx.running() && !kinds.isEmpty() && kinds.stream().noneMatch(k -> k.tier() == PillTier.HIGH);
    }

    public static FlowGame game() {
        return game;
    }

    public static List<PillKind> kinds() {
        return kinds;
    }

    /** True from the start of the stream until its picture has faded out. */
    public static boolean active() {
        return game != null;
    }

    /** The high pill game proper: not a cinematic scene and not the ordinary pill's quick flash (those keep their own pictures and are not touched). */
    static FlowGame gameForFx() {
        return game != null && !sceneRan && !commonQuick() && !PillCalmFx.running() ? game : null;
    }

    /** True while the meridian's effect still eases out after the game object is gone (the camera and the depth stage stay with it). */
    public static boolean lingering() { return FlowMeridianFx.lingering() || PillCalmFx.running(); }

    /** True for the high pill game proper, also while its effect lingers after the game object is gone. */
    static boolean gameForFxOrLingering() { return gameForFx() != null || (game == null && FlowMeridianFx.lingering()); }

    /** The track of the game's camera in blocks (the keys are FlowSpaceMath#cameraDistance: push-in, pull-back in the finale); -1 when the game camera has no say (scenes). */
    public static float cameraDistance() {
        if (PillCalmFx.running()) return PillCalmFx.cameraDistance();
        if (game == null && !FlowMeridianFx.lingering()) return -1;
        if (sceneRan || PillScene.active() || commonQuick()) return -1;
        return FlowMeridianFx.cameraDistance();
    }

    /** Pitch of the game's camera in degrees. */
    public static float cameraPitch() { return PillCalmFx.running() ? PillCalmFx.cameraPitch() : FlowMeridianFx.cameraPitch(); }

    /** Blocks the shot's focus is lifted above the chest-follow height (the head stays in the frame). */
    public static float cameraFocusLift() { return (float) FlowLook.CAM_FOCUS_LIFT + cameraScrollK() * io.github.verycooltimo.murim.client.vfx.fx.FxTune.f("flow.scroll.camFocus", .5F) + FlowMeridianFx.finaleFocus(); }

    /** Scroll framing (high pill game): 0..1 how far the camera is in it, and the share of the frame's half width the figure is shifted to the left of the centre. */
    public static float cameraScrollK() { return gameForFxOrLingering() ? (float) FlowScrollBoard.camK() : 0F; }

    /** Half-width shares to shift the figure left: 0 centred, 1 at the left edge ((1 - 2 * figure x) at full framing). */
    public static float cameraShiftShare() { return cameraScrollK() * (1F - 2F * FlowScrollBoard.figX()); }

    /** True while the scroll game's finale shakes the camera itself (the burst's damped shake, FlowMeridianFx), so the old burst quake stays quiet. */
    public static boolean sceneFinale() { return FlowAura.game(); }

    /** The one damped nudge of the camera's focus at the burst, blocks. */
    public static float cameraNudge() { return FlowMeridianFx.cameraNudge(); }

    /** True while the high pill finale (clean end) plays: a server «landed» must not cut its lift. */
    public static boolean holdsFinaleLift() {
        return gameForFxOrLingering() && FlowMeridianFx.ending() == 0;
    }

    /** Ticks the server's own lift of the body waits: the flash, the black core and the burst of the finale come first (the lift starts just after the burst's peak). */
    public static int finaleDelayTicks() {
        if (!gameForFxOrLingering()) return 0;
        int e = FlowMeridianFx.ending();
        if (e > 0) return 0;
        double at = FlowSpaceMath.liftAt();
        return (int) Math.ceil(Math.max(0, at - Math.max(0, FlowMeridianFx.sinceEnd())) * 20);
    }

    /** Seconds the picture has been up (for the fade). */
    public static double shown() {
        return shown;
    }

    /** 0..1: the picture fades in at the start and out at the end. */
    public static float visibility() {
        if (game == null) {
            return 0.0F;
        }
        double in = Math.min(1.0D, shown / FADE_IN);
        double out = 1.0D;
        if (game.state != FlowGame.State.RUN) {
            double tail = tail();
            out = Math.max(0.0D, Math.min(1.0D, (tail - game.end) / FADE_OUT));
        }
        return (float) (in * out);
    }

    private static double tail() {
        if (game.state == FlowGame.State.DONE && ClientPillState.finaleAge() >= 0) {
            return TAIL_LIFT; // the body rises behind the picture: let it fade as the «bam» dies
        }
        return game.state == FlowGame.State.DONE || game.state == FlowGame.State.TORN ? TAIL_DONE : (game.state == FlowGame.State.BURST ? TAIL_BURST : TAIL_OTHER);
    }

    public static void reset() {
        PillCalmFx.reset();
        sceneRan = false;
        PillScene.reset();
        bodyPresentation = false;
        game = null;
        kinds = List.of();
    }

    /** One frame: the hands, then the stream. */
    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (FxTune.ON) {
            FlowLook.refresh();
            long n0 = System.nanoTime();
            double raw = tuneLast == 0 ? 0 : Math.min(.25D, (n0 - tuneLast) / 1.0E9D);
            tuneLast = n0;
            if (lastStart != null) {
                tuneClock += FxTuneScene.simDt(raw);
                if (FxTuneScene.simLoopDue(tuneClock)) {
                    PillPayloads.FlowStart again = lastStart;
                    FlowMeridianFx.clear();
                    start(again);
                }
            }
        }
        if (game == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        // The stream keeps real time even on a slow machine (the server's clock does not wait): a long frame is played in short steps,
        // but never more than a quarter of a second at once (a hang must not eat the game).
        double dt = Math.min(0.25D, (now - lastNanos) / 1.0E9D);
        lastNanos = now;
        dt = FxTuneScene.simDt(FlowTick.dt(0, dt));
        if (mc.isPaused() || mc.player == null) {
            return;
        }
        shown += dt;
        if (game.state == FlowGame.State.RUN && !game.isAuto()) {
            // Restrain: the attack key or Space. A / D pick the branch at a fork (the last one pressed wins).
            game.hold = mc.options.keyAttack.isDown() || mc.options.keyJump.isDown();
            if (mc.options.keyLeft.isDown() && !mc.options.keyRight.isDown()) {
                game.side = -1;
            } else if (mc.options.keyRight.isDown() && !mc.options.keyLeft.isDown()) {
                game.side = 1;
            }
        }
        // the intro (the pill dissolves, the flame rises) plays first: the game's own clock waits for it (a client delay, FlowGame is not changed)
        boolean intro = !PillCalmFx.running() && shown < FlowLook.INTRO_LEAD + FlowLook.INTRO_DISSOLVE + FlowLook.INTRO_FLAME; // the calm absorption has its own clock: its pill's automatic game runs from the start
        if (game.state == FlowGame.State.RUN && FlowBot.drives() && !intro) FlowBot.drive(game, FlowBot.mode());
        for (double left = intro ? 0 : dt; left > 1.0E-9D && game != null; left -= STEP) {
            if (automatic) game.updateAutomatic(Math.min(STEP, left));
            else game.update(Math.min(STEP, left));
        }
        if (game.state != FlowGame.State.RUN && game.state != FlowGame.State.TORN && !reportedEnd) {
            reportedEnd = true;
            report(game.state == FlowGame.State.DONE ? PillPayloads.Outcome.DONE
                    : game.state == FlowGame.State.BURST ? PillPayloads.Outcome.BURST : PillPayloads.Outcome.TIMEOUT);
        }
        // The scene is clocked in ticks, the game in frame time: when the scene is over the plain picture must not come back.
        if (sceneRan && !PillScene.active()) {
            sceneRan = false;
            game = null;
        } else if (game.state != FlowGame.State.RUN && game.end > tail() && !PillScene.active()) {
            game = null;
        }
    }

    /** While the game runs, the progress goes to the server twice a second (a sitting cut short is paid by it). */
    @SubscribeEvent
    static void onTick(ClientTickEvent.Post event) {
        if (game == null && !ClientPillState.state().active() && ClientPillState.finaleAge() < 0
                && ClientPillState.deviationAge() < 0) {
            bodyPresentation = false;
        }
        if (game == null || game.state != FlowGame.State.RUN || Minecraft.getInstance().isPaused()) {
            return;
        }
        if (++tickAge % 10 == 0) {
            report(PillPayloads.Outcome.RUNNING);
        }
    }

    private static void report(PillPayloads.Outcome outcome) {
        if (FxTune.ON) return; // live tuning replays the run on the client: the server hears nothing
        PacketDistributor.sendToServer(new PillPayloads.FlowReport(id, outcome.ordinal(),
                (int) Math.round(Math.max(0.0D, Math.min(1.0D, game.s)) * 1000.0D),
                (int) Math.round(Math.max(0.0D, Math.min(100.0D, game.lost)) * 10.0D)));
    }

    /** The seated body does not swing or place anything while the stream runs: the buttons are the player's hands on the stream. */
    @SubscribeEvent
    static void onInteraction(InputEvent.InteractionKeyMappingTriggered event) {
        if (game != null || ClientPillState.state().active()) {
            event.setCanceled(true);
            event.setSwingHand(false);
        }
    }

    /** The picture follows the session of the server: a stand-up or a world change ends it. */
    @SubscribeEvent
    static void onLoggedOut(net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent.LoggingOut event) {
        reset();
    }

    /** The server ended the session (a blow, a stand-up): the stream stops where it is and the picture fades; nothing more is reported. */
    public static void interrupted() {
        if (game != null && game.state == FlowGame.State.RUN) {
            reportedEnd = true;
            game.abort();
            PillScene.interrupted();
        }
    }
}
