package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.List;
import java.util.function.IntPredicate;

/**
 * Author 31.10: fragments restore only Rainfall. The first fragment permits two stars; subsequent fragments add one,
 * up to five. Chung Myung's order is a clue to the complete art below the Main Hall, never a deduction of other forms.
 * The vault's complete style manual permits all five forms up to twelve stars, studied in order from the Peak with
 * the previous form at five stars. Fragment restoration is checked on reading and every five seconds.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PlumRestoration {

    public static final List<ResourceLocation> FORMS = Styles.TWENTY_FOUR_PLUM.forms();
    /** Fragment ids {@code frag_0 .. frag_5}. */
    public static final int FRAGMENTS = 6;
    /** Initial star limit from one fragment. */
    public static final int STARS_PER_FRAGMENT = 2;

    private PlumRestoration() {
    }

    public static String clue(int n) {
        return "frag_" + n;
    }

    /** Indexes into {@link #FORMS} of the forms restored by the fragments {@code has} says are known. */
    public static List<Integer> restored(IntPredicate has) {
        for (int i = 0; i < FRAGMENTS; i++) {
            if (has.test(i)) return List.of(0);
        }
        return List.of();
    }

    /** Star limit of the restored forms. */
    public static int cap(IntPredicate has) {
        int n = 0;
        for (int i = 0; i < FRAGMENTS; i++) {
            if (has.test(i)) n++;
        }
        return n == 0 ? 0 : Math.min(5, STARS_PER_FRAGMENT + n - 1);
    }

    public static IntPredicate known(ServerPlayer p) {
        return n -> Clues.known(p, clue(n));
    }

    /** Learns or deepens whatever the fragments of this player restore and the Peak and the form before allow. */
    public static void sync(ServerPlayer p) {
        IntPredicate has = known(p);
        int cap = cap(has);
        if (cap <= 0 || !MasteryRules.canLearn(p.getData(ModAttachments.PROFILE))) {
            return;
        }
        for (int i : restored(has)) {
            ResourceLocation form = FORMS.get(i);
            TechniqueDefinition def = TechniqueLoader.get(form);
            if (def == null || !MasteryRules.rankAllows(p.getData(ModAttachments.PROFILE), def.tier())) {
                continue;
            }
            MasteryState state = p.getData(ModAttachments.MASTERY);
            TechniqueProgress now = state.techniques().get(form);
            if (now != null && Math.min(cap, def.starCap()) <= now.cap()) {
                continue;
            }
            if (!MasteryRules.missing(def.requires(), state.stars()).isEmpty()) {
                continue; // the form before it is not at five stars yet
            }
            boolean fresh = now == null;
            if (MasteryService.learn(p, form, cap) == MasteryService.Learn.LEARNED && fresh) {
                PlayerJournal.record(p, Component.translatable("murim.plum.restored", MasteryService.name(form), cap).withStyle(ChatFormatting.GOLD));
            }
        }
    }

    /** A fragment was read or picked up. */
    public static void onClue(ServerPlayer p, String clue) {
        if (clue != null && clue.startsWith("frag_")) {
            sync(p);
        }
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.tickCount % 100 == 37) {
            sync(p);
        }
    }
}
