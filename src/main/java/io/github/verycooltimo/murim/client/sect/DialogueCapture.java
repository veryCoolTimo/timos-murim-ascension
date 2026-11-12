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
    // path film 09.11: meditation hands. 0 = off, 1 = play the ring (hold while wider than the band centre), 2 = play ring 1, overshoot from beat 2 (hold all the time)
    private static int medBot;
    private static int medIdle;
    // path film 10.11: the bed named by the journal line «Твоя кровать: x, y, z» (chat), and a position log every second
    private static final java.util.regex.Pattern BED = java.util.regex.Pattern.compile("кровать: (-?\\d+), (-?\\d+), (-?\\d+)");
    private static int[] bed;

    @SubscribeEvent
    static void onChat(net.neoforged.neoforge.client.event.ClientChatReceivedEvent event) {
        var m = BED.matcher(event.getMessage().getString());
        if (m.find()) {
            bed = new int[] {Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))};
            MurimMod.LOGGER.info("SMOKE-BED {} {} {}", bed[0], bed[1], bed[2]);
        }
    }

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
        medBot = 0;
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
        if (elapsed % 20 == 0) {
            MurimMod.LOGGER.info("SMOKE-POS t={} {} {} {} yaw={} pitch={}", elapsed, String.format("%.2f", mc.player.getX()),
                    String.format("%.2f", mc.player.getY()), String.format("%.2f", mc.player.getZ()),
                    String.format("%.0f", mc.player.getYRot()), String.format("%.0f", mc.player.getXRot()));
        }
        if (medBot > 0) {
            boolean hold = false;
            if (io.github.verycooltimo.murim.client.ClientMeditationState.minigame()) {
                var ring = io.github.verycooltimo.murim.client.ClientMeditationState.ring(1.0F);
                hold = (medBot == 2 && io.github.verycooltimo.murim.client.ClientMeditationState.state().beats() >= 2) || ring.radius() > ring.centre();
            }
            mc.options.keyJump.setDown(hold);
            // a session that ended (passed ring, forgiven restart is not an end) and no seed yet: sit down again after a second
            var ms = io.github.verycooltimo.murim.client.ClientMeditationState.state();
            if (!ms.active() && ms.beats() < 3) {
                if (++medIdle == 20) {
                    PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.MeditationInputPayload(
                            io.github.verycooltimo.murim.network.MeditationInputPayload.Action.TOGGLE, true));
                    MurimMod.LOGGER.info("SMOKE-MED sit again beats={}", ms.beats());
                }
            } else {
                medIdle = 0;
            }
            if (ms.active() && elapsed % 20 == 0) {
                MurimMod.LOGGER.info("SMOKE-MED active beats={} ticks={} ring={}", ms.beats(), ms.ticks(), ms.ring().active());
            }
        }
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
        // path film 09.11: frames also while a step waits for the screen (elapsed clock), so typewriter pauses are filmed
        if (elapsed % every == 0 && frame < frames) {
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
        if (action.startsWith("rawkey@")) {
            // rawkey@key: press the option with this key without waiting for a next node (checked before the person@ branch below)
            if (mc.screen instanceof DialogueScreen ks) {
                int o = ks.captureAnswer(action.substring(7));
                if (o >= 0) ks.press(o);
            }
            return;
        }
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
            case "scroll" -> {
                // hold the method scroll and use it, as the player does (learns the method)
                for (int i = 0; i < 9; i++) {
                    var item = mc.player.getInventory().getItem(i);
                    if (net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(item.getItem()).getPath().equals("method_scroll")) {
                        mc.player.getInventory().selected = i;
                        if (mc.gameMode != null) mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
                        MurimMod.LOGGER.info("SMOKE-SCROLL used slot {}", i);
                        break;
                    }
                }
            }
            case "learn" -> {
                // the click on the seal of the open method scroll: the same packet, then close the screen
                PacketDistributor.sendToServer(new io.github.verycooltimo.murim.library.LibraryNetwork.LearnMethod(
                        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies")));
                MurimMod.LOGGER.info("SMOKE-LEARN sent");
            }
            case "tpbed" -> {
                // stand beside the player's own bed (SectRooms.bed, the same bed the journal line «Твоя кровать: x, y, z» names)
                var server = mc.getSingleplayerServer();
                var id = mc.player.getUUID();
                if (server == null) return;
                server.execute(() -> {
                    var sp = server.getPlayerList().getPlayer(id);
                    if (sp == null) return;
                    var b = io.github.verycooltimo.murim.sect.SectRooms.bed(sp);
                    if (b == null) {
                        MurimMod.LOGGER.info("SMOKE-BED missing");
                        return;
                    }
                    MurimMod.LOGGER.info("SMOKE-BED {} {} {}", b.getX(), b.getY(), b.getZ());
                    server.getCommands().performPrefixedCommand(sp.createCommandSourceStack().withPermission(4).withSuppressedOutput(),
                            "tp @s " + (b.getX() + 0.5) + " " + (b.getY() + 0.8) + " " + (b.getZ() + 0.5) + " 0 20");
                });
            }
            case "medsit" -> PacketDistributor.sendToServer(new io.github.verycooltimo.murim.network.MeditationInputPayload(
                    io.github.verycooltimo.murim.network.MeditationInputPayload.Action.TOGGLE, true));
            case "medplay" -> medBot = 1;
            case "medover" -> medBot = 2;
            case "medoff" -> {
                medBot = 0;
                mc.options.keyJump.setDown(false);
            }
            case "hold" -> mc.options.keyUse.setDown(true);
            case "free" -> mc.options.keyUse.setDown(false);
            case "fwd" -> mc.options.keyUp.setDown(true);
            case "fwdoff" -> mc.options.keyUp.setDown(false);
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
            case "skip" -> {
                // 10.11 rooms film: the Space press that skips the typing (DialogueScreen.keyPressed)
                if (mc.screen instanceof DialogueScreen ds) {
                    ds.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0);
                    ds.keyReleased(org.lwjgl.glfw.GLFW.GLFW_KEY_SPACE, 0, 0);
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
                // rawN: press option N without waiting for a next node (an answer that closes the screen, e.g. an escort starts)
                if (action.startsWith("rawkey@") && mc.screen instanceof DialogueScreen ks) {
                    int o = ks.captureAnswer(action.substring(7));
                    if (o >= 0) ks.press(o);
                    return;
                }
                if (action.length() == 4 && action.startsWith("raw") && Character.isDigit(action.charAt(3))
                        && mc.screen instanceof DialogueScreen rs) {
                    rs.press(action.charAt(3) - '1');
                    return;
                }
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
