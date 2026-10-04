package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.Set;

/**
 * GameTest-ы иерархии секты (С3, часть 2): младшего перехватывают на пути к главе; охрана не пускает новичка в казну
 * и при силовом входе вызывает на поединок; положение выросло — днём пускают, ночью снова нет.
 * Площадка {@code murim:sect_yard}; раскладка — {@link Only}: только нужные площадки, все в центре двора.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectHierarchyGameTests {

    private static final String YARD = "sect_yard";

    private SectHierarchyGameTests() {
    }

    /** Только эти площадки, все — квадрат 20×20 с центром в {@code origin}. */
    record Only(Vec3 origin, Set<String> zones) implements SectLayout {
        @Override
        public Vec3 at(String zone, double du, double dv) {
            return zones.contains(zone) ? origin.add(du, 0.0D, dv) : null;
        }

        @Override
        public float yaw(double du, double dv) {
            return SectLayout.yawOf(du, dv);
        }

        @Override
        public double[] half(String zone) {
            return zones.contains(zone) ? new double[] {10.0D, 10.0D} : null;
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            return zones.contains(zone) ? new double[] {pos.x - origin.x, pos.z - origin.z} : null;
        }
    }

    private static Only only(GameTestHelper helper, String... zones) {
        return new Only(SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), Set.of(zones));
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

    private static void place(GameTestHelper helper, ServerPlayer p, double x, double z) {
        Vec3 at = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
    }

    // ------------------------------------------------------------------ перехват у главы

    /**
     * Новичок подходит к главе — старший на посту перехватывает и встаёт между ними; по зову главы (урок наставника)
     * и выпускником — глава говорит сам; рядом никого — глава сам отвечает «не сейчас».
     */
    @GameTest(template = YARD, timeoutTicks = 300, batch = "sect_hierarchy")
    public static void youngDiscipleInterceptedAtLeader(GameTestHelper helper) {
        Only layout = only(helper);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        place(helper, p, 4, 4);
        SectDisciple leader = npc(helper, layout, "hyun_jong", 14, 14);
        SectDisciple guard = npc(helper, layout, "baek_ryeong", 3, 12);

        DialogueService.Route r = DialogueService.route(p, leader);
        helper.assertTrue(r != null && r.intercepted(), "новичка не перехватили: " + r);
        helper.assertTrue(r.speaker() == guard, "перехватил не охранник: " + r.speaker().memberKey());
        helper.assertTrue("intercept_leader".equals(r.id().getPath()), "не тот диалог перехвата: " + r.id());

        // Зов главы (наставник после первого урока) — говорит сам, с узла «позвали».
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with("summon.leader"));
        DialogueService.Route summoned = DialogueService.route(p, leader);
        helper.assertTrue(!summoned.intercepted() && summoned.speaker() == leader && "summoned".equals(summoned.node()),
                "по зову не пустили: " + summoned.node());
        // Выпускник Белого Цветка — без зова.
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without("summon.leader")
                .with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO));
        DialogueService.Route graduate = DialogueService.route(p, leader);
        helper.assertTrue(!graduate.intercepted() && "member".equals(graduate.node()), "выпускника не пустили: " + graduate.node());

        // Снова новичок: перехват — охранник шагает наперерез и встаёт между игроком и главой.
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        DialogueService.Route again = DialogueService.route(p, leader);
        DialogueService.engage(p, leader, again);
        helper.assertTrue(guard.blocking() && guard.blockTarget() == p.getId(), "охранник не встал на пути");
        Vec3 between = p.position().add(leader.position().subtract(p.position()).multiply(1, 0, 1).normalize().scale(1.4D));
        helper.succeedWhen(() -> {
            double d = guard.position().multiply(1, 0, 1).distanceTo(between.multiply(1, 0, 1));
            helper.assertTrue(d < 1.2D, "охранник не между игроком и главой: " + String.format("%.2f", d));
            // Охраны рядом нет — глава отвечает сам: «не сейчас».
            guard.setPos(guard.getX() + 40.0D, guard.getY(), guard.getZ());
            DialogueService.Route alone = DialogueService.route(p, leader);
            guard.setPos(guard.getX() - 40.0D, guard.getY(), guard.getZ());
            helper.assertTrue(alone.speaker() == leader && "busy".equals(alone.node()), "без охраны глава не сказал «не сейчас»: " + alone.node());
        });
    }

    // ------------------------------------------------------------------ охрана казны

    /** Новичок в казне: охранник подходит, отталкивает, после трёх толчков — вызов на поединок и −заслуги. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_access_day")
    public static void guardBlocksTreasury(GameTestHelper helper) {
        SectGameTests.setPeriod(helper, SectSchedule.Period.TRAINING);
        Only layout = only(helper, "treasury");
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined().contribute(8));
        place(helper, p, 12, 12);
        SectDisciple guard = npc(helper, layout, "baek_won", 12, 3);
        helper.assertTrue(!SectAccess.forbidden(layout, p.position(), SectWatch.standing(p), Set.of(), SectSchedule.Period.TRAINING, 0.0D).isEmpty(),
                "новичок в казне — не нарушение");
        boolean[] blocked = new boolean[1];
        helper.onEachTick(() -> {
            if (helper.getLevel().getGameTime() % SectWatch.PERIOD == 0) {
                place(helper, p, 12, 12);
                SectWatch.check(p, layout);
            }
            blocked[0] |= guard.blockTarget() == p.getId();
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(blocked[0], "охранник не встал на пути");
            SectWatch.Trespass t = SectWatch.of(p);
            helper.assertTrue("spar".equals(t.last()), "нет поединка за силовой вход, последнее: " + t.last());
            helper.assertTrue(guard.spar() != SectDisciple.Spar.NONE && p.getUUID().equals(guard.partner()), "охранник не вызвал на поединок");
            helper.assertTrue(p.getData(ModAttachments.SECT).contribution() == 8 - SectWatch.PENALTY, "заслуги не сняты");
        });
    }

    // ------------------------------------------------------------------ положение выросло

    /**
     * Выпускник Белого Цветка в казне днём — охрана молчит; наступила ночь — сначала «выходи, закрывается», потом
     * тот же выпускник — нарушитель.
     */
    @GameTest(template = YARD, timeoutTicks = 800, batch = "sect_access_night")
    public static void accessOpensAfterStandingRises(GameTestHelper helper) {
        SectGameTests.setPeriod(helper, SectSchedule.Period.TRAINING);
        Only layout = only(helper, "treasury");
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        p.setData(ModAttachments.SECT, SectState.NONE.joined());
        place(helper, p, 12, 12);
        // Охранник без поста в этой раскладке (его площадки нет) — стоит, где поставлен, и смотрит по сторонам.
        SectDisciple guard = npc(helper, layout, "baek_seo", 12, 7);
        // Новичку сюда нельзя; оба урока наставника сданы — выпускник, днём можно.
        helper.assertTrue(!SectAccess.forbidden(layout, p.position(), SectWatch.standing(p), Set.of(), SectSchedule.Period.TRAINING, 0.0D).isEmpty(),
                "новичку можно в казну");
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectStanding.LESSON_ONE).with(SectStanding.LESSON_TWO));
        helper.assertTrue(SectWatch.standing(p) == SectStanding.GRADUATE, "положение не выросло: " + SectWatch.standing(p));
        int[] ticks = new int[1];
        boolean[] quietByDay = {true};
        helper.onEachTick(() -> {
            ticks[0]++;
            if (ticks[0] == 120) {
                SectGameTests.setPeriod(helper, SectSchedule.Period.NIGHT);
            }
            if (helper.getLevel().getGameTime() % SectWatch.PERIOD == 0) {
                place(helper, p, 12, 12);
                SectWatch.check(p, layout);
                if (ticks[0] < 120 && (SectWatch.of(p).strikes() > 0 || guard.blockTarget() == p.getId())) {
                    quietByDay[0] = false;
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(ticks[0] > 130, "ещё день");
            helper.assertTrue(quietByDay[0], "днём охрана не пустила выпускника");
            // Был внутри по праву: полминуты на выход без толчков.
            helper.assertTrue(ticks[0] > 120 + SectWatch.WARN_EVERY / 2, "ещё время выйти");
            helper.assertTrue(SectWatch.of(p).strikes() > 0 || guard.blockTarget() == p.getId(), "ночью охрана пропустила ученика в казну");
        });
    }
}
