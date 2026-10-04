package io.github.verycooltimo.murim.entity;

import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.sect.SectSchedule.Kind;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

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

    public ScheduleGoal(SectDisciple npc) {
        this.npc = npc;
        setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK, Flag.JUMP));
    }

    @Override
    public boolean canUse() {
        return npc.member().isPresent() && !npc.dormant() && npc.free() && npc.layout() != null;
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
        switch (kind) {
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
            case EAT, MEDITATE, REST -> {
                if (arrive(current.spot(), 0.8D)) {
                    npc.faceYaw(current.yaw(), 30.0F);
                    npc.sit(true);
                }
            }
            case SLEEP -> sleep();
            default -> {
                // WATCH, GUARD, GREET: стоять на месте лицом куда надо, глазами — на ближнего игрока.
                if (arrive(current.spot(), kind == Kind.GREET ? 1.0D : 0.8D)) {
                    npc.faceYaw(current.yaw(), 15.0F);
                    Player p = npc.level().getNearestPlayer(npc, 8.0D);
                    if (p != null) {
                        npc.getLookControl().setLookAt(p, 20.0F, 20.0F);
                    }
                }
            }
        }
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
            Kind k = next.task().kind();
            boolean seated = k == Kind.EAT || k == Kind.MEDITATE || k == Kind.REST || k == Kind.SLEEP;
            if (!seated) {
                npc.sit(false);
            }
            if (k != Kind.SLEEP && npc.isSleeping()) {
                npc.stopSleeping();
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
        if (d < best - 0.25D) {
            best = d;
            stuck = 0;
        } else if (++stuck > STUCK_TICKS) {
            stuck = 0;
            best = Double.MAX_VALUE;
            if (npc.level().getNearestPlayer(npc, 24.0D) == null && npc.level().isLoaded(BlockPos.containing(spot))) {
                npc.moveTo(spot.x, spot.y, spot.z, current.yaw(), 0.0F);
                npc.getNavigation().stop();
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
