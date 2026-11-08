package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.sect.repair.RepairEnv;
import io.github.verycooltimo.murim.world.hua.MountHuaSiteData;
import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The pure rules behind the fixes of the first outside playtest (19.10, docs/adr/0013). */
class SectPlaytest1019Test {

    private static JsonObject lang(String code) throws IOException {
        Path p = Path.of("src/main/resources/assets/murim/lang/" + code + ".json");
        if (!Files.exists(p)) {
            p = Path.of("../../src/main/resources/assets/murim/lang/" + code + ".json");
        }
        return JsonParser.parseString(Files.readString(p)).getAsJsonObject();
    }

    @Test
    @DisplayName("Урок открыт между словом наставника и сдачей: учитель первого — наставник, второго — наставник и старшие")
    void aLessonSanctionsItsInstructorsForItsWholeDuration() {
        SectState novice = SectState.NONE.joined();
        assertFalse(SectLessonSanction.teaches(novice, SectRole.MENTOR), "no lesson taken, no sanction");
        SectState six = novice.with(SectLessonSanction.SIX_TAKEN);
        assertTrue(SectLessonSanction.teaches(six, SectRole.MENTOR));
        for (SectRole other : new SectRole[] {SectRole.SENIOR, SectRole.SECOND, SectRole.LEADER, SectRole.ELDER, SectRole.GUARD, SectRole.STEWARD, SectRole.DISCIPLE}) {
            assertFalse(SectLessonSanction.teaches(six, other), "the first lesson is the mentor's alone: " + other);
        }
        assertFalse(SectLessonSanction.teaches(six.with(SectStanding.LESSON_ONE), SectRole.MENTOR), "handed in: the sanction is over");
        SectState spar = novice.with(SectStanding.LESSON_ONE).with(SectService.LESSON_SPAR);
        for (SectRole partner : new SectRole[] {SectRole.MENTOR, SectRole.SENIOR, SectRole.SECOND}) {
            assertTrue(SectLessonSanction.teaches(spar, partner), "the second lesson covers " + partner);
        }
        for (SectRole other : new SectRole[] {SectRole.LEADER, SectRole.ELDER, SectRole.GUARD, SectRole.STEWARD, SectRole.DISCIPLE}) {
            assertFalse(SectLessonSanction.teaches(spar, other), "the second lesson is not for " + other);
        }
        assertFalse(SectLessonSanction.teaches(spar.with(SectStanding.LESSON_TWO), SectRole.SENIOR), "handed in: the sanction is over");
        assertFalse(SectLessonSanction.teaches(SectState.NONE.with(SectLessonSanction.SIX_TAKEN), SectRole.MENTOR), "an outsider has no lesson");
    }

    @Test
    @DisplayName("Упражнение «удары» после проигранного боя: учитель, поставивший его, прикрыт в тот же день, пока оно не сдано")
    void theStrikesDrillSanctionsItsTeacherForTheDay() {
        long day = 41L;
        SectState drill = SectState.NONE.joined().record("defeat.drill", 2L).record("defeat.day", day);
        assertTrue(SectLessonSanction.drillTeaches(drill, day, "gyeong_pil"));
        assertFalse(SectLessonSanction.drillTeaches(drill, day, "tae_seong"), "the teacher is the mentor until the discipleship");
        assertTrue(SectLessonSanction.drillTeaches(drill.with(SectProgression.DIRECT), day, "tae_seong"), "the master after the discipleship");
        assertFalse(SectLessonSanction.drillTeaches(drill, day + 1, "gyeong_pil"), "yesterday's drill");
        assertFalse(SectLessonSanction.drillTeaches(drill.record("defeat.done", day), day, "gyeong_pil"), "a finished drill");
        assertFalse(SectLessonSanction.drillTeaches(SectState.NONE.joined().record("defeat.drill", 1L).record("defeat.day", day), day, "gyeong_pil"),
                "a meditation drill has nothing to strike");
        assertFalse(SectLessonSanction.drillTeaches(SectState.NONE.record("defeat.drill", 2L).record("defeat.day", day), day, "gyeong_pil"), "an outsider");
    }

    @Test
    @DisplayName("Строки стражи больше не обещают, что глава выйдет к новичку у ворот (его там не бывает)")
    void guardLinesDoNotSendNewcomersToTheHeadAtTheGate() throws IOException {
        JsonObject en = lang("en_us");
        JsonObject ru = lang("ru_ru");
        for (String key : new String[] {"murim.sect.guard.warn_outsider", "murim.sect.guard.expel"}) {
            assertNotNull(en.get(key), key);
            assertFalse(en.get(key).getAsString().contains("Leader will come out") || en.get(key).getAsString().contains("Leader first"), "en " + key);
            assertFalse(ru.get(key).getAsString().contains("глава выйдет") || ru.get(key).getAsString().contains("К главе") || ru.get(key).getAsString().contains("к главе, у ворот"), "ru " + key);
        }
        assertTrue(en.get("murim.sect.guard.warn_outsider").getAsString().contains("mentor"));
        assertTrue(ru.get("murim.sect.guard.warn_outsider").getAsString().contains("наставнику"));
    }

    @Test
    @DisplayName("Новый мир получает канонический склон: запечённая секта ставится, ремонту есть что чинить; признак переживает сохранение")
    void aFreshWorldsMountainIsCanonicalAndStaysSo() {
        MountHuaSiteData fresh = new MountHuaSiteData();
        fresh.choose(3000, 3000, 70, 1);
        assertTrue(fresh.toSite(42L).canonical(), "a fresh world must be canonical");
        MountHuaSiteData back = MountHuaSiteData.FACTORY.deserializer().apply(fresh.save(new CompoundTag(), null), null);
        assertTrue(back.chosen() && back.toSite(42L).canonical(), "the mark did not survive saving");
        assertFalse(new MountHuaSiteData().chosen());
    }

    @Test
    @DisplayName("Срок до ремонта — четверть суток, и умолчание конфига то же (ключ новый, чтобы старый файл не держал сутки)")
    void theRepairGraceIsAQuarterOfADay() {
        assertEquals(6000L, RepairEnv.Settings.GRACE);
        assertEquals(RepairEnv.Settings.GRACE, (long) Config.SECT_REPAIR_GRACE.getDefault());
    }
}
