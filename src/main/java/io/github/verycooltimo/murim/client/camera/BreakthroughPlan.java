package io.github.verycooltimo.murim.client.camera;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The breakthrough camera as data: the shot library and, per target rank, the sequence of shots with the share of the
 * scene each one gets. Loaded from {@code assets/murim/camera/breakthrough.json} (F3+T reloads it), so a sequence is
 * changed by editing text, not code. Pure maths and parsing: no Minecraft client classes ({@code BreakthroughPlanTest}).
 *
 * <p>Shot numbers used in talk and in the capture stand: 1 closeup, 2 low, 3 shoulder, 4 orbit, 5 crane.
 */
public final class BreakthroughPlan {

    /**
     * One entry of a sequence.
     *
     * @param share fraction of the scene (the shares of a sequence are normalised, so any positive numbers work)
     * @param blend ticks to glide from the end of the previous shot into this one; 0 = a hard cut
     * @param flip    take the opposite side from the one the scene chose (for shots that mirror)
     * @param reverse play the shot backwards (an orbit that ends where the next shot begins)
     */
    public record Step(ShotTrack shot, float share, int blend, boolean flip, boolean reverse) {
    }

    /** Which step a scene tick falls in. */
    public record Cue(int index, Step step, float local, float tickInto) {
    }

    private final Map<String, ShotTrack> shots;
    private final Map<Integer, List<Step>> plans;
    private final Map<String, List<Step>> named;
    private final int returnTicks;

    private BreakthroughPlan(Map<String, ShotTrack> shots, Map<Integer, List<Step>> plans, Map<String, List<Step>> named, int returnTicks) {
        this.shots = shots;
        this.plans = plans;
        this.named = named;
        this.returnTicks = returnTicks;
    }

    /** Ticks of the glide back to the player's own camera at the end of the scene. */
    public int returnTicks() {
        return returnTicks;
    }

    public Map<String, ShotTrack> shots() {
        return shots;
    }

    /** The sequence for a target rank (1 third .. 4 Peak); an unknown rank gets the nearest lower plan. */
    public List<Step> steps(int rank) {
        for (int r = rank; r >= 1; r--) {
            List<Step> found = plans.get(r);
            if (found != null) {
                return found;
            }
        }
        return plans.values().iterator().next();
    }

    /** A plan under a non-numeric key (the Peak variants "4a", "4b", "4c"), or {@code null}. */
    public List<Step> named(String key) {
        return named.get(key);
    }

    /** The single static shot used when the data file is missing or broken: the old front close-up. */
    public static BreakthroughPlan fallback() {
        ShotTrack front = new ShotTrack("front", 0, ShotTrack.Ease.LINEAR, 0.0F, false,
                List.of(new ShotTrack.Key(0.0F, ShotPose.FRONT)));
        Map<String, ShotTrack> shots = new LinkedHashMap<>();
        shots.put("front", front);
        Map<Integer, List<Step>> plans = new LinkedHashMap<>();
        plans.put(1, List.of(new Step(front, 1.0F, 0, false, false)));
        return new BreakthroughPlan(shots, plans, new LinkedHashMap<>(), 12);
    }

    /** Same director, wall checks and accessibility scaling; only the shot keys and clock differ. */
    public static List<Step> creationSteps(ShotPose start) {
        var close=new ShotPose(0,8,.85F,.48F,0,1);
        var front=new ShotPose(0,12,2.6F,.68F,0,1);
        double burst=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.BURST_START;
        double grow=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.MERIDIAN_START;
        double title=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.TITLE_START;
        float end=io.github.verycooltimo.murim.client.vfx.preset.DantianFlowMath.SEED_TICKS/20F;
        var push=new ShotTrack("dantian_push",0,ShotTrack.Ease.IN_OUT,0,false,List.of(
                new ShotTrack.Key(0,start),new ShotTrack.Key((float)(.15/burst),start),
                new ShotTrack.Key(1,close)));
        var hold=new ShotTrack("dantian_burst",0,ShotTrack.Ease.LINEAR,0,false,List.of(new ShotTrack.Key(0,close)));
        var pull=new ShotTrack("dantian_pull",0,ShotTrack.Ease.IN_OUT,1,false,List.of(
                new ShotTrack.Key(0,close),new ShotTrack.Key(1,front)));
        var settle=new ShotTrack("dantian_title",0,ShotTrack.Ease.LINEAR,0,false,List.of(new ShotTrack.Key(0,front)));
        return List.of(new BreakthroughPlan.Step(push,(float)burst,0,false,false),
                new BreakthroughPlan.Step(hold,(float)(grow-burst),0,false,false),
                new BreakthroughPlan.Step(pull,(float)(title-grow),0,false,false),
                new BreakthroughPlan.Step(settle,end-(float)title,0,false,false));
    }

