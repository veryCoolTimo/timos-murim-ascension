package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.QiSword;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.mastery.LoadoutService;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.mastery.TechniqueTier;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Optional;
import java.util.Set;

/** Регрессии игрового прогона 10.10: закрытые искусства, вызов, перехват, попадание, выбор формы, медитация. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectGameplayGameTests {
    private SectGameplayGameTests() {
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private static ServerPlayer player(GameTestHelper h, double x, double z) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        return p;
    }

    private static SectDisciple npc(GameTestHelper h, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        // Пустая раскладка: распорядок не уводит человека с площадки проверки.
        d.setLayout(new SectHierarchyGameTests.Only(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), Set.of()));
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    private static void peak(ServerPlayer p) {
        DantianProfile profile = DantianProfile.INITIAL.withTags("clear", "plum")
                .withRank(QiSword.MIN_RANK).withStage(QiSword.MIN_STAGE).withAxes(120.0D, 0.8D, 0.8D);
        p.setData(ModAttachments.PROFILE, profile.withCirculating(profile.maxCirculating()));
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void plumPermissionFollowsStandingAndTier(GameTestHelper h) {
        for (SectStanding s : SectStanding.values()) {
            var expected = s == SectStanding.TRUSTED ? SectReactions.PlumReaction.SUMMONS
                    : s == SectStanding.GRADUATE ? SectReactions.PlumReaction.OFFENCE : SectReactions.PlumReaction.SERIOUS;
            h.assertTrue(SectReactions.plumReaction(id("twenty_four_plum_rainfall"), TechniqueTier.SECRET, s) == expected,
                    "неверная реакция на 24 Сливы для " + s);
            h.assertTrue(SectReactions.plumReaction(id("seven_plum_whirlwind"), TechniqueTier.ADVANCED, s)
                    == (s.atLeast(SectStanding.GRADUATE) ? SectReactions.PlumReaction.NONE : SectReactions.PlumReaction.OFFENCE),
                    "неверное право на Семь Цветков для " + s);
        }
        h.assertTrue(SectReactions.plumReaction(id("seven_plum_execution"), TechniqueTier.SECRET, SectStanding.DISCIPLE)
                == SectReactions.PlumReaction.SERIOUS, "секретная форма Семи Цветков обошла проверку уровня");
        h.assertTrue(SectReactions.plumReaction(id("demon_palm"), TechniqueTier.SECRET, SectStanding.NOVICE)
                == SectReactions.PlumReaction.NONE, "чужое искусство принято за секрет Хуашань");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_gameplay_secret")
    public static void witnessedSecretStopsDisciplesAndSummonsElder(GameTestHelper h) {
        ServerPlayer p = player(h, 4, 12);
        SectDisciple disciple = npc(h, "yul_jin", 8, 12);
        SectDisciple elder = npc(h, "tae_seong", 14, 12);
        var secret = TechniqueLoader.get(id("twenty_four_plum_rainfall"));
        h.assertTrue(secret != null, "нет 24 Сливы в данных");
        SectReactions.onTechnique(p, secret, true);
        h.assertTrue(disciple.watching() == p && disciple.getNavigation().isDone(), "ученик не остановился смотреть");
        h.assertTrue("murim.sect.plum.alarm".equals(disciple.bubbleKey()), "нет возгласа о закрытом искусстве");
        h.assertTrue(p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "младшего не вызвали на приговор");
        h.assertTrue(elder.approachingDialogue(p, PenanceService.DIALOGUE), "старейшина не идёт к нарушителю");
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "приговор открылся до подхода старейшины");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void unwitnessedSecretDoesNotCreateOffence(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12);
        SectReactions.onTechnique(p, TechniqueLoader.get(id("twenty_four_plum_rainfall")), true);
        h.assertTrue(!p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED)
                && !p.getPersistentData().contains("murim_sect_offences"), "нарушение записано без свидетелей");
        npc(h, "yul_jin", 8, 12);
        for (int y = 2; y <= 5; y++) {
            for (int z = 0; z < 24; z++) {
                h.setBlock(new net.minecraft.core.BlockPos(10, y, z), net.minecraft.world.level.block.Blocks.STONE);
            }
        }
        SectReactions.onTechnique(p, TechniqueLoader.get(id("twenty_four_plum_rainfall")), true);
        h.assertTrue(!p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "технику увидели сквозь стену");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40, batch = "sect_gameplay_cancel_summons")
    public static void resolvedSummonsCancelsAnElderStillApproaching(GameTestHelper h) {
        ServerPlayer p = player(h, 2, 12);
        SectDisciple elder = npc(h, "tae_seong", 22, 12);
        SectDisciple nearer = npc(h, "tae_gyun", 3, 12);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(30));
        PenanceService.summon(p, elder);
        h.assertTrue(elder.approachingDialogue(p, PenanceService.DIALOGUE), "нет подходящего старейшины");
        h.assertTrue(!nearer.approachingDialogue(p, PenanceService.DIALOGUE) && !p.getData(ModAttachments.DIALOGUE).active(),
                "nearby elder stole the witness's summons");
        PenanceService.summon(p, nearer);
        h.assertTrue(elder.approachingDialogue(p, PenanceService.DIALOGUE) && !nearer.approachingDialogue(p, PenanceService.DIALOGUE),
                "repeat summons assigned a second elder");
        // Игрок уже ответил на приговор другому члену секты.
        PenanceService.refuse(p);
        h.runAfterDelay(4, () -> {
            h.assertTrue(!elder.blocking() && !p.getData(ModAttachments.DIALOGUE).active(), "решённый приговор открыт повторно");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 300, batch = "sect_gameplay_penance")
    public static void distantElderReadsSentenceWithoutPlayerInteraction(GameTestHelper h) {
        ServerPlayer p = player(h, 2, 12);
        SectDisciple elder = npc(h, "tae_seong", 22, 12);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(30));
        PenanceService.offence(p, "trespass", null);
        PenanceService.offence(p, "trespass", null);
        h.assertTrue(p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "повторный проступок не вызвал приговор");
        h.assertTrue(elder.approachingDialogue(p, PenanceService.DIALOGUE), "старейшина за 16 блоками не вызван");
        h.assertTrue(!p.getData(ModAttachments.DIALOGUE).active(), "дальний старейшина сразу открыл экран");
        h.succeedWhen(() -> {
            var session = p.getData(ModAttachments.DIALOGUE);
            h.assertTrue(session.active() && session.npc() == elder.getId() && PenanceService.DIALOGUE.equals(session.dialogue()),
                    "старейшина не прочёл приговор сам");
            h.assertTrue(elder.distanceToSqr(p) <= 9.0D && elder.getNavigation().isDone(), "говорящий продолжает бежать");
            DialogueService.choose(p, -1);
            h.assertTrue(p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "закрытие экрана сняло вызов");
            h.assertTrue(PenanceService.offer(p, null), "повторный вызов не открыл приговор");
            DialogueService.choose(p, 1);
            h.assertTrue(!p.getData(ModAttachments.SECT).has(PenanceService.SUMMONED), "отказ не снял вызов");
            h.assertTrue(p.getData(ModAttachments.SECT).contribution() == 20, "отказ не снял 10 заслуг");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void summonsBlocksMeritAndExistingPasses(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12);
        SectState state = SectState.NONE.joined().contribute(50).with(SectStanding.LESSON_TWO)
                .with(SectStanding.APPROVAL).with("pass.scriptures").with(PenanceService.SUMMONED);
        p.setData(ModAttachments.SECT, state);
        h.assertTrue(MeritShop.buy(p, "silver") == MeritShop.Result.SUMMONED, "вызванный потратил заслуги");
        h.assertTrue(p.getData(ModAttachments.SECT).equals(state), "запрет покупки изменил заслуги");
        for (SectAccess.Rule rule : SectAccess.RULES) {
            if (!SectAccess.GROUNDS.equals(rule.zone())) {
                h.assertTrue(!SectAccess.allowed(rule, SectStanding.TRUSTED, state.flags(), SectSchedule.Period.TRAINING),
                        "вызванный прошёл в " + rule.id());
            }
        }
        h.assertTrue(MeritShop.check(state.without(PenanceService.SUMMONED), SectStanding.TRUSTED,
                MeritShop.offer("silver").orElseThrow()) == MeritShop.Result.OK, "после снятия вызова обмен всё ещё закрыт");
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 300, batch = "sect_gameplay_intercept")
    public static void guardApproachesAndOpensDialogueNearElder(GameTestHelper h) {
        SectGameTests.setPeriod(h, SectSchedule.Period.TRAINING);
        ServerPlayer p = player(h, 4, 4);
        npc(h, "tae_seong", 6, 4);
        String key = SectRota.dayWatch(SectSchedule.day(h.getLevel().getDayTime()), 0).key();
        SectDisciple guard = npc(h, key, 12, 4);
        DialogueService.interceptNearElder(p);
        h.assertTrue(guard.blocking() && guard.talkingTo() < 0 && !p.getData(ModAttachments.DIALOGUE).active(),
                "страж открыл разговор до перехвата");
        h.succeedWhen(() -> {
            var session = p.getData(ModAttachments.DIALOGUE);
            h.assertTrue(session.active() && session.npc() == guard.getId()
                    && "intercept_elder".equals(session.dialogue().getPath()), "страж сам не заговорил");
            h.assertTrue(guard.talkingTo() == p.getId() && guard.getNavigation().isDone() && !guard.blocking(),
                    "страж продолжает перехват во время разговора");
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void dialogueClearsBothMeditationPoseAndLotusClip(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12);
        SectDisciple d = npc(h, "tae_seong", 14, 12);
        d.sit(true);
        d.holdPose(SectPose.MEDITATE, 200);
        h.assertTrue(DialogueService.openDialogue(p, d, id("plum_witness"), "recognized"), "нет диалога");
        h.runAfterDelay(2, () -> {
            h.assertTrue(!d.sitting() && d.pose() != SectPose.MEDITATE && !d.poseHeld(), "медитация осталась при разговоре");
            h.assertTrue(d.talkingTo() == p.getId() && d.getNavigation().isDone(), "разговор прерван распорядком");
            h.succeed();
        });
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void wheelFormBecomesTheCastTechnique(GameTestHelper h) {
        ServerPlayer p = player(h, 12, 12);
        peak(p);
        ResourceLocation second = id("seven_plum_whirlwind");
        ResourceLocation third = id("seven_plum_execution");
        ResourceLocation basic = id("seven_plum_basic");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(second, TechniqueProgress.learned(2, 10))
                .with(third, TechniqueProgress.learned(2, 10)).with(basic, TechniqueProgress.learned(2, 10)));
        p.setData(ModAttachments.LOADOUT, Loadout.EMPTY.with(0, Optional.of(third)).select(1));
        h.assertTrue(LoadoutService.selectForm(p, 0, second, true), "выбор второй формы отклонён");
        Loadout chosen = p.getData(ModAttachments.LOADOUT);
        h.assertTrue(chosen.active() == 0 && chosen.activeTechnique().equals(Optional.of(second)), "осталась третья форма");
        h.assertTrue(chosen.foundation().equals(Optional.of(basic)), "основа выбранного стиля не переключилась");
        h.assertTrue(!LoadoutService.selectForm(p, 8, third, true), "закрытый слот принят");
        h.assertTrue(!LoadoutService.selectForm(p, 0, id("seven_plum_rush"), true), "невыученная форма принята");
        h.assertTrue(TechniqueService.tryStart(p, p.getData(ModAttachments.LOADOUT).activeTechnique().orElseThrow()), "выбранная форма не запущена");
        h.assertTrue(second.equals(p.getData(ModAttachments.TECHNIQUE_STATE).techniqueId()), "применена прежняя третья форма");
        TechniqueService.cancel(p);
        h.succeed();
    }

    @GameTest(template = "sect_yard", timeoutTicks = 40)
    public static void qiSwordConfirmsSwordDamageAndEligibleHand(GameTestHelper h) {
        ServerPlayer p = player(h, 4, 12);
        peak(p);
        var source = p.damageSources().playerAttack(p);
        h.assertTrue(!QiSword.confirmHit(p, source, 2.0F), "обычный кулак проявил клинок");
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(id("crescent_sweep"), p.level().getGameTime()));
        h.assertTrue(!QiSword.confirmHit(p, source, 0.0F) && !QiSword.drawn(p), "нулевой урон проявил клинок");
        var target = h.spawn(EntityType.ZOMBIE, new Vec3(8.5D, 2.0D, 12.5D));
        target.setNoAi(true);
        target.setInvulnerable(true);
        h.assertTrue(!target.hurt(source, 2.0F) && !QiSword.drawn(p), "защищённая цель проявила клинок");
        target.setInvulnerable(false);
        h.assertTrue(target.hurt(source, 2.0F) && QiSword.drawn(p), "реальное попадание не проявило клинок");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, new ItemStack(Items.STICK));
        h.assertTrue(!QiSword.confirmHit(p, source, 2.0F), "клинок появился с предметом в руке");
        p.setItemInHand(net.minecraft.world.InteractionHand.MAIN_HAND, ItemStack.EMPTY);
        p.setData(ModAttachments.TECHNIQUE_STATE, TechniqueState.started(id("demon_palm"), p.level().getGameTime()));
        h.assertTrue(!QiSword.confirmHit(p, source, 2.0F), "ладонь проявила меч");
        h.succeed();
    }
}
