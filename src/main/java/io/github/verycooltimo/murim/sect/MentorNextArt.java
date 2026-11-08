package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** Mentor advice uses the same loaded requires table as learning, in Styles order. No saved state. */
public final class MentorNextArt {
    public record Next(ResourceLocation form, TechniqueRequirement requirement) {}

    public static Optional<Next> next(Styles.Style style, ToIntFunction<ResourceLocation> stars,
                                      Function<ResourceLocation, List<TechniqueRequirement>> requirements) {
        for (var form : style.forms()) {
            for (var requirement : requirements.apply(form)) {
                if (stars.applyAsInt(Arts.masteryId(requirement.technique())) < requirement.star())
                    return Optional.of(new Next(form, requirement));
            }
        }
        return Optional.empty();
    }

    /** Verbal bands share the breakthrough vocabulary; thresholds come from the form, not the dialogue. */
    public static String words(int stars, int cap) {
        double share = stars / (double) cap;
        return "murim.mastery.requirement." + (share >= 11.0 / 12 ? "summit"
                : share >= 9.0 / 12 ? "settled" : share >= 8.0 / 12 ? "peak"
                : share >= 4.0 / 12 ? "first" : "third");
    }

    /** The canonical Russian art title uses a numeral on the tablet; speech spells it out. */
    public static String artKey(ResourceLocation form) {
        var art = Arts.masteryId(form);
        return art.getPath().equals("twenty_four_plum")
                ? "dialogue.murim.mentor.art.twenty_four_plum" : Arts.nameKey(art);
    }

    /** The art question reports its present mastery, independently of the journey's next errand. */
    public static Component sixReply(ServerPlayer p) {
        int stars = MasteryService.stars(p, SectService.SIX);
        double share = stars / (double) Arts.cap(SectService.SIX);
        String band = stars < 0 ? "unread" : share >= 1 ? "complete" : share >= .75 ? "settled"
                : share >= .5 ? "middle" : "beginning";
        return Component.translatable("dialogue.murim.mentor.six_progress." + band);
    }

    // API: Minecraft Component#empty/translatable, MutableComponent#append;
    // NeoForge attachment/IAttachmentHolder#getData (1.21.1 read-only reference sources).
    public static Component reply(ServerPlayer player) {
        var state = player.getData(ModAttachments.MASTERY);
        java.util.Map<ResourceLocation, Integer> arts = new java.util.HashMap<>();
        state.combatStars().forEach((form, stars) -> arts.merge(Arts.masteryId(form), stars, Math::max));
        var result = Component.empty();
        for (var art : arts.keySet().stream().sorted().toList()) {
            if (!result.getSiblings().isEmpty()) result.append("\n");
            var style = Styles.ALL.stream().filter(s -> Arts.masteryId(s.forms().getFirst()).equals(art)).findFirst();
            var next = style.flatMap(s -> next(s, id -> arts.getOrDefault(id, -1), id -> {
                var def = TechniqueLoader.get(id);
                return def == null ? List.of() : def.requires();
            }));
            if (next.isPresent()) {
                var n = next.get();
                result.append(Component.translatable("dialogue.murim.mentor.next.form", Component.translatable(artKey(art)),
                        Component.translatable("form." + n.form().getNamespace() + "." + n.form().getPath()),
                        Component.translatable(artKey(n.requirement().technique())),
                        Component.translatable(words(n.requirement().star(), Arts.cap(n.requirement().technique()))),
                        Component.translatable("dialogue.murim.mentor.practice." + n.form().getPath())));
            } else if (art.equals(SectService.SIX) && !SectService.state(player).has(SectStanding.LESSON_ONE)) {
                result.append(Component.translatable("dialogue.murim.mentor.next.exam"));
            } else {
                result.append(Component.translatable("dialogue.murim.mentor.next.done"
                        + (style.isPresent() ? "." + art.getPath() : ""), Component.translatable(artKey(art))));
            }
        }
        return arts.isEmpty() ? Component.translatable("dialogue.murim.mentor.next.none") : result;
    }

    private MentorNextArt() {}
}
