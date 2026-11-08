package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SectNavigation;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;
import java.util.Optional;

/**
 * Жизнь секты на сервере (docs/design/23-mount-hua-sect.md §4.2, §6.1): люди встают на гору, распорядок
 * дня переводится в точки мира, колокол бьёт смену занятий, пары учеников сходятся на поединок, игрок
 * может встать в утренний строй. Сам NPC ходит и действует в {@code ScheduleGoal}; здесь — то, что
 * касается нескольких людей сразу или мира.
 *
 * <p>Производительность (план §8.3): NPC тикают только в загруженных чанках; дальше
 * {@link SectDisciple#AWAKE_RANGE} от игроков ИИ отдыхает после пешего прихода к месту дела. Чанки не держатся загруженными.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectLife {

    /** Сколько форм в такт нужно для «Утренней тренировки» (план §4.2: «повторил 10 форм»). */
    public static final int MORNING_FORMS = 10;
    /**
     * Что даёт утренний строй (Track C, docs/design/research/hua-main-1810.md §6, T3): не общий +15 % на день, а опыт
     * искусству, формы которого отрабатывали в строю. Доля следующей звезды: {@link #MORNING_SHARE} за искусство и ещё
     * {@link #MORNING_PER_FORM} за каждую разную форму сверх первой (не больше {@link #MORNING_EXTRA_FORMS} таких).
     */
    public static final double MORNING_SHARE = 0.02D;
    public static final double MORNING_PER_FORM = 0.01D;
    public static final int MORNING_EXTRA_FORMS = 3;
    private static final String MORNING_TAG = "murim_morning";

    private SectLife() {
    }

    // ------------------------------------------------------------------ раскладка и точки

    /** Раскладка площадок горы в этом измерении (только оверворлд), или null — горы нет. */
    public static SectLayout layout(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            return null;
        }
        MountHuaSite site = MountHuaSites.get(level.getServer());
        return site == null ? null : SectLayout.hua(site);
    }

    /**
     * Точка, где можно стоять, рядом с {@code guess}: сначала на высоте площадки и чуть выше (пол
     * постройки автора), потом ниже. Крыша над площадкой не годится: поиск идёт от пола вверх.
     */
    public static Vec3 stand(Level level, Vec3 guess) {
        BlockPos base = BlockPos.containing(guess);
        for (int i = 0; i <= 12; i++) {
            int dy = (i % 2 == 0) ? i / 2 : -(i + 1) / 2;
            BlockPos b = base.offset(0, dy, 0);
            if (standable(level, b)) {
                var support = level.getBlockState(b.below()).getCollisionShape(level, b.below());
                return new Vec3(guess.x, b.getY() - 1 + support.max(net.minecraft.core.Direction.Axis.Y), guess.z);
            }
        }
        return guess;
    }

    /** No collision, or only a rug or a carpet (up to 0.1 high) that the body walks over. */
    static boolean passable(Level level, BlockPos p) {
        var shape = level.getBlockState(p).getCollisionShape(level, p);
        return shape.isEmpty() || shape.max(Direction.Axis.Y) <= 0.1D;
    }

    public static boolean standable(Level level, BlockPos b) {
        BlockState below = level.getBlockState(b.below());
        return !below.getCollisionShape(level, b.below()).isEmpty()
                && passable(level, b)
                && level.getBlockState(b.above()).getCollisionShape(level, b.above()).isEmpty()
                && level.getFluidState(b).isEmpty();
    }

    /**
     * Задание с точкой мира и поворотом. {@code spotId}, {@code room} и {@code pickup} заполнены, когда место взято из
     * раскладки {@link SectLayoutFile} (docs/design/38): id места (для брони и отказа от него), комната, второй конец
     * маршрута носильщика.
     */
    public record Resolved(SectSchedule.Task task, Vec3 spot, float yaw, String spotId, String room, Vec3 pickup) {
        public Resolved(SectSchedule.Task task, Vec3 spot, float yaw) {
            this(task, spot, yaw, null, null, null);
        }
    }

    /** Что сейчас делает человек и где. null — не человек секты или нет раскладки. */
    public static Resolved resolve(SectDisciple npc) {
        Optional<SectRoster> m = npc.member();
        SectLayout layout = npc.layout();
        if (m.isEmpty() || layout == null) {
            return null;
        }
        long time = npc.level().getDayTime();
        SectLayoutFile file = layout.file();
        // Две волны трапезы и подъём с верхних этажей (docs/design/38 §5.2, §5.5): только там, где есть построенная секта.
        // The council and the quarrel need the built council hall and court; a layout without them lives the ordinary day.
        boolean events = file == null || SectPlaces.hasCouncilHall(file);
        // Lead time (docs/adr/0017): in the built sect a person sets off for the next part of the day early enough to be there when
        // it starts; {@code sched} is the time his routine is read at (the start of the next part from the moment he sets off).
        boolean squareWatch = SectSchedule.at(time) == SectSchedule.Period.NIGHT
                && m.get().key().equals(SectNightWatch.member(time));
        long sched = file == null || squareWatch ? time : SectLead.time(npc, file, m.get(), events, time);
        long taskTime = file == null ? time : SectPlaces.time(file, m.get(), sched);
        SectSchedule.Task t = SectSchedule.task(m.get(), taskTime, events);
        // Разница во времени (автор 06.10): в начале части суток каждый ещё немного доделывает прежнее — из-за стола,
        // со сна, в строй встают не в один тик. Пост, поединок и смотр — по колоколу: там ждут друг друга.
        // The two dining waves already spread the meal; the personal lag would push the second wave past it.
        // In the built sect the personal delay is the arrival delay of {@link SectLead#JITTER}, not a late start of the next task.
        long late = file != null && (SectLead.ENABLED || SectSchedule.at(time).meal()) ? taskTime : SectStagger.scheduleTime(m.get(), taskTime);
        if (late != taskTime && m.get().role() != SectRole.GATEKEEPER && !SectRota.onDuty(m.get(), time) && !SectRota.onDuty(m.get(), late)
                && !SectReview.window(time) && !SectReview.window(late)) {
            SectSchedule.Task before = SectSchedule.task(m.get(), late, events);
            if (before.kind() != SectSchedule.Kind.SPAR) {
                t = before;
            }
        }
        // The square watch uses tonight's clock, ahead of sleep/lead-time adjustments (tester round 4).
        if (squareWatch) {
            t = SectSchedule.task(m.get(), time, events);
        }
        // Смотр учеников: боец сетки идёт на своё место в ринге (SectReview).
        SectSchedule.Task fight = SectReview.fighterTask(npc);
        if (fight != null) {
            t = fight;
        }
        if (npc.level() instanceof ServerLevel level && data(level).bladeExam != null
                && !SectRota.onDuty(m.get(), time) && !SectRota.afterNight(m.get(), time)) {
            SectSchedule.Task watch = SectReview.spectator(m.get());
            if (watch != null) t = watch;
        }
        // Раненый после поединка сидит, где упал, и ждёт лекаря.
        if (fight == null && npc.wounded()) {
            Vec3 at = npc.woundSpot();
            return new Resolved(new SectSchedule.Task(SectSchedule.Kind.WAIT_TREAT, t.zone(), 0.0D, 0.0D, 0.0D, 0.0D), at, npc.getYRot());
        }
        // Лекарь за своим делом днём — к ближайшему раненому (кроме совета и ночи).
        if ("gyeong_cho".equals(m.get().key()) && (t.kind() == SectSchedule.Kind.BREW || t.kind() == SectSchedule.Kind.GRIND
                || t.kind() == SectSchedule.Kind.HEAL_POST)) {
            SectDisciple patient = patient(npc);
            if (patient != null) {
                Vec3 p = patient.woundSpot();
                Vec3 dir = npc.position().subtract(p).multiply(1.0D, 0.0D, 1.0D);
                dir = dir.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : dir.normalize();
                Vec3 spot = p.add(dir.scale(1.1D));
                float yaw = SectLayout.yawOf(p.x - spot.x, p.z - spot.z);
                return new Resolved(new SectSchedule.Task(SectSchedule.Kind.TREAT, t.zone(), 0.0D, 0.0D, 0.0D, 0.0D, patient.memberKey()),
                        spot, yaw);
            }
        }
        // Раненый игрок на земле секты (живая гора, автор 05.10): Гён Чхо днём за своим делом идёт перевязать.
        if ("gyeong_cho".equals(m.get().key()) && !npc.dormant() && healerFree(t.kind()) && SectSchedule.at(time) != SectSchedule.Period.NIGHT) {
            ServerPlayer hurt = SectReactions.patient(npc);
            if (hurt != null) {
                Vec3 p = hurt.position();
                Vec3 dir = npc.position().subtract(p).multiply(1.0D, 0.0D, 1.0D);
                dir = dir.lengthSqr() < 1.0E-4D ? new Vec3(1.0D, 0.0D, 0.0D) : dir.normalize();
                Vec3 spot = stand(npc.level(), p.add(dir.scale(1.3D)));
                float yaw = SectLayout.yawOf(p.x - spot.x, p.z - spot.z);
                return new Resolved(new SectSchedule.Task(SectSchedule.Kind.TREAT, t.zone(), 0.0D, 0.0D, 0.0D, 0.0D,
                        SectReactions.PLAYER_PATIENT), spot, yaw);
            }
        } else if ("gyeong_cho".equals(m.get().key()) && npc.healingPlayer() >= 0) {
            npc.setHealingPlayer(-1);
        }
        // The gatekeeper leaves only after his covering guard has physically reached the gate.
        // Once asleep he stays asleep; the cover may then step away to meet an outsider.
        if (m.get().role() == SectRole.GATEKEEPER && t.kind() == SectSchedule.Kind.SLEEP
                && npc.doingKind() != SectSchedule.Kind.SLEEP && !npc.isSleeping()) {
            SectRoster cover = SectRota.nightWatch(SectSchedule.day(time), 0);
            boolean relieved = !npc.level().getEntitiesOfClass(SectDisciple.class,
                    npc.getBoundingBox().inflate(64.0D), d -> {
                        if (!d.isAlive() || !cover.key().equals(d.memberKey()) || d.isSleeping()) return false;
                        // Compare with the cover's actual layout post, including authored spot placement.
                        Resolved post = resolve(d);
                        return post != null && post.task().kind() == SectSchedule.Kind.GUARD
                                && "sect_gate".equals(post.task().zone()) && d.distanceToSqr(post.spot()) < 1.6D * 1.6D;
                    }).isEmpty();
            // Wait on the gatekeeper's daytime side, leaving the cover's post free to approach.
            if (!relieved) t = new SectSchedule.Task(SectSchedule.Kind.GUARD, "sect_gate", 3.0D, 0.0D, 0.0D, -1.0D);
        }
        // Смена поста: сменяемый ждёт сменщика на посту, пока они не поклонятся друг другу (или окно не выйдет).
        Optional<SectRota.Duty> ending = SectRota.ending(m.get(), time);
        if (ending.isPresent() && handoverPending(npc, m.get())) {
            t = SectSchedule.post(ending.get());
        }
        if (file != null) {
            if ("tae_gyun".equals(m.get().key())) SectOffice.ensureAccess(npc, file);
            t = SectPlaces.adjust(m.get(), t, sched);
            Resolved placed = placed(npc, file, m.get(), t, sched);
            if (placed != null) {
                return SectWeather.apply(npc, placed);
            }
        }
        Vec3 guess = layout.at(t.zone(), t.du(), t.dv());
        if (guess == null) {
            return null;
        }
        Vec3 spot = npc.level().isLoaded(BlockPos.containing(guess)) ? stand(npc.level(), guess) : guess;
        spot = SectBuildings.routine(npc, t, spot);
        // Дождь: дело под открытым небом прервано — под крышу рядом (SectWeather).
        return SectWeather.apply(npc, new Resolved(t, spot, layout.yaw(t.faceU(), t.faceV())));
    }

    // ------------------------------------------------------------------ места из раскладки постройки (docs/design/38)

    /** Построенная секта как данные для этого человека, или null (тестовый двор, чужой мир): тогда план горы. */
    public static SectLayoutFile file(SectDisciple npc) {
        SectLayout layout = npc.layout();
        return layout == null ? null : layout.file();
    }

    /**
     * Место дела из раскладки: комната, место, кровать. null — для этого дела в раскладке места нет (уличные
     * занятия, тестовая площадка): остаётся позиция по плану.
     */
    static Resolved placed(SectDisciple npc, SectLayoutFile file, SectRoster m, SectSchedule.Task t, long time) {
        Level level = npc.level();
        if (t.kind() == SectSchedule.Kind.REPORT) {
            return reportPlace(npc, file, m, t, time);
        }
        SectPlaces.Pick p = SectPlaces.pick(file, m, t, time, SectSpots.of(level), level.getGameTime(), npc.spotBans());
        // A seat a player sits on is not his (holes review npc-6): give it up for a while and take the next free one, else stand on the plan.
        for (int tries = 0; p != null && p.seat() != null && playerSits(level, p.seat()) && tries < 4; tries++) {
            npc.banSpot(p.spotId(), SEAT_TAKEN_BAN);
            p = SectPlaces.pick(file, m, t, time, SectSpots.of(level), level.getGameTime(), npc.spotBans());
        }
        if (p == null || p.seat() != null && playerSits(level, p.seat())) {
            return null;
        }
        Vec3 pickup = p.pickup() == null ? null : world(level, npc, new SectPlaces.Pick(null, p.pickup().getX() + 0.5D, p.pickup().getY(),
                p.pickup().getZ() + 0.5D, 0.0F, null, null, null, null, 0));
        return new Resolved(t, world(level, npc, p), p.yaw(), p.spotId(), p.room(), pickup);
    }

    /** Точка мира для выбранного места: верх сиденья, если можно встать на него, иначе клетка подхода. */
    static Vec3 world(Level level, SectDisciple npc, SectPlaces.Pick p) {
        BlockPos cell = p.cell();
        Vec3 raw = new Vec3(p.x(), p.y(), p.z());
        if (!level.isLoaded(cell)) {
            return raw;
        }
        if (p.seat() != null && level.isLoaded(p.seat())) {
            // Every seat (chair, armchair, stool, bench, cushion, mat) is sat down on from the cell beside it, never walked onto:
            // the furniture is no path node (SeatBlock#isPathfindable is false), a chair's back reaches 1.0, and the old target for a
            // chair hung a block in the air above it (19.10); {@link #sitDown} puts the person on the seat from there.
            return stand(level, Vec3.atBottomCenterOf(besideSeat(level, p.seat(), cell, p.yaw())));
        }
        return stand(level, raw);
    }

    /**
     * The cell a person stands in before he sits down on {@code seat}: the approach cell of the layout when it is a cell of its own,
     * else (a cushion or a mat is its own approach) a standable neighbour, behind the sitter first, then the sides, then in front.
     * The order is fixed, so the target does not jump from one second to the next.
     */
    public static BlockPos besideSeat(Level level, BlockPos seat, BlockPos approach, float yaw) {
        if (approach != null && !approach.equals(seat)) {
            return approach;
        }
        Direction front = Direction.fromYRot(yaw);
        for (Direction d : new Direction[] {front.getOpposite(), front.getClockWise(), front.getCounterClockWise(), front}) {
            BlockPos c = seat.relative(d);
            if (level.isLoaded(c) && standable(level, c) && !SectNavigation.tallObstacle(level, c, level.getBlockState(c))) {
                return c;
            }
        }
        return seat;
    }

    /** How long a person keeps away from a seat a player sat on, ticks. */
    static final int SEAT_TAKEN_BAN = 400;

    /** A player rides a seat entity on the seat block {@code seat}. */
    public static boolean playerSits(Level level, BlockPos seat) {
        return level.isLoaded(seat) && !level.getEntitiesOfClass(io.github.verycooltimo.murim.decor.SeatEntity.class, new net.minecraft.world.phys.AABB(seat).inflate(0.2D),
                e -> e.getPassengers().stream().anyMatch(x -> x instanceof net.minecraft.world.entity.player.Player)).isEmpty();
    }

    /** The layout spot a resolved task stands on, or null (plan zones, test yards). */
    public static SectLayoutFile.Spot spotOf(SectDisciple npc, Resolved r) {
        SectLayoutFile file = file(npc);
        return file == null || r == null || r.spotId() == null ? null : file.spot(r.spotId());
    }

    /**
     * Where a person sits down on the seat block {@code seat}: the middle of it, at the height of its sitting surface (the
     * {@code seatTop} of a seat block, else the top of its collision shape). Null when there is nothing to sit on there.
     */
    public static Vec3 seatTop(Level level, BlockPos seat) {
        if (!level.isLoaded(seat)) {
            return null;
        }
        BlockState state = level.getBlockState(seat);
        var shape = state.getCollisionShape(level, seat);
        if (shape.isEmpty()) {
            return null;
        }
        double top = state.getBlock() instanceof io.github.verycooltimo.murim.decor.SeatBlock block ? block.seatTop(state) : shape.max(Direction.Axis.Y);
        return top <= 0.0D ? null : Vec3.atBottomCenterOf(seat).add(0.0D, top, 0.0D);
    }

    /**
     * Sit the person down for the resolved task, the way the furniture asks: put on the seat from the cell beside it, in the chair
     * sit on a chair, an armchair, a stool or a bench, in the lotus on a cushion, a mat or the floor ({@link SeatStyle}). Idempotent:
     * the routine calls it every tick. Returns true when he sits on a seat he was put on (the seat is his place, not the cell beside it).
     */
    public static boolean sitDown(SectDisciple npc, Resolved r) {
        SectLayoutFile.Spot spot = spotOf(npc, r);
        Level level = npc.level();
        BlockState block = spot != null && spot.pos() != null && level.isLoaded(spot.pos()) ? level.getBlockState(spot.pos()) : null;
        SeatStyle style = SeatStyle.of(spot, block);
        Vec3 top = spot != null && spot.seated() && spot.pos() != null ? seatTop(level, spot.pos()) : null;
        npc.sit(style, top, r.yaw(), top == null ? null : npc.position());
        return top != null && npc.sitting();
    }

    /** Докладчик встаёт в шаге перед главой, где бы тот ни был в постройке; нет места главы в раскладке — по плану. */
    private static Resolved reportPlace(SectDisciple npc, SectLayoutFile file, SectRoster m, SectSchedule.Task t, long time) {
        SectRoster leader = SectRoster.of(t.partner()).orElse(null);
        if (leader == null) {
            return null;
        }
        SectSchedule.Task lt = SectPlaces.adjust(leader, SectSchedule.task(leader, time), time);
        SectPlaces.Pick lp = SectPlaces.pick(file, leader, lt, time, null, npc.level().getGameTime(), null);
        if (lp == null) {
            return null;
        }
        Level level = npc.level();
        double fx = -Math.sin(Math.toRadians(lp.yaw()));
        double fz = Math.cos(Math.toRadians(lp.yaw()));
        double[][] tries = {{fx, fz}, {fz, -fx}, {-fz, fx}, {-fx, -fz}};
        for (double[] d : tries) {
            Vec3 guess = new Vec3(lp.x() + d[0] * 1.8D, lp.y(), lp.z() + d[1] * 1.8D);
            BlockPos cell = BlockPos.containing(guess);
            if (level.isLoaded(cell) && standable(level, cell)) {
                float yaw = SectLayout.yawOf(lp.x() - guess.x, lp.z() - guess.z);
                return new Resolved(t, stand(level, guess), yaw, null, lp.room(), null);
            }
        }
        return new Resolved(t, stand(level, new Vec3(lp.x(), lp.y(), lp.z())), lp.yaw() + 180.0F, null, lp.room(), null);
    }

    /** Где сейчас было бы дело {@code t} (без брони места): пост сменяемого, точка смены. null — по плану. */
    public static Vec3 placeOfTask(SectDisciple npc, SectSchedule.Task t) {
        SectLayoutFile file = file(npc);
        Optional<SectRoster> m = npc.member();
        if (file == null || m.isEmpty()) {
            return null;
        }
        SectPlaces.Pick p = SectPlaces.pick(file, m.get(), t, npc.level().getDayTime(), null, npc.level().getGameTime(), null);
        return p == null ? null : world(npc.level(), npc, p);
    }

    /**
     * Where a sect member is right now, as a phrase for a line of dialogue ("in the Main Hall"): the room or yard
     * under him in the built sect. Unknown (not loaded, no layout): "somewhere on the mountain".
     */
    public static net.minecraft.network.chat.Component whereIs(ServerLevel level, String memberKey, BlockPos around) {
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, columnBox(level, around, WHERE_RANGE),
                d -> d.isAlive() && memberKey.equals(d.memberKey()))) {
            String fn = placeFunction(d);
            if (!fn.isEmpty()) {
                return net.minecraft.network.chat.Component.translatable("murim.sect.place." + fn);
            }
            break;
        }
        return net.minecraft.network.chat.Component.translatable("murim.sect.place.unknown");
    }

    /** How far from the asker a dialogue line looks for a member ("where is he?"): the whole shelf, not the world. */
    public static final double WHERE_RANGE = 300.0D;

    /** A full-height box of {@code range} blocks around {@code centre}: never a world-sized scan. */
    static AABB columnBox(Level level, BlockPos centre, double range) {
        return new AABB(centre.getX() - range, level.getMinBuildHeight(), centre.getZ() - range,
                centre.getX() + range, level.getMaxBuildHeight(), centre.getZ() + range);
    }

    /**
     * The box that holds the whole sect: the massif footprint plus a margin. Null when this world has no mountain (the
     * sect lives nowhere, so there is nobody to find).
     */
    static AABB sectBox(ServerLevel level) {
        if (level.dimension() != Level.OVERWORLD) {
            return null;
        }
        MountHuaSite site = MountHuaSites.get(level.getServer());
        return site == null ? null : new AABB(site.minX() - 64, level.getMinBuildHeight(), site.minZ() - 64,
                site.maxX() + 64, level.getMaxBuildHeight(), site.maxZ() + 64);
    }

    /** Every named member standing on the mountain (loaded chunks only). */
    static List<SectDisciple> members(ServerLevel level) {
        AABB box = sectBox(level);
        return box == null ? List.of() : level.getEntitiesOfClass(SectDisciple.class, box, d -> !d.memberKey().isEmpty());
    }

    /** Функция места под человеком ({@code dining_hall}, {@code audience_hall}, ...) для реплики; "" — неизвестно. */
    public static String placeFunction(SectDisciple npc) {
        SectLayoutFile file = file(npc);
        return file == null ? "" : file.placeFunction(npc.blockPosition());
    }

    /** Where a person first appears on the mountain: the real place of his task when the layout has one. */
    static Vec3 spawnGuess(SectLayout layout, SectRoster m, SectSchedule.Task t, long time) {
        SectLayoutFile file = layout.file();
        if (file != null) {
            SectPlaces.Pick p = SectPlaces.pick(file, m, SectPlaces.adjust(m, t, time), time, null, 0L, null);
            if (p != null) {
                return new Vec3(p.x(), p.y(), p.z());
            }
        }
        return layout.at(t.zone(), t.du(), t.dv());
    }

    /** Лекарь может отойти к раненому игроку: за своим делом, в трапезу, вечером (не на совете, не с докладом, не во сне). */
    static boolean healerFree(SectSchedule.Kind k) {
        return k == SectSchedule.Kind.BREW || k == SectSchedule.Kind.GRIND || k == SectSchedule.Kind.HEAL_POST
                || k == SectSchedule.Kind.EAT || k == SectSchedule.Kind.MEDITATE || k == SectSchedule.Kind.WORK
                || k == SectSchedule.Kind.WATCH;
    }

    // ------------------------------------------------------------------ члены секты за делом (автор 05.10)

    /** Лекарь ищет раненых в этом радиусе. */
    public static final double PATIENT_RANGE = 64.0D;

    /** Ближайший раненый ученик для лекаря, или null. */
    public static SectDisciple patient(SectDisciple healer) {
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        for (SectDisciple d : healer.level().getEntitiesOfClass(SectDisciple.class, healer.getBoundingBox().inflate(PATIENT_RANGE),
                d -> d != healer && d.isAlive() && d.wounded() && !d.questHeld() && d.spar() == SectDisciple.Spar.NONE)) {
            double dist = d.distanceToSqr(healer);
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        return best;
    }

    /**
     * Охранник только что сменился (началась чужая смена) и ещё ждёт сменщика: окно {@link SectSchedule#HANDOVER},
     * и его не сменили в эту часть суток.
     */
    public static boolean handoverPending(SectDisciple npc, SectRoster m) {
        long time = npc.level().getDayTime();
        SectSchedule.Period p = SectSchedule.at(time);
        int since = SectSchedule.sincePeriodStart(time);
        if (SectRota.ending(m, time).isEmpty() || since >= SectSchedule.HANDOVER) {
            return false;
        }
        return npc.relievedKey() != SectSchedule.day(time) * 8L + p.ordinal();
    }

    /**
     * Ученик второго поколения сейчас на страже (SectRota): его смена или ждёт сменщика. Не на смене — занимается,
     * спит, ест и не смотрит за закрытыми местами.
     */
    public static boolean onWatch(SectDisciple d) {
        Optional<SectRoster> m = d.member();
        if (m.isEmpty()) {
            return false;
        }
        long time = d.level().getDayTime();
        // At his meal (SectRota#meal) he is not on watch: the other guard stands in his place.
        return SectRota.onDuty(m.get(), time) && !SectRota.eating(m.get(), time) || handoverPending(d, m.get())
                || SectRota.atHallDoor(m.get(), time);
    }

    /**
     * Носильщик донёс груз до кладовой: Тэ Гюн у стола (или управляющий) поворачивается к нему и кивает — принял;
     * носильщик кланяется.
     */
    public static void delivered(SectDisciple porter) {
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        boolean water = porter.role() == SectRole.WATER_CARRIER;
        for (SectDisciple d : porter.level().getEntitiesOfClass(SectDisciple.class, porter.getBoundingBox().inflate(12.0D),
                d -> (water ? d.role() == SectRole.COOK : "tae_gyun".equals(d.memberKey()) || d.role() == SectRole.STEWARD) && d.free() && !d.dormant())) {
            double dist = d.distanceToSqr(porter);
            if (dist < bestD) {
                bestD = dist;
                best = d;
            }
        }
        if (best != null) {
            best.attend(porter, 50);
            best.gesture(best.getRandom().nextInt(3) == 0 ? "point" : "nod");
            porter.attend(best, 50);
            // servants-1/2: the carrier says his word, the receiver turns and answers a moment later (once a delivery, never both at once)
            SectBubbles.Group pool = water ? SectBubbles.Group.STAFF_WATER : SectBubbles.Group.STAFF_DELIVERY;
            String say = pool.key(water ? 1 : 1 + porter.getRandom().nextInt(2));
            if (SectChatter.sayIfQuiet(porter, say, 200) && !water) {
                String back = pool.key(3);
                best.queueBubble(back, net.minecraft.network.chat.Component.translatable(back), 30 + porter.getRandom().nextInt(20));
                best.markBubble(back);
            } else if (water) {
                String back = pool.key(5);
                best.queueBubble(back, net.minecraft.network.chat.Component.translatable(back), 30 + porter.getRandom().nextInt(20));
                best.markBubble(back);
            }
        }
        porter.holdPose(io.github.verycooltimo.murim.entity.SectPose.BOW, 44);
    }

    static SectSiteData data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(SectSiteData.FACTORY, SectSiteData.NAME);
    }

    // ------------------------------------------------------------------ NPC: раз в секунду

    /** Раз в секунду для каждого человека секты (из {@code SectDisciple#tickLife}). */
    public static void tickNpc(SectDisciple npc) {
        if (!(npc.level() instanceof ServerLevel level)) {
            return;
        }
        if (npc.member().isEmpty()) {
            // Человек прежнего состава (стража, старший первой версии): уходит с горы — дублей и «статистов» нет.
            if (SectRoster.RETIRED.contains(npc.memberKey()) || npc.role() == SectRole.GUARD) {
                MurimMod.LOGGER.info("Секта: {} ({}) больше не в составе — убран", npc.memberKey(), npc.role().id());
                npc.discard();
                return;
            }
            adoptLegacy(npc, level);
            return;
        }
        if ((npc.tickCount + npc.getId()) % 200 == 0 && duplicate(npc, level)) {
            return;
        }
        // Колокол пробил — занятия кончились: поединок учеников прекращается (с игроком — доигрывается).
        // Дождь — тоже конец поединка учеников: оба под крышу (живая гора, автор 05.10).
        if (npc.inBout() && (SectSchedule.at(level.getDayTime()) != SectSchedule.Period.TRAINING || SectWeather.wet(npc) && SectReview.fighterTask(npc) == null)) {
            npc.stopBout();
        }
        // Свои — на защиту: чужой враждебный моб рядом с учеником.
        if (npc.free() && !npc.dormant()) {
            for (Mob enemy : level.getEntitiesOfClass(Mob.class, npc.getBoundingBox().inflate(10.0D),
                    e -> threat(e))) {
                alarm(npc, enemy);
                break;
            }
        }
        // Rank at the post, the guard's meal, and the watch's eye for a hostile creature (reviews hier-1, watch-2, night-1).
        SectHonour.tick(npc);
        SectHonour.meal(npc);
        watchMonsters(npc, level);
        if (npc.dormant()) {
            settleUnseen(npc, level);
        }
    }

    /** Idle, distant NPCs may heal in place; a changed routine wakes them to walk. */
    static void settleUnseen(SectDisciple npc, ServerLevel level) {
        Resolved r = resolve(npc);
        if (r == null || !npc.atPlace(r.spot())) return;
        if (npc.wounded() && !npc.questHeld()) npc.setWounded(false);
        if (r.task().kind().seated()) {
            sitDown(npc, r);
        } else {
            npc.sit(SeatStyle.NONE);
        }
        npc.setPose(io.github.verycooltimo.murim.entity.SectPose.forTask(r.task().kind(), true,
                io.github.verycooltimo.murim.entity.SectPose.carrier(npc)));
    }

    /**
     * NPC первой версии (роль без имени, поставлены на площадки «gate», «sect_gate», «training»)
     * становятся людьми из списка, а не дублями. Только на горе: NPC от {@code /murim dev sched spawn} в
     * другом месте остаются статистами.
     */
    static void adoptLegacy(SectDisciple npc, ServerLevel level) {
        Optional<SectRoster> m = SectRoster.legacy(npc.role());
        SectLayout layout = npc.layout();
        if (m.isEmpty() || layout == null) {
            return;
        }
        boolean onMountain = m.get().role() == SectRole.GATEKEEPER ? layout.inside("gate", npc.position(), 24.0D) || layout.inside("sect_gate", npc.position(), 24.0D)
                : layout.inside("sect_gate", npc.position(), 24.0D) || layout.inside("training", npc.position(), 24.0D);
        if (!onMountain) {
            return;
        }
        npc.setMember(m.get());
        data(level).place("npc:" + m.get().key());
        MurimMod.LOGGER.info("Секта Хуашань: NPC первой версии ({}) — теперь {}", npc.role().id(), m.get().key());
    }

    /** Двое с одним ключом в одной секте (старое сохранение, команда) — младший уходит. */
    private static boolean duplicate(SectDisciple npc, ServerLevel level) {
        for (SectDisciple other : level.getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().inflate(256.0D),
                o -> o != npc && o.memberKey().equals(npc.memberKey())
                        // Separate yards must not delete each other's guides or live mentor targets.
                        // Source: SectEscorts#sameLayout, also used by arrival and destination lookup.
                        && SectEscorts.sameLayout(npc.layout(), o.layout()))) {
            if (other.getUUID().compareTo(npc.getUUID()) < 0) {
                MurimMod.LOGGER.info("Секта: второй {} убран", npc.memberKey());
                npc.discard();
                return true;
            }
        }
        return false;
    }

    /** На ученика (или рядом с ним) напал чужой моб: все бойцы в 24 блоках встают на защиту. */
    public static void alarm(SectDisciple victim, Mob attacker) {
        for (SectDisciple d : victim.level().getEntitiesOfClass(SectDisciple.class, victim.getBoundingBox().inflate(24.0D),
                d -> d.member().isPresent() && d.free() && !d.role().lay())) {
            d.defend(attacker);
        }
    }

    /** A person on the watch sees a hostile creature in the grounds from this far (with a line of sight). */
    public static final double WATCH_RANGE = 24.0D;
    /** The same creature raises the alarm only once in this many ticks. */
    public static final long MONSTER_COOL = 600L;

    /** A hostile creature the sect answers: monsters and bandits, but not a peaceful guest (quest folk, a trader, a calm visitor). */
    static boolean threat(Mob e) {
        if (!(e instanceof Enemy) || e instanceof SectDisciple || !e.isAlive()) return false;
        return !(e instanceof io.github.verycooltimo.murim.npc.GuestFighter g) || g.hostile();
    }

    /**
     * Night-1: any awake sect member sees a hostile creature from {@link #WATCH_RANGE} blocks and raises the whole
     * mountain ({@link SectAlarm.Kind#MONSTER}: sleepers wake, the runners come) instead of waiting for it at ten blocks. The fighters
     * then go for it as before.
     */
    static void watchMonsters(SectDisciple npc, ServerLevel level) {
        if (npc.member().isEmpty() || npc.isSleeping() || npc.doingKind() == SectSchedule.Kind.SLEEP || npc.dormant() || npc.wounded()) {
            return;
        }
        long now = level.getGameTime();
        var seen = data(level).reportedMonsters;
        for (Mob enemy : level.getEntitiesOfClass(Mob.class, npc.getBoundingBox().inflate(WATCH_RANGE),
                e -> threat(e))) {
            if (npc.distanceToSqr(enemy) > WATCH_RANGE * WATCH_RANGE || !npc.getSensing().hasLineOfSight(enemy)) {
                continue;
            }
            Long last = seen.get(enemy.getUUID());
            if (last != null && now - last < MONSTER_COOL) {
                continue;
            }
            if (seen.size() > 512) {
                seen.entrySet().removeIf(e -> now - e.getValue() > MONSTER_COOL);
            }
            SectChatter.sayIfQuiet(npc, SectBubbles.Group.ALARM_MONSTER.pick(npc.getRandom().nextInt(1 << 16)), 20);
            if (SectAlarm.raise(level, enemy.position(), SectAlarm.Kind.MONSTER) > 0) {
                seen.put(enemy.getUUID(), now);
            }
            alarm(npc, enemy);
            return;
        }
    }

    /** For tests: forget the creatures already reported. */
    public static void forgetMonsters(ServerLevel level) {
        data(level).reportedMonsters.clear();
    }

    public static void onDeath(SectDisciple npc) {
        if (npc.level() instanceof ServerLevel level) {
            data(level).unplace("npc:" + npc.memberKey());
        }
    }

    // ------------------------------------------------------------------ поединки учеников

    /**
     * Ученик на месте в ринге: если партнёр тоже на месте и оба отдохнули — поединок (первым по
     * списку начинает тот, у кого номер меньше, чтобы пара не стартовала дважды).
     */
    public static void tryPair(SectDisciple npc, String partnerKey) {
        if (partnerKey.isEmpty() || npc.resting() || !npc.free() || npc.wounded()) {
            return;
        }
        int mine = npc.member().map(SectRoster::index).orElse(-1);
        int theirs = SectRoster.of(partnerKey).map(SectRoster::index).orElse(-1);
        if (mine < 0 || theirs < 0 || mine > theirs) {
            return;
        }
        for (SectDisciple other : npc.level().getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().inflate(9.0D),
                o -> o.memberKey().equals(partnerKey))) {
            if (other.free() && !other.resting() && !other.wounded() && other.distanceTo(npc) < 7.0D && other.getNavigation().isDone()) {
                npc.sparWith(other, 10);
            }
            return;
        }
    }

    // ------------------------------------------------------------------ кровати

    /**
     * Печь лекаря на площадке алхимии, если автор её поставил: печь, коптильня, плавильня, костёр, котёл, варочная
     * стойка (ближайшая). null — нет: лекарь варит на своём месте.
     */
    public static BlockPos findStove(SectDisciple npc, String zone) {
        return SectBuildings.furniture(npc, zone, SectBuildings.Kind.STOVE);
    }

    /**
     * The bed to sleep in. In the built sect it is the person's own bed ({@code owner_key}, else role and slot), so
     * everybody finds the same room every night; elsewhere (test yards) the nearest free bed of the zone.
     */
    public static BlockPos findBed(SectDisciple npc, String zone) {
        SectLayoutFile file = file(npc);
        Optional<SectRoster> m = npc.member();
        if (file != null && m.isPresent() && npc.level() instanceof ServerLevel server) {
            SectLayoutFile.Bed own = SectPlaces.bed(file, m.get());
            if (own != null && server.isLoaded(own.head())) {
                BlockState state = server.getBlockState(own.head());
                if (state.getBlock() instanceof BedBlock && state.getValue(BedBlock.PART) == BedPart.HEAD
                        && (!state.getValue(BedBlock.OCCUPIED) || npc.isSleeping())
                        && SectRooms.claimOwn(server, own.head(), SectRooms.owner(npc))) {
                    return own.head();
                }
            }
        }
        return SectBuildings.furniture(npc, zone, SectBuildings.Kind.BED);
    }

    /** The cell beside the bed from where the person lies down: from the layout, else the nearest free neighbour. */
    public static Vec3 bedApproach(SectDisciple npc, BlockPos bed) {
        SectLayoutFile file = file(npc);
        SectLayoutFile.Bed b = file == null ? null : file.bedAt(bed);
        if (b != null && npc.level().isLoaded(b.approach())) {
            return stand(npc.level(), Vec3.atBottomCenterOf(b.approach()));
        }
        return SectBuildings.approach(npc, bed);
    }

    /** After getting up: still on top of the bed (vanilla found no free cell beside it) means the approach cell. */
    public static void standBesideBed(SectDisciple npc, BlockPos bed) {
        double onTop = npc.getY() - bed.getY();
        if (onTop < 0.3D || onTop > 1.2D || Math.abs(npc.getX() - (bed.getX() + 0.5D)) > 0.9D || Math.abs(npc.getZ() - (bed.getZ() + 0.5D)) > 0.9D) {
            return;
        }
        Vec3 to = bedApproach(npc, bed);
        if (to != null && npc.level().noCollision(npc, npc.getBoundingBox().move(to.subtract(npc.position())).deflate(0.05D))) {
            npc.moveTo(to.x, to.y, to.z, npc.getYRot(), 0.0F);
        }
    }

    // ------------------------------------------------------------------ мир: люди, колокол, ворота

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 5 == 2) {
            for (ServerLevel l : event.getServer().getAllLevels()) {
                SectDoors.sweep(l);
            }
        }
        MountHuaSite site = MountHuaSites.get(event.getServer());
        if (site == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        SectLayout layout = SectLayout.hua(site);
        SectSiteData data = data(level);
        int tick = event.getServer().getTickCount();
        if (event.getServer().getTickCount() % 20 == 0) {
            ServerLevel teachingLevel = event.getServer().overworld();
            SectLessons.of(teachingLevel).sample(teachingLevel, members(teachingLevel), teachingLevel.players());
        }
        bell(level, layout, data);
        if (tick % 40 == 17) {
            spawnRoster(level, layout, data);
            for (ServerPlayer p : level.players()) {
                SectService.trackClimb(p, site);
            }
        }
        // The training yard (Track C): dummies on the poles ground, put back by the sect when a restart or an unloaded chunk dropped them.
        if (tick % 100 == 31) {
            SectDoors.adoptLayout(level, SectLayoutFile.anchored(site));
        }
        if (tick % 200 == 57) {
            SectYard.ensure(level, layout, true);
        }
    }

    /** How long a person may be kept from standing up in view of a player before he does (30 s; holes review robust-2). */
    static final long POP_IN_WAIT = 600L;
    /** Roster mark -> game time since which a player has been within 32 blocks of his spot in sight of it. */
    private static final java.util.Map<String, Long> POP_BLOCKED = new java.util.HashMap<>();

    /** Server stopped: forget cross-world state. */
    public static void clearPopBlocked() { POP_BLOCKED.clear(); }

    /**
     * A person does not appear in front of a player's eyes, but a player who waits (came by a waystone, respawned, pearled to the gate)
     * must not find the gatekeeper missing for good: he appears where the player cannot see the spot, or after {@link #POP_IN_WAIT}.
     */
    static boolean mayAppear(ServerLevel level, Vec3 guess, Player close, String mark) {
        Vec3 eye = close.getEyePosition();
        Vec3 target = guess.add(0.0D, 1.0D, 0.0D);
        boolean seen = level.clip(new net.minecraft.world.level.ClipContext(eye, target, net.minecraft.world.level.ClipContext.Block.VISUAL,
                net.minecraft.world.level.ClipContext.Fluid.NONE, close)).getType() == net.minecraft.world.phys.HitResult.Type.MISS;
        if (!seen) {
            return true;
        }
        long now = level.getGameTime();
        Long since = POP_BLOCKED.get(mark);
        if (since == null || since > now) {
            POP_BLOCKED.put(mark, now);
            return false;
        }
        return now - since >= POP_IN_WAIT;
    }

    /**
     * Люди встают на гору, когда рядом игрок (дом — в 96 блоках), но не у него на глазах (никого ближе
     * 32 блоков к месту). Отметка «уже стоит» — в {@link SectSiteData}: генерацию горы не трогаем.
     */
    static void spawnRoster(ServerLevel level, SectLayout layout, SectSiteData data) {
        for (SectRoster m : SectRoster.ALL) {
            String mark = "npc:" + m.key();
            if (data.placed(mark) || SectWar.isDead(level, m.key())) {
                continue;
            }
            long time = level.getDayTime();
            SectSchedule.Task t = SectSchedule.task(m, time);
            Vec3 guess = spawnGuess(layout, m, t, time);
            if (guess == null) {
                continue;
            }
            BlockPos at = BlockPos.containing(guess);
            if (!level.isPositionEntityTicking(at)) {
                continue;
            }
            Player near = level.getNearestPlayer(guess.x, guess.y, guess.z, 96.0D, false);
            Player close = level.getNearestPlayer(guess.x, guess.y, guess.z, 32.0D, false);
            // The day simulation (-Dmurim.sect.timings) has no player: the roster stands up in the force-loaded sect by itself.
            if (!SectTimings.ON && (near == null || close != null && !mayAppear(level, guess, close, mark))) {
                continue;
            }
            POP_BLOCKED.remove(mark);
            // Уже стоит (старое сохранение с ролью без имени получает ключ сам — см. adoptLegacy).
            boolean present = !level.getEntitiesOfClass(SectDisciple.class, new AABB(at).inflate(200.0D),
                    d -> d.memberKey().equals(m.key())).isEmpty();
            if (!present) {
                Vec3 spot = stand(level, guess);
                SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                npc.setMember(m);
                SectWar.dress(level, npc);
                float yaw = layout.yaw(t.faceU(), t.faceV());
                npc.moveTo(spot.x, spot.y, spot.z, yaw, 0.0F);
                npc.setYHeadRot(yaw);
                npc.setYBodyRot(yaw);
                level.addFreshEntity(npc);
                MurimMod.LOGGER.info("Секта Хуашань: {} встал на площадке {} ({})", m.key(), t.zone(), BlockPos.containing(spot));
            }
            data.place(mark);
        }
    }

    /**
     * Колокол на колокольне бьёт три раза в начале каждой части суток. Если автор поставил на
     * площадке ванильный колокол — качается он сам, иначе звук идёт с площадки.
     */
    static void bell(ServerLevel level, SectLayout layout, SectSiteData data) {
        long time = level.getDayTime();
        int since = SectSchedule.sincePeriodStart(time);
        if (since > 60 || since % 30 != 0) {
            return;
        }
        long key = (SectSchedule.day(time) * 8L + SectSchedule.at(time).ordinal()) * 4L + since / 30;
        if (key == data.lastRing) {
            return;
        }
        data.lastRing = key;
        Vec3 c = layout.at("bell", 0.0D, 0.0D);
        if (c == null || level.getNearestPlayer(c.x, c.y, c.z, 160.0D, false) == null || !level.isLoaded(BlockPos.containing(c))) {
            return;
        }
        strikeBell(level, c);
        if (since == 0) {
            MurimMod.LOGGER.info("Секта Хуашань: колокол — {}", SectSchedule.at(time).id());
        }
    }

    /** The bell rings out the war alarm (a blow on an elder, a killing): the same bell, the same sound, any time of day. */
    public static void alarmBell(ServerLevel level) {
        SectLayout layout = layout(level);
        Vec3 c = layout == null ? null : layout.at("bell", 0.0D, 0.0D);
        if (c != null && level.isLoaded(BlockPos.containing(c))) {
            strikeBell(level, c);
        }
    }

    private static void strikeBell(ServerLevel level, Vec3 c) {
        BlockPos bell = null;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(c.x - 5, c.y - 1, c.z - 5), BlockPos.containing(c.x + 5, c.y + 14, c.z + 5))) {
            if (level.getBlockState(p).is(Blocks.BELL) || level.getBlockState(p).getBlock() instanceof io.github.verycooltimo.murim.decor.SectBellBlock) {
                bell = p.immutable();
                break;
            }
        }
        if (bell != null && level.getBlockState(bell).getBlock() instanceof BellBlock b) {
            b.attemptToRing(level, bell, Direction.NORTH);
        }
        // Бронзовый колокол секты (decor/HallFurniture): качается и звучит своим звуком вместо ванильного.
        if (bell != null && level.getBlockState(bell).getBlock() instanceof io.github.verycooltimo.murim.decor.SectBellBlock) {
            io.github.verycooltimo.murim.decor.SectBellBlock.ring(level, bell, Direction.NORTH, null, false);
            level.playSound(null, bell, io.github.verycooltimo.murim.decor.HallFurniture.SECT_BELL_SOUND.get(), SoundSource.BLOCKS, 6.0F, 1.0F);
        } else {
            // Колокол слышно на всей полке: громкость > 1 растягивает дальность звука (16 блоков × громкость).
            BlockPos from = bell != null ? bell : BlockPos.containing(c.x, c.y + 6, c.z);
            level.playSound(null, from, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 6.0F, 0.6F);
        }
    }

    // ------------------------------------------------------------------ игрок в утреннем строю

    /**
     * Форма {@code formIndex} основы игрока (из {@code FoundationService#onSwing}): в рассветный строй на площади
     * засчитывается одна форма за такт, если удар в такт со строем. Десять — «Утренняя тренировка»: отработанные формы
     * учатся ({@link #teachDrilledForms}; план §4.2, §6.1, Track C T3).
     */
    public static void onPlayerForm(ServerPlayer player, int formIndex) {
        // Испытание двери тайника: форма основы — ступень лестницы (seal/VaultTrial).
        player.getData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT).foundation().ifPresent(f -> io.github.verycooltimo.murim.sect.seal.VaultTrial
                .onFoundation(player, f, player.getData(io.github.verycooltimo.murim.registry.ModAttachments.FOUNDATION_SWING)[0]));
        SectLayout layout = layout(player.serverLevel());
        if (layout == null) {
            return;
        }
        onPlayerForm(player, layout, formIndex);
    }

    public static void onPlayerForm(ServerPlayer player, SectLayout layout) {
        onPlayerForm(player, layout, player.getData(io.github.verycooltimo.murim.registry.ModAttachments.FOUNDATION_SWING)[0]);
    }

    public static void onPlayerForm(ServerPlayer player, SectLayout layout, int formIndex) {
        long time = player.level().getDayTime();
        if (SectSchedule.at(time) != SectSchedule.Period.FORMATION || !inFormation(layout, player.position())) {
            return;
        }
        long day = SectSchedule.day(time);
        CompoundTag tag = player.getPersistentData().getCompound(MORNING_TAG);
        if (tag.getLong("day") != day) {
            tag = new CompoundTag();
            tag.putLong("day", day);
        }
        if (tag.getBoolean("done")) {
            return;
        }
        long now = player.level().getGameTime();
        long beat = SectSchedule.beat(now);
        if (!SectSchedule.onBeat(now)) {
            SectLessons.of(player.serverLevel()).miss(player, now);
            PlayerJournal.refuse(player, Component.translatable("murim.sect.morning.offbeat").withStyle(ChatFormatting.GRAY));
            player.getPersistentData().put(MORNING_TAG, tag);
            return;
        }
        if (tag.getLong("beat") == beat && tag.getInt("count") > 0) {
            return;
        }
        int count = tag.getInt("count") + 1;
        tag.putInt("count", count);
        tag.putLong("beat", beat);
        // Какая форма какого искусства отработана в такт: за неё строй и платит (T3).
        Optional<net.minecraft.resources.ResourceLocation> art = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT).foundation();
        if (art.isPresent()) {
            net.minecraft.nbt.ListTag drilled = tag.getList("drilled", net.minecraft.nbt.Tag.TAG_STRING);
            String key = art.get() + "#" + formIndex;
            boolean known = false;
            for (net.minecraft.nbt.Tag t : drilled) {
                known |= t.getAsString().equals(key);
            }
            if (!known) {
                drilled.add(net.minecraft.nbt.StringTag.valueOf(key));
            }
            tag.put("drilled", drilled);
        }
        if (count >= MORNING_FORMS) {
            tag.putBoolean("done", true);
            teachDrilledForms(player, tag);
            MurimMod.LOGGER.info("Секта Хуашань: {} — утренняя тренировка засчитана", player.getName().getString());
            SectNotice.bar(player, Component.translatable("murim.sect.morning.done").withStyle(ChatFormatting.GOLD));
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 1.4F);
            SectService.contribute(player, SectService.MORNING_CONTRIBUTION);
            // Журнал секты: строй отмечен (распорядок для игрока, автор 05.10).
            SectAttendance.record(player, SectAttendance.Activity.FORMATION);
            // A first disciple called to lead the line: the line is led (docs/design/38, «Совет и приглашения»).
            SectOffices.formationDone(player);
            SectLessons.of(player.serverLevel()).completed(player);
            for (SectDisciple d : player.level().getEntitiesOfClass(SectDisciple.class, player.getBoundingBox().inflate(20.0D),
                    d -> d.role() == SectRole.MENTOR)) {
                d.gesture("nod");
            }
        }
        player.getPersistentData().put(MORNING_TAG, tag);
    }

    /**
     * Read-only state of the morning drill for {@code /murim progress}: {count, done, inZone}, or null when it is not the
     * formation hour or the world has no sect layout.
     */
    public static int[] morningStatus(ServerPlayer player) {
        return morningStatus(player, layout(player.serverLevel()));
    }

    /** The same read-only query on an explicit layout, as used by the drill GameTests. */
    public static int[] morningStatus(ServerPlayer player, SectLayout layout) {
        long time = player.level().getDayTime();
        if (layout == null || SectSchedule.at(time) != SectSchedule.Period.FORMATION) {
            return null;
        }
        CompoundTag tag = player.getPersistentData().getCompound(MORNING_TAG);
        boolean today = tag.getLong("day") == SectSchedule.day(time);
        return new int[]{today ? tag.getInt("count") : 0, today && tag.getBoolean("done") ? 1 : 0,
                inFormation(layout, player.position()) ? 1 : 0};
    }

    /** Точка в строю: ряды на площади с запасом в два блока. */
    public static boolean inFormation(SectLayout layout, Vec3 pos) {
        SectLayoutFile file = layout.file();
        SectLayoutFile.Rect area = file == null ? null : SectPlaces.formationArea(file, 2);
        if (area != null) {
            var platform = file.zone("ceremony_platform");
            return area.contains(Mth.floor(pos.x), Mth.floor(pos.z)) && (platform == null || Math.abs(pos.y - platform.y()) < 3.0D);
        }
        double[] l = layout.local("training", pos);
        if (l == null) {
            return false;
        }
        double halfU = (SectSchedule.COLUMNS - 1) / 2.0D * SectSchedule.SPACING + 2.0D;
        double front = SectSchedule.FRONT_ROW + 2.0D;
        double back = SectSchedule.FRONT_ROW - (SectSchedule.rows() - 1) * SectSchedule.SPACING - 2.0D;
        return Math.abs(l[0]) <= halfU && l[1] <= front && l[1] >= back;
    }

    /**
     * Строй учит то, что отрабатывали: каждому искусству, формы которого сделаны в такт, — доля следующей звезды
     * ({@link #MORNING_SHARE}), больше за разные формы. Через обычный путь освоения: предел и стена озарения держат.
     */
    static void teachDrilledForms(ServerPlayer player, CompoundTag tag) {
        java.util.Map<net.minecraft.resources.ResourceLocation, java.util.Set<String>> byArt = new java.util.LinkedHashMap<>();
        for (net.minecraft.nbt.Tag t : tag.getList("drilled", net.minecraft.nbt.Tag.TAG_STRING)) {
            String[] parts = t.getAsString().split("#", 2);
            net.minecraft.resources.ResourceLocation art = net.minecraft.resources.ResourceLocation.tryParse(parts[0]);
            if (art != null && parts.length == 2) {
                byArt.computeIfAbsent(art, a -> new java.util.LinkedHashSet<>()).add(parts[1]);
            }
        }
        byArt.forEach((art, forms) -> {
            int stars = io.github.verycooltimo.murim.mastery.MasteryService.stars(player, art);
            if (stars < 0) {
                return;
            }
            double share = MORNING_SHARE + MORNING_PER_FORM * Math.min(MORNING_EXTRA_FORMS, forms.size() - 1);
            io.github.verycooltimo.murim.mastery.MasteryService.study(player, art,
                    io.github.verycooltimo.murim.mastery.MasteryRules.need(stars) * share);
        });
    }

    /** Прибавка освоения за усердие: столько дней подряд всё, чего ждала секта, сделано ({@link SectAttendance}). */
    public static final double DILIGENCE_BONUS = 1.05D;

    /**
     * Множитель освоения: при усердии ({@link SectAttendance#STREAK_DAYS} полных дней подряд) — {@link #DILIGENCE_BONUS}, при
     * подвозе припасов — ещё ×1,05. Утренний строй общего множителя больше не даёт: он учит отработанные формы
     * ({@link #teachDrilledForms}).
     */
    public static double masteryBonus(ServerPlayer player) {
        double bonus = 1.0D;
        if (player.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT_ATTENDANCE).streak() >= SectAttendance.STREAK_DAYS) {
            bonus *= DILIGENCE_BONUS;
        }
        if (player.level().dimension() == net.minecraft.world.level.Level.OVERWORLD
                && player.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT).member()
                && data(player.serverLevel()).suppliedUntil() > SectSchedule.day(player.level().getDayTime())) bonus *= 1.05D;
        return bonus;
    }

    /**
     * Стенд и команда {@code /murim dev sched settle}: недостающие люди встают сразу (даже на глазах), все
     * переходят на места текущей части суток. В игре так не бывает — только для съёмки и проверки.
     *
     * @return сколько людей секты на горе
     */
    public static int settleAll(ServerLevel level) {
        SectLayout layout = layout(level);
        if (layout == null) {
            return 0;
        }
        SectSiteData data = data(level);
        for (SectRoster m : SectRoster.ALL) {
            data.unplace("npc:" + m.key());
        }
        spawnRosterNow(level, layout, data);
        int n = 0;
        for (SectDisciple d : members(level)) {
            Resolved r = resolve(d);
            if (r == null || !level.isPositionEntityTicking(BlockPos.containing(r.spot()))) {
                continue;
            }
            d.stopBout();
            d.wake();
            d.moveTo(r.spot().x, r.spot().y, r.spot().z, r.yaw(), 0.0F);
            d.setYHeadRot(r.yaw());
            d.setYBodyRot(r.yaw());
            d.getNavigation().stop();
            n++;
        }
        return n;
    }

    private static void spawnRosterNow(ServerLevel level, SectLayout layout, SectSiteData data) {
        for (SectRoster m : SectRoster.ALL) {
            if (SectWar.isDead(level, m.key())) {
                continue;
            }
            long time = level.getDayTime();
            SectSchedule.Task t = SectSchedule.task(m, time);
            Vec3 guess = spawnGuess(layout, m, t, time);
            if (guess == null || !level.isPositionEntityTicking(BlockPos.containing(guess))) {
                continue;
            }
            boolean present = !level.getEntitiesOfClass(SectDisciple.class, new AABB(BlockPos.containing(guess)).inflate(300.0D),
                    d -> d.memberKey().equals(m.key())).isEmpty();
            if (!present) {
                Vec3 spot = stand(level, guess);
                SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                npc.setMember(m);
                SectWar.dress(level, npc);
                npc.moveTo(spot.x, spot.y, spot.z, layout.yaw(t.faceU(), t.faceV()), 0.0F);
                level.addFreshEntity(npc);
            }
            data.place("npc:" + m.key());
        }
    }

    /** Отчёт для команды: кто где и что делает. */
    public static List<String> report(ServerLevel level) {
        List<String> out = new java.util.ArrayList<>();
        long time = level.getDayTime();
        out.add("period=" + SectSchedule.at(time).id() + " day=" + SectSchedule.day(time) + " time=" + Math.floorMod(time, 24000L));
        for (SectDisciple d : members(level)) {
            Resolved r = resolve(d);
            out.add(String.format("%s %s %s at %s spot %s%s%s%s%s", d.memberKey(), r == null ? "-" : r.task().kind(),
                    r == null ? "" : r.task().zone(), d.blockPosition().toShortString(),
                    r == null ? "-" : BlockPos.containing(r.spot()).toShortString(), d.dormant() ? " dormant" : "",
                    d.spar() != SectDisciple.Spar.NONE ? " spar:" + d.spar() : "",
                    r == null || r.spotId() == null ? "" : " place=" + r.spotId() + (r.room() == null ? "" : "/" + r.room()),
                    (d.sitting() ? " sitting" : "") + (d.isSleeping() ? " asleep" : "")
                            + (d.pose() == io.github.verycooltimo.murim.entity.SectPose.NONE ? "" : " pose=" + d.pose().id())));
        }
        return out;
    }
}
