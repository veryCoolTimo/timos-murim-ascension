package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectRoster;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Optional;

/**
 * Распорядок дня у NPC секты (docs/design/23-mount-hua-sect.md §4.2): «время → место → действие».
 * Что и где — {@link SectLife#resolve}; здесь только как: дойти, встать лицом куда надо и делать дело
 * (формы в такт строя, обход рядов, прыжки по столбам, сидеть за столом, лечь на кровать).
 *
 * <p>Застрял: каждые две секунды новая попытка пути; через 30 с без продвижения и без игрока рядом —
 * переход к месту (план §4.2: «через 30 с застревания — переход к точке, если игрока рядом нет»).
 * В бою, поединке и разговоре цель не работает: у них приоритет выше.
 */
public final class ScheduleGoal extends Goal {

    /** Подошёл к месту. */
    private static final double ARRIVE = 0.6D;
    /** Без продвижения столько тиков — застрял. */
    private static final int STUCK_TICKS = 600;

    private final SectDisciple npc;
    private SectLife.Resolved current;
    private int refresh;
    private int repath;
    private int stuck;
    private double best = Double.MAX_VALUE;
    private int pause;
    private int waypoint;
    private Vec3 roam;
    private BlockPos bed;
    private int bedSearch;
    private int drillStep;
    private int drillClock;
    /** Последний {@link #arrive} — на месте дела (поза дела), иначе в пути (шаг). */
    private boolean onSpot;
    /** Глава у ворот уже поклонился этому игроку (id), пока тот рядом. */
    private int greeted = -1;
    /** Часы реплик и жестов (совет, доклад, урок, поклон предкам). */
    private int talkClock;
    /** Докладчик уже поклонился главе на этом докладе. */
    private boolean reported;
    /** Печь лекаря (если автор её поставил) и место перед ней. */
    private BlockPos stove;
    private Vec3 stoveSpot;
    private float stoveYaw;
    private int stoveSearch;
    /** Сколько тиков лекарь идёт к раненому. */
    private int treatWalk;

