package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
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
 * {@link SectDisciple#AWAKE_RANGE} от игроков ИИ спит, а человек переходит к делу без ходьбы, раз в
 * секунду. Чанки не держатся загруженными.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectLife {

    /** Сколько форм в такт нужно для «Утренней тренировки» (план §4.2: «повторил 10 форм»). */
    public static final int MORNING_FORMS = 10;
    /** Прибавка освоения на день после утренней тренировки. */
    public static final double MORNING_BONUS = 1.15D;
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
                return new Vec3(guess.x, b.getY(), guess.z);
            }
        }
        return guess;
    }

    static boolean standable(Level level, BlockPos b) {
        BlockState below = level.getBlockState(b.below());
        return !below.getCollisionShape(level, b.below()).isEmpty()
                && level.getBlockState(b).getCollisionShape(level, b).isEmpty()
                && level.getBlockState(b.above()).getCollisionShape(level, b.above()).isEmpty()
                && level.getFluidState(b).isEmpty();
    }

    /** Задание с точкой мира и поворотом. */
    public record Resolved(SectSchedule.Task task, Vec3 spot, float yaw) {
    }

    /** Что сейчас делает человек и где. null — не человек секты или нет раскладки. */
    public static Resolved resolve(SectDisciple npc) {
        Optional<SectRoster> m = npc.member();
        SectLayout layout = npc.layout();
        if (m.isEmpty() || layout == null) {
            return null;
        }
        long time = npc.level().getDayTime();
        SectSchedule.Task t = SectSchedule.task(m.get(), time);
        // Разница во времени (автор 06.10): в начале части суток каждый ещё немного доделывает прежнее — из-за стола,
        // со сна, в строй встают не в один тик. Пост, поединок и смотр — по колоколу: там ждут друг друга.
        long late = SectStagger.scheduleTime(m.get(), time);
        if (late != time && !SectRota.onDuty(m.get(), time) && !SectRota.onDuty(m.get(), late)
                && !SectReview.window(time) && !SectReview.window(late)) {
            SectSchedule.Task before = SectSchedule.task(m.get(), late);
            if (before.kind() != SectSchedule.Kind.SPAR) {
                t = before;
            }
        }
        // Чужак у ворот: глава выходит навстречу и принимает без экзамена (автор 04.10).
        if (m.get().role() == SectRole.LEADER && npc.level() instanceof ServerLevel server && data(server).outsiderAtGate) {
            t = new SectSchedule.Task(SectSchedule.Kind.GREET, "sect_gate", 2.0D, 1.0D, 0.0D, -1.0D);
        }
        // Смотр учеников: боец сетки идёт на своё место в ринге (SectReview).
        SectSchedule.Task fight = SectReview.fighterTask(npc);
        if (fight != null) {
            t = fight;
        }
        // Раненый после поединка сидит, где упал, и ждёт лекаря.
        if (fight == null && npc.wounded()) {
            Vec3 at = npc.woundSpot();
            return new Resolved(new SectSchedule.Task(SectSchedule.Kind.WAIT_TREAT, t.zone(), 0.0D, 0.0D, 0.0D, 0.0D), at, npc.getYRot());
        }
        // Лекарь за своим делом днём — к ближайшему раненому (кроме совета и ночи).
        if ("un_gak".equals(m.get().key()) && (t.kind() == SectSchedule.Kind.BREW || t.kind() == SectSchedule.Kind.GRIND
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
        // Раненый игрок на земле секты (живая гора, автор 05.10): Ун Гак днём за своим делом идёт перевязать.
        if ("un_gak".equals(m.get().key()) && !npc.dormant() && healerFree(t.kind()) && SectSchedule.at(time) != SectSchedule.Period.NIGHT) {
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
        } else if ("un_gak".equals(m.get().key()) && npc.healingPlayer() >= 0) {
            npc.setHealingPlayer(-1);
        }
        // Смена поста: сменяемый ждёт сменщика на посту, пока они не поклонятся друг другу (или окно не выйдет).
        Optional<SectRota.Duty> ending = SectRota.ending(m.get(), time);
        if (ending.isPresent() && handoverPending(npc, m.get())) {
            t = SectSchedule.post(ending.get());
        }
        Vec3 guess = layout.at(t.zone(), t.du(), t.dv());
        if (guess == null) {
            return null;
        }
        Vec3 spot = npc.level().isLoaded(BlockPos.containing(guess)) ? stand(npc.level(), guess) : guess;
        // Дождь: дело под открытым небом прервано — под крышу рядом (SectWeather).
        return SectWeather.apply(npc, new Resolved(t, spot, layout.yaw(t.faceU(), t.faceV())));
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
                d -> d != healer && d.isAlive() && d.wounded() && d.spar() == SectDisciple.Spar.NONE)) {
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
        return SectRota.onDuty(m.get(), d.level().getDayTime()) || handoverPending(d, m.get());
    }

    /**
     * Носильщик донёс груз до кладовой: Хён Ён у стола (или управляющий) поворачивается к нему и кивает — принял;
     * носильщик кланяется.
     */
    public static void delivered(SectDisciple porter) {
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        for (SectDisciple d : porter.level().getEntitiesOfClass(SectDisciple.class, porter.getBoundingBox().inflate(12.0D),
                d -> ("hyun_young".equals(d.memberKey()) || d.role() == SectRole.STEWARD) && d.free() && !d.dormant())) {
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
                    e -> e instanceof Enemy && !(e instanceof SectDisciple) && e.isAlive())) {
                alarm(npc, enemy);
                break;
            }
        }
        if (npc.dormant()) {
            settleUnseen(npc, level);
        }
    }

    /**
     * Далеко от игроков человек не ходит, а сразу оказывается на месте распорядка (его никто не видит).
     * Только если точка в тикающем чанке: чанки ради NPC не грузим.
     */
    static void settleUnseen(SectDisciple npc, ServerLevel level) {
        Resolved r = resolve(npc);
        if (r == null) {
            return;
        }
        BlockPos at = BlockPos.containing(r.spot());
        if (!level.isPositionEntityTicking(at)) {
            return;
        }
        SectSchedule.Kind kind = r.task().kind();
        if (kind != SectSchedule.Kind.SLEEP && npc.isSleeping()) {
            npc.stopSleeping();
        }
        if (npc.position().distanceToSqr(r.spot()) > 9.0D) {
            if (npc.isSleeping()) {
                npc.stopSleeping();
            }
            npc.moveTo(r.spot().x, r.spot().y, r.spot().z, r.yaw(), 0.0F);
            npc.setYHeadRot(r.yaw());
            npc.setYBodyRot(r.yaw());
            npc.getNavigation().stop();
        }
        npc.sit(kind.seated());
        // Невидимый никому раненый заживает сам: лекарь не ходит к тем, кого никто не видит.
        if (npc.wounded()) {
            npc.setWounded(false);
        }
        // Поза дела сразу на месте (анимации секты, entity/SectPose): издалека люди не стоят столбом.
        npc.setPose(io.github.verycooltimo.murim.entity.SectPose.forTask(kind, true,
                io.github.verycooltimo.murim.entity.SectPose.carrier(npc)));
    }

    /**
     * NPC первой версии (роль без имени, поставлены на площадки «gate», «sect_gate», «training»)
     * становятся людьми из списка, а не дублями. Только на горе: NPC от {@code /murim sect spawn} в
     * другом месте остаются статистами.
     */
    static void adoptLegacy(SectDisciple npc, ServerLevel level) {
        Optional<SectRoster> m = SectRoster.legacy(npc.role());
        SectLayout layout = npc.layout();
        if (m.isEmpty() || layout == null) {
            return;
        }
        boolean onMountain = m.get().role() == SectRole.GATEKEEPER ? layout.inside("gate", npc.position(), 24.0D)
                : layout.inside("sect_gate", npc.position(), 24.0D) || layout.inside("training", npc.position(), 24.0D);
        if (!onMountain) {
            return;
        }
        npc.setMember(m.get());
        data(level).place("npc:" + m.get().key());
        MurimMod.LOGGER.info("Секта Хуашань: NPC первой версии ({}) — теперь {}", npc.role().id(), m.get().key());
    }

    /** Двое с одним ключом (старое сохранение, команда) — младший уходит. */
    private static boolean duplicate(SectDisciple npc, ServerLevel level) {
        for (SectDisciple other : level.getEntitiesOfClass(SectDisciple.class, npc.getBoundingBox().inflate(256.0D),
                o -> o != npc && o.memberKey().equals(npc.memberKey()))) {
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
        SectLayout layout = npc.layout();
        double[] h = layout == null ? null : layout.half(zone);
        Vec3 c = layout == null ? null : layout.at(zone, 0.0D, 0.0D);
        if (h == null || c == null) {
            return null;
        }
        Level level = npc.level();
        double r = Math.max(h[0], h[1]);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(c.x - r, c.y - 2, c.z - r),
                BlockPos.containing(c.x + r, c.y + 6, c.z + r))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            boolean stove = s.is(Blocks.FURNACE) || s.is(Blocks.BLAST_FURNACE) || s.is(Blocks.SMOKER) || s.is(Blocks.CAMPFIRE)
                    || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.BREWING_STAND)
                    || s.getBlock() instanceof net.minecraft.world.level.block.AbstractCauldronBlock;
            if (stove && layout.inside(zone, Vec3.atCenterOf(p), 1.0D)) {
                double d = p.distToCenterSqr(npc.position());
                if (d < bestDist) {
                    bestDist = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    /** Свободная кровать (изголовье) на площадке общежития; null — кроватей нет. */
    public static BlockPos findBed(SectDisciple npc, String zone) {
        SectLayout layout = npc.layout();
        double[] h = layout == null ? null : layout.half(zone);
        Vec3 c = layout == null ? null : layout.at(zone, 0.0D, 0.0D);
        if (h == null || c == null) {
            return null;
        }
        Level level = npc.level();
        double r = Math.max(h[0], h[1]);
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(c.x - r, c.y - 2, c.z - r),
                BlockPos.containing(c.x + r, c.y + 8, c.z + r))) {
            if (!level.isLoaded(p)) {
                continue;
            }
            BlockState s = level.getBlockState(p);
            if (s.getBlock() instanceof BedBlock && s.getValue(BedBlock.PART) == BedPart.HEAD && !s.getValue(BedBlock.OCCUPIED)
                    && layout.inside(zone, Vec3.atCenterOf(p), 1.0D)) {
                double d = p.distToCenterSqr(npc.position()) + (npc.getRandom().nextDouble() * 0.01D);
                if (d < bestDist) {
                    bestDist = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ мир: люди, колокол, ворота

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        MountHuaSite site = MountHuaSites.get(event.getServer());
        if (site == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        SectLayout layout = SectLayout.hua(site);
        SectSiteData data = data(level);
        int tick = event.getServer().getTickCount();
        bell(level, layout, data);
        if (tick % 20 == 3) {
            data.outsiderAtGate = outsiderNear(level, layout);
        }
        if (tick % 40 == 17) {
            spawnRoster(level, layout, data);
            for (ServerPlayer p : level.players()) {
                SectService.trackClimb(p, site);
            }
        }
    }

    /** Не ученик секты в 40 блоках от ворот секты. */
    static boolean outsiderNear(ServerLevel level, SectLayout layout) {
        Vec3 gate = layout.at("sect_gate", 0.0D, 0.0D);
        if (gate == null) {
            return false;
        }
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && !SectService.state(p).member() && p.position().distanceToSqr(gate) < 40.0D * 40.0D) {
                return true;
            }
        }
        return false;
    }

    /**
     * Люди встают на гору, когда рядом игрок (дом — в 96 блоках), но не у него на глазах (никого ближе
     * 12 блоков к месту). Отметка «уже стоит» — в {@link SectSiteData}: генерацию горы не трогаем.
     */
    static void spawnRoster(ServerLevel level, SectLayout layout, SectSiteData data) {
        for (SectRoster m : SectRoster.ALL) {
            String mark = "npc:" + m.key();
            if (data.placed(mark)) {
                continue;
            }
            long time = level.getDayTime();
            SectSchedule.Task t = SectSchedule.task(m, time);
            Vec3 guess = layout.at(t.zone(), t.du(), t.dv());
            if (guess == null) {
                continue;
            }
            BlockPos at = BlockPos.containing(guess);
            if (!level.isPositionEntityTicking(at)) {
                continue;
            }
            Player near = level.getNearestPlayer(guess.x, guess.y, guess.z, 96.0D, false);
            Player close = level.getNearestPlayer(guess.x, guess.y, guess.z, 12.0D, false);
            if (near == null || close != null) {
                continue;
            }
            // Уже стоит (старое сохранение с ролью без имени получает ключ сам — см. adoptLegacy).
            boolean present = !level.getEntitiesOfClass(SectDisciple.class, new AABB(at).inflate(200.0D),
                    d -> d.memberKey().equals(m.key())).isEmpty();
            if (!present) {
                Vec3 spot = stand(level, guess);
                SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                npc.setMember(m);
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
        BlockPos bell = null;
        for (BlockPos p : BlockPos.betweenClosed(BlockPos.containing(c.x - 5, c.y - 1, c.z - 5), BlockPos.containing(c.x + 5, c.y + 14, c.z + 5))) {
            if (level.getBlockState(p).is(Blocks.BELL)) {
                bell = p.immutable();
                break;
            }
        }
        if (bell != null && level.getBlockState(bell).getBlock() instanceof BellBlock b) {
            b.attemptToRing(level, bell, Direction.NORTH);
        }
        // Колокол слышно на всей полке: громкость > 1 растягивает дальность звука (16 блоков × громкость).
        BlockPos from = bell != null ? bell : BlockPos.containing(c.x, c.y + 6, c.z);
        level.playSound(null, from, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 6.0F, 0.6F);
        if (since == 0) {
            MurimMod.LOGGER.info("Секта Хуашань: колокол — {}", SectSchedule.at(time).id());
        }
    }

    // ------------------------------------------------------------------ игрок в утреннем строю

    /**
     * Форма Шести Равновесий игрока (из {@code FoundationService#onSwing}): в рассветный строй на
     * площади засчитывается одна форма за такт, если удар в такт со строем. Десять — «Утренняя
     * тренировка»: до конца дня освоение идёт быстрее (план §4.2, §6.1).
     */
    public static void onPlayerForm(ServerPlayer player) {
        // Испытание двери тайника: форма основы — ступень лестницы (seal/VaultTrial).
        player.getData(io.github.verycooltimo.murim.registry.ModAttachments.LOADOUT).foundation().ifPresent(f -> io.github.verycooltimo.murim.sect.seal.VaultTrial
                .onFoundation(player, f, player.getData(io.github.verycooltimo.murim.registry.ModAttachments.FOUNDATION_SWING)[0]));
        SectLayout layout = layout(player.serverLevel());
        if (layout == null) {
            return;
        }
        onPlayerForm(player, layout);
    }

    public static void onPlayerForm(ServerPlayer player, SectLayout layout) {
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
            player.displayClientMessage(Component.translatable("murim.sect.morning.offbeat").withStyle(ChatFormatting.GRAY), true);
            player.getPersistentData().put(MORNING_TAG, tag);
            return;
        }
        if (tag.getLong("beat") == beat && tag.getInt("count") > 0) {
            return;
        }
        int count = tag.getInt("count") + 1;
        tag.putInt("count", count);
        tag.putLong("beat", beat);
        if (count >= MORNING_FORMS) {
            tag.putBoolean("done", true);
            MurimMod.LOGGER.info("Секта Хуашань: {} — утренняя тренировка засчитана", player.getName().getString());
            player.displayClientMessage(Component.translatable("murim.sect.morning.done").withStyle(ChatFormatting.GOLD), false);
            player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.4F, 1.4F);
            SectService.contribute(player, SectService.MORNING_CONTRIBUTION);
            // Журнал секты: строй отмечен (распорядок для игрока, автор 05.10).
            SectAttendance.record(player, SectAttendance.Activity.FORMATION);
            for (SectDisciple d : player.level().getEntitiesOfClass(SectDisciple.class, player.getBoundingBox().inflate(20.0D),
                    d -> d.role() == SectRole.MENTOR)) {
                d.gesture("nod");
            }
        } else {
            player.displayClientMessage(Component.translatable("murim.sect.morning.count", count, MORNING_FORMS)
                    .withStyle(ChatFormatting.GOLD), true);
        }
        player.getPersistentData().put(MORNING_TAG, tag);
    }

    /** Точка в строю: ряды на площади с запасом в два блока. */
    public static boolean inFormation(SectLayout layout, Vec3 pos) {
        double[] l = layout.local("training", pos);
        if (l == null) {
            return false;
        }
        double halfU = (SectSchedule.COLUMNS - 1) / 2.0D * SectSchedule.SPACING + 2.0D;
        double front = SectSchedule.FRONT_ROW + 2.0D;
        double back = SectSchedule.FRONT_ROW - (SectSchedule.rows() - 1) * SectSchedule.SPACING - 2.0D;
        return Math.abs(l[0]) <= halfU && l[1] <= front && l[1] >= back;
    }

    /** Прибавка освоения за усердие: столько дней подряд всё, чего ждала секта, сделано ({@link SectAttendance}). */
    public static final double DILIGENCE_BONUS = 1.05D;

    /**
     * Множитель освоения: в день утренней тренировки — {@link #MORNING_BONUS}; при усердии
     * ({@link SectAttendance#STREAK_DAYS} полных дней подряд) — ещё {@link #DILIGENCE_BONUS}.
     */
    public static double masteryBonus(ServerPlayer player) {
        CompoundTag tag = player.getPersistentData().getCompound(MORNING_TAG);
        double bonus = tag.getBoolean("done") && tag.getLong("day") == SectSchedule.day(player.level().getDayTime()) ? MORNING_BONUS : 1.0D;
        if (player.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT_ATTENDANCE).streak() >= SectAttendance.STREAK_DAYS) {
            bonus *= DILIGENCE_BONUS;
        }
        return bonus;
    }

    /**
     * Стенд и команда {@code /murim sect settle}: недостающие люди встают сразу (даже на глазах), все
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
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, new AABB(BlockPos.ZERO).inflate(3.0E7D),
                d -> !d.memberKey().isEmpty())) {
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
            long time = level.getDayTime();
            SectSchedule.Task t = SectSchedule.task(m, time);
            Vec3 guess = layout.at(t.zone(), t.du(), t.dv());
            if (guess == null || !level.isPositionEntityTicking(BlockPos.containing(guess))) {
                continue;
            }
            boolean present = !level.getEntitiesOfClass(SectDisciple.class, new AABB(BlockPos.containing(guess)).inflate(300.0D),
                    d -> d.memberKey().equals(m.key())).isEmpty();
            if (!present) {
                Vec3 spot = stand(level, guess);
                SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
                npc.setMember(m);
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
        for (SectDisciple d : level.getEntitiesOfClass(SectDisciple.class, new AABB(BlockPos.ZERO).inflate(3.0E7D),
                d -> !d.memberKey().isEmpty())) {
            Resolved r = resolve(d);
            out.add(String.format("%s %s %s at %s spot %s%s%s", d.memberKey(), r == null ? "-" : r.task().kind(),
                    r == null ? "" : r.task().zone(), d.blockPosition().toShortString(),
                    r == null ? "-" : BlockPos.containing(r.spot()).toShortString(), d.dormant() ? " dormant" : "",
                    d.spar() != SectDisciple.Spar.NONE ? " spar:" + d.spar() : ""));
        }
        return out;
    }
}
