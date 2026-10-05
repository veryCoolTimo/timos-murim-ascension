package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.mastery.MasteryState;
import io.github.verycooltimo.murim.mastery.TechniqueProgress;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * GameTest-ы жизни секты (план §4.2, §5.1, §5.4): строй в такт, поединок учеников без смерти, защита своих,
 * утренняя тренировка игрока, цепочка уроков наставника. Площадка {@code murim:sect_yard} — пол 24×24;
 * раскладка площадок горы заменена сжатой {@link Yard}: все площадки в одном дворе.
 *
 * <p>API: reference/neoforge-src/net/neoforged/neoforge/gametest/GameTestHolder.java,
 * reference/minecraft-src/net/minecraft/gametest/framework/GameTestHelper.java
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectGameTests {

    private static final String YARD = "sect_yard";

    private SectGameTests() {
    }

    /** Все площадки — в одном дворе: смещения распорядка сжаты в {@code scale} раз. */
    record Yard(Vec3 origin, double scale) implements SectLayout {
        @Override
        public Vec3 at(String zone, double du, double dv) {
            return origin.add(du * scale, 0.0D, dv * scale);
        }

        @Override
        public float yaw(double du, double dv) {
            return SectLayout.yawOf(du, dv);
        }

        @Override
        public double[] half(String zone) {
            return new double[] {10.0D / scale, 10.0D / scale};
        }

        @Override
        public double[] local(String zone, Vec3 pos) {
            return new double[] {(pos.x - origin.x) / scale, (pos.z - origin.z) / scale};
        }
    }

    private static Yard yard(GameTestHelper helper, double scale) {
        helper.assertBlockPresent(net.minecraft.world.level.block.Blocks.POLISHED_ANDESITE, new net.minecraft.core.BlockPos(12, 1, 12));
        return new Yard(SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), scale);
    }

    private static SectDisciple npc(GameTestHelper helper, Yard yard, String key, double x, double z) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), helper.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(yard);
        d.setKeepAwake(true);
        Vec3 at = SectLife.stand(helper.getLevel(), helper.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.getLevel().addFreshEntity(d);
        return d;
    }

    /**
     * Игрок для проверки без входа на сервер: {@code makeMockServerPlayerInLevel} вызывает вход, и события входа
     * шлют наши пакеты, которые тестовое соединение не принимает. Здесь — соединение на встроенном канале,
     * ванильные пакеты (сообщения, звук) уходят в никуда.
     * API: reference/minecraft-src/net/minecraft/server/network/ServerGamePacketListenerImpl.java (конструктор ставит player.connection)
     */
    static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "sect-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }

    static void setPeriod(GameTestHelper helper, SectSchedule.Period p) {
        long day = Math.floorDiv(helper.getLevel().getDayTime(), 24000L) * 24000L;
        helper.getLevel().setDayTime(day + p.start() + 20);
    }

    // ------------------------------------------------------------------ строй

    /** Рассвет: ученики сами доходят до своих мест в рядах и делают формы в такт (одна и та же форма у всех). */
    @GameTest(template = YARD, timeoutTicks = 500, batch = "sect_dawn")
    public static void formationInStep(GameTestHelper helper) {
        setPeriod(helper, SectSchedule.Period.FORMATION);
        Yard yard = yard(helper, 0.8D);
        // Второе поколение в строю — не дежурный (SectRota): первый по списку после старшего.
        long day = SectSchedule.day(helper.getLevel().getDayTime());
        List<SectDisciple> row = List.of(npc(helper, yard, SectRota.training(day).get(1).key(), 3, 3), npc(helper, yard, "cheong_jin", 20, 4),
                npc(helper, yard, "cheong_seok", 4, 20));
        Set<String> drilled = new HashSet<>();
        Set<String> together = new HashSet<>();
        helper.onEachTick(() -> {
            for (SectDisciple d : row) {
                if (d.anim().contains("six_form_")) {
                    drilled.add(d.memberKey());
                }
            }
            // Через пару тиков после начала такта у всех одна и та же форма — строй синхронен.
            boolean settled = Math.floorMod(helper.getLevel().getGameTime(), (long) SectSchedule.BEAT) == 3;
            if (settled && row.stream().allMatch(d -> d.anim().contains("six_form_"))) {
                together.add(row.get(0).anim());
                if (row.stream().map(SectDisciple::anim).distinct().count() != 1) {
                    helper.fail("строй вразнобой: " + row.stream().map(SectDisciple::anim).toList());
                }
            }
        });
        helper.succeedWhen(() -> {
            for (SectDisciple d : row) {
                double[] slot = SectSchedule.formationSlot(d.member().orElseThrow(), day);
                Vec3 want = yard.at("training", slot[0], slot[1]);
                double dx = d.getX() - want.x;
                double dz = d.getZ() - want.z;
                helper.assertTrue(dx * dx + dz * dz < 1.6D * 1.6D, d.memberKey() + " не на своём месте в ряду: " + d.position()
                        + " вместо " + want + ", " + d.doing() + " " + d.getNavigation().isDone() + " спит " + d.dormant());
            }
            helper.assertTrue(drilled.size() == row.size(), "не все сделали форму: " + drilled);
            helper.assertTrue(together.size() >= 2, "строй сделал меньше двух общих форм");
        });
    }

    // ------------------------------------------------------------------ поединок учеников

    /** Двое учеников бьются друг с другом до половины сил, кланяются и оба живы. */
    @GameTest(template = YARD, timeoutTicks = 1600, batch = "sect_day")
    public static void disciplesSparWithoutDeath(GameTestHelper helper) {
        setPeriod(helper, SectSchedule.Period.TRAINING);
        Yard yard = yard(helper, 0.3D);
        SectDisciple a = npc(helper, yard, "cheong_pyo", 10, 12);
        SectDisciple b = npc(helper, yard, "cheong_il", 14, 12);
        boolean[] fought = new boolean[1];
        float[] lowest = {Float.MAX_VALUE, Float.MAX_VALUE};
        boolean[] sheathedBow = {true};
        boolean[] drawnFight = {false};
        helper.runAfterDelay(5, () -> a.sparWith(b, 0));
        helper.onEachTick(() -> {
            if (a.spar() == SectDisciple.Spar.FIGHT && b.spar() == SectDisciple.Spar.FIGHT) {
                fought[0] = true;
                // Меч обнажается в поединке (сервер ставит со следующего тика).
                drawnFight[0] |= a.drawn() && b.drawn();
            }
            if (a.spar() == SectDisciple.Spar.BOW_IN && a.drawn()) {
                sheathedBow[0] = false;
            }
            lowest[0] = Math.min(lowest[0], a.getHealth());
            lowest[1] = Math.min(lowest[1], b.getHealth());
            for (SectDisciple d : List.of(a, b)) {
                if (!d.isAlive()) {
                    helper.fail(d.memberKey() + " погиб: " + d.getRemovalReason() + " / " + d.getLastDamageSource()
                            + " y=" + d.getY() + " hp=" + d.getHealth());
                }
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(fought[0], "поединок не начался");
            helper.assertTrue(drawnFight[0], "в поединке меч не обнажён");
            helper.assertTrue(sheathedBow[0], "поклон с обнажённым мечом");
            helper.assertTrue(a.isAlive() && b.isAlive(), "ученик погиб в поединке");
            helper.assertTrue(a.spar() == SectDisciple.Spar.NONE && b.spar() == SectDisciple.Spar.NONE, "поединок ещё идёт");
            // Без позы с мечом и без боя — меч в ножнах.
            for (SectDisciple d : List.of(a, b)) {
                helper.assertTrue(!d.drawn() || d.pose() != io.github.verycooltimo.murim.entity.SectPose.NONE || !d.free(),
                        d.memberKey() + ": после поединка меч не убран в ножны");
            }
            float floorA = a.getMaxHealth() * (1.0F - SectDisciple.SPAR_LOSS);
            float floorB = b.getMaxHealth() * (1.0F - SectDisciple.SPAR_LOSS);
            helper.assertTrue(lowest[0] <= floorA + 0.5F || lowest[1] <= floorB + 0.5F, "никто не дошёл до порога");
            helper.assertTrue(lowest[0] >= floorA - 0.5F && lowest[1] >= floorB - 0.5F, "урон прошёл ниже порога");
        });
    }

    // ------------------------------------------------------------------ защита своих

    /** Зомби бьёт ученика: ученики рядом встают на защиту и убивают его; сам ученик не умирает. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_night")
    public static void disciplesDefendEachOther(GameTestHelper helper) {
        setPeriod(helper, SectSchedule.Period.NIGHT);
        Yard yard = yard(helper, 0.3D);
        SectDisciple victim = npc(helper, yard, "cheong_yeon", 8, 8);
        SectDisciple friend = npc(helper, yard, "cheong_gwang", 16, 16);
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new Vec3(9.5D, 2.0D, 9.5D));
        helper.runAfterDelay(3, () -> {
            victim.hurt(helper.getLevel().damageSources().mobAttack(zombie), 100.0F);
            helper.assertTrue(victim.isAlive() && victim.getHealth() >= 1.0F, "ученик погиб от одного удара");
            helper.assertTrue(friend.defending() || friend.getTarget() == zombie, "товарищ не встал на защиту");
        });
        boolean[] drawn = new boolean[1];
        helper.onEachTick(() -> drawn[0] |= friend.defending() && friend.drawn());
        helper.succeedWhen(() -> {
            helper.assertTrue(!zombie.isAlive(), "зомби жив");
            helper.assertTrue(drawn[0], "защищает своих с мечом в ножнах");
            helper.assertTrue(victim.isAlive() && friend.isAlive(), "ученик погиб");
        });
    }

    // ------------------------------------------------------------------ игрок в строю

    /** Игрок в строю: десять форм в такт — «Утренняя тренировка», освоение на день быстрее; не в такт — не считается. */
    @GameTest(template = YARD, timeoutTicks = 600, batch = "sect_dawn")
    public static void playerMorningTraining(GameTestHelper helper) {
        setPeriod(helper, SectSchedule.Period.FORMATION);
        Yard yard = yard(helper, 0.5D);
        ServerPlayer player = fakePlayer(helper);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT, SectState.NONE.joined());
        double[] free = SectSchedule.slot(1, SectSchedule.COLUMNS - 1);
        Vec3 at = yard.at("training", free[0], free[1]);
        player.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        helper.assertTrue(SectLife.inFormation(yard, player.position()), "свободное место строя — не в строю");
        int[] calls = new int[2];
        helper.onEachTick(() -> {
            long now = helper.getLevel().getGameTime();
            int phase = (int) Math.floorMod(now, (long) SectSchedule.BEAT);
            if (phase == SectSchedule.BEAT_STRIKE) {
                SectLife.onPlayerForm(player, yard);
                calls[0]++;
            } else if (phase == SectSchedule.BEAT - 3) {
                // Не в такт: не засчитывается.
                SectLife.onPlayerForm(player, yard);
                calls[1]++;
            }
        });
        helper.succeedWhen(() -> {
            helper.assertTrue(calls[0] >= SectLife.MORNING_FORMS, "форм в такт пока " + calls[0]);
            helper.assertTrue(SectLife.masteryBonus(player) > 1.0D, "утренняя тренировка не засчитана");
            helper.assertTrue(SectAttendance.current(player).today().did(SectAttendance.Activity.FORMATION), "строй не отмечен в журнале секты");
        });
    }

    // ------------------------------------------------------------------ уроки наставника

    private static Dialogue.Option option(Dialogue d, String node, String text, int nth) {
        int seen = 0;
        for (Dialogue.Option o : d.nodes().get(node).options()) {
            if (o.text().equals(text) && seen++ == nth) {
                return o;
            }
        }
        throw new IllegalStateException("нет варианта " + text + " в " + node);
    }

    private static boolean hasBook(ServerPlayer p, ResourceLocation technique) {
        for (ItemStack s : p.getInventory().items) {
            if (s.is(ModItems.TECHNIQUE_MANUAL.get()) && technique.equals(s.get(ModDataComponents.TECHNIQUE.get()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Цепочка уроков по данным диалога (план §5.1): вступление → книга Шести Равновесий; Шесть Равновесий до 2-го
     * слоя + ранг → книга Падающего Цветка; урок спарринга, три чистых удара + второй ранг → книга Семи Цветков.
     */
    @GameTest(template = YARD, timeoutTicks = 100, batch = "sect_lessons")
    public static void mentorLessonChain(GameTestHelper helper) {
        ServerPlayer p = fakePlayer(helper);
        Yard yard = yard(helper, 0.3D);
        SectDisciple mentor = npc(helper, yard, "un_geom", 12, 12);
        Dialogue d = DialogueLoader.get(mentor.dialogue());
        helper.assertTrue(d != null, "нет диалога наставника " + mentor.dialogue());
        ResourceLocation six = SectService.SIX;
        ResourceLocation petal = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "falling_petal_sword");
        ResourceLocation plum = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "seven_plum_blossoms");

        SectService.join(p);
        helper.assertTrue(p.getData(ModAttachments.SECT).member(), "не вступил");
        helper.assertTrue(hasBook(p, six), "нет книги Шести Равновесий");
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with("met_mentor"));

        helper.assertTrue(DialogueService.applyHeadless(p, mentor, option(d, "lessons", "dialogue.murim.mentor.opt.take_six", 0)), "урок 1 не взят");
        Dialogue.Option doneSix = option(d, "lessons", "dialogue.murim.mentor.opt.done_six", 0);
        helper.assertTrue(!DialogueService.applyHeadless(p, mentor, doneSix), "урок 1 сдан без слоя");
        p.setData(ModAttachments.MASTERY, MasteryState.EMPTY.with(six, TechniqueProgress.learned(2, 6)));
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(1));
        helper.assertTrue(DialogueService.applyHeadless(p, mentor, doneSix), "урок 1 не сдан на 2-м слое и третьем ранге");
        helper.assertTrue(hasBook(p, petal), "нет книги Падающего Цветка");

        helper.assertTrue(DialogueService.applyHeadless(p, mentor, option(d, "lessons", "dialogue.murim.mentor.opt.take_spar", 0)), "урок 2 не взят");
        helper.assertTrue(p.getData(ModAttachments.SECT).has(SectService.LESSON_SPAR), "нет флага урока спарринга");
        // Три чистых удара по старшему — флаг ставит SectService.onSparEnd по счёту чистых попаданий.
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SectService.SPAR_CLEAN));
        Dialogue.Option doneSpar = option(d, "lessons", "dialogue.murim.mentor.opt.done_spar", 0);
        helper.assertTrue(!DialogueService.applyHeadless(p, mentor, doneSpar), "урок 2 сдан на первом ранге");
        p.setData(ModAttachments.PROFILE, p.getData(ModAttachments.PROFILE).withRank(2));
        helper.assertTrue(DialogueService.applyHeadless(p, mentor, doneSpar), "урок 2 не сдан");
        helper.assertTrue(hasBook(p, plum), "нет книги Семи Цветков");
        helper.succeed();
    }
}
