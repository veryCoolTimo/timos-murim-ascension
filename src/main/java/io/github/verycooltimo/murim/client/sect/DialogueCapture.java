package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.network.FoundationPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.List;

/**
 * Стенд разговора (только автосъёмка, {@code ./capture.sh … dialogue}): сценарий из
 * {@code MURIM_CAPTURE_DIALOGUE="10:use,70:1,130:2"} — тик от начала и действие: {@code use} — ПКМ по NPC
 * роли {@code MURIM_CAPTURE_NPC}, {@code 1..9} — ответ клавишей, {@code down}/{@code up} — стрелки, {@code wheel} — колесо, {@code close} — клавиша 0 (закрыть), {@code atk} — форма основы по манекену,
 * {@code look} — повернуться к NPC. Кадр каждые {@code MURIM_CAPTURE_EVERY} тиков, всего
 * {@code MURIM_CAPTURE_FRAMES}. The scenario clock pauses at each use until the target is present
 * in the client level and its dialogue opens; subsequent offsets start at that opening.
 * Answers wait for ready text/pages and an acknowledged new payload; answer@translation.key selects a stable option.
 * Missing targets time out after 200 client ticks; unanswered uses retry three times at 20-tick intervals.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DialogueCapture {

    private record Step(int tick, String action) {
    }

    private static List<Step> steps = List.of();
    private static int tick = -1;
    // Only the capture scenario clock pauses; retry deadlines use elapsed client ticks.
    private static int elapsed;
    private static int nextStep;
    private static DialogueCaptureGate gate;
    private static DialogueCaptureAnswerGate answerGate;
    private static int frames;
    private static int frame;
    private static int every = 2;

    private DialogueCapture() {
    }

    public static void begin() {
        List<Step> out = new ArrayList<>();
        for (String part : System.getenv().getOrDefault("MURIM_CAPTURE_DIALOGUE", "10:use").split(",")) {
            String[] kv = part.trim().split(":");
            if (kv.length == 2) {
                out.add(new Step(Integer.parseInt(kv[0].trim()), kv[1].trim()));
            }
        }
        out.sort(java.util.Comparator.comparingInt(Step::tick));
        steps = out;
        tick = 0;
        elapsed = 0;
        nextStep = 0;
        gate = null;
        answerGate = null;
        frame = 0;
        frames = Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_FRAMES", "120").trim());
        every = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_EVERY", "2").trim()));
        MurimMod.LOGGER.info("Стенд разговора: {} шагов, {} кадров", steps.size(), frames);
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (tick < 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        elapsed++;
        boolean waiting = false;
        while (nextStep < steps.size() && steps.get(nextStep).tick() <= tick) {
            Step s = steps.get(nextStep);
            if (s.action().equals("use") || s.action().startsWith("use@")) {
                if (gate == null) gate = new DialogueCaptureGate();
                Entity target = s.action().equals("use") ? npc(mc) : person(mc, s.action().substring(4));
                // The capture sends interact directly (act below), without vanilla crosshair picking.
                // Source: SectDisciple.mobInteract -> DialogueService.open; retain server validation.
                boolean ready = DialogueCaptureGate.ready(target != null, mc.gameMode != null, mc.screen != null);
                if (target != null && mc.screen == null) face(mc, target);
                DialogueCaptureGate.Result result = gate.poll(elapsed, ready, mc.screen instanceof DialogueScreen);
                if (result == DialogueCaptureGate.Result.FAIL) {
                    MurimMod.LOGGER.info("SMOKE-DIALOGUE-FAIL tick={} action={} reason=dialogue_never_opened", elapsed, s.action());
                    tick = -1;
                    return;
                }
                if (result == DialogueCaptureGate.Result.USE) runStep(mc, s.action());
                if (result != DialogueCaptureGate.Result.OPEN) {
                    waiting = true;
                    break;
                }
                MurimMod.LOGGER.info("SMOKE-DIALOGUE-OPEN tick={} scheduled={}", elapsed, tick);
                gate = null;
            } else if (s.action().equals("close") || s.action().startsWith("answer@")
                    || s.action().matches("[1-9]")) {
                boolean closing = s.action().equals("close");
                if (answerGate == null) answerGate = new DialogueCaptureAnswerGate(closing);
                DialogueScreen screen = mc.screen instanceof DialogueScreen ds ? ds : null;
                int option = s.action().startsWith("answer@") && screen != null
                        ? screen.captureAnswer(s.action().substring(7)) : -1;
                boolean ready = screen != null && screen.captureReady()
                        && (!s.action().startsWith("answer@") || option >= 0);
                var result = answerGate.poll(elapsed, screen == null ? null : screen.captureLine(), ready);
                if (result == DialogueCaptureAnswerGate.Result.FAIL) {
                    MurimMod.LOGGER.info("SMOKE-DIALOGUE-FAIL tick={} action={} reason={} screen={}",
                            elapsed, s.action(), answerGate.failure(), screen == null ? "none" : "DialogueScreen");
                    mc.options.keyUse.setDown(false);
                    tick = -1;
                    return;
                }
                if (result == DialogueCaptureAnswerGate.Result.PRESS) {
                    runStep(mc, option >= 0 ? Integer.toString(option + 1) : s.action());
                }
                if (result != DialogueCaptureAnswerGate.Result.DONE) {
                    waiting = true;
                    break;
                }
                MurimMod.LOGGER.info("SMOKE-DIALOGUE-ACK tick={} action={}", elapsed, s.action());
                answerGate = null;
            } else {
                runStep(mc, s.action());
            }
            nextStep++;
        }
        if (!waiting && tick % every == 0 && frame < frames) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_dialogue_%03d.png", frame), mc.getMainRenderTarget(), m -> {
            });
            Screenshot.grab(mc.gameDirectory, String.format("clean_dialogue_%03d.png", frame), mc.getMainRenderTarget(), m -> {
            });
            frame++;
        }
        if (!waiting) tick++;
        if (frame >= frames && nextStep >= steps.size()) {
            tick = -1;
            MurimMod.LOGGER.info("SMOKE-DIALOGUE-DONE steps={} frames={}", nextStep, frame);
            // API: DevBanditCampHandler#getSingleplayerServer/execute and DevSetupEvents#runCaptureCommands.
            // Keep permission 4 for save/stop, as in the setup queue; run only after the client acknowledges completion.
            var server = mc.getSingleplayerServer();
            var playerId = mc.player.getUUID();
            String tail = System.getenv().getOrDefault("MURIM_CAPTURE_DIALOGUE_DONE_CMDS", "");
            if (server != null) server.execute(() -> {
                var player = server.getPlayerList().getPlayer(playerId);
                var source = (player == null ? server.createCommandSourceStack() : player.createCommandSourceStack())
                        .withPermission(4).withSuppressedOutput();
                for (String command : tail.split(";")) {
                    if (command.isBlank()) continue;
                    MurimMod.LOGGER.info("Capture completion command: {}", command);
                    server.getCommands().performPrefixedCommand(source, command.trim());
                }
                if (io.github.verycooltimo.murim.dev.SmokeWalkRoute.enabled()
                        && "1".equals(System.getenv("MURIM_SMOKE_SAVE_QUIT"))) {
                    mc.execute(SmokeJourneyCapture::requestSaveAndQuit);
                }
            });
        }
    }

    private static void runStep(Minecraft mc, String action) {
        String before = mc.screen == null ? "none" : mc.screen.getClass().getSimpleName();
        act(mc, action);
        MurimMod.LOGGER.info("SMOKE-STEP tick={} action={} screen {} -> {}", elapsed, action, before,
                mc.screen == null ? "none" : mc.screen.getClass().getSimpleName());
    }

    private static void act(Minecraft mc, String action) {
        MurimMod.LOGGER.info("Стенд разговора: тик {} — {}", elapsed, action);
        // use@ключ / look@ключ — конкретный человек горы (MURIM_CAPTURE_PEOPLE, SectCapture).
        int at = action.indexOf('@');
        if (at > 0) {
            Entity who = person(mc, action.substring(at + 1));
            if (who != null && action.startsWith("bubble")) {
                // a speech bubble over the person, as the server would send it (plate-and-bubble frames); "bubble_long" is a long line
                String text = action.startsWith("bubble_long") ? "Mind the steps on the way up, the stones are wet in the morning mist."
                        : "Good morning, junior.";
                SpeechBubbles.onBubble(new io.github.verycooltimo.murim.network.SectBubblePayload(who.getId(),
                        net.minecraft.network.chat.Component.literal(text), 400));
                return;
            }
            if (who != null) {
                face(mc, who);
                if (action.startsWith("use") && mc.gameMode != null) {
                    mc.gameMode.interact(mc.player, who, InteractionHand.MAIN_HAND);
                }
            }
            return;
        }
        switch (action) {
            case "use" -> {
                Entity npc = npc(mc);
                if (npc != null && mc.gameMode != null) {
                    mc.gameMode.interact(mc.player, npc, InteractionHand.MAIN_HAND);
                }
            }
            case "atk" -> {
                Entity dummy = null;
                for (Entity e : mc.level.entitiesForRendering()) {
                    if (e instanceof TrainingDummy && (dummy == null || e.distanceTo(mc.player) < dummy.distanceTo(mc.player))) {
                        dummy = e;
                    }
                }
                if (dummy != null && mc.gameMode != null) {
                    face(mc, dummy);
                    PacketDistributor.sendToServer(new FoundationPayloads.Swing(0));
                    mc.gameMode.attack(mc.player, dummy);
                    mc.player.swing(InteractionHand.MAIN_HAND);
                }
            }
            case "look" -> {
                Entity npc = npc(mc);
                if (npc != null) {
                    face(mc, npc);
                }
            }
            // smoke pass (tools/smoke): hotbar slot 1 in hand, the use key held (eat a pill) and let go
            case "slot0" -> {
                int slot = 0;
                if ("1".equals(System.getenv("MURIM_SMOKE_PRESERVE"))) {
                    slot = -1;
                    for (int i = 0; i < 9; i++) {
                        var item = mc.player.getInventory().getItem(i);
                        if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath().equals("pill_snow_plum")) {
                            slot = i; break;
                        }
                    }
                    if (slot < 0) {
                        MurimMod.LOGGER.info("SMOKE-DIALOGUE-FAIL reason=pill_not_in_hotbar");
                        return;
                    }
                }
                mc.player.getInventory().selected = slot;
            }
            case "hold" -> mc.options.keyUse.setDown(true);
            case "free" -> mc.options.keyUse.setDown(false);
            case "down", "up" -> {
                if (mc.screen instanceof DialogueScreen ds) {
                    ds.nudge("down".equals(action) ? 1 : -1);
                }
            }
            case "wheel" -> {
                if (mc.screen instanceof DialogueScreen ds) {
                    ds.wheel(2);
                }
            }
            case "close" -> {
                // the way out, as the key 0 presses it
                if (mc.screen instanceof DialogueScreen ds) {
                    ds.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_0, 0, 0);
                }
            }
            case "esc" -> {
                if (mc.screen != null) {
                    mc.screen.onClose();
                }
            }
            default -> {
                if (mc.screen instanceof DialogueScreen ds && action.length() == 1 && Character.isDigit(action.charAt(0))) {
                    ds.press(action.charAt(0) - '1');
                }
            }
        }
    }

    private static void face(Minecraft mc, Entity e) {
        double dx = e.getX() - mc.player.getX();
        double dz = e.getZ() - mc.player.getZ();
        float yaw = (float) Math.toDegrees(Math.atan2(dz, dx)) - 90.0F;
        mc.player.setYRot(yaw);
        mc.player.setYHeadRot(yaw);
    }

    private static Entity person(Minecraft mc, String key) {
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SectDisciple d && key.equals(d.memberKey())) {
                return e;
            }
        }
        return null;
    }

    private static Entity npc(Minecraft mc) {
        String role = System.getenv().getOrDefault("MURIM_CAPTURE_NPC", "mentor");
        Entity best = null;
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof SectDisciple d && d.role().id().equals(role)
                    && (best == null || e.distanceTo(mc.player) < best.distanceTo(mc.player))) {
                best = e;
            }
        }
        return best;
    }
}
