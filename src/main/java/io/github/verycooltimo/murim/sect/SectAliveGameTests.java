package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectPose;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTest-ы живой горы (автор 05.10: «ночью спят, утром тренируются, под дождь уходят, реакции и фразы — да»): дождь
 * уводит под крышу и отпускает обратно; ночью стража на посту с фонарём, остальные спят; техника игрока рядом — люди
 * останавливаются и смотрят. Площадка {@code murim:sect_yard} ({@link SectGameTests.Yard}); дождь задаётся человеку
 * ({@link SectDisciple#setRainOverride}), чтобы не трогать погоду мира соседних проверок.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectAliveGameTests {

    private static final String YARD = "sect_yard";

    private SectAliveGameTests() {
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

    /** Для сообщений провала: что делает человек. */
    static String state(SectDisciple d) {
        SectLife.Resolved r = SectLife.resolve(d);
        return d.memberKey() + " дело " + d.doingKind() + " поза " + d.pose().id() + " у " + d.blockPosition().toShortString()
                + (r == null ? "" : " до места " + String.format("%.1f", d.position().distanceTo(r.spot())) + " (" + r.task().kind() + ")")
                + " свободен " + d.free() + " спит " + d.isSleeping() + " сидит " + d.sitting() + " тик " + d.tickCount;
    }

    private static boolean covered(SectDisciple d) {
        return SectWeather.covered(d.level(), d.blockPosition());
    }

    /** Дождь: занимающийся днём уходит под навес в углу двора; дождь кончился — выходит обратно к своему делу. */
    // Небо открыто: по умолчанию GameTest закрывает площадку потолком из барьера, и «крыша» была бы везде.
    @GameTest(template = YARD, timeoutTicks = 900, batch = "sect_alive_rain", skyAccess = true)
    public static void rainSendsUnderCover(GameTestHelper helper) {
        setTime(helper, 3000);
        // Навес 4×4 в углу двора на высоте трёх блоков над полом.
        for (int x = 2; x <= 5; x++) {
            for (int z = 2; z <= 5; z++) {
                helper.setBlock(new BlockPos(x, 5, z), Blocks.OAK_PLANKS);
            }
        }
        SectGameTests.Yard yard = yard(helper, 0.5D);
        SectDisciple d = npc(helper, yard, "cheong_jin", 16, 16);
        d.setRainOverride(true);
        int[] phase = {0};
        helper.onEachTick(() -> {
            if (phase[0] == 0 && covered(d) && d.doingKind() != null
                    && (d.doingKind() == SectSchedule.Kind.SHELTER || d.doingKind().seated()) && d.getNavigation().isDone()) {
                phase[0] = 1;
                d.setRainOverride(false);
            } else if (phase[0] == 1 && !covered(d) && d.doingKind() != SectSchedule.Kind.SHELTER) {
                phase[0] = 2;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(phase[0] >= 1, "в дождь не ушёл под навес: " + state(d) + ", под крышей " + covered(d));
            helper.assertTrue(phase[0] == 2, "после дождя остался под навесом: дело " + d.doingKind());
        });
    }

    /** Ночь: дежурный ночной смены стоит на посту с фонарём и не спит; ученик третьего поколения и наставник спят. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_alive_night")
    public static void nightGuardsAwakeOthersAsleep(GameTestHelper helper) {
        setTime(helper, SectSchedule.Period.NIGHT.start() + SectSchedule.HANDOVER + 100);
        SectGameTests.Yard yard = yard(helper, 0.5D);
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        SectRoster watchKey = SectRota.nightWatch(day, 0);
        SectDisciple guard = npc(helper, yard, watchKey.key(), 4, 4);
        SectDisciple junior = npc(helper, yard, "cheong_jin", 20, 6);
        SectDisciple mentor = npc(helper, yard, "un_geom", 6, 20);
        SectSchedule.Task post = SectSchedule.post(SectRota.duty(watchKey, helper.getLevel().getDayTime()).orElseThrow());
        Vec3 at = yard.at(post.zone(), post.du(), post.dv());
        helper.succeedWhen(() -> {
            helper.assertTrue(guard.position().distanceTo(at) < 1.5D, "ночной дежурный не на посту: "
                    + String.format("%.1f", guard.position().distanceTo(at)));
            helper.assertTrue(guard.pose() == SectPose.GUARD && !guard.isSleeping() && !guard.sitting(),
                    "ночной дежурный не стоит на страже: поза " + guard.pose().id());
            helper.assertTrue(guard.getOffhandItem().is(Items.LANTERN), "у ночного дежурного нет фонаря");
            for (SectDisciple s : java.util.List.of(junior, mentor)) {
                helper.assertTrue(s.doingKind() == SectSchedule.Kind.SLEEP && (s.isSleeping() || s.pose() == SectPose.SLEEP),
                        "не спит ночью: " + state(s));
            }
        });
    }

    /** Игрок применил технику рядом: свободный ученик останавливается и поворачивается к нему. */
    @GameTest(template = YARD, timeoutTicks = 400, batch = "sect_alive_watch")
    public static void techniqueDrawsWatchers(GameTestHelper helper) {
        setTime(helper, 3000);
        SectGameTests.Yard yard = yard(helper, 0.5D);
        SectDisciple d = npc(helper, yard, "cheong_jin", 12, 12);
        ServerPlayer p = SectGameTests.fakePlayer(helper);
        Vec3 pp = helper.absoluteVec(new Vec3(4.5D, 2.0D, 20.5D));
        p.moveTo(pp.x, SectLife.stand(helper.getLevel(), pp).y, pp.z, 0.0F, 0.0F);
        long[] fired = {-1L};
        boolean[] watched = new boolean[1];
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            if (fired[0] < 0 && d.doingKind() != null && d.tickCount > 60) {
                fired[0] = now;
                SectReactions.onTechnique(p);
            }
            if (fired[0] >= 0 && now - fired[0] > 15 && d.watching() == p) {
                float want = (float) (Mth.atan2(p.getZ() - d.getZ(), p.getX() - d.getX()) * Mth.RAD_TO_DEG) - 90.0F;
                if (Math.abs(Mth.wrapDegrees(want - d.yBodyRot)) < 35.0F && d.getNavigation().isDone()) {
                    watched[0] = true;
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(fired[0] >= 0, "ученик не начал дело распорядка: " + state(d));
            helper.assertTrue(watched[0], "ученик не остановился и не повернулся к игроку: смотрит на " + d.watching()
                    + ", дело " + d.doingKind());
        });
    }
}
