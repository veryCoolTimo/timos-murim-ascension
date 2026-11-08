package io.github.verycooltimo.murim.client;

import com.zigythebird.playeranim.animation.PlayerAnimationController;
import io.github.verycooltimo.murim.network.SixFormPayload;
import io.github.verycooltimo.murim.technique.SixRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.HashMap;
import java.util.Map;

/** Forms share the technique controller with footwork so PAL snapshots the visible outgoing pose.
 * The single authored clip is time-warped: chain accelerates after the first thrust; counter holds
 * guard until an actual trigger. No foundation controller or LMB clip is changed. */
@EventBusSubscriber(modid = "murim", value = Dist.CLIENT)
public final class SixFormAnimations {
    private static final class Cast {
        final int form, tier; int age, trigger = -1; float position;
        Cast(int form, int tier) { this.form = form; this.tier = tier; }
    }
    private static final Map<Integer, Cast> casts = new HashMap<>();
    public static void accept(SixFormPayload p) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !(mc.level.getEntity(p.casterId()) instanceof AbstractClientPlayer player)) return;
        PlayerAnimationController controller = MurimPlayerAnimations.techniqueController(player);
        if (controller == null) return;
        if (p.event() == SixFormPayload.START) {
            casts.put(p.casterId(), new Cast(p.form(), p.tier()));
            MurimPlayerAnimations.speed(controller, 0);
            MurimPlayerAnimations.start(controller, clip(p.form()), 0);
        } else if (p.event() == SixFormPayload.END || p.event() == SixFormPayload.CANCEL) {
            Cast cast = casts.get(p.casterId());
            if (cast != null && cast.form == p.form()) {
                casts.remove(p.casterId());
                if (MurimPlayerAnimations.owns(controller, clip(cast.form)))
                    MurimPlayerAnimations.stopBlended(controller, AnimationTransitions.STEP_FADE_OUT_TICKS);
            }
        } else if (p.event() == SixFormPayload.FREEZE && p.form() == 4) {
            Cast s = casts.get(p.casterId()); if (s != null && s.trigger < 0) s.trigger = s.age;
        }
    }
    private static ResourceLocation clip(int form) {
        return ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies_" + SixRules.FORMS[form]);
    }

    @SubscribeEvent static void tick(ClientTickEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) { casts.clear(); return; }
        if (mc.isPaused()) return;
        casts.entrySet().removeIf(entry -> {
            Cast s = entry.getValue();
            if (!(mc.level.getEntity(entry.getKey()) instanceof AbstractClientPlayer player)) return true;
            PlayerAnimationController controller = MurimPlayerAnimations.techniqueController(player);
            if (controller == null || !MurimPlayerAnimations.owns(controller, clip(s.form))) return true;
            ++s.age;
            if (s.age > SixRules.total(s.form) + 2) {
                MurimPlayerAnimations.stopBlended(controller, AnimationTransitions.STEP_FADE_OUT_TICKS);
                return true;
            }
            if (s.form == 4 && s.tier < 4 && s.trigger >= 0 && s.age - s.trigger >= SixRules.COUNTER_RECOVER) {
                MurimPlayerAnimations.stopBlended(controller, AnimationTransitions.STEP_FADE_OUT_TICKS);
                return true;
            }
            float next = SixRules.animationTick(s.form, s.tier, s.age, s.trigger);
            MurimPlayerAnimations.speed(controller, Math.max(0, next - s.position)); s.position = next;
            return false;
        });
    }
    private SixFormAnimations() { }
}
