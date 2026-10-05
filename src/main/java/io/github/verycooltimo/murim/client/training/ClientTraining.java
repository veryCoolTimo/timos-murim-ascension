package io.github.verycooltimo.murim.client.training;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.MurimPlayerAnimations;
import io.github.verycooltimo.murim.training.Exercise;
import io.github.verycooltimo.murim.training.TrainingPayloads;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;

import java.util.HashMap;
import java.util.Map;

/**
 * Client side of body training: who trains what (for the clips and the props on the back and in the hands), and the
 * local player's set for the HUD. Fed by {@link TrainingPayloads}; decides nothing.
 *
 * <p>Clips (blockout, {@code tools/art/training_anims.py}; the author polishes them in Blockbench):
 * {@code training_squat} per rep, {@code training_horse} loop, {@code training_plank} into the plank (holds),
 * {@code training_pushup(_weighted)} per rep (starts and ends in the plank, holds), {@code training_plank_up} out of it,
 * {@code training_carry} loop (upper body).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientTraining {

    static final ResourceLocation SQUAT = id("training_squat");
    static final ResourceLocation HORSE = id("training_horse");
    static final ResourceLocation PLANK = id("training_plank");
    static final ResourceLocation PUSHUP = id("training_pushup");
    static final ResourceLocation PUSHUP_WEIGHTED = id("training_pushup_weighted");
    static final ResourceLocation PLANK_UP = id("training_plank_up");
    static final ResourceLocation CARRY = id("training_carry");

    /** Exercise of each player entity in view (props, clip end). */
    private static final Map<Integer, Exercise> ACTIVE = new HashMap<>();

    /** Local player's latest state and when it came (client game time). */
    private static TrainingPayloads.State local;
    private static long localAt;
    /** Last event that the HUD flashes, and when. */
    private static TrainingPayloads.Beat flash = TrainingPayloads.Beat.NONE;
    private static long flashAt;
    private static TrainingPayloads.Body body = new TrainingPayloads.Body(0, 0.0F, 0.0F, 1.0F, 0, 0);

    private ClientTraining() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    public static void onBody(TrainingPayloads.Body payload) {
        body = payload;
    }

    public static void onState(TrainingPayloads.State s) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Exercise e = s.exerciseOrNull();
        boolean ended = s.beat() == TrainingPayloads.Beat.END || s.beat() == TrainingPayloads.Beat.VOID
                || s.beat() == TrainingPayloads.Beat.FINISH || e == null;
        Exercise before = ACTIVE.get(s.entity());
        if (ended) {
            ACTIVE.remove(s.entity());
        } else {
            ACTIVE.put(s.entity(), e);
        }
        if (mc.player != null && s.entity() == mc.player.getId()) {
            local = ended ? null : s;
            localAt = mc.level.getGameTime();
            if (s.beat() != TrainingPayloads.Beat.NONE) {
                flash = s.beat();
                flashAt = localAt;
            }
            if (s.beat() == TrainingPayloads.Beat.FINISH) {
                // The finished run stays on the HUD for a few seconds with its time.
                finished = s;
                finishedAt = localAt;
            }
        }
        Entity entity = mc.level.getEntity(s.entity());
        if (entity instanceof AbstractClientPlayer player) {
            clip(player, e, before, s.beat());
        }
    }

    private static TrainingPayloads.State finished;
    private static long finishedAt;

    /** Plays the clip of a training event on a player (the owner and anyone watching). */
    private static void clip(AbstractClientPlayer player, Exercise e, Exercise before, TrainingPayloads.Beat beat) {
        if (e == null) {
            return;
        }
        switch (beat) {
            case START -> {
                if (e.pushup()) {
                    MurimPlayerAnimations.play(player, PLANK);
                } else if (e == Exercise.CARRY_STONE) {
                    MurimPlayerAnimations.play(player, CARRY);
                }
            }
            case GOOD, FAIR, OFF -> {
                if (e == Exercise.SQUAT) {
                    MurimPlayerAnimations.play(player, SQUAT);
                } else if (e.pushup()) {
                    MurimPlayerAnimations.play(player, e == Exercise.PUSHUP_WEIGHTED ? PUSHUP_WEIGHTED : PUSHUP);
                }
            }
            case SWITCH -> {
                if (e == Exercise.HORSE_STANCE) {
                    MurimPlayerAnimations.play(player, HORSE);
                } else {
                    MurimPlayerAnimations.stop(player);
                }
            }
            case END -> {
                Exercise was = before != null ? before : e;
                if (was.pushup()) {
                    MurimPlayerAnimations.play(player, PLANK_UP);
                } else if (!was.route()) {
                    MurimPlayerAnimations.stop(player);
                }
            }
            default -> {
            }
        }
    }

    /** Exercise a player entity is doing right now (props layer), or null. */
    public static Exercise of(int entityId) {
        return ACTIVE.get(entityId);
    }

    /** The local player's set or run, or null. */
    public static TrainingPayloads.State local() {
        return local;
    }

    public static long localAt() {
        return localAt;
    }

    public static TrainingPayloads.Beat flash() {
        return flash;
    }

    public static long flashAt() {
        return flashAt;
    }

    public static TrainingPayloads.Body body() {
        return body;
    }

    public static TrainingPayloads.State finished() {
        return finished;
    }

    public static long finishedAt() {
        return finishedAt;
    }

    /**
     * Auto-run and auto-shadow of footwork (sprint 0.5 s, still crouch 0.7 s — ClientTechniqueHandler) stay quiet while
     * the local player trains: a held crouch here is a horse stance, a sprint up the trail is without qi.
     */
    public static boolean suppressFootwork() {
        return local != null;
    }

    @SubscribeEvent
    static void onLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ACTIVE.clear();
        local = null;
        finished = null;
    }
}