    public ScheduleGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return npc.member().isPresent() && !npc.dormant() && npc.free() && !npc.blocking() && npc.layout() != null;
    }

    @Override
    public boolean canContinueToUse() {
        return canUse();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        current = null;
        refresh = 0;
    }

    @Override
    public void stop() {
        npc.getNavigation().stop();
        current = null;
        npc.setPose(SectPose.NONE);
    }

    @Override
    public void tick() {
        if (current == null || --refresh <= 0) {
            update();
        }
        if (current == null) {
            return;
        }
        Kind kind = current.task().kind();
        // Разовая поза (поклон старшему, передача поста, носильщик отдал груз): стоит на месте, пока она идёт.
        if (npc.poseHeld()) {
            npc.getNavigation().stop();
            LivingEntity to = npc.attending();
            if (to != null) {
                npc.faceEntity(to, 20.0F);
                npc.getLookControl().setLookAt(to, 30.0F, 30.0F);
            }
            return;
        }
        // Повернулся к подошедшему (носильщик, докладчик, сменщик, лекарь): стоит, смотрит, говорит.
        LivingEntity to = npc.attending();
        if (to != null) {
            npc.getNavigation().stop();
            if (!kind.seated()) {
                npc.faceEntity(to, 20.0F);
                npc.setPose(SectPose.TALK);
            }
            npc.getLookControl().setLookAt(to, 30.0F, 30.0F);
            return;
        }
        if (!onSpot && greetSenior(kind)) {
            return;
        }
        tickPose(kind);
        switch (kind) {
            case COUNCIL -> council();
            case REPORT -> report();
            case RECEIVE -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 15.0F);
                    lookAtNearPlayer(6.0D);
                }
            }
            case COUNT, READ -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 20.0F);
                    if (kind == Kind.COUNT && npc.getRandom().nextInt(400) == 0) {
                        npc.gesture("nod");
                    }
                }
            }
            case LECTURE -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 20.0F);
                    if (--talkClock <= 0) {
                        talkClock = 45 + npc.getRandom().nextInt(40);
                        npc.gesture(npc.getRandom().nextInt(3) == 0 ? "point" : "explain");
                    }
                }
            }
            case GRIND -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 30.0F);
                    npc.sit(true);
                }
            }
            case BREW -> brew();
            case TREAT -> treat();
            case WAIT_TREAT -> {
                if (arrive(current.spot(), 0.5D)) {
                    npc.sit(true);
                }
            }
            case REVERE -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 15.0F);
                    if (--talkClock <= 0) {
                        talkClock = 300 + npc.getRandom().nextInt(200);
                        npc.holdPose(SectPose.BOW, 44);
                    }
                }
            }
            case FORM_ROW -> formRow();
            case INSPECT -> inspect();
            case SPAR -> {
                if (arrive(current.spot(), 0.9D)) {
                    npc.faceYaw(current.yaw(), 20.0F);
                    if ((npc.tickCount & 15) == 0) {
                        SectLife.tryPair(npc, current.task().partner());
                    }
                }
            }
            case POLES -> poles();
            case DRILL -> drill();
            case CHORE, WORK -> roamAround(kind == Kind.CHORE ? 0.7D : 0.5D, kind == Kind.CHORE ? 6.0D : 3.0D);
            case CARRY -> carry();
            case SWEEP -> work(0.45D, 7.0D, "sweep", 50, 70);
            case COOK -> work(0.4D, 1.5D, "cook", 100, 120);
            case SERVE -> work(0.5D, 5.0D, "serve", 30, 40);
            case TEND -> work(0.4D, 6.0D, "tend", 80, 100);
            case EAT, MEDITATE, REST -> {
                if (arrive(current.spot(), 0.8D)) {
                    npc.faceYaw(current.yaw(), 30.0F);
                    npc.sit(true);
                }
            }
            case SLEEP -> sleep();
            case GREET -> {
                // Глава встречает: стоит у ворот, подошедшему игроку — поклон «кулак в ладонь» (один раз).
                if (arrive(current.spot(), 1.0D)) {
                    npc.faceYaw(current.yaw(), 15.0F);
                    Player p = npc.level().getNearestPlayer(npc, 8.0D);
                    if (p != null) {
                        npc.getLookControl().setLookAt(p, 20.0F, 20.0F);
                    }
                    Player near = npc.level().getNearestPlayer(npc, 4.5D);
                    if (near == null) {
                        greeted = -1;
                    } else if (near.getId() != greeted) {
                        greeted = near.getId();
                        npc.holdPose(SectPose.BOW, 44);
                    }
                }
            }
            default -> {
                // WATCH, GUARD, HEAL_POST: стоять на месте лицом куда надо, глазами — на ближнего игрока.
                if (kind == Kind.GUARD && handover()) {
                    return;
                }
                if (arrive(current.spot(), 0.8D)) {
                    npc.faceYaw(current.yaw(), 15.0F);
                    // Старшие у площадки поединков смотрят и поправляют: показывают рукой, кивают.
                    if (kind == Kind.WATCH && --talkClock <= 0) {
                        talkClock = 80 + npc.getRandom().nextInt(120);
                        npc.gesture(npc.getRandom().nextInt(3) == 0 ? "point" : "nod");
                    }
                    Player p = npc.level().getNearestPlayer(npc, 8.0D);
                    // Ночная стража не чует затылком: голова поворачивается к игроку, только если он перед ней или
                    // вплотную — иначе охрана «видела бы» любого в 8 блоках (SectWatch: ночью — только перед собой).
                    if (p != null && kind == Kind.GUARD && SectSchedule.at(npc.level().getDayTime()) == SectSchedule.Period.NIGHT
                            && npc.distanceTo(p) > io.github.verycooltimo.murim.sect.SectAccess.CLOSE
                            && Math.abs(net.minecraft.util.Mth.wrapDegrees(
                            (float) (net.minecraft.util.Mth.atan2(p.getZ() - npc.getZ(), p.getX() - npc.getX()) * net.minecraft.util.Mth.RAD_TO_DEG) - 90.0F
                                    - npc.yBodyRot)) > io.github.verycooltimo.murim.sect.SectAccess.NIGHT_HALF_ANGLE) {
                        p = null;
                    }
                    if (p != null) {
                        npc.getLookControl().setLookAt(p, 20.0F, 20.0F);
                    }
                }
            }
        }
    }

    /**
     * Поза по делу (крючок анимаций, {@link SectPose#forTask}): на месте — поза дела, в пути — шаг. Ставится
     * ДО дела этого тика: дело читает {@link #onSpot} прошлого тика, разница в тик не видна.
     */
    private void tickPose(Kind kind) {
        if (npc.poseHeld()) {
            return;
        }
        if (kind == Kind.SLEEP && npc.isSleeping()) {
            npc.setPose(SectPose.SLEEP);
            return;
        }
        npc.setPose(SectPose.forTask(kind, onSpot, SectPose.carrier(npc)));
    }

    /** Новое задание (раз в секунду): смена части суток, выход главы к воротам. */
    private void update() {
        refresh = 20;
        SectLife.Resolved next = SectLife.resolve(npc);
        if (next == null) {
            current = null;
            return;
        }
        boolean changed = current == null || current.task().kind() != next.task().kind()
                || !current.task().zone().equals(next.task().zone()) || current.spot().distanceToSqr(next.spot()) > 1.0D;
        current = next;
        if (changed) {
            stuck = 0;
            best = Double.MAX_VALUE;
            repath = 0;
            pause = 0;
            waypoint = 0;
            roam = null;
            bed = null;
            bedSearch = 0;
            onSpot = false;
            Kind k = next.task().kind();
            if (!k.seated()) {
                npc.sit(false);
            }
            if (k != Kind.SLEEP && npc.isSleeping()) {
                npc.stopSleeping();
            }
            npc.workPose(null);
            if (k != Kind.CARRY) {
                npc.setCarrying(false);
            }
            pickup = null;
            legTicks = 0;
            reported = false;
            talkClock = 0;
            stove = null;
            stoveSpot = null;
            stoveSearch = 0;
            outfit(k);
        }
        // Ночная стража — с фонарём в левой руке.
        if (next.task().kind() == Kind.GUARD) {
            boolean night = SectSchedule.at(npc.level().getDayTime()) == SectSchedule.Period.NIGHT;
            npc.holdOff(night ? Items.LANTERN : null);
        }
    }

    // ------------------------------------------------------------------ слуги (С3, часть 2)

    /** Где носильщик берёт груз (несёт ли — {@link SectDisciple#carrying()}). */
    private Vec3 pickup;
    private int legTicks;

    /**
     * Реквизит по делу (ванильные предметы в руках): слуги — метла, мотыга, миска, книга учёта; Хён Ён — книга учёта
     * в левой руке; Хён Сан — книга; Ун Гак — ступка (миска) и пестик (палка), черпак у печи, бинт (бумага) у раненого.
     */
    private void outfit(Kind k) {
        if (npc.role().lay()) {
            npc.hold(switch (k) {
                // Метла: ванильная кисть — ближайший предмет-«щётка» (своей метлы в моде нет).
                case SWEEP -> Items.BRUSH;
                case TEND -> Items.WOODEN_HOE;
                case SERVE -> Items.BOWL;
                case WORK -> npc.role() == SectRole.STEWARD ? Items.WRITABLE_BOOK : null;
                case CARRY -> npc.carrying() ? load() : empty();
                default -> null;
            });
            return;
        }
        if (k == Kind.GUARD) {
            return;
        }
        boolean treasurer = "hyun_young".equals(npc.memberKey());
        npc.hold(switch (k) {
            case READ -> Items.BOOK;
            case GRIND, BREW -> Items.STICK;
            case TREAT -> Items.PAPER;
            default -> null;
        });
        npc.holdOff(switch (k) {
            case COUNT -> Items.WRITABLE_BOOK;
            case REPORT -> treasurer ? Items.WRITABLE_BOOK : null;
            case GRIND -> Items.BOWL;
            default -> null;
        });
    }

    /** Груз в руках: носильщик — сноп или бочонок, водонос — ведро воды. */
    private net.minecraft.world.item.Item load() {
        return switch (npc.role()) {
            case WATER_CARRIER -> Items.WATER_BUCKET;
            case PORTER -> "porter_oh".equals(npc.memberKey()) ? Items.BARREL : Items.HAY_BLOCK;
            default -> Items.BUNDLE;
        };
    }

    /** Обратный путь: водонос несёт пустое ведро, носильщик идёт налегке. */
    private net.minecraft.world.item.Item empty() {
        return npc.role() == io.github.verycooltimo.murim.sect.SectRole.WATER_CARRIER ? Items.BUCKET : null;
    }

    /**
     * Ношение: к месту, где берут груз ({@code to} задания), — пауза, груз в руках — к месту задания, пауза,
     * снова за грузом.
     */
    private void carry() {
        if (pause > 0) {
            pause--;
            return;
        }
        if (pickup == null) {
            SectLayout layout = npc.layout();
            SectSchedule.Task t = current.task();
            Vec3 guess = t.route() ? layout.at(t.toZone(), t.toU(), t.toV()) : null;
            if (guess == null) {
                // Пути нет (тестовая раскладка, нет площадки) — просто стоит у места.
                arrive(current.spot(), 0.6D);
                return;
            }
            pickup = npc.level().isLoaded(BlockPos.containing(guess)) ? SectLife.stand(npc.level(), guess) : guess;
        }
        boolean loaded = npc.carrying();
        Vec3 target = loaded ? current.spot() : pickup;
        // Между площадками пути может не быть, пока автор не поставил лестницы: застрявший носильщик не стоит
        // столбом перед игроком, а через 20 с поворачивает обратно (как будто отдал груз на полпути).
        boolean giveUp = ++legTicks > 400;
        boolean there = arrive(target, loaded ? 0.55D : 0.7D);
        if (there || giveUp) {
            legTicks = 0;
            boolean handOver = there && loaded && npc.role() == SectRole.PORTER;
            loaded = !loaded;
            npc.setCarrying(loaded);
            npc.hold(loaded ? load() : empty());
            npc.workPose(loaded ? "carry" : null);
            pause = 40 + npc.getRandom().nextInt(40);
            // Носильщик донёс груз до кладовой: Хён Ён (или управляющий) принимает — кивает, носильщик кланяется.
            if (handOver) {
                SectLife.delivered(npc);
            }
        }
    }

    // ------------------------------------------------------------------ члены секты за делом (автор 05.10)

    /** Человек секты по ключу рядом. */
    private SectDisciple find(String key, double range) {
        if (key == null || key.isEmpty()) {
            return null;
        }
        for (SectDisciple d : npc.level().getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().inflate(range),
                d -> d != npc && d.isAlive() && key.equals(d.memberKey()))) {
            return d;
        }
        return null;
    }

    private void lookAtNearPlayer(double range) {
        Player p = npc.level().getNearestPlayer(npc, range);
        if (p != null) {
            npc.getLookControl().setLookAt(p, 20.0F, 20.0F);
        }
    }

    /**
     * Совет старейшин: сидят на своих местах; говорят по очереди (глава, Хён Ён, Хён Сан, Ун Гак, Ун Ам — по 6 с),
     * говорящий объясняет и показывает, остальные смотрят на него, кивают; Хён Ён чаще качает головой (скупой, гл. 64).
     */
    private void council() {
        if (!arrive(current.spot(), 0.6D)) {
            return;
        }
        npc.faceYaw(current.yaw(), 30.0F);
        npc.sit(true);
        long now = npc.level().getGameTime();
        String speaker = SectSchedule.COUNCIL.get((int) Math.floorMod(now / 120L, (long) SectSchedule.COUNCIL.size()));
        boolean me = speaker.equals(npc.memberKey());
        SectDisciple s = me ? null : find(speaker, 12.0D);
        if (s != null) {
            npc.getLookControl().setLookAt(s, 30.0F, 30.0F);
        } else if (me) {
            lookAtNearPlayer(8.0D);
        }
        if (--talkClock <= 0) {
            if (me) {
                talkClock = 26 + npc.getRandom().nextInt(16);
                String[] g = {"explain", "point", "explain", "fist"};
                npc.gesture(g[npc.getRandom().nextInt(g.length)]);
            } else {
                talkClock = 40 + npc.getRandom().nextInt(50);
                if (npc.getRandom().nextInt(3) == 0) {
                    boolean stingy = "hyun_young".equals(npc.memberKey());
                    npc.gesture(npc.getRandom().nextInt(stingy ? 2 : 5) == 0 ? "shake" : "nod");
                }
            }
        }
    }

    /**
     * Доклад главе: дошёл, встал перед ним, поклон «кулак в ладонь» (глава кивает), потом говорит — жесты; глава
     * поворачивается к докладчику и отвечает.
     */
    private void report() {
        if (!arrive(current.spot(), 0.7D)) {
            // Отошёл и вернулся — доклад снова начнётся с поклона.
            reported = false;
            return;
        }
        npc.faceYaw(current.yaw(), 20.0F);
        SectDisciple leader = find(current.task().partner(), 6.0D);
        if (leader != null) {
            npc.getLookControl().setLookAt(leader, 30.0F, 30.0F);
        }
        if (!reported) {
            reported = true;
            npc.holdPose(SectPose.BOW, 44);
            if (leader != null) {
                leader.attend(npc, 90);
                leader.gesture("nod");
            }
            return;
        }
        if (--talkClock <= 0) {
            talkClock = 50 + npc.getRandom().nextInt(40);
            npc.gesture(npc.getRandom().nextBoolean() ? "explain" : "point");
            if (leader != null && npc.getRandom().nextInt(2) == 0) {
                leader.attend(npc, 60);
                leader.gesture(npc.getRandom().nextInt(3) == 0 ? "explain" : "nod");
            }
        }
    }

    /** Лекарь у печи: печь автора на площадке алхимии (если стоит) — встать перед ней и мешать; иначе — на своём месте. */
    private void brew() {
        if (stove == null && --stoveSearch <= 0) {
            stoveSearch = 200;
            stove = SectLife.findStove(npc, current.task().zone());
            if (stove != null) {
                Vec3 c = Vec3.atBottomCenterOf(stove);
                Vec3 d = npc.position().subtract(c).multiply(1.0D, 0.0D, 1.0D);
                d = d.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, 1.0D) : d.normalize();
                stoveSpot = SectLife.stand(npc.level(), c.add(d.scale(1.4D)));
                stoveYaw = SectLayout.yawOf(c.x - stoveSpot.x, c.z - stoveSpot.z);
            }
        }
        Vec3 spot = stoveSpot != null ? stoveSpot : current.spot();
        float yaw = stoveSpot != null ? stoveYaw : current.yaw();
        if (arrive(spot, 0.5D)) {
            npc.faceYaw(yaw, 20.0F);
            if ((npc.tickCount + npc.getId()) % 90 == 0) {
                BlockPos at = stove != null ? stove : npc.blockPosition();
                npc.level().playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.35F, 0.8F + npc.getRandom().nextFloat() * 0.3F);
            }
        }
    }

    /** Лекарь лечит раненого: на колено рядом, раз в секунду — здоровье назад; вылечил — тот кланяется, лекарь кивает. */
    private void treat() {
        SectDisciple patient = find(current.task().partner(), SectLife.PATIENT_RANGE);
        if (patient == null || !patient.wounded()) {
            return;
        }
        if (!arrive(current.spot(), 0.5D)) {
            // Не дойти (нет лестницы между площадками): через 30 с раненый «дошёл до павильона сам» — лекарь не залипает.
            if (++treatWalk > 600) {
                treatWalk = 0;
                patient.setWounded(false);
            }
            return;
        }
        treatWalk = 0;
        npc.faceEntity(patient, 30.0F);
        npc.getLookControl().setLookAt(patient, 30.0F, 30.0F);
        patient.attend(npc, 30);
        if ((npc.tickCount + npc.getId()) % 20 == 0) {
            patient.treatBy(npc, 2.0F);
            if ((npc.tickCount / 20) % 3 == 0) {
                npc.level().playSound(null, patient.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.25F, 1.5F);
            }
            if (!patient.wounded()) {
                patient.sit(false);
                patient.attend(npc, 44);
                patient.holdPose(SectPose.BOW, 44);
                npc.gesture("nod");
                io.github.verycooltimo.murim.MurimMod.LOGGER.info("Секта: Ун Гак вылечил {}", patient.memberKey());
            }
        }
    }

    /** Старшинство: глава 0, старейшины Хён 1, первое поколение (Ун, наставник) 2, Пэк 3, Чхон 4, миряне 5. */
    static int seniority(SectRoster m) {
        if (m.role() == SectRole.LEADER) {
            return 0;
        }
        if (m.lay()) {
            return 5;
        }
        return switch (m.generation()) {
            case 0 -> 1;
            case 1 -> 2;
            case 2 -> 3;
            default -> 4;
        };
    }

    /**
     * Поклон старшему на ходу (канон: порядок старшинства держится строго, гл. 104): младший, проходя мимо главы,
     * старейшины или первого поколения, останавливается и кланяется «кулак в ладонь»; старший кивает. Не чаще раза в
     * минуту одному и тому же.
     */
    private boolean greetSenior(Kind kind) {
        if ((npc.tickCount + npc.getId()) % 10 != 0 || kind.seated() || kind == Kind.FORM_ROW || kind == Kind.SPAR
                || kind == Kind.POLES || kind == Kind.DRILL || kind == Kind.INSPECT || kind == Kind.TREAT || npc.sitting()) {
            return false;
        }
        Optional<SectRoster> me = npc.member();
        if (me.isEmpty() || seniority(me.get()) <= 1 && me.get().role() != SectRole.ELDER) {
            return false;
        }
        int mine = seniority(me.get());
        for (SectDisciple s : npc.level().getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().inflate(3.5D),
                d -> d != npc && d.isAlive() && !d.isSleeping() && !d.dormant())) {
            Optional<SectRoster> sm = s.member();
            if (sm.isEmpty()) {
                continue;
            }
            int theirs = seniority(sm.get());
            if (theirs > 2 || theirs >= mine || !npc.mayBowTo(s)) {
                continue;
            }
            npc.bowedTo(s);
            npc.attend(s, 44);
            npc.faceEntity(s, 90.0F);
            npc.holdPose(SectPose.BOW, 44);
            s.gesture("nod");
            return true;
        }
        return false;
    }

    /**
     * Смена поста (автор 05.10: «охрана меняется»): сменщик приходит, встаёт рядом со сменяемым, оба кланяются,
     * сменяемый уходит (его отпускает {@link SectDisciple#relieve}), сменщик встаёт на пост.
     *
     * @return идёт передача (дело поста ждёт)
     */
    private boolean handover() {
        Optional<SectRoster> me = npc.member();
        if (me.isEmpty()) {
            return false;
        }
        long time = npc.level().getDayTime();
        SectSchedule.Period p = SectSchedule.at(time);
        long key = SectSchedule.day(time) * 8L + p.ordinal();
        Optional<io.github.verycooltimo.murim.sect.SectRota.Duty> duty = io.github.verycooltimo.murim.sect.SectRota.duty(me.get(), time);
        if (duty.isEmpty() || SectSchedule.sincePeriodStart(time) >= SectSchedule.HANDOVER || npc.handedOver() == key) {
            return false;
        }
        // Кого сменяю (дежурство второго поколения, SectRota): дневной — вчерашнего ночного, ночной — дневного.
        Optional<SectRoster> outKey = Optional.of(io.github.verycooltimo.murim.sect.SectRota.relieved(duty.get(), time));
        SectDisciple out = outKey.isEmpty() ? null : find(outKey.get().key(), 24.0D);
        if (out == null || !SectLife.handoverPending(out, outKey.get()) || out.position().distanceToSqr(current.spot()) > 4.0D * 4.0D) {
            return false;
        }
        Vec3 f = Vec3.directionFromRotation(0.0F, current.yaw());
        Vec3 side = SectLife.stand(npc.level(), current.spot().add(new Vec3(-f.z, 0.0D, f.x).scale(1.6D)));
        if (!arrive(side, 0.6D)) {
            return true;
        }
        npc.setHandedOver(key);
        npc.attend(out, 44);
        out.attend(npc, 44);
        npc.faceEntity(out, 90.0F);
        out.faceEntity(npc, 90.0F);
        npc.holdPose(SectPose.BOW, 44);
        out.holdPose(SectPose.BOW, 44);
        out.relieve(key);
        io.github.verycooltimo.murim.MurimMod.LOGGER.info("Секта: смена поста — {} сменил {}", npc.memberKey(), out.memberKey());
        return true;
    }

    /** Работа на месте: обход площадки, на остановках — поза дела. */
    private void work(double speed, double radius, String pose, int pauseMin, int pauseRand) {
        if (pause > 0) {
            pause--;
            if (pause == 1) {
                npc.workPose(null);
            }
            return;
        }
        if (roam == null) {
            roam = pick(radius, radius * 0.7D);
        }
        if (roam == null) {
            return;
        }
        if (arrive(roam, speed)) {
            pause = pauseMin + npc.getRandom().nextInt(pauseRand);
            roam = null;
            npc.faceYaw(current.yaw(), 30.0F);
            npc.workPose(pose);
            if (npc.getRandom().nextInt(5) == 0) {
                npc.gesture("nod");
            }
        }
    }

    // ------------------------------------------------------------------ ходьба

    /**
     * Идти к точке; true — уже на месте. Скорость — поспешная, если далеко (опаздывает к колоколу).
     */
    private boolean arrive(Vec3 spot, double speed) {
        double d = horizontal(spot);
        if (d <= ARRIVE * ARRIVE && Math.abs(npc.getY() - spot.y) < 2.5D) {
            npc.getNavigation().stop();
            onSpot = true;
            // Ровно на место: строй и столы читаются сеткой, а не кучкой (codex по кадрам 04.10).
            if (d > 0.0025D && npc.onGround()) {
                npc.setPos(spot.x, npc.getY(), spot.z);
            }
            stuck = 0;
            best = Double.MAX_VALUE;
            return true;
        }
        if (npc.isSleeping()) {
            npc.stopSleeping();
        }
        npc.sit(false);
        onSpot = false;
        if (d < best - 0.25D) {
            best = d;
            stuck = 0;
        } else if (++stuck > STUCK_TICKS) {
            stuck = 0;
            best = Double.MAX_VALUE;
            if (npc.level().getNearestPlayer(npc, 24.0D) == null && npc.level().isLoaded(BlockPos.containing(spot))) {
                npc.moveTo(spot.x, spot.y, spot.z, current.yaw(), 0.0F);
                npc.getNavigation().stop();
                onSpot = true;
                return true;
            }
        }
        if (d < 3.0D * 3.0D && npc.getNavigation().isDone()) {
            // Последние шаги — прямо к точке: путь кончается на блоке, а место в ряду — в его центре.
            npc.getMoveControl().setWantedPosition(spot.x, spot.y, spot.z, speed);
            return false;
        }
        if (--repath <= 0 || npc.getNavigation().isDone()) {
            repath = 40;
            double run = d > 30.0D * 30.0D ? 1.0D : speed;
            npc.getNavigation().moveTo(spot.x, spot.y, spot.z, run);
        }
        return false;
    }

    private double horizontal(Vec3 spot) {
        double dx = npc.getX() - spot.x;
        double dz = npc.getZ() - spot.z;
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ строй

    private void formRow() {
        if (!arrive(current.spot(), 0.9D)) {
            return;
        }
        npc.faceYaw(current.yaw(), 30.0F);
        long now = npc.level().getGameTime();
        if (SectSchedule.beatStarts(now)) {
            npc.drillForm(SectSchedule.beatForm(now), SectSchedule.BEAT_STRIKE);
        }
        // Один взмах на весь строй — у первого в первом ряду (слышно, а не двадцать свистов).
        if (Math.floorMod(now, (long) SectSchedule.BEAT) == SectSchedule.BEAT_STRIKE
                && npc.member().map(m -> m.index() == firstInRow()).orElse(false)) {
            npc.drillSound();
        }
    }

    private int firstInRow() {
        return io.github.verycooltimo.murim.sect.SectRoster.generation(2).get(0).index();
    }

    /** Наставник ходит между рядами из конца в конец, останавливается и поправляет (жест). */
    private void inspect() {
        SectLayout layout = npc.layout();
        if (pause > 0) {
            pause--;
            if (pause == 30 && npc.getRandom().nextInt(2) == 0) {
                npc.gesture(npc.getRandom().nextBoolean() ? "point" : "explain");
            }
            Player p = npc.level().getNearestPlayer(npc, 6.0D);
            if (p != null) {
                npc.getLookControl().setLookAt(p, 20.0F, 20.0F);
            }
            return;
        }
        int rows = SectSchedule.rows();
        int lines = rows + 1;
        int line = (waypoint / 2) % lines;
        int end = waypoint % 2;
        boolean east = (line % 2 == 0) == (end == 1);
        double du = (east ? 1 : -1) * ((SectSchedule.COLUMNS - 1) / 2.0D * SectSchedule.SPACING + 2.5D);
        double dv = SectSchedule.FRONT_ROW + 1.5D - line * SectSchedule.SPACING;
        Vec3 guess = layout.at(current.task().zone(), du, dv);
        if (guess == null) {
            return;
        }
        Vec3 spot = SectLife.stand(npc.level(), guess);
        if (arriveSlow(spot)) {
            waypoint++;
            pause = 40 + npc.getRandom().nextInt(40);
        }
    }

    private boolean arriveSlow(Vec3 spot) {
        return arrive(spot, 0.5D);
    }

    // ------------------------------------------------------------------ занятия

    /** Столбы: прыжок к соседней точке, пауза, иногда форма. */
    private void poles() {
        if (pause > 0) {
            pause--;
            return;
        }
        if (roam == null) {
            roam = pick(3.5D, 2.5D);
        }
        if (roam == null) {
            return;
        }
        if (arrive(roam, 0.7D)) {
            npc.faceYaw(current.yaw(), 40.0F);
            if (npc.getRandom().nextInt(3) == 0) {
                npc.drillForm(npc.getRandom().nextInt(3), 6);
            } else if (npc.onGround()) {
                npc.getJumpControl().jump();
            }
            pause = 20 + npc.getRandom().nextInt(30);
            roam = null;
        } else if (npc.onGround() && npc.getRandom().nextInt(12) == 0) {
            npc.getJumpControl().jump();
        }
    }

    /** Формы в одиночку за строем: своя связка в своём ритме. */
    private void drill() {
        if (!arrive(current.spot(), 0.8D)) {
            return;
        }
        npc.faceYaw(current.yaw(), 30.0F);
        if (--drillClock <= 0) {
            drillClock = 26 + npc.getRandom().nextInt(10);
            npc.drillForm(drillStep++ % 3, 7);
        }
    }

    /** Обход площадки: точка — постоял — следующая. */
    private void roamAround(double speed, double radius) {
        if (pause > 0) {
            pause--;
            return;
        }
        if (roam == null) {
            roam = pick(radius, radius * 0.7D);
        }
        if (roam == null) {
            return;
        }
        if (arrive(roam, speed)) {
            pause = 80 + npc.getRandom().nextInt(120);
            roam = null;
            if (npc.getRandom().nextInt(4) == 0) {
                npc.gesture("explain");
            }
        }
    }

    /** Случайная точка рядом с местом задания (в пределах площадки). */
    private Vec3 pick(double ru, double rv) {
        SectLayout layout = npc.layout();
        double[] h = layout.half(current.task().zone());
        double maxU = h == null ? ru : Math.min(ru, h[0] - 1.0D);
        double maxV = h == null ? rv : Math.min(rv, h[1] - 1.0D);
        double du = current.task().du() + (npc.getRandom().nextDouble() * 2.0D - 1.0D) * maxU;
        double dv = current.task().dv() + (npc.getRandom().nextDouble() * 2.0D - 1.0D) * maxV;
        Vec3 guess = layout.at(current.task().zone(), du, dv);
        return guess == null ? null : SectLife.stand(npc.level(), guess);
    }

    /** Ночь: кровать в общежитии, если автор их поставил; иначе — сидя во дворе лагеря. */
    private void sleep() {
        if (npc.isSleeping()) {
            return;
        }
        if (bed == null && --bedSearch <= 0) {
            bedSearch = 200;
            bed = SectLife.findBed(npc, current.task().zone());
        }
        if (bed != null) {
            if (!npc.level().getBlockState(bed).isBed(npc.level(), bed, npc)) {
                bed = null;
                return;
            }
            Vec3 at = Vec3.atBottomCenterOf(bed);
            if (horizontal(at) < 2.2D * 2.2D && Math.abs(npc.getY() - bed.getY()) < 2.0D) {
                npc.getNavigation().stop();
                npc.sit(false);
                npc.startSleeping(bed);
                return;
            }
            arrive(at, 0.7D);
            return;
        }
        // Кроватей нет: сидит на своём месте в общежитии (площадка в лагере).
        if (arrive(current.spot(), 0.6D)) {
            npc.faceYaw(current.yaw(), 30.0F);
            npc.sit(true);
        }
    }
}
