package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** DialogueService.show/choose coverage, including the real four-option trimming and server session. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectBasicsGameTests {
    private static final List<String> TOPICS = List.of("qi", "dantian", "seed", "meditation", "methods", "echo",
            "stars", "insight", "breakthroughs", "readiness", "body", "pills");
    private static final String PREFIX = "dialogue.murim.basics.";

    private static SectDisciple npc(GameTestHelper h, ServerPlayer p, String member) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of(member).orElseThrow());
        npc.setNoAi(true);
        npc.setPos(p.getX(), p.getY(), p.getZ());
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    private static void choose(GameTestHelper h, ServerPlayer p, Dialogue d, String next) {
        DialogueService.Session session = p.getData(ModAttachments.DIALOGUE);
        Dialogue.Node node = d.nodes().get(session.node());
        for (int i = 0; i < session.shown().size(); i++) {
            int raw = session.shown().get(i);
            if (raw < node.options().size() && node.options().get(raw).next().filter(next::equals).isPresent()) {
                DialogueService.choose(p, i);
                h.assertTrue(p.getData(ModAttachments.DIALOGUE).node().equals(next), "choice failed: " + next);
                return;
            }
        }
        h.assertTrue(false, "no visible way from " + session.node() + " to " + next);
    }

    @GameTest(template = "sect_yard", batch = "sect_basics", timeoutTicks = 20)
    public static void openingATalkStopsARunningPlayer(net.minecraft.gametest.framework.GameTestHelper helper) {
        var player = helper.makeMockPlayer(net.minecraft.world.level.GameType.SURVIVAL);
        player.setSprinting(true);
        player.setDeltaMovement(0.3D, -0.2D, 0.25D);
        DialogueStop.stop(player);
        helper.assertTrue(!player.isSprinting(), "still sprinting");
        helper.assertTrue(player.getDeltaMovement().x == 0.0D && player.getDeltaMovement().z == 0.0D, "still moving sideways");
        helper.assertTrue(player.getDeltaMovement().y == -0.2D, "the fall was cancelled");
        helper.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_basics", timeoutTicks = 60)
    public static void bothTeachersOpenEveryTopicAndAllowRepeatQuestions(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        for (String member : List.of("gyeong_pil", "tae_rok")) {
            SectDisciple npc = npc(h, p, member);
            ResourceLocation id = npc.dialogue();
            Dialogue d = DialogueLoader.get(id);
            h.assertTrue(d != null && DialogueService.validate(d).isEmpty(), "invalid dialogue: " + id);
            // Every greeting has a door; the social topics menu keeps it reachable if goal hooks fill a greeting.
            for (Dialogue.Entry entry : d.start()) {
                h.assertTrue(d.nodes().get(entry.node()).options().stream()
                        .anyMatch(o -> o.next().filter("basics"::equals).isPresent()), "no basics door: " + entry.node());
            }
            DialogueService.show(p, npc, id, d, "sect_topics", "");
            choose(h, p, d, "basics");
            for (int repeat = 0; repeat < 2; repeat++) {
                for (int i = 0; i < TOPICS.size(); i++) {
                    for (int page = 1; page <= i / 3; page++) choose(h, p, d, "basics_" + (page + 1));
                    String topic = TOPICS.get(i);
                    choose(h, p, d, "basics_" + topic);
                    h.assertTrue(d.nodes().get("basics_" + topic).line().equals(PREFIX + topic), "wrong topic line");
                    choose(h, p, d, "basics");
                }
            }
            DialogueService.close(p, npc);
        }
        h.succeed();
    }

    @GameTest(template = "sect_yard", batch = "sect_basics", timeoutTicks = 60)
    public static void firstLessonOffersBasicsOnceAndAllWordsExistInBothLanguages(GameTestHelper h) throws Exception {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        SectDisciple npc = npc(h, p, "gyeong_pil");
        Dialogue d = DialogueLoader.get(npc.dialogue());
        h.assertTrue(!DialogueService.route(p, npc).node().equals("basics_offer"), "offer before first lesson");
        DialogueService.show(p, npc, npc.dialogue(), d, "lessons", "");
        choose(h, p, d, "six_given");
        h.assertTrue(!SectService.state(p).has("basics.offered"), "offer already consumed");
        h.assertTrue(DialogueService.route(p, npc).node().equals("basics_offer"), "first lesson did not offer basics");
        choose(h, p, d, "basics_offer");
        h.assertTrue(SectService.state(p).has("basics.offered"), "offer not remembered");
        choose(h, p, d, "again");
        DialogueService.close(p, npc);
        h.assertTrue(!DialogueService.route(p, npc).node().equals("basics_offer"), "offer repeated");
        for (String lang : List.of("en_us", "ru_ru")) {
            try (var in = SectBasicsGameTests.class.getResourceAsStream("/assets/murim/lang/" + lang + ".json")) {
                h.assertTrue(in != null, "missing language " + lang);
                var words = com.google.gson.JsonParser.parseReader(new InputStreamReader(in, StandardCharsets.UTF_8)).getAsJsonObject();
                for (String path : List.of("mentor", "tae_rok")) {
                    Dialogue dialogue = DialogueLoader.get(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path));
                    dialogue.nodes().forEach((key, node) -> {
                        if (!key.startsWith("basics")) return;
                        h.assertTrue(words.has(node.line()), lang + " lacks " + node.line());
                        for (Dialogue.Option option : node.options()) {
                            h.assertTrue(words.has(option.text()), lang + " lacks " + option.text());
                        }
                        h.assertTrue(node.options().size() <= Dialogue.MAX_OPTIONS, "topic menu overflows: " + key);
                    });
                }
            }
        }
        h.succeed();
    }
}
