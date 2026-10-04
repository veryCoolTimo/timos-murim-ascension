package io.github.verycooltimo.murim.client.sect;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import org.joml.Vector3f;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Анимации игрока (PAL, {@code assets/murim/player_animations/*.json}) для гуманоидных NPC
 * на Bedrock-модели бандита (этап С0 секты: NPC применяет те же техники, что игрок).
 *
 * <p>Один файл анимации на игрока и на NPC: автор правит позу в Blockbench в одном месте.
 * Кости PAL переименовываются в кости бандита ({@code right_arm → arm_r} …), знаки осей у обоих
 * одинаковые (вращение — градусы Java-модели: рука над головой x = −150 и у игрока, и в клипе
 * {@code windup} бандита; BedrockGeo сверял то же на ванильном гуманоиде). Изинги PAL ванильный
 * {@link KeyframeAnimations} не знает — они запекаются в линейные ключи через 1/40 с.
 * API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class PalClips {

    /** Кость PAL → кость модели бандита. */
    private static final Map<String, String> BONES = Map.of(
            "torso", "torso", "head", "head", "right_arm", "arm_r", "left_arm", "arm_l",
            "right_leg", "leg_r", "left_leg", "leg_l", "body", "waist");

    /** Шаг запекания изингов, секунд. */
    static final float STEP = 0.025F;

    private static final Map<ResourceLocation, AnimationDefinition> CLIPS = new HashMap<>();

    /** Клип по имени анимации игрока ({@code murim:seven_plum_rush}) или null. */
    public static AnimationDefinition get(ResourceLocation id) {
        return CLIPS.get(id);
    }

    @SubscribeEvent
    static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) PalClips::reload);
    }

    private static void reload(ResourceManager resources) {
        CLIPS.clear();
        // Клипы игрока (их же играет NPC) и свои клипы NPC — позы секты (entity/SectPose, npc_animations/).
        Map<ResourceLocation, Resource> found = new HashMap<>(resources.listResources("player_animations", p -> p.getPath().endsWith(".json")));
        found.putAll(resources.listResources("npc_animations", p -> p.getPath().endsWith(".json")));
        for (Map.Entry<ResourceLocation, Resource> e : found.entrySet()) {
            try (Reader reader = e.getValue().openAsReader()) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
                for (Map.Entry<String, JsonElement> a : root.getAsJsonObject("animations").entrySet()) {
                    CLIPS.put(ResourceLocation.fromNamespaceAndPath(e.getKey().getNamespace(), a.getKey()),
                            clip(a.getValue().getAsJsonObject()));
                }
            } catch (Exception ex) {
                MurimMod.LOGGER.error("Не прочитать анимацию игрока для NPC {}", e.getKey(), ex);
            }
        }
        MurimMod.LOGGER.info("Анимации игрока для NPC: {}", CLIPS.size());
    }

    static AnimationDefinition clip(JsonObject a) {
        float length = a.has("animation_length") ? a.get("animation_length").getAsFloat() : 0.0F;
        Map<String, List<AnimationChannel>> bones = new HashMap<>();
        float maxTime = 0.0F;
        if (a.has("bones")) {
            for (Map.Entry<String, JsonElement> b : a.getAsJsonObject("bones").entrySet()) {
                String bone = BONES.get(b.getKey());
                if (bone == null) {
                    continue;
                }
                JsonObject channels = b.getValue().getAsJsonObject();
                List<AnimationChannel> list = new ArrayList<>();
                for (String ch : new String[] {"rotation", "position"}) {
                    if (!channels.has(ch)) {
                        continue;
                    }
                    List<Key> keys = keys(channels.get(ch));
                    if (keys.isEmpty()) {
                        continue;
                    }
                    maxTime = Math.max(maxTime, keys.get(keys.size() - 1).t);
                    list.add(new AnimationChannel(ch.equals("position") ? AnimationChannel.Targets.POSITION
                            : AnimationChannel.Targets.ROTATION, bake(keys, ch)));
                }
                bones.put(bone, list);
            }
        }
        // "loop": true — цикл (позы секты); "hold_on_last_frame" и false — один раз.
        JsonElement loop = a.get("loop");
        boolean looping = loop != null && loop.isJsonPrimitive() && loop.getAsJsonPrimitive().isBoolean() && loop.getAsBoolean();
        return new AnimationDefinition(length > 0.0F ? length : Math.max(0.05F, maxTime), looping, bones);
    }

    /** Ключ PAL: время, значение и изинг перехода В этот ключ (как у GeckoLib/PAL). */
    record Key(float t, float[] v, String easing) {
    }

    static List<Key> keys(JsonElement e) {
        List<Key> out = new ArrayList<>();
        if (!e.isJsonObject()) {
            out.add(new Key(0.0F, values(e), "linear"));
            return out;
        }
        TreeMap<Float, Key> sorted = new TreeMap<>();
        for (Map.Entry<String, JsonElement> k : e.getAsJsonObject().entrySet()) {
            float t;
            try {
                t = Float.parseFloat(k.getKey());
            } catch (NumberFormatException ex) {
                continue;
            }
            JsonElement v = k.getValue();
            String easing = "linear";
            if (v.isJsonObject()) {
                JsonObject o = v.getAsJsonObject();
                if (o.has("easing")) {
                    easing = o.get("easing").getAsString();
                } else if (o.has("lerp_mode")) {
                    easing = o.get("lerp_mode").getAsString();
                }
                v = o.has("vector") ? o.get("vector") : o.has("post") ? o.get("post") : o.get("pre");
            }
            sorted.put(t, new Key(t, values(v), easing));
        }
        out.addAll(sorted.values());
        return out;
    }

    /** Запекание: значения между ключами по изингу, линейные ключи через {@link #STEP}. */
    static Keyframe[] bake(List<Key> keys, String channel) {
        List<Keyframe> out = new ArrayList<>();
        out.add(frame(0.0F, keys.get(0).v, channel));
        for (int i = 1; i < keys.size(); i++) {
            Key a = keys.get(i - 1);
            Key b = keys.get(i);
            float span = b.t - a.t;
            if (java.util.Arrays.equals(a.v, b.v)) {
                // Удержание позы: один ключ, а не тысячи (лотос держит позу 10 000 с — было 400 000 ключей).
                out.add(frame(b.t, b.v, channel));
                continue;
            }
            int steps = Math.max(1, (int) Math.ceil(span / STEP));
            for (int s = 1; s <= steps; s++) {
                float u = (float) s / steps;
                float[] v = new float[3];
                if ("catmullrom".equals(b.easing)) {
                    float[] p0 = keys.get(Math.max(0, i - 2)).v;
                    float[] p3 = keys.get(Math.min(keys.size() - 1, i + 1)).v;
                    for (int c = 0; c < 3; c++) {
                        v[c] = catmull(p0[c], a.v[c], b.v[c], p3[c], u);
                    }
                } else {
                    float w = ease(b.easing, u);
                    for (int c = 0; c < 3; c++) {
                        v[c] = a.v[c] + (b.v[c] - a.v[c]) * w;
                    }
                }
                out.add(frame(a.t + span * u, v, channel));
            }
        }
        if (keys.get(0).t > 0.0F) {
            // Первый ключ не в нуле: до него держится его значение.
            out.set(0, frame(0.0F, keys.get(0).v, channel));
        }
        return out.toArray(new Keyframe[0]);
    }

    private static Keyframe frame(float t, float[] v, String channel) {
        Vector3f vec = channel.equals("position") ? KeyframeAnimations.posVec(v[0], v[1], v[2])
                : KeyframeAnimations.degreeVec(v[0], v[1], v[2]);
        return new Keyframe(t, vec, AnimationChannel.Interpolations.LINEAR);
    }

    static float catmull(float p0, float p1, float p2, float p3, float t) {
        float t2 = t * t;
        float t3 = t2 * t;
        return 0.5F * (2.0F * p1 + (-p0 + p2) * t + (2.0F * p0 - 5.0F * p1 + 4.0F * p2 - p3) * t2
                + (-p0 + 3.0F * p1 - 3.0F * p2 + p3) * t3);
    }

    /** Изинги PAL/GeckoLib по имени (easings.net); неизвестный — линейный. */
    static float ease(String name, float x) {
        if (name == null) {
            return x;
        }
        double t = x;
        double r = switch (name) {
            case "easeInSine" -> 1 - Math.cos(t * Math.PI / 2);
            case "easeOutSine" -> Math.sin(t * Math.PI / 2);
            case "easeInOutSine" -> -(Math.cos(Math.PI * t) - 1) / 2;
            case "easeInQuad" -> t * t;
            case "easeOutQuad" -> 1 - (1 - t) * (1 - t);
            case "easeInOutQuad" -> t < 0.5 ? 2 * t * t : 1 - Math.pow(-2 * t + 2, 2) / 2;
            case "easeInCubic" -> t * t * t;
            case "easeOutCubic" -> 1 - Math.pow(1 - t, 3);
            case "easeInOutCubic" -> t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
            case "easeInQuart" -> t * t * t * t;
            case "easeOutQuart" -> 1 - Math.pow(1 - t, 4);
            case "easeInExpo" -> t == 0 ? 0 : Math.pow(2, 10 * t - 10);
            case "easeOutExpo" -> t == 1 ? 1 : 1 - Math.pow(2, -10 * t);
            case "easeInOutExpo" -> t == 0 ? 0 : t == 1 ? 1 : t < 0.5 ? Math.pow(2, 20 * t - 10) / 2 : (2 - Math.pow(2, -20 * t + 10)) / 2;
            case "easeOutBack" -> 1 + 2.70158 * Math.pow(t - 1, 3) + 1.70158 * Math.pow(t - 1, 2);
            default -> t;
        };
        return (float) r;
    }

    private static float[] values(JsonElement e) {
        if (e == null) {
            return new float[] {0.0F, 0.0F, 0.0F};
        }
        if (e.isJsonPrimitive()) {
            float f = number(e);
            return new float[] {f, f, f};
        }
        JsonArray a = e.getAsJsonArray();
        return new float[] {number(a.get(0)), number(a.get(1)), number(a.get(2))};
    }

    private static float number(JsonElement e) {
        try {
            return e.getAsFloat();
        } catch (RuntimeException ex) {
            return 0.0F;
        }
    }

    private PalClips() {
    }
}
