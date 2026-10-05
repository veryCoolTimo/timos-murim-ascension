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
 * роли {@code MURIM_CAPTURE_NPC}, {@code 1..4} — ответ клавишей, {@code atk} — форма основы по манекену,
 * {@code look} — повернуться к NPC. Кадр каждые {@code MURIM_CAPTURE_EVERY} тиков, всего
 * {@code MURIM_CAPTURE_FRAMES}.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class DialogueCapture {

    private record Step(int tick, String action) {
    }

    private static List<Step> steps = List.of();
    private static int tick = -1;
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
        steps = out;
        tick = 0;
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
        for (Step s : steps) {
            if (s.tick() == tick) {
                act(mc, s.action());
            }
        }
        if (tick % every == 0 && frame < frames) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_dialogue_%03d.png", frame), mc.getMainRenderTarget(), m -> {
            });
            Screenshot.grab(mc.gameDirectory, String.format("clean_dialogue_%03d.png", frame), mc.getMainRenderTarget(), m -> {
            });
            frame++;
        }
        tick++;
        if (frame >= frames) {
            tick = -1;
        }
    }

    private static void act(Minecraft mc, String action) {
        MurimMod.LOGGER.info("Стенд разговора: тик {} — {}", tick, action);
        // use@ключ / look@ключ — конкретный человек горы (MURIM_CAPTURE_PEOPLE, SectCapture).
        int at = action.indexOf('@');
        if (at > 0) {
            Entity who = person(mc, action.substring(at + 1));
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