    public static BreakthroughPlan parse(JsonObject root) {
        Map<String, ShotTrack> shots = new LinkedHashMap<>();
        JsonObject shotsJson = root.getAsJsonObject("shots");
        if (shotsJson == null || shotsJson.size() == 0) {
            throw new IllegalArgumentException("no \"shots\"");
        }
        for (Map.Entry<String, JsonElement> e : shotsJson.entrySet()) {
            shots.put(e.getKey(), parseShot(e.getKey(), e.getValue().getAsJsonObject()));
        }
        Map<Integer, List<Step>> plans = new LinkedHashMap<>();
        Map<String, List<Step>> named = new LinkedHashMap<>();
        JsonObject plansJson = root.getAsJsonObject("plans");
        if (plansJson == null || plansJson.size() == 0) {
            throw new IllegalArgumentException("no \"plans\"");
        }
        for (Map.Entry<String, JsonElement> e : plansJson.entrySet()) {
            List<Step> steps = new ArrayList<>();
            for (JsonElement item : e.getValue().getAsJsonArray()) {
                JsonObject o = item.getAsJsonObject();
                ShotTrack shot = find(shots, o.get("shot").getAsString());
                float share = num(o, "share", 1.0F);
                if (share <= 0.0F) {
                    throw new IllegalArgumentException("plan " + e.getKey() + ": share must be positive");
                }
                steps.add(new Step(shot, share, (int) num(o, "blend", 0.0F),
                        o.has("flip") && o.get("flip").getAsBoolean(), o.has("reverse") && o.get("reverse").getAsBoolean()));
            }
            if (steps.isEmpty()) {
                throw new IllegalArgumentException("plan " + e.getKey() + " is empty");
            }
            if (e.getKey().matches("\\d+")) {
                plans.put(Integer.parseInt(e.getKey()), steps);
            } else {
                named.put(e.getKey(), steps);
            }
        }
        return new BreakthroughPlan(shots, plans, named, (int) num(root, "return_ticks", 12.0F));
    }

    private static ShotTrack parseShot(String name, JsonObject o) {
        List<ShotTrack.Key> keys = new ArrayList<>();
        JsonArray array = o.getAsJsonArray("keys");
        if (array == null) {
            throw new IllegalArgumentException("shot " + name + " has no keys");
        }
        for (JsonElement k : array) {
            JsonObject key = k.getAsJsonObject();
            keys.add(new ShotTrack.Key(num(key, "t", 0.0F), new ShotPose(num(key, "az", 0.0F), num(key, "pitch", 0.0F),
                    num(key, "dist", 2.0F), num(key, "focus", 0.9F), num(key, "roll", 0.0F), num(key, "fov", 1.0F))));
        }
        ShotTrack.Ease ease = ShotTrack.Ease.valueOf(o.has("ease") ? o.get("ease").getAsString().toUpperCase(Locale.ROOT) : "IN_OUT");
        return new ShotTrack(name, (int) num(o, "id", 0.0F), ease, num(o, "rest", 0.5F),
                !o.has("mirror") || o.get("mirror").getAsBoolean(), keys);
    }

    private static float num(JsonObject o, String key, float fallback) {
        return o.has(key) ? o.get(key).getAsFloat() : fallback;
    }

    private static ShotTrack find(Map<String, ShotTrack> shots, String ref) {
        ShotTrack byName = shots.get(ref);
        if (byName != null) {
            return byName;
        }
        for (ShotTrack s : shots.values()) {
            if (String.valueOf(s.id()).equals(ref)) {
                return s;
            }
        }
        throw new IllegalArgumentException("unknown shot \"" + ref + "\"");
    }

    /**
     * A sequence typed by hand (capture stand, {@code MURIM_CAMERA_SHOTS}): {@code "1,3"} or
     * {@code "orbit:2,crane:1"} - shot name or number, optional share after a colon.
     */
    public List<Step> sequence(String spec) {
        List<Step> steps = new ArrayList<>();
        for (String token : spec.split(",")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            int colon = t.indexOf(':');
            String ref = colon < 0 ? t : t.substring(0, colon);
            float share = colon < 0 ? 1.0F : Float.parseFloat(t.substring(colon + 1));
            steps.add(new Step(find(shots, ref), share, 0, false, false));
        }
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("empty shot list \"" + spec + "\"");
        }
        return steps;
    }

    /** The step a scene tick falls in, and how far into it. After the scene the last step holds at its end. */
    public static Cue cue(List<Step> steps, float tick, float total) {
        float sum = 0.0F;
        for (Step s : steps) {
            sum += s.share();
        }
        float start = 0.0F;
        for (int i = 0; i < steps.size(); i++) {
            Step s = steps.get(i);
            float len = total * s.share() / sum;
            boolean last = i == steps.size() - 1;
            if (tick < start + len || last) {
                float into = Math.max(0.0F, tick - start);
                return new Cue(i, s, Math.min(1.0F, len <= 0.0F ? 1.0F : into / len), into);
            }
            start += len;
        }
        throw new IllegalStateException("unreachable");
    }

    /**
     * The pose of a sequence at a scene tick.
     *
     * @param side   +1 or -1: which way the scene turns (orbit direction, which shoulder); only mirrored shots use it
     * @param motion 1 = the camera moves as authored; 0 = it holds each shot's rest pose (reduced shake): the cut
     *               between shots stays, the glide into a shot does not
     */
    public static ShotPose poseAt(List<Step> steps, float tick, float total, int side, float motion) {
        Cue cue = cue(steps, tick, total);
        ShotPose now = shotPose(cue.step(), cue.local(), side, motion);
        if (cue.index() > 0 && cue.step().blend() > 0 && motion > 0.01F && cue.tickInto() < cue.step().blend()) {
            ShotPose before = shotPose(steps.get(cue.index() - 1), 1.0F, side, motion);
            float k = cue.tickInto() / cue.step().blend();
            return before.lerp(now, k * k * (3.0F - 2.0F * k));
        }
        return now;
    }

    /** One step at a local time 0..1, with the motion scale and the side applied. */
    public static ShotPose shotPose(Step step, float local, int side, float motion) {
        ShotPose moving = step.shot().at(step.reverse() ? 1.0F - local : local);
        ShotPose base = motion >= 0.999F ? moving : step.shot().restPose().lerp(moving, Math.max(0.0F, motion));
        int s = step.shot().mirror() ? (step.flip() ? -side : side) : 1;
        return base.mirrored(s);
    }
}
