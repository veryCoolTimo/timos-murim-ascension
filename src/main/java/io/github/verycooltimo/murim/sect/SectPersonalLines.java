package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.server.level.ServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Personal equivalents of shared lines: same facts, the speaker's own phrasing. */
final class SectPersonalLines {
    private record Line(String source, String personal) {}
    private static final Map<String, List<Line>> LINES = Map.ofEntries(
        Map.entry("hwang_sol", List.of(
                new Line("dialogue.murim.recruiter.greet.1", "dialogue.murim.hwang_sol.personal.1"),
                new Line("dialogue.murim.recruiter.greet.2", "dialogue.murim.hwang_sol.personal.2"),
                new Line("dialogue.murim.recruiter.greet.3", "dialogue.murim.hwang_sol.personal.3"),
                new Line("dialogue.murim.recruiter.greet.4", "dialogue.murim.hwang_sol.personal.4"),
                new Line("dialogue.murim.recruiter.greet.5", "dialogue.murim.hwang_sol.personal.5"),
                new Line("dialogue.murim.recruiter.greet.6", "dialogue.murim.hwang_sol.personal.6"))),
        Map.entry("jang_cheol", List.of(
                new Line("dialogue.murim.recruiter.greet.1", "dialogue.murim.jang_cheol.personal.1"),
                new Line("dialogue.murim.recruiter.greet.2", "dialogue.murim.jang_cheol.personal.2"),
                new Line("dialogue.murim.recruiter.greet.3", "dialogue.murim.jang_cheol.personal.3"),
                new Line("dialogue.murim.recruiter.greet.4", "dialogue.murim.jang_cheol.personal.4"),
                new Line("dialogue.murim.recruiter.greet.5", "dialogue.murim.jang_cheol.personal.5"),
                new Line("dialogue.murim.recruiter.greet.6", "dialogue.murim.jang_cheol.personal.6"))),
        Map.entry("mok_rin", List.of(
                new Line("dialogue.murim.recruiter.greet.1", "dialogue.murim.mok_rin.personal.1"),
                new Line("dialogue.murim.recruiter.greet.2", "dialogue.murim.mok_rin.personal.2"),
                new Line("dialogue.murim.recruiter.greet.3", "dialogue.murim.mok_rin.personal.3"),
                new Line("dialogue.murim.recruiter.greet.4", "dialogue.murim.mok_rin.personal.4"),
                new Line("dialogue.murim.recruiter.greet.5", "dialogue.murim.mok_rin.personal.5"),
                new Line("dialogue.murim.recruiter.greet.6", "dialogue.murim.mok_rin.personal.6"))),
        Map.entry("gyeong_on", List.of(
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_on.personal.1"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_on.personal.2"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_on.personal.3"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_on.personal.4"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_on.personal.5"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_on.personal.6"))),
        Map.entry("gyeong_dan", List.of(
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_dan.personal.1"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_dan.personal.2"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_dan.personal.3"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_dan.personal.4"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_dan.personal.5"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_dan.personal.6"))),
        Map.entry("gyeong_ha", List.of(
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_ha.personal.1"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_ha.personal.2"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_ha.personal.3"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_ha.personal.4"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_ha.personal.5"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_ha.personal.6"))),
        Map.entry("gyeong_ryun", List.of(
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_ryun.personal.1"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_ryun.personal.2"),
                new Line("dialogue.murim.first.advice1", "dialogue.murim.gyeong_ryun.personal.3"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_ryun.personal.4"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_ryun.personal.5"),
                new Line("dialogue.murim.first.advice2", "dialogue.murim.gyeong_ryun.personal.6"))),
        Map.entry("porter_jang", List.of(
                new Line("dialogue.murim.porter.greet1", "dialogue.murim.porter_jang.personal.1"),
                new Line("dialogue.murim.porter.greet2", "dialogue.murim.porter_jang.personal.2"),
                new Line("dialogue.murim.porter.greet3", "dialogue.murim.porter_jang.personal.3"),
                new Line("dialogue.murim.porter.greet4", "dialogue.murim.porter_jang.personal.4"),
                new Line("dialogue.murim.porter.greet5", "dialogue.murim.porter_jang.personal.5"),
                new Line("dialogue.murim.porter.greet6", "dialogue.murim.porter_jang.personal.6"))),
        Map.entry("porter_oh", List.of(
                new Line("dialogue.murim.porter.greet1", "dialogue.murim.porter_oh.personal.1"),
                new Line("dialogue.murim.porter.greet2", "dialogue.murim.porter_oh.personal.2"),
                new Line("dialogue.murim.porter.greet3", "dialogue.murim.porter_oh.personal.3"),
                new Line("dialogue.murim.porter.greet4", "dialogue.murim.porter_oh.personal.4"),
                new Line("dialogue.murim.porter.greet5", "dialogue.murim.porter_oh.personal.5"),
                new Line("dialogue.murim.porter.greet6", "dialogue.murim.porter_oh.personal.6"))),
        Map.entry("disciple_a", List.of(
                new Line("dialogue.murim.disciple.doing_training1", "dialogue.murim.disciple_a.personal.1"),
                new Line("dialogue.murim.disciple.doing_training2", "dialogue.murim.disciple_a.personal.2"),
                new Line("dialogue.murim.disciple.doing_training3", "dialogue.murim.disciple_a.personal.3"),
                new Line("dialogue.murim.disciple.doing_training4", "dialogue.murim.disciple_a.personal.4"),
                new Line("dialogue.murim.disciple.doing_training5", "dialogue.murim.disciple_a.personal.5"),
                new Line("dialogue.murim.disciple.doing_training6", "dialogue.murim.disciple_a.personal.6"))),
        Map.entry("disciple_b", List.of(
                new Line("dialogue.murim.disciple.doing_training1", "dialogue.murim.disciple_b.personal.1"),
                new Line("dialogue.murim.disciple.doing_training2", "dialogue.murim.disciple_b.personal.2"),
                new Line("dialogue.murim.disciple.doing_training3", "dialogue.murim.disciple_b.personal.3"),
                new Line("dialogue.murim.disciple.doing_training4", "dialogue.murim.disciple_b.personal.4"),
                new Line("dialogue.murim.disciple.doing_training5", "dialogue.murim.disciple_b.personal.5"),
                new Line("dialogue.murim.disciple.doing_training6", "dialogue.murim.disciple_b.personal.6")))
    );

    private SectPersonalLines() {}

    static boolean hasFor(SectDisciple npc, List<String> shared) {
        return LINES.getOrDefault(npc.memberKey(), List.of()).stream().anyMatch(line -> shared.contains(line.source()));
    }

    static List<String> choices(ServerPlayer player, SectDisciple npc, List<String> shared) {
        List<String> personal = LINES.getOrDefault(npc.memberKey(), List.of()).stream()
                .filter(line -> shared.contains(line.source())).map(Line::personal).toList();
        if (personal.isEmpty()) return shared;
        List<String> heard = SectLines.heardToday(player, SectLines.commonPool(personal), personal);
        List<String> fresh = personal.stream().filter(key -> !heard.contains(key)).toList();
        if (!fresh.isEmpty()) return fresh;
        List<String> fallback = new ArrayList<>(shared);
        fallback.addAll(personal);
        return fallback;
    }
}
