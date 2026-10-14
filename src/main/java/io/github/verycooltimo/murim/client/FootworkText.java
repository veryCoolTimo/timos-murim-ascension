package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.mastery.MasteryRules;
import io.github.verycooltimo.murim.technique.FootworkFamily;
import io.github.verycooltimo.murim.technique.ShadowRules;
import io.github.verycooltimo.murim.technique.Styles;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import io.github.verycooltimo.murim.technique.TraverseRules;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One source of live God Wind stats for the manual, tooltip and selected HUD form. */
public final class FootworkText {
    public static List<Component> lines(ResourceLocation id) {
        return lines(id, true);
    }

    /** Live combat stats omit the mastery gauge; stars belong to menus and new-star insight. */
    public static List<Component> combatLines(ResourceLocation id) {
        return lines(id, false);
    }

    private static List<Component> lines(ResourceLocation id, boolean mastery) {
        var def = TechniqueLoader.get(id);
        if (io.github.verycooltimo.murim.mastery.Arts.isMethod(id)) {
            var entry = TechniqueSlotsHud.mastery(id);
            return mastery ? List.of(MasteryText.summary(id, entry).withStyle(ChatFormatting.AQUA)) : List.of();
        }
        if (def == null) return List.of();
        int known = ClientMasteryState.stars(id);
        List<Component> out = new ArrayList<>();
        if (mastery) out.add(MasteryText.summary(id, Math.max(0, known)).withStyle(ChatFormatting.AQUA));
        var entry = TechniqueSlotsHud.mastery(id);
        if (mastery && entry != null && entry.cap() < def.starCap()) {
            out.add(Component.translatable("murim.mastery.manuscript_cap", entry.cap()).withStyle(ChatFormatting.GRAY));
        }
        if (mastery && entry != null && entry.progress() >= 1.0F && entry.stars() >= MasteryRules.INSIGHT_STAR
                && entry.stars() < entry.cap()) {
            out.add(Component.translatable("murim.mastery.insight_needed").withStyle(ChatFormatting.GOLD));
        }
        if (!Styles.WIND_GOD.forms().contains(id)
                || !(def.behavior() instanceof TechniqueBehavior.Footwork form)) return List.copyOf(out);
        int tier = ClientMasteryState.executionTier(id);
        int executionTier = FootworkFamily.windTier(tier);
        double pill = ClientMasteryState.qiCostFactor();
        if (known < 0) {
            out.add(Component.translatable("murim.footwork.unlearned").withStyle(ChatFormatting.GRAY));
        }
        switch (form.mode()) {
            case "run" -> out.add(Component.translatable("murim.footwork.run_stats",
                    number(TraverseRules.qiPerSecond(FootworkFamily.WIND_GOD, executionTier) * pill),
                    number(TraverseRules.acceleratedSpeed(FootworkFamily.WIND_GOD, executionTier, TraverseRules.WIND_RAMP_TICKS)))
                    .withStyle(ChatFormatting.GRAY));
            case "shadow" -> out.add(Component.translatable(executionTier < 1 ? "murim.footwork.shadow_locked" : "murim.footwork.shadow_stats",
                    number(ShadowRules.entryQi(executionTier) * pill), number(ShadowRules.qiPerSecond(executionTier) * pill),
                    number(Math.min(ShadowRules.WALK, ShadowRules.speed(executionTier)))).withStyle(ChatFormatting.GRAY));
            default -> {
                double cost = TechniqueService.techniqueCost(def) * MasteryRules.costFactor(Math.max(0, known), def.starCap()) * pill;
                double distance = "death".equals(form.mode()) ? ShadowRules.deathDistance(executionTier) : FootworkFamily.windEvadeDistance(executionTier);
                out.add(Component.translatable("murim.footwork.dash_stats", number(cost), number(distance),
                        number(TechniqueService.effectiveCooldown(def, executionTier) / 20.0D)).withStyle(ChatFormatting.GRAY));
                if ("death".equals(form.mode()) && executionTier < 3) {
                    out.add(Component.translatable("murim.footwork.death_locked").withStyle(ChatFormatting.GRAY));
                }
            }
        }
        return List.copyOf(out);
    }

    private static String number(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private FootworkText() {
    }
}
