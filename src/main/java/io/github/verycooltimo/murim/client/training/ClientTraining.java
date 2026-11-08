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
 * Client side of the sect drills (stone carry, South Peak climb, trail sprint): who does what (for the clip and the stone
 * in the hands), and the local player's drill for the HUD. Fed by {@link TrainingPayloads}; decides nothing.
 *
 * <p>Clip (blockout, {@code tools/art/training_anims.py}; the author polishes it in Blockbench): {@code training_carry}
 * loop (upper body). The squat / push-up / horse clips are gone with the Shift-started sets (25.10).
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class ClientTraining {

    static final ResourceLocation CARRY = id("training_carry");

    /** Drill of each player entity in view (props, clip end). */
    private static final Map<Integer, Exercise> ACTIVE = new HashMap<>();

    /** Local player's latest state and when it came (client game time). */
    private static TrainingPayloads.State local;
    private static long localAt;
    /** Last event that the HUD flashes, and when. */
    private static TrainingPayloads.Beat flash = TrainingPayloads.Beat.NONE;
    private static long flashAt;
    private static TrainingPayloads.Body body = new TrainingPayloads.Body(0, 0.0F, 0, 0);

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
                beatSound(s.beat());
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

    /**
     * A quiet knock for the trainee only: a block gained with the stone, a checkpoint reached.
     * API: reference/minecraft-src/net/minecraft/client/resources/sounds/SimpleSoundInstance.java#forUI
     */
    private static void beatSound(TrainingPayloads.Beat beat) {
        var rep = io.github.verycooltimo.murim.registry.ModSounds.TRAINING_REP;
        switch (beat) {
            case GAIN -> io.github.verycooltimo.murim.client.Sfx.ui(rep, 0.5F, 1.25F);
            case REACH -> io.github.verycooltimo.murim.client.Sfx.ui(rep, 0.55F, 1.5F);
            default -> {
            }
        }
    }

    private static TrainingPayloads.State finished;
    private static long finishedAt;

    /** Plays the clip of a drill event on a player (the owner and anyone watching). */
    private static void clip(AbstractClientPlayer player, Exercise e, Exercise before, TrainingPayloads.Beat beat) {
        if (e == null) {
            return;
        }
        switch (beat) {
            case START -> {
                if (e == Exercise.CARRY_STONE) {
                    MurimPlayerAnimations.playBlended(player, CARRY, 5);
                }
            }
            case END -> {
                Exercise was = before != null ? before : e;
                if (!was.route()) {
                    MurimPlayerAnimations.stopBlended(player, 6);
                }
            }
            default -> {
            }
        }
    }

    /** Drill a player entity is doing right now (props layer), or null. */
    public static Exercise of(int entityId) {
        return ACTIVE.get(entityId);
    }

    /** The local player's drill, or null. */
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
     * the local player does a drill: the trail sprint is without qi.
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
