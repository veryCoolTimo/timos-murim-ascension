package io.github.verycooltimo.murim.client.sect.face;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * The face of a sect student, built from parts (docs/design/58-face-constructor.md). The renderer asks {@link #texture} for the
 * skin to draw: the plain skin when the constructor is off (the default until the author has drawn the parts), the skin with
 * the composed face when it is on. Client only; nothing here touches the server or the save.
 *
 * <p>The parts of a person come from his member key ({@link FaceSpec}); an NPC without a key (stand, wanderer) uses his look,
 * then his UUID. The mood follows what the entity is doing: asleep, hurt; the dev command can force one.
 * API: reference neoforge-src net/neoforged/neoforge/client/event/RegisterClientReloadListenersEvent.java
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class FaceConstructor {

    /** Dev switch: null follows the config {@code hud.faceConstructor}; the stand sets MURIM_FACES=1. */
    private static Boolean forced = "1".equals(System.getenv("MURIM_FACES")) ? Boolean.TRUE : null;

    /** Per-entity dev overrides (entity id): a salt that re-rolls the face, a forced mood, forced parts. */
    private record Override(int salt, FaceMood mood, FaceSpec spec) {
        Override withSalt(int s) {
            return new Override(s, mood, null);
        }

        Override withMood(FaceMood m) {
            return new Override(salt, m, spec);
        }

        Override withSpec(FaceSpec s) {
            return new Override(salt, mood, s);
        }
    }

    private static final Map<Integer, Override> OVERRIDES = new HashMap<>();

    private FaceConstructor() {
    }

    @SubscribeEvent
    static void onRegisterReload(RegisterClientReloadListenersEvent event) {
        event.registerReloadListener((ResourceManagerReloadListener) resources -> {
            OVERRIDES.clear();
            FaceTextures.reload(resources);
        });
    }

    public static boolean enabled() {
        return forced != null ? forced : ClientConfig.FACE_CONSTRUCTOR.get();
    }

    /** Dev: true / false force it; null gives control back to the config. */
    public static void force(Boolean on) {
        forced = on;
    }

    /** The texture to draw this person with: {@code skin} unless the constructor is on and the skin has a face rectangle. */
    public static ResourceLocation texture(SectDisciple e, ResourceLocation skin) {
        if (!enabled() || !FaceTextures.available() || !FaceTextures.hasFace(skin)) {
            return skin;
        }
        FaceParts parts = FaceTextures.parts().get();
        Override o = OVERRIDES.get(e.getId());
        FaceSpec spec = o != null && o.spec() != null ? o.spec() : specOf(e, parts, o == null ? 0 : o.salt());
        FaceMood mood = o != null && o.mood() != null ? o.mood() : moodOf(e);
        return FaceTextures.get(skin, spec, mood, e.tickCount / 5);
    }

    /** The person's seed: his member key, else his look, else his UUID. */
    static String seedOf(SectDisciple e) {
        String key = e.memberKey();
        if (!key.isEmpty()) {
            return key;
        }
        return e.look().isEmpty() ? e.getUUID().toString() : e.look();
    }

    private static FaceSpec specOf(SectDisciple e, FaceParts p, int salt) {
        return FaceSpec.of(seedOf(e), salt, p.count("eyes"), p.count("brows"), p.count("nose"), p.count("mouth"));
    }

    /** What the face shows now: asleep, hurt (the vanilla hurt time, 10 ticks); the rest is the neutral face.
     *  No talking mouth on its own (author 27.10: «анимации разговора не надо»); the dev command can still force TALK. */
    static FaceMood moodOf(SectDisciple e) {
        if (e.isSleeping() || e.pose() == SectPose.SLEEP) {
            return FaceMood.ASLEEP;
        }
        if (e.hurtTime > 0) {
            return FaceMood.PAIN;
        }
        return FaceMood.NEUTRAL;
    }

    // ------------------------------------------------------------------ dev (FaceDevCommand)

    /** The current parts of a person as the renderer would pick them, with overrides; empty when the art is missing. */
    static Optional<FaceSpec> specNow(SectDisciple e) {
        if (!FaceTextures.available()) {
            return Optional.empty();
        }
        Override o = OVERRIDES.get(e.getId());
        return Optional.of(o != null && o.spec() != null ? o.spec() : specOf(e, FaceTextures.parts().get(), o == null ? 0 : o.salt()));
    }

    /** Re-roll the face of one person: next salt. Returns the new parts. */
    static Optional<FaceSpec> cycle(SectDisciple e) {
        Override o = OVERRIDES.getOrDefault(e.getId(), new Override(0, null, null));
        OVERRIDES.put(e.getId(), o.withSalt(o.salt() + 1));
        return specNow(e);
    }

    static void setMood(SectDisciple e, FaceMood mood) {
        OVERRIDES.put(e.getId(), OVERRIDES.getOrDefault(e.getId(), new Override(0, null, null)).withMood(mood));
    }

    /** Forced parts, 1-based like the template labels (EYE1..): clamped to the number of variants. */
    static Optional<FaceSpec> setParts(SectDisciple e, int eyes, int brows, int nose, int mouth) {
        if (!FaceTextures.available()) {
            return Optional.empty();
        }
        FaceParts p = FaceTextures.parts().get();
        FaceSpec s = new FaceSpec(clamp(eyes, p.count("eyes")), clamp(brows, p.count("brows")), clamp(nose, p.count("nose")),
                clamp(mouth, p.count("mouth")));
        OVERRIDES.put(e.getId(), OVERRIDES.getOrDefault(e.getId(), new Override(0, null, null)).withSpec(s));
        return Optional.of(s);
    }

    private static int clamp(int oneBased, int count) {
        return Math.max(0, Math.min(count - 1, oneBased - 1));
    }

    static void clearOverrides() {
        OVERRIDES.clear();
    }

    /** Test / stand hook: the composed texture for explicit parts and mood, without an entity. */
    public static ResourceLocation compose(ResourceLocation skin, FaceSpec spec, FaceMood mood, int phase) {
        return FaceTextures.get(skin, spec, mood, phase);
    }
}
