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

    private static final List<String> SCENES = List.of("hud", "training", "meditation", "loadout", "manual", "junk", "dialogue");
    /** Ticks per scene: set up at 0, frame at {@link #SHOT}, tear down at the end. */
    private static final int SPAN = 70;
    private static final int SHOT = 55;
    private static final int WARMUP = 120;

    private static int tick = -WARMUP;
    private static boolean done;

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
        if (tick++ < 0) {
            if (tick == -WARMUP + 2) {
                mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
            }
            return;
        }
        int[] scales = mc.getWindow().getWidth() >= 1280 ? new int[] {2, 3} : new int[] {2};
        int perScale = SCENES.size() * SPAN;
        int si = tick / perScale;
        if (si >= scales.length) {
            done = true;
            MurimMod.LOGGER.info("HUD gallery done");
            mc.stop();
            return;
        }
        int local = tick % perScale;
        String scene = SCENES.get(local / SPAN);
        int t = local % SPAN;
        if (local == 0) {
            mc.options.guiScale().set(scales[si]);
            mc.resizeDisplay();
        }
        if (t == 0) {
            setUp(mc, scene);
        }
        if (t > 0 && t < SHOT && "training".equals(scene) && t % 12 == 0) {
            beat(mc, t);
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

    private static void setUp(Minecraft mc, String scene) {
        switch (scene) {
            case "hud" -> {
                CombatMode.engage();
                // Chat sits over the lower-left HUD; two typical lines show the overlap.
                mc.gui.getChat().addMessage(Component.translatable("murim.training.end.set",
                        Component.translatable("murim.training.exercise.squat"), 13, 11, "4.2"));
                mc.gui.getChat().addMessage(Component.translatable("murim.meditation.held"));
            }
            case "training" -> {
                ClientTraining.onBody(new TrainingPayloads.Body(3, 0.62F, 0.35F, 0.4F, 0, 0));
                beat(mc, 0);
            }
            case "meditation" -> {
                ClientProfileState.setProfile(ClientProfileState.profile().isAwakened() ? ClientProfileState.profile()
                        : io.github.verycooltimo.murim.profile.DantianProfile.INITIAL.withTags("calm", "none")
                        .withPool(12.4D).withCirculating(3.1D));
                ClientMeditationState.accept(new SyncMeditationPayload(true, 3, 400, SyncMeditationPayload.Event.NONE,
                        SyncMeditationPayload.Ring.NONE, 0, ClientProfileState.profile().rank()));
                ClientPlaceState.forceForCapture(new io.github.verycooltimo.murim.world.Place(mc.player.blockPosition(),
                        io.github.verycooltimo.murim.world.PlaceKind.values()[0], false));
            }
            case "loadout" -> mc.setScreen(new LoadoutScreen());
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
            default -> {
            }
        }
    }

    /** A squat set on the beat: count grows, stamina falls. */
    private static void beat(Minecraft mc, int t) {
        int reps = 4 + t / 12;
        ClientTraining.onState(new TrainingPayloads.State(mc.player.getId(), 0, TrainingPayloads.Beat.GOOD, reps, reps - 1,
                0.7F - t * 0.004F, mc.level.getGameTime() - 3, 0.0F));
    }

    private static void tearDown(Minecraft mc, String scene) {
        switch (scene) {
            case "training" -> ClientTraining.onState(new TrainingPayloads.State(mc.player.getId(), -1,
                    TrainingPayloads.Beat.VOID, 0, 0, 0.0F, 0L, 0.0F));
            case "meditation" -> {
                ClientPlaceState.forceForCapture(null);
                ClientMeditationState.accept(new SyncMeditationPayload(false, 0, 0, SyncMeditationPayload.Event.NONE,
                        SyncMeditationPayload.Ring.NONE, 0, ClientProfileState.profile().rank()));
            }
            default -> {
            }
        }
        if (mc.screen != null) {
            mc.setScreen(null);
        }
    }
}
