package io.github.verycooltimo.murim.client.bedrock;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.animation.AnimationChannel;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.Keyframe;
import net.minecraft.client.animation.KeyframeAnimations;
import org.joml.Vector3f;

import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Анимации Bedrock ({@code *.animation.json} из Blockbench, формат 1.8.0) → ванильные
 * {@link AnimationDefinition} для {@link KeyframeAnimations#animate}.
 * API: reference/minecraft-src/net/minecraft/client/animation/KeyframeAnimations.java
 *
 * <p>Поддержано то, что даёт Blockbench без молангов: ключи {@code "время": [x, y, z]} или
 * {@code {"post": [...], "lerp_mode": "catmullrom"}}, статичное значение без ключей, каналы
 * rotation / position / scale. Позиция по y переворачивается (у Java y вниз) — так делает и сама
 * {@link KeyframeAnimations#posVec}. Строковые значения-числа читаются, моланг — ноль.
 * Имя клипа — хвост после последней точки: {@code animation.bandit.windup} → {@code windup}.
 */
public final class BedrockAnim {

    public static Map<String, AnimationDefinition> parse(Reader reader) {
        JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
        Map<String, AnimationDefinition> out = new HashMap<>();
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("animations").entrySet()) {
            String key = e.getKey();
            String name = key.substring(key.lastIndexOf('.') + 1);
            out.put(name, clip(e.getValue().getAsJsonObject()));
        }
        return out;
    }

    private static AnimationDefinition clip(JsonObject a) {
        float length = a.has("animation_length") ? a.get("animation_length").getAsFloat() : 0.0F;
        boolean loop = a.has("loop") && a.get("loop").isJsonPrimitive() && a.get("loop").getAsJsonPrimitive().isBoolean()
                && a.get("loop").getAsBoolean();
        float maxTime = 0.0F;
        Map<String, List<AnimationChannel>> bones = new HashMap<>();
        if (a.has("bones")) {
            for (Map.Entry<String, JsonElement> b : a.getAsJsonObject("bones").entrySet()) {
                JsonObject channels = b.getValue().getAsJsonObject();
                List<AnimationChannel> list = new ArrayList<>();
                for (String ch : new String[] {"rotation", "position", "scale"}) {
                    if (!channels.has(ch)) {
                        continue;
                    }
                    Keyframe[] frames = frames(channels.get(ch), ch);
                    if (frames.length == 0) {
                        continue;
                    }
                    maxTime = Math.max(maxTime, frames[frames.length - 1].timestamp());
                    AnimationChannel.Target target = switch (ch) {
                        case "position" -> AnimationChannel.Targets.POSITION;
                        case "scale" -> AnimationChannel.Targets.SCALE;
                        default -> AnimationChannel.Targets.ROTATION;
                    };
                    list.add(new AnimationChannel(target, frames));
                }
                bones.put(b.getKey(), list);
            }
        }
        return new AnimationDefinition(length > 0.0F ? length : Math.max(0.05F, maxTime), loop, bones);
    }

    private static Keyframe[] frames(JsonElement e, String channel) {
        List<Keyframe> out = new ArrayList<>();
        if (e.isJsonArray() || e.isJsonPrimitive()) {
            out.add(new Keyframe(0.0F, value(values(e), channel), AnimationChannel.Interpolations.LINEAR));
        } else {
            for (Map.Entry<String, JsonElement> k : e.getAsJsonObject().entrySet()) {
                float t;
                try {
                    t = Float.parseFloat(k.getKey());
                } catch (NumberFormatException ex) {
                    continue;
                }
                JsonElement v = k.getValue();
                AnimationChannel.Interpolation lerp = AnimationChannel.Interpolations.LINEAR;
                if (v.isJsonObject()) {
                    JsonObject o = v.getAsJsonObject();
                    if (o.has("lerp_mode") && "catmullrom".equals(o.get("lerp_mode").getAsString())) {
                        lerp = AnimationChannel.Interpolations.CATMULLROM;
                    }
                    v = o.has("post") ? o.get("post") : o.get("pre");
                }
                out.add(new Keyframe(t, value(values(v), channel), lerp));
            }
        }
        out.sort((x, y) -> Float.compare(x.timestamp(), y.timestamp()));
        return out.toArray(new Keyframe[0]);
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

    private static Vector3f value(float[] v, String channel) {
        return switch (channel) {
            case "position" -> KeyframeAnimations.posVec(v[0], v[1], v[2]);
            case "scale" -> KeyframeAnimations.scaleVec(v[0], v[1], v[2]);
            default -> KeyframeAnimations.degreeVec(v[0], v[1], v[2]);
        };
    }

    private BedrockAnim() {
    }
}
