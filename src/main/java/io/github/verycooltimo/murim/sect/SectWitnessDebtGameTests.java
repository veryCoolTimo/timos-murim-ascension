package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import static io.github.verycooltimo.murim.sect.SectPlaytestRig.empty;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.id;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.npc;
import static io.github.verycooltimo.murim.sect.SectPlaytestRig.player;

/**
 * Bug 2 of the 19.10 playtest (docs/adr/0013): the witness of a closed art who promised a talk raises it when spoken to.
 * Every test has its own batch (they spawn named people: two of one key within 256 blocks are folded into one).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectWitnessDebtGameTests {

    private static final String YARD = "sect_yard";

    private SectWitnessDebtGameTests() {
    }

    /**
     * «Мне говорят: мы с тобой поговорим об этом. Когда я начинаю диалог с этим же персонажем, он забывает про это.» The
     * elder's walk to the player gives up when the player walks off; the talk he owes is kept in the journal and raised at the
     * next conversation, once.
     */
    @GameTest(template = YARD, timeoutTicks = 80, batch = "playtest1019_witness")
    public static void anElderWhoOwesATalkAboutTheSecretArtRaisesItWhenSpokenTo(GameTestHelper h) {
        SectHierarchyGameTests.Only layout = empty(h);
        ServerPlayer p = player(h, 4, 12, SectState.NONE.joined().with(SectPositions.ELDER));
        SectDisciple elder = npc(h, layout, "tae_seong", 14, 12, true);
        var secret = TechniqueLoader.get(id("twenty_four_plum_rainfall"));
        h.assertTrue(secret != null, "no Twenty-Four Plum in the data");
        SectReactions.onTechnique(p, secret, true);
        h.assertTrue(elder.approachingDialogue(p, id("plum_witness")), "the elder did not set out to talk about the art");
        // the player walks off, the approach runs out
        elder.clearBlock();
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "the dialogue opened by itself");
        DialogueService.open(p, elder);
        var session = p.getData(ModAttachments.DIALOGUE);
        h.assertTrue(session.active() && "plum_witness".equals(session.dialogue().getPath()),
                "the elder forgot the promised talk: he opened " + session.dialogue());
        DialogueService.close(p, elder);
        DialogueService.open(p, elder);
        var again = p.getData(ModAttachments.DIALOGUE);
        h.assertTrue(again.active() && !"plum_witness".equals(again.dialogue().getPath()),
                "the talk was raised a second time: " + again.dialogue());
        h.succeed();
    }
}
