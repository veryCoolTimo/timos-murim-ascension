package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.Set;

/**
 * GameTest-ы состава секты 05.10, обмена заслуг и смотра учеников: дежурный второго поколения на смене — страж, тот же
 * ученик не на смене — нет; NPC прежнего состава уходит; покупка за заслуги не роняет положение; сетка смотра доходит
 * до победителя без смертей. Площадка — двор {@code murim:sect_yard}, раскладка сжата ({@link SectGameTests.Yard}).
 * API: reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectMvpGameTests {

    private static final String YARD = "sect_yard";

    private SectMvpGameTests() {
    }

    private static SectGameTests.Yard yard(GameTestHelper helper, double scale) {
        return new SectGameTests.Yard(SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), scale);
    }

    private static SectDisciple npc(GameTestHelper helper, SectLayout layout, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), helper.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    /** Время дня секты {@code day} в тиках суток {@code t}. */
    private static void setTime(GameTestHelper helper, long day, int t) {
        helper.getLevel().setDayTime(day * 24000L + t);
    }

    // ------------------------------------------------------------------ состав и дежурство

    /**
     * Днём страж — дневной дежурный второго поколения (SectRota), занимающийся ученик второго поколения — не страж;
     * ночью — наоборот для ночного дежурного. NPC прежней стражи (ключ из RETIRED) при первом тике уходит.
     */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_mvp_roster")
    public static void shiftGuardsAndRetiredLeave(GameTestHelper helper) {
        long day = SectSchedule.day(helper.getLevel().getDayTime()) + 1;
        setTime(helper, day, 4000);
        SectGameTests.Yard yard = yard(helper, 0.3D);
        SectRoster onKey = SectRota.dayWatch(day, 1);
        SectRoster nightKey = SectRota.nightWatch(day, 1);
        SectDisciple on = npc(helper, yard, onKey.key(), 4, 4);
        SectDisciple trainee = npc(helper, yard, SectRota.training(day).get(1).key(), 6, 4);
        SectDisciple night = npc(helper, yard, nightKey.key(), 8, 4);
        helper.assertTrue(SectWatch.guards(on), "дневной дежурный не на страже днём");
        helper.assertFalse(SectWatch.guards(trainee), "занимающийся ученик сторожит");
        helper.assertFalse(SectWatch.guards(night), "ночной дежурный сторожит днём");
        helper.assertTrue(SectSchedule.task(onKey, helper.getLevel().getDayTime()).kind() == SectSchedule.Kind.GUARD, "дежурный не на посту");
        setTime(helper, day, 15000);
        helper.assertTrue(SectWatch.guards(night), "ночной дежурный не на страже ночью");
        helper.assertFalse(SectWatch.guards(on), "дневной дежурный сторожит ночью");

        // Сохранение прежнего состава: стражник Со Сын (ночная смена С3, часть 2) — роль guard, ключа нет в списке.
        SectDisciple old = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), helper.getLevel());
        CompoundTag tag = new CompoundTag();
        old.saveWithoutId(tag);
        tag.putString("role", "guard");
        tag.putString("member", "baek_seung");
        old.load(tag);
        Vec3 at = helper.absoluteVec(new Vec3(10.5D, 2.0D, 10.5D));
        old.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(old);
        helper.assertTrue("baek_seung".equals(old.memberKey()) && old.member().isEmpty(), "ключ прежнего состава потерян: " + old.memberKey());
        SectLife.tickNpc(old);
        helper.assertTrue(old.isRemoved(), "стражник прежнего состава остался на горе");
        helper.succeed();
    }

    // ------------------------------------------------------------------ обмен заслуг

    /**
     * Ученик третьего класса с 30 заслугами меняет 20 на Меч Хуашань: меч в инвентаре, остаток 10, сумма за всё время и
     * положение те же; второй меч — не хватает, пилюля Изначальной Энергии — не по положению; пилюля Снежной Сливы — да.
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_mvp_merit")
    public static void meritPurchase(GameTestHelper helper) {
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with(SectStanding.LESSON_ONE).contribute(30));
        SectStanding before = SectService.standing(p);
        helper.assertTrue(before == SectStanding.DISCIPLE, "не ученик третьего класса: " + before);

        helper.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.OK, "меч не куплен");
        SectState s = p.getData(ModAttachments.SECT);
        helper.assertTrue(s.merit() == 10 && s.contribution() == 30, "заслуги: остаток " + s.merit() + ", всего " + s.contribution());
        helper.assertTrue(p.getInventory().countItem(ModItems.HUASHAN_SWORD.get()) == 1, "меча нет в инвентаре");
        helper.assertTrue(SectService.standing(p) == before, "положение упало после траты");

        helper.assertTrue(MeritShop.buy(p, "sword") == MeritShop.Result.MERIT, "второй меч без заслуг");
        helper.assertTrue(MeritShop.buy(p, "origin_energy") == MeritShop.Result.STANDING, "редкая пилюля не по положению");
        helper.assertTrue(MeritShop.buy(p, "snow_plum") == MeritShop.Result.OK, "пилюля Снежной Сливы не куплена");
        helper.assertTrue(p.getInventory().countItem(ModItems.PILL_SNOW_PLUM.get()) == 1, "пилюли нет");
        helper.assertTrue(p.getData(ModAttachments.SECT).merit() == 6, "остаток после пилюли: " + p.getData(ModAttachments.SECT).merit());
        // Покупка из разговора: вариант Тэ Гюна с действием merit_buy, цена — в тексте варианта.
        Dialogue d = DialogueLoader.get(net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "tae_gyun"));
        helper.assertTrue(d != null && d.nodes().containsKey("exchange"), "у Тэ Гюна нет обмена");
        Dialogue.Option silver = d.nodes().get("exchange_more").options().get(0);
        SectDisciple treasurer = npc(helper, yard(helper, 0.3D), "tae_gyun", 12, 12);
        helper.assertTrue(DialogueService.applyHeadless(p, treasurer, silver), "вариант серебра не выполнился");
        helper.assertTrue(p.getInventory().countItem(ModItems.SILVER_TAEL.get()) == 3, "серебра нет");
        helper.assertTrue(p.getData(ModAttachments.SECT).merit() == 4, "серебро не списало заслуги");
        helper.succeed();
    }

    // ------------------------------------------------------------------ смотр учеников

    /**
     * Смотр: сетка из четырёх учеников третьего поколения — два полуфинала и финал на площадке поединков; все живы,
     * есть победитель, смотр снят, день смотра записан.
     */
    @GameTest(template = YARD, timeoutTicks = 4800, batch = "sect_mvp_review")
    public static void reviewBracketCompletes(GameTestHelper helper) {
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        day += SectReview.daysUntil(day * 24000L);
        setTime(helper, day, SectReview.FROM + 20);
        helper.assertTrue(SectReview.window(helper.getLevel().getDayTime()), "не окно смотра");
        SectGameTests.Yard yard = yard(helper, 0.3D);
        List<String> keys = SectReview.bracket(day, false);
        List<SectDisciple> fighters = new java.util.ArrayList<>();
        for (int i = 0; i < keys.size(); i++) {
            fighters.add(npc(helper, yard, keys.get(i), 4 + i * 5, 20));
        }
        // Зритель: глава смотрит с помоста.
        SectDisciple leader = npc(helper, yard, "tae_hwi", 20, 4);
        helper.assertTrue(SectSchedule.task(leader.member().orElseThrow(), helper.getLevel().getDayTime()).kind() == SectSchedule.Kind.WATCH,
                "глава не смотрит смотр");
        long finalDay = day;
        SectReview.State s = SectReview.begin(helper.getLevel(), yard, day, null, 48.0D);
        String[] champion = {""};
        helper.onEachTick(() -> {
            // Время стоит в окне смотра (тест длиннее окна не идёт, но день не должен смениться).
            helper.getLevel().setDayTime(finalDay * 24000L + SectReview.FROM + 20);
            if (helper.getLevel().getGameTime() % 10 == 0) {
                SectReview.step(helper.getLevel(), yard);
            }
            if (s.done()) {
                champion[0] = s.champion();
            }
            for (SectDisciple d : fighters) {
                if (!d.isAlive()) {
                    helper.fail(d.memberKey() + " погиб на смотре");
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(!champion[0].isEmpty(), "нет победителя: " + s.bouts().stream().map(SectReview.Bout::winner).toList());
            helper.assertTrue(s.bouts().size() == 3, "не три боя: " + s.bouts().size());
            helper.assertTrue(s.bouts().stream().allMatch(b -> b.started >= 0), "бой решён без поединка (неявка)");
            helper.assertTrue(keys.contains(champion[0]), "победитель не из сетки: " + champion[0]);
            helper.assertTrue(s.bouts().get(2).winner().equals(champion[0]), "победитель не выиграл финал");
            helper.assertTrue(Set.of(s.bouts().get(0).winner(), s.bouts().get(1).winner()).contains(champion[0]), "финалист не из полуфинала");
            helper.assertTrue(SectReview.state(helper.getLevel()) == null, "смотр не снят");
            helper.assertTrue(SectLife.data(helper.getLevel()).lastReview() == finalDay, "день смотра не записан");
            // Время мира общее для всех тестов: уводим его из дня смотра, иначе у следующих партий начнётся окно смотра.
            helper.getLevel().setDayTime((finalDay + 1) * 24000L + 2000L);
        });
    }
}
