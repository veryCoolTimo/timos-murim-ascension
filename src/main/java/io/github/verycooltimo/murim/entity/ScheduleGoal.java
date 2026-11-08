package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectBubbles;
import io.github.verycooltimo.murim.sect.SectCouncil;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectReactions;
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
 * <p>Ходьба и ограниченные по частоте обходы препятствий — SectWalking; опоздавшие идут дальше.
 * В бою, поединке и разговоре цель не работает: у них приоритет выше.
 */
public final class ScheduleGoal extends Goal {

    private final SectDisciple npc;
    private SectLife.Resolved current;
    private final io.github.verycooltimo.murim.sect.SectDining dining;
    private int refresh;
    private int pause;
    private int waypoint;
    private Vec3 roam;
    private BlockPos bed;
    private int bedSearch;
    private int drillStep;
    private int drillClock;
    private long visualBeat=Long.MIN_VALUE, visualDay=Long.MIN_VALUE;
    private int visualOffset;
    private boolean visualWobble;
    /** Последний вызов arrive — на месте дела (поза дела), иначе в пути (шаг). */
    private boolean onSpot;
    /** Day simulation probe ({@link io.github.verycooltimo.murim.sect.SectTimings}): when and where the current task was handed over. */
    private long assignedAt;
    private Vec3 assignedFrom = Vec3.ZERO;
    private boolean arrivalNoted = true;
    private long assignedWait;
    private int assignedRepaths;
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
    // Cook and servants (author 18.10): the pose the routine below wants right now; null = the default pose of the task kind.
    private SectPose activity;
    /** Cook at the stove: 0 stir, 1 taste (one shot), 2 chop, 3 stir again; ticks left in the stage. */
    private int cookStage;
    private int cookClock;
    /** Servant with the tray: 0 loads at the counter, 1 walks to a table, 2 wipes it, 3 walks back; clock in ticks. */
    private int trayStage;
    private int trayRuns;
    private int serveClock;
    private Vec3 tableSpot;
    private float tableYaw;
    // The council of elders (docs/design/38): whose turn it was last, the head opened the meeting, the tea round of the servant.
    private int councilTurn = -1;
    private boolean councilOpened;
    private int teaDesk;
    private int teaRound = -1;
    private int teaClock;

    public ScheduleGoal(SectDisciple npc) {
        this.npc = npc;
        this.dining = new io.github.verycooltimo.murim.sect.SectDining(npc);
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return !npc.headIntroReserved() && npc.member().isPresent() && !npc.dormant() && npc.free() && !npc.blocking() && !npc.escorting() && npc.guardTarget() == null && npc.layout() != null;
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
        io.github.verycooltimo.murim.sect.SectLessons.of(npc).cancelActor(npc);
        npc.walking().stop();
        dining.cancel();
        current = null;
        npc.setDoingKind(null);
        npc.setPose(SectPose.NONE);
        // A cutscene takes over the reception chair when it takes the conversation lock.
        // Otherwise the routine is over and its seat must be released.
        if (!npc.headIntroScene() && !npc.headIntroReserved()) npc.sit(SeatStyle.NONE);
        mounted = false;
    }

