package io.github.verycooltimo.murim.client.flow;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientPillState;
import io.github.verycooltimo.murim.cultivation.PillKind;
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
    private static final double TAIL_LIFT = 1.9D;
    /** The longest step of the stream: the mockup's feel is tuned at 60 frames a second and a frame of 50 ms at most. */
    private static final double STEP = 1.0D / 30.0D;
    private static final double FADE_IN = 0.5D;
    private static final double FADE_OUT = 0.5D;

    private static FlowGame game;
    private static boolean bodyPresentation;
    private static boolean automatic;
    private static int id;
    private static boolean reportedEnd;
    private static long lastNanos;
    private static double shown;
    private static int tickAge;
    private static List<PillKind> kinds = List.of();

    private FlowClient() {
    }

    /** The server sat the player with pills in the window: start the stream (or the torn picture). */
    public static void start(PillPayloads.FlowStart payload) {
        List<PillKind> list = new ArrayList<>();
        for (int o : payload.kinds()) {
            list.add(PillKind.byId(o));
        }
        kinds = list;
        automatic = payload.automatic();
        bodyPresentation = true;
        FlowCharacter c = PillService.character(list);
        game = new FlowGame(c, payload.seed(), payload.hard(), payload.torn(), payload.poisonNature());
        id = payload.id();
        reportedEnd = false;
        shown = 0.0D;
        tickAge = 0;
        lastNanos = System.nanoTime();
        game.setAuto(automatic || "auto".equals(System.getenv("MURIM_CAPTURE_FLOW")));
    }

    /** Owns the body through the server's lift/recoil tail, so legacy pill effects do not reappear after our fade. */
    public static boolean bodyPresentation() {
        return bodyPresentation;
    }

    public static boolean automatic() { return automatic; }

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
        return game.state == FlowGame.State.DONE || game.state == FlowGame.State.TORN ? TAIL_DONE : TAIL_OTHER;
    }

    public static void reset() {
        bodyPresentation = false;
        game = null;
        kinds = List.of();
    }

    /** One frame: the hands, then the stream. */
    @SubscribeEvent
    static void onFrame(RenderFrameEvent.Pre event) {
        if (game == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        long now = System.nanoTime();
        // The stream keeps real time even on a slow machine (the server's clock does not wait): a long frame is played in short steps,
        // but never more than a quarter of a second at once (a hang must not eat the game).
        double dt = Math.min(0.25D, (now - lastNanos) / 1.0E9D);
        lastNanos = now;
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
        for (double left = dt; left > 1.0E-9D && game != null; left -= STEP) {
            if (automatic) game.updateAutomatic(Math.min(STEP, left));
            else game.update(Math.min(STEP, left));
        }
        if (game.state != FlowGame.State.RUN && game.state != FlowGame.State.TORN && !reportedEnd) {
            reportedEnd = true;
            report(game.state == FlowGame.State.DONE ? PillPayloads.Outcome.DONE
                    : game.state == FlowGame.State.BURST ? PillPayloads.Outcome.BURST : PillPayloads.Outcome.TIMEOUT);
        }
        if (game.state != FlowGame.State.RUN && game.end > tail()) {
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
        }
    }
}
