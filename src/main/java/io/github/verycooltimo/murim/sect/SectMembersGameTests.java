package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.sect.SectAttendance.Activity;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest-ы членов секты и распорядка для игрока (автор 05.10): доклад главе с поклоном, лекарь лечит раненого после
 * поединка, смена поста охраны; журнал секты — перекличка, занятие через крючок, трапеза, итог наставника; пропущенный
 * строй — ворчание, наряд, вода повару.
 * Площадка {@code murim:sect_yard}; все площадки сжаты в один двор ({@link SectGameTests.Yard}).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectMembersGameTests {

    private static final String YARD = "sect_yard";

    private SectMembersGameTests() {
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

    private static void setTime(GameTestHelper helper, int timeOfDay) {
        long day = Math.floorDiv(helper.getLevel().getDayTime(), 24000L) * 24000L;
        helper.getLevel().setDayTime(day + timeOfDay);
    }

    // ------------------------------------------------------------------ члены секты

    /** Завтрак: Гён Так идёт к главе в главный зал, кланяется; глава поворачивается к нему. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_members_report")
    public static void unAmReportsToLeader(GameTestHelper helper) {
        setTime(helper, 1300);
        SectGameTests.Yard yard = yard(helper, 1.0D);
        SectDisciple leader = npc(helper, yard, "tae_hwi", 12, 14);
        SectDisciple unAm = npc(helper, yard, "gyeong_tak", 3, 3);
        boolean[] seen = new boolean[2];
        helper.onEachTick(() -> {
            seen[0] |= unAm.pose() == SectPose.BOW && unAm.distanceTo(leader) < 3.0D;
            seen[1] |= leader.attending() == unAm;
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(seen[0], "Гён Так не поклонился главе (поза " + unAm.pose().id() + ", до главы "
                    + String.format("%.1f", unAm.distanceTo(leader)) + ")");
            helper.assertTrue(seen[1], "глава не повернулся к докладчику");
        });
    }

    /** После поединка раненый сидит и ждёт; Гён Чхо приходит, лечит на колене; вылеченный встаёт и кланяется. */
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_members_healer")
    public static void healerTreatsWounded(GameTestHelper helper) {
        setTime(helper, 8000);
        SectGameTests.Yard yard = yard(helper, 0.5D);
        SectDisciple healer = npc(helper, yard, "gyeong_cho", 3, 3);
        SectDisciple hurt = npc(helper, yard, "bok_manseok", 18, 18);
        hurt.setHealth(hurt.getMaxHealth() * 0.5F);
        hurt.setWounded(true);
        boolean[] knelt = new boolean[1];
        helper.onEachTick(() -> knelt[0] |= healer.distanceTo(hurt) < 2.5D && (healer.pose() == SectPose.TREAT || hurt.getHealth() > hurt.getMaxHealth() * 0.5F));
        helper.succeedWhen(() -> {
            helper.assertTrue(knelt[0], "лекарь не подошёл лечить раненого");
            helper.assertTrue(!hurt.wounded(), "раненый не вылечен");
            helper.assertTrue(hurt.getHealth() >= hurt.getMaxHealth() - 0.01F, "здоровье не восстановлено: " + hurt.getHealth());
        });
    }

    /** Ночь: сменщик приходит на пост, оба кланяются, сменённый уходит спать; пост не пустует. */
    @GameTest(template = YARD, timeoutTicks = 700, batch = "sect_members_shift")
    public static void guardShiftChange(GameTestHelper helper) {
        setTime(helper, SectSchedule.Period.NIGHT.start() + 20);
        SectGameTests.Yard yard = yard(helper, 0.5D);
        // Дежурство второго поколения (SectRota): дневной дежурный поста 0 ждёт ночного того же дня.
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectRoster dayGuard = SectRota.dayWatch(day, 0);
        SectSchedule.Task post = SectSchedule.post(new SectRota.Duty(0, false));
        Vec3 at = yard.at(post.zone(), post.du(), post.dv());
        Vec3 rel = at.subtract(helper.absoluteVec(Vec3.ZERO));
        SectDisciple out = npc(helper, yard, dayGuard.key(), rel.x - 0.5D, rel.z - 0.5D);
        SectDisciple in = npc(helper, yard, SectRota.nightWatch(day, 0).key(), 20, 20);
        helper.assertTrue(SectLife.onWatch(out), "сменяемый ушёл с поста до прихода сменщика");
        boolean[] bowed = new boolean[1];
        helper.onEachTick(() -> bowed[0] |= in.pose() == SectPose.BOW && out.pose() == SectPose.BOW);
        helper.succeedWhen(() -> {
            helper.assertTrue(bowed[0], "смена без поклона");
            helper.assertTrue(!SectLife.onWatch(out), "сменённый всё ещё на страже");
            helper.assertTrue(SectLife.onWatch(in), "сменщик не на страже");
            helper.assertTrue(in.position().distanceTo(at) < 2.5D, "сменщик не на посту: " + String.format("%.1f", in.position().distanceTo(at)));
            helper.assertTrue(out.position().distanceTo(at) > 2.0D, "сменённый не ушёл с поста");
        });
    }

    // ------------------------------------------------------------------ журнал секты

    /**
     * День ученика: перекличка строя и занятий, занятие через крючок (упражнение по id), трапеза за столом; вечером
     * наставник начинает разговор с итога дня.
     */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_members_log")
    public static void attendanceDay(GameTestHelper helper) {
        SectGameTests.Yard yard = yard(helper, 0.3D);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("met_mentor"));
        Vec3 at = yard.at("training", 0.0D, 0.0D);
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        SectDisciple mentor = npc(helper, yard, "gyeong_pil", 14, 14);
        long base = Math.floorDiv(helper.getLevel().getDayTime(), 24000L) * 24000L;

        // Рассвет открывает новый день секты (23000): строй, потом занятия и ужин того же дня.
        helper.getLevel().setDayTime(base + 23500L);
        SectAttendance.tick(p, yard, 20);
        SectAttendance.Day today = SectAttendance.current(p).today();
        helper.assertTrue(today.expects(Activity.FORMATION), "нет переклички строя");
        SectAttendance.record(p, Activity.FORMATION);

        helper.getLevel().setDayTime(base + 24000L + 2500L);
        SectAttendance.tick(p, yard, 20);
        helper.assertTrue(SectAttendance.current(p).today().missed(Activity.LESSON), "занятия не ждут");
        int before = p.getData(ModAttachments.SECT).contribution();
        helper.assertTrue(SectAttendance.record(p, "pushups"), "крючок не отметил упражнение");
        helper.assertTrue(!SectAttendance.record(p, Activity.LESSON), "занятие отмечено дважды за день");
        helper.assertTrue(p.getData(ModAttachments.SECT).contribution() == before + SectAttendance.LESSON_CONTRIBUTION, "нет заслуг за занятие");

        helper.getLevel().setDayTime(base + 24000L + 9300L);
        for (int i = 0; i < SectAttendance.MEAL_TICKS / 20 + 1; i++) {
            SectAttendance.tick(p, yard, 20);
        }
        today = SectAttendance.current(p).today();
        helper.assertTrue(today.did(Activity.MEAL), "трапеза не засчитана");
        helper.assertTrue(today.full(), "день не полный: " + today);

        DialogueService.Route r = DialogueService.route(p, mentor);
        helper.assertTrue("summary".equals(r.node()), "наставник вечером начинает не с итога дня: " + r.node());
        helper.assertTrue(SectAttendance.verdict(p).getContents() instanceof net.minecraft.network.chat.contents.TranslatableContents t
                && t.getKey().endsWith(".full"), "итог дня не «хорошо»: " + SectAttendance.verdict(p));
        helper.succeed();
    }

    /** Строй пропущен: наставник ворчит и начинает разговор с упрёка; два таких утра — наряд, ведро воды повару снимает его. */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_members_chores")
    public static void missedFormationGivesChores(GameTestHelper helper) {
        SectGameTests.Yard yard = yard(helper, 0.3D);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().with("met_mentor"));
        Vec3 at = yard.at("training", 0.0D, 0.0D);
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        SectDisciple mentor = npc(helper, yard, "gyeong_pil", 13, 13);
        SectDisciple cook = npc(helper, yard, "cook_kim", 11, 13);

        long base = Math.floorDiv(helper.getLevel().getDayTime(), 24000L) * 24000L;
        helper.getLevel().setDayTime(base + 23300L);
        SectAttendance.tick(p, yard, 20);
        helper.getLevel().setDayTime(base + 24000L + 1500L);
        SectAttendance.tick(p, yard, 20);
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        helper.assertTrue(SectAttendance.current(p).scolded() == day, "наставник не заметил пропуск строя");
        helper.assertTrue("scold".equals(DialogueService.route(p, mentor).node()), "разговор не с упрёка");
        // Остался при секте на занятия — пропуск строя засчитан (ушедший в поход после переклички — нет).
        helper.getLevel().setDayTime(base + 24000L + 2500L);
        SectAttendance.tick(p, yard, 20);

        // Второй рассвет без строя, снова на занятиях.
        helper.getLevel().setDayTime(base + 48000L - 700L);
        SectAttendance.tick(p, yard, 20);
        helper.getLevel().setDayTime(base + 48000L + 2500L);
        SectAttendance.tick(p, yard, 20);
        // Третий рассвет: смена дня подводит итог — наряд.
        helper.getLevel().setDayTime(base + 72000L - 700L);
        SectAttendance.tick(p, yard, 20);
        helper.assertTrue(SectAttendance.current(p).chores(), "нет наряда после двух пропусков: " + SectAttendance.report(p));
        helper.assertTrue("chores".equals(DialogueService.route(p, cook).node()), "повар не про наряд");

        p.getInventory().add(new ItemStack(Items.WATER_BUCKET));
        Dialogue d = DialogueLoader.get(cook.dialogue());
        Dialogue.Option water = d.nodes().get("chores").options().get(0);
        helper.assertTrue(DialogueService.applyHeadless(p, cook, water), "вода не принята");
        helper.assertTrue(!SectAttendance.current(p).chores(), "наряд не снят");
        helper.assertTrue(p.getInventory().contains(new ItemStack(Items.BUCKET)), "пустое ведро не вернули");
        helper.succeed();
    }
}