    @Override
    public void tick() {
        if (dining.leaving()) {
            if (dining.tick()) { current = null; refresh = 0; }
            return;
        }
        if (current == null || --refresh <= 0) {
            update();
        }
        if (current == null || dining.leaving()) {
            return;
        }
        // Stuck on the way to the spot: another spot of the same kind next time (docs/design/38 section 5.6).
        if (npc.walking().takeSpotBan() && current.spotId() != null) {
            npc.banSpot(current.spotId(), 1200);
            refresh = 1;
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
        if (io.github.verycooltimo.murim.sect.SectSocial.yielding(npc)) return;
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
        // Игрок применил технику рядом: остановился и смотрит (сидящий — только головой).
        LivingEntity seen = npc.watching();
        if (seen != null && kind != Kind.SLEEP) {
            npc.getNavigation().stop();
            if (!kind.seated() && !npc.sitting()) {
                npc.faceEntity(seen, 25.0F);
                npc.setPose(SectPose.NONE);
            }
            npc.getLookControl().setLookAt(seen, 30.0F, 30.0F);
            return;
        }
        if (io.github.verycooltimo.murim.sect.SectLessons.of(npc).act(npc, kind)) return;
        if (!onSpot && greetSenior(kind)) {
            return;
        }
        if (greetPlayer(kind)) {
            return;
        }
        // The post-breakfast break is for walking, then resting at the lesson place; no exercises before it ends.
        if ((SectSchedule.at(npc.level().getDayTime()) == SectSchedule.Period.BREAKFAST
                || SectSchedule.lessonBreak(npc.level().getDayTime())) && SectSchedule.lessonKind(kind)) {
            arrive(current.spot(), 0.6D);
            npc.setPose(SectPose.NONE);
            return;
        }
        tickPose(kind);
        switch (kind) {
            case COUNCIL -> council();
            case TEA -> tea();
            case ARGUE -> argue();
            case REPORT -> report();
            case RECEIVE -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 15.0F);
                    sitIfSeat();
                    lookAtNearPlayer(6.0D);
                }
            }
            case COUNT, READ -> {
                // ADR 0023: reach the office around the desk at the ordinary meal walking pace;
                // the counting animation starts only after he has reached his own chair.
                if (arrive(current.spot(), kind == Kind.COUNT ? 0.8D : 0.6D)) {
                    npc.faceYaw(current.yaw(), 20.0F);
                    sitIfSeat();
                    if (kind == Kind.COUNT && npc.getRandom().nextInt(400) == 0) {
                        npc.gesture("nod");
                    }
                }
            }
            case LECTURE -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 20.0F);
                    io.github.verycooltimo.murim.sect.SectLessons.of(npc).lecture(npc);
                    if (--talkClock <= 0) {
                        talkClock = 45 + npc.getRandom().nextInt(40);
                        npc.gesture(npc.getRandom().nextInt(3) == 0 ? "point" : "explain");
                    }
                }
            }
            case GRIND -> {
                if (arrive(current.spot(), 0.6D)) {
                    npc.faceYaw(current.yaw(), 30.0F);
                    sitHere();
                }
            }
            case BREW -> brew();
            case TREAT -> treat();
            case WAIT_TREAT -> {
                if (arrive(current.spot(), 0.5D)) {
                    sitHere();
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
            case CHORE, WORK -> {
                if (kind == Kind.WORK && SectSchedule.officeHelper(npc.memberKey())
                        && "treasury".equals(current.task().zone())) {
                    officeHelper();
                } else if (kind == Kind.WORK && seatedSpot()) {
                    // At the desk of his house or counter: sits there while the period lasts.
                    if (arrive(current.spot(), 0.6D)) {
                        npc.faceYaw(current.yaw(), 20.0F);
                        sitHere();
                    }
                } else {
                    roamAround(kind == Kind.CHORE ? 0.7D : 0.5D, kind == Kind.CHORE ? 6.0D : 3.0D);
                }
            }
            case CARRY -> carry();
            case SWEEP -> work(0.45D, 7.0D, "sweep", 50, 70);
            case COOK -> cook();
            case SERVE -> serve();
            case TEND -> work(0.4D, 6.0D, "tend", 80, 100);
            case EAT, MEDITATE, REST -> {
                if (arrive(current.spot(), 0.8D)) {
                    npc.faceYaw(current.yaw(), 30.0F);
                    sitHere();
                }
            }
            case SLEEP -> sleep();
            default -> {
                // WATCH, GUARD, HEAL_POST: стоять на месте лицом куда надо, глазами — на ближнего игрока.
                if (kind == Kind.GUARD && handover()) {
                    return;
                }
                if (arrive(current.spot(), kind == Kind.GUARD ? 0.5D : 0.8D)) {
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
                    // The door of the council hall: whoever is not an elder is asked to wait on the stair (docs/design/38). A request, not
                    // a wall: members of the sect are never driven off by force.
                    if (kind == Kind.GUARD && SectSchedule.COUNCIL_DOOR.equals(current.task().partner()) && p instanceof net.minecraft.server.level.ServerPlayer sp
                            && npc.distanceTo(sp) < 4.5D && !sp.isSpectator()
                            && !io.github.verycooltimo.murim.sect.SectService.state(sp).has(io.github.verycooltimo.murim.sect.SectPositions.ELDER)
                            && npc.level().getGameTime() - npc.bubbleAt() > 160L) {
                        npc.faceEntity(sp, 40.0F);
                        npc.gesture("shake");
                        String key = SectBubbles.Group.COUNCIL_DOOR.pick(sp, npc.getRandom());
                        io.github.verycooltimo.murim.sect.SectChatter.send(npc, key, net.minecraft.network.chat.Component.translatable(key));
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
        SectPose want = activity;
        if (want == null && kind == Kind.CARRY && npc.carrying()) {
            // A loaded porter walks with the sack or the jar, the water carrier with the yoke (props of the pose).
            want = SectPose.loadPose(npc.role(), npc.memberKey());
        }
        npc.setPose(want != null ? want : SectPose.forTask(kind, onSpot, SectPose.carrier(npc)));
    }

    private Vec3 nightWatchPost;
    private long nightWatchDay = Long.MIN_VALUE;

    /** Новое задание (раз в секунду): смена части суток и места дела. */
    private void update() {
        // Раз в секунду, но у каждого в свой тик: смена дела и поиск пути не в один тик у всей горы.
        refresh = current == null ? 1 + Math.floorMod(npc.getId() * 13, 20) : 20;
        SectLife.Resolved next = SectLife.resolve(npc);
        if (next == null) {
            if (current != null && current.task().kind() == Kind.EAT && dining.begin(current)) return;
            current = null;
            // No place for this period: nobody stays in a bed or on a seat he has no business at.
            npc.standUp();
            mounted = false;
            return;
        }
        if (npc.memberKey().equals(io.github.verycooltimo.murim.sect.SectNightWatch.member(npc.level().getDayTime()))
                && SectSchedule.at(npc.level().getDayTime()) == SectSchedule.Period.NIGHT
                && next.task().kind() == Kind.WATCH) {
            long night = SectSchedule.day(npc.level().getDayTime());
            if (nightWatchPost == null || nightWatchDay != night) {
                nightWatchPost = io.github.verycooltimo.murim.sect.SectNightWatch.post(npc, next.spot());
                if (nightWatchDay != night) {
                    io.github.verycooltimo.murim.MurimMod.LOGGER.info("Секта: night watch {} at {}",
                            npc.memberKey(), BlockPos.containing(nightWatchPost));
                    nightWatchDay = night;
                }
            }
            next = new SectLife.Resolved(next.task(), nightWatchPost, next.yaw());
        } else nightWatchPost = null;
        boolean changed = current == null || current.task().kind() != next.task().kind()
                || !current.task().zone().equals(next.task().zone()) || current.spot().distanceToSqr(next.spot()) > 1.0D;
        if (changed && current != null && current.task().kind() == Kind.EAT && next.task().kind() != Kind.EAT
                && dining.begin(current)) return;
        // Rising from the meal for what comes next (SectLead): a nod to the table on the way out.
        if (changed && current != null && current.task().kind() == Kind.EAT && npc.sitting() && next.task().kind() != Kind.SLEEP) {
            npc.gesture("nod");
        }
        // Counting and eating at the same desk share a seat. A task change alone must not send him back to its approach.
        boolean keepSeat = mounted && npc.sitting() && current != null
                && java.util.Objects.equals(current.spotId(), next.spotId())
                && current.spot().distanceToSqr(next.spot()) < 0.25D;
        current = next;
        npc.setDoingKind(next.task().kind());
        npc.setDoingSpot(next.spotId());
        if (changed) {
            if (io.github.verycooltimo.murim.sect.SectTimings.ON) {
                assignedAt = npc.level().getDayTime();
                assignedFrom = npc.position();
                arrivalNoted = false;
                assignedWait = npc.walking().probeWait();
                assignedRepaths = npc.walking().probeRepaths();
            }
            pause = 0;
            waypoint = 0;
            roam = null;
            bed = null;
            bedSearch = 0;
            onSpot = false;
            Kind k = next.task().kind();
            mounted = keepSeat;
            if (!keepSeat && !k.seated()) {
                npc.sit(SeatStyle.NONE);
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
            playerTreat = false;
            talkClock = 0;
            activity = null;
            cookStage = 0;
            cookClock = 0;
            trayStage = 0;
            serveClock = 0;
            tableSpot = null;
            councilTurn = -1;
            councilOpened = false;
            teaDesk = 0;
            teaRound = -1;
            teaClock = 0;
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
     * Реквизит по делу (ванильные предметы в руках): слуги — метла, мотыга, миска, книга учёта; Тэ Гюн — книга учёта
     * в левой руке; Тэ Рок — книга; Гён Чхо — ступка (миска) и пестик (палка), черпак у печи, бинт (бумага) у раненого.
     */
    private void outfit(Kind k) {
        if (npc.role().lay()) {
            npc.hold(switch (k) {
                // Метла: ванильная кисть — ближайший предмет-«щётка» (своей метлы в моде нет).
                case SWEEP -> Items.BRUSH;
                case TEND -> Items.WOODEN_HOE;
                case WORK -> npc.role() == SectRole.STEWARD ? Items.WRITABLE_BOOK : null;
                case CARRY -> npc.carrying() ? load() : empty();
                default -> null;
            });
            return;
        }
        if (k == Kind.GUARD) {
            return;
        }
        boolean treasurer = "tae_gyun".equals(npc.memberKey());
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
            // The yoke with its buckets, the sack and the jar are props of the carry pose (SectPose#loadPose), not held items.
            case WATER_CARRIER, PORTER -> null;
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
        // Playtest 1.11: the two porters used to shuttle to the gate indefinitely.
        // A receipt survives goal interruptions/reloads; only a new period allows another collection.
        // Server-only receipt, no client sync; carrying/held-item visuals keep the existing entity synchronization.
        // API: neoforge-src IEntityExtension#getPersistentData; minecraft-src CompoundTag#contains/getLong/putLong.
        boolean gateJob = npc.role() == SectRole.PORTER && "sect_gate".equals(current.task().toZone());
        long period = SectSchedule.day(npc.level().getDayTime()) * 8L
                + SectSchedule.at(npc.level().getDayTime()).ordinal();
        var receipt = npc.getPersistentData();
        if (gateJob && receipt.contains("murim_gate_delivery") && receipt.getLong("murim_gate_delivery") == period) {
            npc.setCarrying(false);
            npc.hold(empty());
            npc.workPose(null);
            arrive(current.spot(), 0.55D);
            return;
        }
        if (pause > 0) {
            pause--;
            return;
        }
        if (pickup == null && current.pickup() != null) {
            // Маршрут носильщика из построенной секты: второй конец уже выбран по раскладке.
            pickup = current.pickup();
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
        if (gateJob && receipt.contains("murim_gate_pickup") && receipt.getLong("murim_gate_pickup") == period) {
            // A conversation or another goal may have cleared the carry pose after collection; resume the return leg.
            npc.setCarrying(true);
            npc.hold(load());
            npc.workPose("carry");
        }
        boolean loaded = npc.carrying();
        Vec3 target = loaded ? current.spot() : pickup;
        // Между площадками пути может не быть, пока автор не поставил лестницы: застрявший носильщик не стоит
        // столбом перед игроком, а через 20 с поворачивает обратно (как будто отдал груз на полпути).
        boolean giveUp = ++legTicks > 400;
        boolean there = arrive(target, loaded ? 0.55D : 0.7D);
        if (there || giveUp) {
            if (gateJob && there && !loaded) receipt.putLong("murim_gate_pickup", period);
            if (gateJob && (loaded || giveUp)) {
                // Failed paths end the errand too: return inside, never turn around every 400 ticks.
                receipt.putLong("murim_gate_delivery", period);
            }
            legTicks = 0;
            boolean handOver = there && loaded && (npc.role() == SectRole.PORTER || npc.role() == SectRole.WATER_CARRIER);
            loaded = !loaded;
            npc.setCarrying(loaded);
            npc.hold(loaded ? load() : empty());
            npc.workPose(loaded ? "carry" : null);
            pause = 40 + npc.getRandom().nextInt(40);
            // Носильщик донёс груз до кладовой: Тэ Гюн (или управляющий) принимает — кивает, носильщик кланяется.
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

    /** One speaker holds the floor this long, in ticks. */
    private static final int COUNCIL_TURN = 140;

    /**
     * Совет старейшин (docs/design/38, «Совет и приглашения»): идут в зал совета, садятся за свои места (глава — на помосте
     * во главе, старейшины — на подушках за низкими столами двумя рядами), говорят по очереди: говорящий объясняет и показывает
     * и бросает фразу о делах секты (пузырь), остальные смотрят на него, кивают; Тэ Гюн чаще качает головой (скупой, гл. 64).
     * Когда слуга обошёл столы, пьют чай. В конце — встают и кланяются главе, он закрывает совет.
     */
    private void council() {
        // The elders hurry: the hall is far and the council does not wait (a brisk step, not the stroll of the working day).
        if (!arrive(current.spot(), 0.9D)) {
            return;
        }
        npc.faceYaw(current.yaw(), 30.0F);
        long now = npc.level().getGameTime();
        long dayTime = npc.level().getDayTime();
        java.util.List<String> members = SectCouncil.config().members();
        boolean head = SectCouncil.seat(npc.memberKey()) == 0;
        if (SectCouncil.phase(dayTime) == SectCouncil.Phase.LEAVE) {
            // The meeting is ending: stand up, bow to the head, the head closes it.
            if (!reported) {
                reported = true;
                npc.sit(SeatStyle.NONE);
                activity = null;
                SectDisciple chair = head ? null : find(members.get(0), 14.0D);
                if (chair != null) {
                    npc.faceEntity(chair, 90.0F);
                    npc.attend(chair, 50);
                }
                npc.holdPose(SectPose.BOW, 44);
                if (head) {
                    councilSay(SectBubbles.Group.COUNCIL_CLOSE);
                }
            }
            return;
        }
        sitHere();
        if (io.github.verycooltimo.murim.sect.SectCouncilIntrusion.paused(now)) {
            // Somebody has walked in on the meeting: the table falls silent and turns to him.
            lookAtNearPlayer(12.0D);
            return;
        }
        if (head && !councilOpened && SectCouncil.sinceSat(dayTime) >= 40) {
            councilOpened = true;
            councilSay(SectBubbles.Group.COUNCIL_OPEN);
        }
        int turn = (int) Math.floorMod(now / COUNCIL_TURN, (long) members.size());
        String speaker = members.get(turn);
        boolean me = speaker.equals(npc.memberKey());
        SectDisciple s = me ? null : find(speaker, 12.0D);
        if (s != null) {
            npc.getLookControl().setLookAt(s, 30.0F, 30.0F);
        } else if (me) {
            lookAtNearPlayer(8.0D);
        }
        if (me && councilTurn != turn) {
            councilTurn = turn;
            if (npc.getRandom().nextInt(3) != 0) {
                councilSay(SectBubbles.Group.COUNCIL);
            }
        }
        // Tea: once the round is on, a sip now and then (the cup itself is set down by #tea).
        if (SectCouncil.teaServed(dayTime) && !me && !npc.poseHeld()
                && Math.floorMod(now / 20L + npc.getId() * 7L, 16L) < 3L) {
            activity = SectPose.TEA;
        } else {
            activity = null;
        }
        if (--talkClock <= 0) {
            if (me) {
                talkClock = 26 + npc.getRandom().nextInt(16);
                String[] g = {"explain", "point", "explain", "fist"};
                npc.gesture(g[npc.getRandom().nextInt(g.length)]);
            } else {
                talkClock = 40 + npc.getRandom().nextInt(50);
                if (npc.getRandom().nextInt(3) == 0) {
                    boolean stingy = "tae_gyun".equals(npc.memberKey());
                    npc.gesture(npc.getRandom().nextInt(stingy ? 2 : 5) == 0 ? "shake" : "nod");
                }
            }
        }
    }

    /** A line of the group over this person's head, picked for the nearest player so he does not hear the same line twice. */
    private void councilSay(SectBubbles.Group group) {
        Player near = npc.level().getNearestPlayer(npc, io.github.verycooltimo.murim.sect.SectChatter.HEAR_RANGE);
        if (near == null) {
            return;
        }
        String key = null;
        if (group == SectBubbles.Group.COUNCIL && npc.level() instanceof net.minecraft.server.level.ServerLevel level) {
            // elders-8: the middle of the sitting follows the one topic fixed at its start (news, review, weather, else the table talk).
            var seniors = io.github.verycooltimo.murim.sect.SectLessons.of(npc).seniors;
            key = seniors.councilKey(near instanceof net.minecraft.server.level.ServerPlayer tp ? tp : null, npc, npc.getRandom());
            if (key != null) seniors.councilAnnounced(level, level.getDayTime());
        }
        if (key == null) key = near instanceof net.minecraft.server.level.ServerPlayer sp ? group.pick(sp, npc.getRandom())
                : group.pick(npc.getRandom().nextInt(64));
        io.github.verycooltimo.murim.sect.SectChatter.send(npc, key, net.minecraft.network.chat.Component.translatable(key));
    }

    /** Ticks between two rounds of the tea. */
    private static final int TEA_ROUND = 900;

    /**
     * The servant at the council: waits at the south end of the aisle with the tray, and when the tea is due walks the round of
     * the low desks, sets the cups down at each (the sitter lifts his cup), now and then says a word; a second round after a
     * while. Without a built council hall he just stands where the plan puts him.
     */
    private void tea() {
        SectLayoutFile file = npc.layout() == null ? null : npc.layout().file();
        java.util.List<SectLayoutFile.Spot> desks = file == null ? java.util.List.of() : io.github.verycooltimo.murim.sect.SectPlaces.councilDesks(file);
        long dayTime = npc.level().getDayTime();
        SectCouncil.Phase phase = SectCouncil.phase(dayTime);
        int round = phase == SectCouncil.Phase.SESSION && SectCouncil.teaServed(dayTime)
                ? (SectCouncil.tick(dayTime) - SectCouncil.config().tea()) / TEA_ROUND : -1;
        if (round > teaRound && !desks.isEmpty()) {
            teaRound = round;
            teaDesk = 0;
            teaClock = 0;
        }
        boolean serving = teaRound >= 0 && teaDesk < desks.size() && round == teaRound;
        if (!serving) {
            // Waiting (or done): at the south end of the aisle, the tray in his hands (loaded before the round, empty after it).
            activity = teaRound >= 0 ? SectPose.TRAY_EMPTY : SectPose.TRAY;
            if (arrive(current.spot(), 0.8D)) {
                npc.faceYaw(current.yaw(), 30.0F);
            }
            return;
        }
        SectLayoutFile.Spot desk = desks.get(teaDesk);
        net.minecraft.core.BlockPos w = desk.walkTo();
        Vec3 at = new Vec3(w.getX() + 0.5D, w.getY(), w.getZ() + 0.5D);
        activity = SectPose.TRAY;
        // 25 s at most to reach a desk; a desk he cannot reach is skipped.
        if (arrive(at, 0.5D) || ++teaClock > 500) {
            npc.faceYaw(desk.yaw(), 90.0F);
            // The sitter of this desk: the member on the cushion beside it.
            SectDisciple sitter = null;
            for (SectDisciple d : npc.level().getEntitiesOfClass(SectDisciple.class, new net.minecraft.world.phys.AABB(at, at).inflate(2.6D),
                    d -> d != npc && d.isAlive() && d.doingKind() == Kind.COUNCIL && d.sitting())) {
                sitter = d;
                break;
            }
            npc.holdPose(SectPose.TRAY_SET, 50);
            if (sitter != null) {
                sitter.attend(npc, 60);
                sitter.holdPose(SectPose.TEA, 110);
                if (npc.getRandom().nextInt(2) == 0) {
                    sitter.gesture("nod");
                }
            }
            if (teaDesk % 2 == 0) {
                councilSay(SectBubbles.Group.TEA);
            }
            teaDesk++;
            teaClock = 0;
        }
    }

    /**
     * The quarrel in the forecourt: the two juniors stand face to face and talk with their hands, each in his turn says a
     * word (a bubble); they do not touch each other. {@code SectOffices} lets them calm down and bow when the elder has judged.
     */
    private void argue() {
        if (!arrive(current.spot(), 0.6D)) {
            return;
        }
        npc.faceYaw(current.yaw(), 30.0F);
        SectDisciple other = find(current.task().partner(), 6.0D);
        if (other != null) {
            npc.getLookControl().setLookAt(other, 30.0F, 30.0F);
        }
        if (npc.getPersistentData().getLong(io.github.verycooltimo.murim.sect.SectOffices.DISPUTE_DONE) == SectSchedule.day(npc.level().getDayTime())) {
            // The elder has judged: they stand calm, hands down, until the hour of the quarrel is over.
            return;
        }
        if (--talkClock <= 0) {
            talkClock = 50 + npc.getRandom().nextInt(50);
            npc.gesture(npc.getRandom().nextInt(3) == 0 ? "point" : "explain");
            if (npc.getRandom().nextInt(2) == 0) {
                councilSay(SectBubbles.Group.DISPUTE);
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
        if (stove == null && current.spotId() == null && --stoveSearch <= 0) {
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
        if (SectReactions.PLAYER_PATIENT.equals(current.task().partner())) {
            treatPlayer();
            return;
        }
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
                patient.sit(SeatStyle.NONE);
                patient.attend(npc, 44);
                patient.holdPose(SectPose.BOW, 44);
                npc.gesture("nod");
                io.github.verycooltimo.murim.MurimMod.LOGGER.info("Секта: Гён Чхо вылечил {}", patient.memberKey());
            }
        }
    }

    /**
     * Лекарь перевязывает раненого игрока (живая гора, автор 05.10): подходит, «Сиди смирно, перевяжу», раз в секунду — два
     * здоровья, пока не {@link SectReactions#HEALED}; в конце — «Готово» и кивок. Игрок отошёл — лекарь идёт за ним
     * (место пересчитывается раз в секунду).
     */
    /** Лекарь уже начал перевязку игрока (сказал «Сиди смирно»). */
    private boolean playerTreat;

    private void treatPlayer() {
        if (!(npc.level().getEntity(npc.healingPlayer()) instanceof Player p)) {
            return;
        }
        if (!arrive(current.spot(), 0.7D) && npc.distanceTo(p) > 2.2D) {
            return;
        }
        npc.getNavigation().stop();
        npc.faceEntity(p, 30.0F);
        npc.getLookControl().setLookAt(p, 30.0F, 30.0F);
        if (!playerTreat) {
            playerTreat = true;
            npc.gesture("explain");
            io.github.verycooltimo.murim.sect.SectChatter.sayIfQuiet(npc, p instanceof net.minecraft.server.level.ServerPlayer sp
                    ? io.github.verycooltimo.murim.sect.SectBubbles.Group.HEAL_START.pick(sp, npc.getRandom())
                    : io.github.verycooltimo.murim.sect.SectBubbles.Group.HEAL_START.pick(npc.getRandom().nextInt(64)), 60);
        }
        if ((npc.tickCount + npc.getId()) % 20 == 0) {
            p.heal(2.0F);
            npc.level().playSound(null, p.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.25F, 1.5F);
            if (p.getHealth() >= p.getMaxHealth() * SectReactions.HEALED) {
                npc.setHealingPlayer(-1);
                npc.gesture("nod");
                io.github.verycooltimo.murim.sect.SectChatter.sayIfQuiet(npc, p instanceof net.minecraft.server.level.ServerPlayer sp
                        ? io.github.verycooltimo.murim.sect.SectBubbles.Group.HEAL_DONE.pick(sp, npc.getRandom())
                        : io.github.verycooltimo.murim.sect.SectBubbles.Group.HEAL_DONE.pick(npc.getRandom().nextInt(64)), 20);
                io.github.verycooltimo.murim.MurimMod.LOGGER.info("Секта: Гён Чхо перевязал {}", p.getName().getString());
            }
        }
    }

    /** Старшинство: глава 0, старейшины Тэ 1, первое поколение (Гён, наставник) 2, Со 3, Юль 4, миряне 5. */
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
        // Не хором (автор 06.10): каждый замечает старшего в своё время — проверка через раз, в среднем ~1 с.
        if ((npc.tickCount + npc.getId()) % 10 != 0 || npc.getRandom().nextInt(3) != 0 || kind.seated() || kind == Kind.FORM_ROW || kind == Kind.SPAR
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
     * Поклон игроку на ходу (живая гора, автор 05.10): победителю смотра и старшему по положению
     * ({@link SectReactions#bowReason}) — раз в день; иногда со словом.
     */
    private boolean greetPlayer(Kind kind) {
        // Поклон игроку — тоже не хором: свой миг у каждого (в среднем через ~1 с после прихода игрока).
        if ((npc.tickCount + npc.getId()) % 10 != 5 || npc.getRandom().nextInt(3) != 0 || kind.seated() || kind == Kind.FORM_ROW || kind == Kind.SPAR
                || kind == Kind.POLES || kind == Kind.DRILL || kind == Kind.INSPECT || kind == Kind.TREAT || kind == Kind.GUARD
                || kind == Kind.COUNCIL || kind == Kind.LECTURE || kind == Kind.REPORT || npc.sitting()) {
            return false;
        }
        Player near = npc.level().getNearestPlayer(npc, SectReactions.BOW_RANGE);
        if (!(near instanceof net.minecraft.server.level.ServerPlayer p) || p.isSpectator()) {
            return false;
        }
        long day = SectSchedule.day(npc.level().getDayTime());
        if (npc.bowedToday(p, day)) {
            return false;
        }
        // An office holder (elder, first disciple) is greeted by his office: juniors bow and make room, the first
        // generation and the council nod as to a colleague (SectSocial#office). Servants take the bow below.
        io.github.verycooltimo.murim.sect.SectRank rank = io.github.verycooltimo.murim.sect.SectRank.of(p);
        if (rank.office() && !npc.role().lay()) {
            io.github.verycooltimo.murim.sect.SectSocial.react(p, npc);
            return npc.bowedToday(p, day);
        }
        if (io.github.verycooltimo.murim.sect.SectLessons.greet(npc, p)) return true;
        // Stronger players receive the bow AND room to pass, including previous review winners.
        if (!rank.office() && !npc.role().lay() && npc.rank() <= 2 && io.github.verycooltimo.murim.sect.SectSocial.power(p, npc)
                == io.github.verycooltimo.murim.sect.SectSocial.Power.STRONG) {
            io.github.verycooltimo.murim.sect.SectSocial.react(p, npc);
            if (npc.bowedToday(p, day)) return true;
        }
        SectReactions.Bow why = SectReactions.bowReason(npc, p);
        if (why == null) {
            return false;
        }
        npc.markBowed(p, day);
        npc.attend(p, 44);
        npc.faceEntity(p, 90.0F);
        npc.holdPose(SectPose.BOW, 44);
        if (rank.office()) {
            // A servant bows to the office: the same words as the juniors' (SectSocial#bubbleKeys).
            String key = io.github.verycooltimo.murim.sect.SectLines.pick(p, "murim.bubble.social."
                    + io.github.verycooltimo.murim.sect.SectSocial.officeGroup(rank, npc),
                    io.github.verycooltimo.murim.sect.SectSocial.VARIANTS, npc.getRandom());
            io.github.verycooltimo.murim.sect.SectChatter.send(npc, key, net.minecraft.network.chat.Component.translatable(key));
        } else if (npc.getRandom().nextInt(2) == 0) {
            io.github.verycooltimo.murim.sect.SectChatter.sayIfQuiet(npc, SectReactions.bowKey(why, p, npc.getRandom()), 100);
        }
        return true;
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
        SectRoster outKey = io.github.verycooltimo.murim.sect.SectRota.relieved(duty.get(), time);
        SectSchedule.Task oldPost = SectSchedule.post(new io.github.verycooltimo.murim.sect.SectRota.Duty(
                duty.get().post(), !duty.get().night()));
        Vec3 oldSpot = SectLife.placeOfTask(npc, oldPost);
        if (oldSpot == null) {
            oldSpot = npc.layout().at(oldPost.zone(), oldPost.du(), oldPost.dv());
            if (oldSpot != null && npc.level().isLoaded(BlockPos.containing(oldSpot))) oldSpot = SectLife.stand(npc.level(), oldSpot);
        }
        if (oldSpot == null || !npc.level().isLoaded(BlockPos.containing(oldSpot))) return false;
        // Post 0 moves from the main hall to the gate at night. Relieve the outgoing
        // guard at HIS post before walking to the new one; he cannot meet us at the gate.
        SectDisciple out = npc.level().getEntitiesOfClass(SectDisciple.class,
                new net.minecraft.world.phys.AABB(oldSpot, oldSpot).inflate(4.0D),
                d -> d.isAlive() && d.memberKey().equals(outKey.key())).stream().findFirst().orElse(null);
        if (out == null || !SectLife.handoverPending(out, outKey)) {
            return false;
        }
        Vec3 f = Vec3.directionFromRotation(0.0F, npc.layout().yaw(oldPost.faceU(), oldPost.faceV()));
        Vec3 side = SectLife.stand(npc.level(), oldSpot.add(new Vec3(-f.z, 0.0D, f.x).scale(1.6D)));
        // Close enough to the relieved guard is enough to bow: the exact side spot can be a fractional, crowded
        // or prop-blocked block, and a walker that circles it a block away would otherwise miss the whole window.
        boolean near = npc.distanceToSqr(out) < 2.8D * 2.8D && Math.abs(npc.getY() - out.getY()) < 1.5D;
        if (!arrive(side, 0.6D) && !near) {
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
        if (arriveRoam(roam, speed)) {
            pause = pauseMin + npc.getRandom().nextInt(pauseRand);
            roam = null;
            npc.faceYaw(current.yaw(), 30.0F);
            npc.workPose(pose);
            if (npc.getRandom().nextInt(5) == 0) {
                npc.gesture("nod");
            }
        }
    }

    // ------------------------------------------------------------------ cook and servants (author 18.10)

    /**
     * The cook at the stove of the dining hall: stirs the pot (loop), tastes a ladleful (one shot), chops greens (loop), stirs
     * again, round and round. The clips are blockout poses ({@link SectPose#COOK_STIR} and the others).
     */
    private void cook() {
        if (!arrive(current.spot(), 0.5D)) {
            activity = null;
            return;
        }
        npc.faceYaw(current.yaw(), 20.0F);
        if (activity == null) {
            activity = SectPose.COOK_STIR;
            cookClock = 60 + npc.getRandom().nextInt(60);
        }
        if (activity == SectPose.COOK_STIR && (npc.tickCount + npc.getId()) % 100 == 0) {
            npc.level().playSound(null, npc.blockPosition(), SoundEvents.BREWING_STAND_BREW, SoundSource.NEUTRAL, 0.2F, 0.7F + npc.getRandom().nextFloat() * 0.2F);
        }
        if (--cookClock > 0) {
            return;
        }
        cookStage = (cookStage + 1) % 4;
        switch (cookStage) {
            case 0 -> {
                activity = SectPose.COOK_STIR;
                cookClock = 200 + npc.getRandom().nextInt(120);
            }
            case 1 -> {
                // The one-shot clip is 3.4 s; the hold ends a little before it so the stir comes back without a flash of the rest pose.
                activity = SectPose.COOK_STIR;
                cookClock = 12;
                npc.holdPose(SectPose.COOK_TASTE, 66);
            }
            case 2 -> {
                activity = SectPose.COOK_CHOP;
                cookClock = 140 + npc.getRandom().nextInt(100);
            }
            default -> {
                activity = SectPose.COOK_STIR;
                cookClock = 100 + npc.getRandom().nextInt(60);
            }
        }
    }

    /**
     * Serving a meal: the cook ladles at the counter, the other servants carry trays of bowls to the tables of the dining hall,
     * set them down and wipe the table. Without a built dining hall (test layouts) the old round of the counter area.
     */
    private void serve() {
        SectLayoutFile file = npc.layout() == null ? null : npc.layout().file();
        if (file == null || current.spotId() == null) {
            work(0.5D, 5.0D, "serve", 30, 40);
            return;
        }
        if (npc.role() == SectRole.COOK) {
            ladleRound();
        } else {
            trayRound(file);
        }
    }

    /** The cook behind the counter: stirs the big pot and ladles a bowl every few seconds (one shot). */
    private void ladleRound() {
        if (!arrive(current.spot(), 0.5D)) {
            activity = null;
            return;
        }
        npc.faceYaw(current.yaw(), 20.0F);
        if (activity == null) {
            activity = SectPose.COOK_STIR;
            serveClock = 30 + npc.getRandom().nextInt(40);
        }
        if (--serveClock > 0) {
            return;
        }
        serveClock = 110 + npc.getRandom().nextInt(70);
        npc.holdPose(SectPose.COOK_LADLE, 58);
    }

    /** The tray round of a servant: loads at the counter, walks to a table, sets the bowls down, wipes the table, walks back. */
    private void trayRound(SectLayoutFile file) {
        switch (trayStage) {
            case 0 -> {
                activity = SectPose.TRAY_EMPTY;
                if (!arrive(current.spot(), 0.5D)) {
                    return;
                }
                npc.faceYaw(current.yaw(), 20.0F);
                if (++serveClock < 36) {
                    return;
                }
                SectLayoutFile.Spot table = nextTable(file);
                if (table == null) {
                    serveClock = 0;
                    return;
                }
                BlockPos w = table.walkTo();
                tableSpot = new Vec3(w.getX() + 0.5D, w.getY(), w.getZ() + 0.5D);
                tableYaw = table.yaw();
                trayStage = 1;
                serveClock = 0;
                activity = SectPose.TRAY;
            }
            case 1 -> {
                activity = SectPose.TRAY;
                // 25 s at most: a table the servant cannot reach is skipped, the bowls are "set down" where he stands.
                if (arrive(tableSpot, 0.45D) || ++serveClock > 500) {
                    trayStage = 2;
                    serveClock = 0;
                    activity = SectPose.WIPE;
                    npc.faceYaw(tableYaw, 90.0F);
                    npc.holdPose(SectPose.TRAY_SET, 50);
                }
            }
            case 2 -> {
                activity = SectPose.WIPE;
                npc.faceYaw(tableYaw, 30.0F);
                if (++serveClock > 70 + (npc.getId() & 31)) {
                    trayStage = 3;
                    serveClock = 0;
                    activity = SectPose.TRAY_EMPTY;
                }
            }
            default -> {
                activity = SectPose.TRAY_EMPTY;
                if (arrive(current.spot(), 0.5D) || ++serveClock > 500) {
                    trayStage = 0;
                    serveClock = 0;
                    trayRuns++;
                }
            }
        }
    }

    /** The next table of the dining hall for the tray: reachable standing cells beside the tables, a different one each round. */
    private SectLayoutFile.Spot nextTable(SectLayoutFile file) {
        java.util.List<SectLayoutFile.Spot> tables = new java.util.ArrayList<>();
        java.util.List<SectLayoutFile.Spot> away = new java.util.ArrayList<>();
        for (SectLayoutFile.Spot s : file.spots()) {
            if ("table".equals(s.kind()) && "dining_hall".equals(s.building()) && "ok".equals(s.reach())) {
                tables.add(s);
                // A table at his own feet is no walk: prefer the ones at least three blocks from the counter he starts from.
                Vec3 at = Vec3.atBottomCenterOf(s.walkTo());
                Vec3 home = current.spot();
                if ((at.x - home.x) * (at.x - home.x) + (at.z - home.z) * (at.z - home.z) >= 9.0D) {
                    away.add(s);
                }
            }
        }
        if (!away.isEmpty()) {
            tables = away;
        }
        if (tables.isEmpty()) {
            return null;
        }
        tables.sort(java.util.Comparator.comparing(SectLayoutFile.Spot::id));
        return tables.get(Math.floorMod(npc.getId() * 7 + trayRuns * 5, tables.size()));
    }

    // ------------------------------------------------------------------ ходьба

    /**
     * Идти к точке; true — уже на месте. Скорость — поспешная, если далеко (опаздывает к колоколу).
     */
    private boolean arrive(Vec3 spot, double speed) {
        return arrive(spot, speed, point -> true);
    }

    private boolean arrive(Vec3 spot, double speed, java.util.function.Predicate<Vec3> area) {
        if (npc.isSleeping()) npc.stopSleeping();
        if (mounted) {
            // Sitting on the seat that belongs to this spot (the spot itself is the cell beside it): nothing to walk. Got up
            // (pushed, woken, a fight): walk again.
            if (npc.sitting()) {
                onSpot = true;
                return true;
            }
            mounted = false;
        }
        if (current != null && current.task().kind() == Kind.EAT
                && "dining".equals(current.task().zone())
                && io.github.verycooltimo.murim.sect.SectDining.waitForAisle(npc)) {
            onSpot = false;
            return false;
        }
        double walkingSpeed = current != null && current.task().kind() == Kind.GUARD ? speed
                : io.github.verycooltimo.murim.sect.SectLead.speed(npc, speed, Math.sqrt(horizontal(spot)));
        onSpot = npc.walking().walk(spot, walkingSpeed * pace(), area);
        if (!onSpot) npc.sit(SeatStyle.NONE);
        else io.github.verycooltimo.murim.sect.SectLead.arrived(npc);
        if (onSpot && !arrivalNoted && current != null && spot.distanceToSqr(current.spot()) < 0.25D) {
            arrivalNoted = true;
            io.github.verycooltimo.murim.sect.SectTimings.arrived(npc, current.task().kind(), assignedAt, assignedFrom, current.spot(),
                    npc.walking().routeCost(), npc.walking().probeWait() - assignedWait, npc.walking().probeRepaths() - assignedRepaths);
        }
        return onSpot;
    }

    /** He sits down the way the furniture of his spot asks (chair sit on a chair, lotus on a cushion, mat or the floor). */
    private void sitHere() {
        mounted = SectLife.sitDown(npc, current);
    }

    /** Existing junior models: a ledger clerk and a runner carrying the office's small boxes. */
    private void officeHelper() {
        if (npc.tickCount % 1200 == Math.floorMod(npc.getId(), 1200)) {
            String key = "murim.bubble.office." + ("yul_san".equals(npc.memberKey()) ? "ledger" : "boxes");
            io.github.verycooltimo.murim.sect.SectChatter.send(npc, key, net.minecraft.network.chat.Component.translatable(key));
        }
        if ("yul_san".equals(npc.memberKey())) {
            if (arrive(current.spot(), 0.6D)) {
                sitHere();
                npc.faceYaw(current.yaw(), 20.0F);
                activity = SectPose.READ;
                npc.hold(Items.WRITABLE_BOOK);
            }
            return;
        }
        // The runner returns to his visitor chair between errands; every endpoint needs a complete grounded path.
        if (waypoint == 0 && arrive(current.spot(), 0.55D)) {
            sitHere();
            activity = SectPose.COUNT;
            npc.hold(Items.PAPER);
            if (++pause >= 160) { waypoint = 1; pause = 0; npc.standUp(); mounted = false; }
        } else if (waypoint == 1 && !npc.isNoGravity() && npc.onGround()) {
            var file = SectLife.file(npc);
            var room = file == null ? null : file.room(current.room());
            if (room == null) { waypoint = 0; return; }
            if (roam == null) {
                for (var cell : BlockPos.betweenClosed(new BlockPos(room.bounds().x0(), room.y(), room.bounds().z0()),
                        new BlockPos(room.bounds().x1(), room.y(), room.bounds().z1()))) {
                    Vec3 point = Vec3.atBottomCenterOf(cell);
                    if (point.distanceToSqr(current.spot()) < 4 || !SectLife.standable(npc.level(), cell)) continue;
                    var path = npc.getNavigation().createPath(cell, 0);
                    if (path != null && path.canReach()) { roam = point; break; }
                }
            }
            activity = SectPose.TALK;
            npc.hold(Items.CHEST);
            if (roam == null || arrive(roam, 0.5D)) { waypoint = 2; pause = 0; }
        } else if (waypoint == 2) {
            activity = SectPose.COUNT;
            if (++pause >= 40) { waypoint = 0; pause = 0; roam = null; activity = null; }
        }
    }

    /** Office work is done sitting when the spot is a seat; standing otherwise. */
    private void sitIfSeat() {
        if (seatedSpot()) {
            sitHere();
        } else {
            npc.sit(SeatStyle.NONE);
        }
    }

    /** True when he sits on a seat he was put on. */
    private boolean mounted;

    /**
     * Walk to a random point of a pacing round. A point next to a lantern or a table that the body cannot reach is
     * dropped at the first stuck step and another one is drawn, instead of waiting for the relocation.
     */
    private boolean arriveRoam(Vec3 spot, double speed) {
        if (arrive(spot, speed, this::insideWorkArea)) {
            return true;
        }
        if (npc.walking().stuckStage() >= 1) {
            // Dropping the destination must also cancel its path/recovery step before another draw.
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/ai/navigation/PathNavigation.java#stop
            npc.walking().stop();
            roam = null;
        }
        return false;
    }

    /** The spot he works at is a seat (chair, armchair, cushion, mat): office work is done sitting. */
    private boolean seatedSpot() {
        var layout = npc.layout();
        var file = layout == null ? null : layout.file();
        if (file == null || current == null || current.spotId() == null) {
            return false;
        }
        var spot = file.spot(current.spotId());
        return spot != null && spot.seated();
    }

    /** Личный темп шага по ключу человека. */
    private double pace() {
        return io.github.verycooltimo.murim.sect.SectStagger.factor(npc.memberKey(), "pace", 0.12D);
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
        long beat = SectSchedule.beat(now);
        long day = SectSchedule.day(npc.level().getDayTime());
        if(visualBeat!=beat || visualDay!=day) {
            visualBeat=beat; visualDay=day;
            visualOffset=io.github.verycooltimo.murim.sect.SectLessons.visualOffset(npc.memberKey(),day,beat);
            visualWobble=npc.member().map(m->io.github.verycooltimo.murim.sect.SectLessons.of(npc).wobbles(m,day,beat)).orElse(false);
        }
        int offset=visualOffset;
        if (Math.floorMod(now, (long) SectSchedule.BEAT) == offset) {
            if (visualWobble) npc.gesture("shake");
            else if (!io.github.verycooltimo.murim.sect.SectLessons.of(npc).skipSwing(npc))
                npc.drillForm(SectSchedule.beatForm(now), SectSchedule.BEAT_STRIKE - offset);
        }
        io.github.verycooltimo.murim.sect.SectLessons.of(npc).conductLook(npc);
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
        io.github.verycooltimo.murim.sect.SectLessons.of(npc).inspectPupil(npc);
        SectLayout layout = npc.layout();
        if (pause > 0) {
            pause--;
            if (pause == 30 && npc.getRandom().nextInt(2) == 0) {
                npc.gesture(npc.getRandom().nextBoolean() ? "point" : "explain");
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
        var file = layout.file();
        if (file != null) {
            // The rows of the built square: walk the gaps between the formation rows from the west end to the east end.
            BlockPos cell = io.github.verycooltimo.murim.sect.SectPlaces.inspectPoint(file, line, east ? 1 : 0);
            if (cell != null) {
                guess = new Vec3(cell.getX() + 0.5D, cell.getY(), cell.getZ() + 0.5D);
            }
        }
        if (guess == null) {
            return;
        }
        Vec3 spot = npc.level().isLoaded(BlockPos.containing(guess)) ? SectLife.stand(npc.level(), guess) : guess;
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
        if (arriveRoam(roam, 0.7D)) {
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
            drillClock = io.github.verycooltimo.murim.sect.SectStagger.ticks(npc.memberKey(),
                    "drill:" + SectSchedule.day(npc.level().getDayTime()) + ":" + drillStep, 26, 36);
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
        if (arriveRoam(roam, speed)) {
            pause = 80 + npc.getRandom().nextInt(120);
            roam = null;
            if (npc.getRandom().nextInt(4) == 0) {
                npc.gesture("explain");
            }
        }
    }

    /** Случайная точка рядом с местом задания (в пределах площадки). */
    private Vec3 pick(double ru, double rv) {
        if (current.spotId() != null && npc.layout() != null && npc.layout().file() != null) {
            return pickNear(Math.min(ru, 3.0D));
        }
        SectLayout layout = npc.layout();
        double[] h = layout.half(current.task().zone());
        double maxU = h == null ? ru : Math.min(ru, h[0] - 1.0D);
        double maxV = h == null ? rv : Math.min(rv, h[1] - 1.0D);
        double du = current.task().du() + (npc.getRandom().nextDouble() * 2.0D - 1.0D) * maxU;
        double dv = current.task().dv() + (npc.getRandom().nextDouble() * 2.0D - 1.0D) * maxV;
        Vec3 guess = layout.at(current.task().zone(), du, dv);
        return guess == null || !insideWorkArea(guess) ? null : SectLife.stand(npc.level(), guess);
    }

    /** Work without an outside destination never randomly crosses the gate plane. */
    private boolean insideWorkArea(Vec3 point) {
        SectLayout layout = npc.layout();
        Vec3 gate = layout.at("sect_gate", 0, 0);
        Vec3 outside = layout.at("sect_gate", 0, -10);
        var file = layout.file();
        if (file != null) {
            var inside = file.anchor("gate_inside").orElse(null);
            var trail = file.anchor("trail_approach").orElse(null);
            if (inside != null && trail != null) {
                gate = Vec3.atBottomCenterOf(inside.pos());
                outside = Vec3.atBottomCenterOf(trail.pos());
            }
        }
        if (gate == null || outside == null) return true;
        double dx = outside.x - gate.x, dz = outside.z - gate.z;
        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/pathfinder/Path.java#getEntityPosAtNode
        // Keep one block of clearance: vanilla paths use cell centres and walking accepts a half-block arrival.
        // Checking only the destination's sign allows a legal target at the plane to finish outside it.
        return (point.x - gate.x) * dx + (point.z - gate.z) * dz <= -Math.hypot(dx, dz);
    }

    /**
     * Roam near the spot of the built sect: a free cell within {@code radius} that lies in the same room (or, outdoors,
     * on the same level), never through a wall. Null when the spot has no free neighbour: the person stays on it.
     */
    private Vec3 pickNear(double radius) {
        var file = npc.layout().file();
        Vec3 base = current.spot();
        for (int i = 0; i < 8; i++) {
            double dx = (npc.getRandom().nextDouble() * 2.0D - 1.0D) * radius;
            double dz = (npc.getRandom().nextDouble() * 2.0D - 1.0D) * radius;
            Vec3 guess = new Vec3(base.x + dx, base.y, base.z + dz);
            BlockPos cell = BlockPos.containing(guess);
            if (!insideWorkArea(guess) || !npc.level().isLoaded(cell) || !SectLife.standable(npc.level(), cell)) {
                continue;
            }
            if (current.room() != null) {
                var around = file.roomAt(cell);
                if (around == null || !around.id().equals(current.room())) {
                    continue;
                }
            }
            return SectLife.stand(npc.level(), guess);
        }
        return insideWorkArea(base) ? base : null;
    }

    /** Ночь: кровать в общежитии, если автор их поставил; иначе — сидя во дворе лагеря. */
    private void sleep() {
        if (npc.isSleeping()) {
            return;
        }
        if (--bedSearch <= 0) {
            bedSearch = 40;
            bed = SectLife.findBed(npc, current.task().zone());
        }
        if (bed != null) {
            if (!npc.level().getBlockState(bed).isBed(npc.level(), bed, npc)) {
                bed = null;
                return;
            }
            Vec3 at = Vec3.atBottomCenterOf(bed);
            if (horizontal(at) < 1.21D && Math.abs(npc.getY() - bed.getY()) < 1.0D) {
                npc.getNavigation().stop();
                npc.startSleeping(bed);
                return;
            }
            Vec3 approach = SectLife.bedApproach(npc, bed);
            // Standing on the cell beside the bed is enough: the path ends up to half a block short of its middle.
            if (arrive(approach == null ? current.spot() : approach, 0.7D) && Math.abs(npc.getY() - bed.getY()) < 1.0D) {
                npc.getNavigation().stop();
                npc.startSleeping(bed);
            }
            return;
        }
        // Кроватей нет: сидит на своём месте в общежитии (площадка в лагере).
        if (arrive(current.spot(), 0.6D)) {
            npc.faceYaw(current.yaw(), 30.0F);
            sitHere();
        }
    }
}
