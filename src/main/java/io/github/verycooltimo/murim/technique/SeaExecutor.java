package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.network.SeaPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.LargeFireball;
import net.minecraft.world.entity.projectile.LlamaSpit;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.Snowball;
import net.minecraft.world.entity.projectile.ThrownEgg;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Сервер Моря Цветущей Сливы (см. {@link SeaRules}, docs/design/techniques/twenty-four-plum-sea-spec.md).
 * Состояние — вложение {@code SEA}: {слой, ось x, y, z, R удержан, тик миража или −1, освоение засчитано,
 * стартовая цена ци, оплачено шагов, игровое время старта, слоты захвата по 3 числа: id снаряда или −1,
 * тик захвата, 1 — второй слот огненного шара гаста}.
 *
 * <p>Урона нет. Снаряд, впервые вошедший в сектор (отрезок пути за тик), летящий в мастера, —
 * захватывается, если хватает слотов, иначе помечен «прошёл» и этим Морем больше не ловится (по codex:
 * решение один раз на входе). Захваченный висит {@link SeaRules#HANG} тика, затем падает; попадание
 * по сущностям ему запрещено меткой на время {@link SeaRules#MARK_TICKS}.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SeaExecutor {

    private static final int LAYER = 0;
    private static final int AX = 1;
    private static final int HELD = 4;
    private static final int MELT = 5;
    private static final int MASTERED = 6;
    private static final int COST = 7;
    private static final int STEPS = 8;
    private static final int START = 9;
    private static final int SLOTS = 10;
    private static final int SLOT_SIZE = 3;
    private static final int MAX_SLOTS = 4;
    private static final int SIZE = SLOTS + SLOT_SIZE * MAX_SLOTS;

    /** Метки в данных снаряда: до какого игрового времени он в обвивке; каким кастом пропущен. */
    private static final String TAG_UNTIL = "murim_sea_until";
    private static final String TAG_PASSED = "murim_sea_passed";

    /** Начало каста: ось сектора — захваченная цель (только начальное направление, по codex), иначе взгляд. */
    public static void begin(ServerPlayer player, ResourceLocation id, double cost) {
        release(player, player.getData(ModAttachments.SEA), false);
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        Vec3 chest = chest(player);
        LivingEntity target = io.github.verycooltimo.murim.combat.TargetLock.locked(player, 32.0D);
        Vec3 axis = target != null ? io.github.verycooltimo.murim.combat.TargetLock.centre(target).subtract(chest).normalize()
                : player.getLookAngle();
        double[] d = new double[SIZE];
        d[LAYER] = layer;
        d[AX] = axis.x;
        d[AX + 1] = axis.y;
        d[AX + 2] = axis.z;
        d[HELD] = "1".equals(System.getenv("MURIM_CAPTURE_HOLD")) && Boolean.getBoolean("murim.capture") ? 1.0D : 0.0D;
        d[MELT] = -1.0D;
        d[COST] = cost;
        d[START] = player.serverLevel().getGameTime();
        for (int i = 0; i < MAX_SLOTS; i++) {
            d[SLOTS + i * SLOT_SIZE] = -1.0D;
        }
        player.setData(ModAttachments.SEA, d);
    }

    /** IMPACT — раскрытие, не удар: мастер укореняется до конца, поле начинает расти. */
    public static boolean release(ServerPlayer player) {
        double[] d = player.getData(ModAttachments.SEA);
        if (d.length < SIZE) {
            return false;
        }
        int layer = (int) d[LAYER];
        player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                SeaRules.END - SeaRules.RELEASE, 9, false, false, false));
        if (layer > 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new SeaPayload(player.getId(), player.position(), axis(d), 0, layer, SeaPayload.BEGIN));
        }
        return false;
    }

    /** Клиент сообщил о клавише R: принимается только во время своего Моря. */
    public static void hold(ServerPlayer player, boolean held) {
        double[] d = player.getData(ModAttachments.SEA);
        if (d.length < SIZE || !active(player)) {
            return;
        }
        d[HELD] = held ? 1.0D : 0.0D;
        player.setData(ModAttachments.SEA, d);
    }

    /**
     * Тик техники (от начала): ось сектора, захваты, продление, мираж.
     *
     * @return {@code true}, если Море кончилось и техника погашена досрочно
     */
    public static boolean tick(ServerPlayer player, ResourceLocation id, int tick) {
        double[] d = player.getData(ModAttachments.SEA);
        if (d.length < SIZE) {
            return false;
        }
        captureVolley(player, tick);
        int layer = (int) d[LAYER];
        // Ось ведёт взгляд игрока, не быстрее 4°/тик (захваченная цель — только начальное направление).
        Vec3 axis = SeaRules.turn(axis(d), player.getLookAngle());
        d[AX] = axis.x;
        d[AX + 1] = axis.y;
        d[AX + 2] = axis.z;
        int holdEnd = layer <= 0 ? SeaRules.TRAINING_TO : SeaRules.HOLD_END;
        if (d[MELT] < 0.0D && tick >= holdEnd) {
            int over = tick - holdEnd;
            boolean extend = SeaRules.extendable(layer) && d[HELD] > 0.5D && over < SeaRules.EXTEND_MAX;
            if (extend && over % SeaRules.EXTEND_STEP == 0) {
                extend = pay(player, d, layer);
            }
            if (!extend) {
                melt(player, d, layer, tick);
            }
        }
        if (d[MELT] >= 0.0D) {
            player.setData(ModAttachments.SEA, d);
            if (tick >= d[MELT] + (layer <= 0 ? 1 : SeaRules.MELT)) {
                player.removeEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
                player.setData(ModAttachments.SEA, new double[0]);
                io.github.verycooltimo.murim.combat.TechniqueService.finish(player);
                return true;
            }
            return false;
        }
        if (tick >= SeaRules.RELEASE) {
            tickSlots(player, d, layer, tick);
            catchNew(player, id, d, layer, tick, axis);
        }
        player.setData(ModAttachments.SEA, d);
        return false;
    }

    /** Шаг продления: 1 сердце и четверть стартовой цены ци; не хватает — мираж. */
    private static boolean pay(ServerPlayer player, double[] d, int layer) {
        io.github.verycooltimo.murim.profile.DantianProfile profile = player.getData(ModAttachments.PROFILE);
        double qi = d[COST] * SeaRules.EXTEND_QI;
        if (player.getHealth() <= SeaRules.EXTEND_FLOOR || profile.circulating() < qi) {
            return false;
        }
        player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - qi));
        io.github.verycooltimo.murim.profile.ProfileNetwork.sync(player);
        // Без события урона (codex): не срывает, не даёт неуязвимости, не тратит поглощение.
        player.setHealth(player.getHealth() - SeaRules.EXTEND_HEALTH);
        d[STEPS] += 1.0D;
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new SeaPayload(player.getId(), player.getEyePosition(), axis(d), (int) d[STEPS], layer, SeaPayload.BLEED));
        return true;
    }

    /** Тихий мираж: захваченные — сразу вниз, клиенту — таяние. */
    private static void melt(ServerPlayer player, double[] d, int layer, int tick) {
        d[MELT] = tick;
        release(player, d, true);
        if (layer > 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new SeaPayload(player.getId(), player.position(), axis(d), 0, layer, SeaPayload.MELT));
        }
    }

    /** Захваченные снаряды: висение, падение, гашение огненного шара, посадка. */
    private static void tickSlots(ServerPlayer player, double[] d, int layer, int tick) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            int s = SLOTS + i * SLOT_SIZE;
            if (d[s] < 0.0D || d[s + 2] > 0.5D) {
                continue;
            }
            Entity e = player.serverLevel().getEntity((int) d[s]);
            int age = tick - (int) d[s + 1];
            if (!(e instanceof Projectile p) || !p.isAlive()) {
                free(d, (int) d[s]);
                send(player, layer, SeaPayload.LAND, Vec3.ZERO, Vec3.ZERO, (int) d[s]);
                continue;
            }
            if (p instanceof AbstractHurtingProjectile && age >= SeaRules.SNUFF) {
                // Огненный шар гаснет в обвивке: без взрыва и поджога.
                Vec3 at = p.position();
                p.discard();
                free(d, (int) d[s]);
                send(player, layer, SeaPayload.SNUFF, at, Vec3.ZERO, p.getId());
                continue;
            }
            boolean still = age > SeaRules.HANG + 1 && p.position().distanceToSqr(p.xo, p.yo, p.zo) < 1.0E-4D;
            if (still || age >= SeaRules.HOLD_PROJECTILE) {
                drop(p);
                free(d, p.getId());
                send(player, layer, SeaPayload.LAND, p.position(), Vec3.ZERO, p.getId());
                continue;
            }
            p.setDeltaMovement(SeaRules.brake(p.getDeltaMovement(), age));
            p.hasImpulse = true;
        }
    }

    /** Новые входы в сектор: по возрастанию расстояния; нет слота — «прошёл». */
    private static void catchNew(ServerPlayer player, ResourceLocation id, double[] d, int layer, int tick, Vec3 axis) {
        if (layer <= 0 && tick > SeaRules.TRAINING_TO) {
            return;
        }
        Vec3 chest = chest(player);
        double grown = layer <= 0 ? 1.0D : SeaRules.grown(tick);
        long now = player.serverLevel().getGameTime();
        long cast = (long) d[START];
        AABB box = new AABB(chest, chest).inflate(SeaRules.radius(layer) + 3.0D);
        List<Projectile> in = new ArrayList<>();
        for (Projectile p : player.serverLevel().getEntitiesOfClass(Projectile.class, box, p -> p.isAlive() && supported(p))) {
            if (p.getOwner() == player) {
                continue;
            }
            CompoundTag tag = p.getPersistentData();
            if (tag.getLong(TAG_UNTIL) > now || tag.contains(TAG_PASSED) && tag.getLong(TAG_PASSED) == cast) {
                continue;
            }
            if (SeaRules.entering(layer, chest, axis, p.position(), p.getDeltaMovement(), grown)) {
                in.add(p);
            }
        }
        in.sort(Comparator.comparingDouble(p -> p.position().distanceToSqr(chest)));
        for (Projectile p : in) {
            int weight = p instanceof LargeFireball ? 2 : 1;
            if (freeSlots(d, layer) < weight) {
                p.getPersistentData().putLong(TAG_PASSED, cast);
                if (Boolean.getBoolean("murim.capture")) {
                    MurimMod.LOGGER.info("Море: {} прошёл (нет слота) на тике {}", p.getType().getDescriptionId(), tick);
                }
                continue;
            }
            capture(player, id, d, layer, tick, p, weight);
        }
    }

    private static void capture(ServerPlayer player, ResourceLocation id, double[] d, int layer, int tick, Projectile p, int weight) {
        long now = player.serverLevel().getGameTime();
        p.getPersistentData().putLong(TAG_UNTIL, now + SeaRules.MARK_TICKS);
        p.setNoGravity(true);
        if (p instanceof AbstractHurtingProjectile h) {
            h.accelerationPower = 0.0D;
        }
        if (p instanceof TangDagger dagger) {
            dagger.setMode(TangDagger.FALL);
            dagger.life = dagger.tickCount + SeaRules.MARK_TICKS;
        }
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(SeaRules.brake(v, 0));
        p.hasImpulse = true;
        int placed = 0;
        for (int i = 0; i < MAX_SLOTS && placed < weight; i++) {
            int s = SLOTS + i * SLOT_SIZE;
            if (d[s] < 0.0D) {
                d[s] = p.getId();
                d[s + 1] = tick;
                d[s + 2] = placed > 0 ? 1.0D : 0.0D;
                placed++;
            }
        }
        if (d[MASTERED] < 0.5D && hostile(player, p.getOwner())) {
            d[MASTERED] = 1.0D;
            io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, p.getOwner());
        }
        if (layer > 0) {
            send(player, layer, SeaPayload.CAPTURE, p.position(), v, p.getId());
        } else {
            // Слой 0 — учебное парирование: только простой звук, без эффектов.
            player.level().playSound(null, p.getX(), p.getY(), p.getZ(), net.minecraft.sounds.SoundEvents.SHIELD_BLOCK,
                    net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, 1.3F);
        }
        if (Boolean.getBoolean("murim.capture")) {
            // Стенд: журнал захватов — по нему проверяется «лишний проходит».
            MurimMod.LOGGER.info("Море: захват {} на тике {}, свободно слотов {}", p.getType().getDescriptionId(), tick, freeSlots(d, layer));
        }
    }

    /** Снаряды, которые Море умеет опустить (codex: зелья, жемчуг и чужие модовые — не трогать). */
    private static boolean supported(Projectile p) {
        return p instanceof AbstractArrow || p instanceof Snowball || p instanceof ThrownEgg || p instanceof AbstractHurtingProjectile
                || p instanceof LlamaSpit || p instanceof TangDagger;
    }

    private static boolean hostile(ServerPlayer player, Entity owner) {
        return owner != null && owner != player && (owner instanceof net.minecraft.world.entity.monster.Enemy
                || owner instanceof net.minecraft.world.entity.player.Player
                || owner instanceof net.minecraft.world.entity.Mob m && m.getTarget() == player);
    }

    private static int freeSlots(double[] d, int layer) {
        int used = 0;
        for (int i = 0; i < MAX_SLOTS; i++) {
            if (d[SLOTS + i * SLOT_SIZE] >= 0.0D) {
                used++;
            }
        }
        return Math.max(0, SeaRules.captures(layer) - used);
    }

    private static void free(double[] d, int projectile) {
        for (int i = 0; i < MAX_SLOTS; i++) {
            int s = SLOTS + i * SLOT_SIZE;
            if ((int) d[s] == projectile) {
                d[s] = -1.0D;
                d[s + 2] = 0.0D;
            }
        }
    }

    /** Снаряд выходит из обвивки: своя гравитация, вниз; огненный шар гаснет. */
    private static void drop(Projectile p) {
        p.setNoGravity(false);
        if (p instanceof AbstractHurtingProjectile) {
            p.discard();
            return;
        }
        Vec3 v = p.getDeltaMovement();
        p.setDeltaMovement(v.x * 0.3D, Math.min(-0.2D, v.y), v.z * 0.3D);
        p.hasImpulse = true;
    }

    /** Отпустить все захваты (конец Моря, срыв, смерть); метки попадания истекут сами. */
    private static void release(ServerPlayer player, double[] d, boolean keep) {
        if (d.length < SIZE) {
            return;
        }
        for (int i = 0; i < MAX_SLOTS; i++) {
            int s = SLOTS + i * SLOT_SIZE;
            if (d[s] >= 0.0D && player.serverLevel().getEntity((int) d[s]) instanceof Projectile p && p.isAlive()) {
                drop(p);
            }
            d[s] = -1.0D;
        }
        if (keep) {
            player.setData(ModAttachments.SEA, d);
        }
    }

    private static void send(ServerPlayer player, int layer, int stage, Vec3 a, Vec3 b, int value) {
        if (layer > 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new SeaPayload(player.getId(), a, b, value, layer, stage));
        }
    }

    private static Vec3 axis(double[] d) {
        return new Vec3(d[AX], d[AX + 1], d[AX + 2]);
    }

    private static Vec3 chest(ServerPlayer player) {
        return player.position().add(0.0D, 1.2D, 0.0D);
    }

    /** Идёт ли сейчас Море у игрока. */
    private static boolean active(ServerPlayer player) {
        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (!state.isActive() || state.techniqueId() == null) {
            return false;
        }
        TechniqueDefinition def = TechniqueLoader.all().get(state.techniqueId());
        return def != null && def.behavior() instanceof TechniqueBehavior.PlumSea;
    }

    /** Срыв, смерть, другая техника: захваты отпускаются, клиенту — рассыпаться. */
    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        double[] d = player.getData(ModAttachments.SEA);
        if (d.length < SIZE || active(player) && player.isAlive()) {
            return;
        }
        release(player, d, false);
        player.setData(ModAttachments.SEA, new double[0]);
        if (d[LAYER] > 0.5D) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new SeaPayload(player.getId(), player.position(), Vec3.ZERO, 0, (int) d[LAYER], SeaPayload.LOST));
        }
    }

    /** Обвитый снаряд никого не ранит и не взрывается о блок, пока метка жива. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onProjectileImpact(ProjectileImpactEvent event) {
        Projectile p = event.getProjectile();
        if (p.level().isClientSide || p.getPersistentData().getLong(TAG_UNTIL) <= p.level().getGameTime()) {
            return;
        }
        if (event.getRayTraceResult() instanceof EntityHitResult) {
            event.setCanceled(true);
        } else if (p instanceof AbstractHurtingProjectile) {
            event.setCanceled(true);
            p.discard();
        }
    }

    /** Меч ведёт поле: пока Море держится, мастер не бьёт. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && active(player)) {
            double[] d = player.getData(ModAttachments.SEA);
            if (d.length >= SIZE && d[LAYER] > 0.5D) {
                event.setCanceled(true);
            }
        }
    }

    /**
     * Стенд (MURIM_CAPTURE_VOLLEY=1, только при съёмке): ближайшая цель стреляет в мастера —
     * одиночная стрела, веер из 3, снежок и огненный шар, кинжал Тан, веер из 6 (лишние проходят).
     */
    private static void captureVolley(ServerPlayer player, int tick) {
        if (!Boolean.getBoolean("murim.capture") || !"1".equals(System.getenv("MURIM_CAPTURE_VOLLEY"))) {
            return;
        }
        if (tick != 34 && tick != 44 && tick != 56 && tick != 66 && tick != 76) {
            return;
        }
        LivingEntity shooter = null;
        double best = 24.0D;
        for (LivingEntity e : player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(20.0D),
                e -> e != player && e.isAlive() && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))) {
            double dd = e.distanceTo(player);
            if (dd < best) {
                best = dd;
                shooter = e;
            }
        }
        if (shooter == null) {
            return;
        }
        Vec3 from = shooter.getEyePosition();
        Vec3 to = player.position().add(0.0D, 1.1D, 0.0D);
        Vec3 dir = to.subtract(from);
        Vec3 side = new Vec3(-dir.z, 0.0D, dir.x).normalize();
        switch (tick) {
            case 34 -> arrow(player, shooter, from, dir, 0.0D);
            case 44 -> {
                for (int k = -1; k <= 1; k++) {
                    arrow(player, shooter, from.add(side.scale(0.5D * k)), dir.add(side.scale(0.9D * k)), 0.1D * k);
                }
            }
            case 56 -> {
                Snowball ball = new Snowball(player.level(), shooter);
                ball.setPos(from.add(side.scale(0.6D)));
                ball.shoot(dir.x, dir.y + dir.horizontalDistance() * 0.06D, dir.z, 1.4F, 0.0F);
                player.level().addFreshEntity(ball);
                Vec3 fv = dir.normalize().scale(0.9D);
                net.minecraft.world.entity.projectile.SmallFireball fire = new net.minecraft.world.entity.projectile.SmallFireball(
                        player.level(), shooter, fv);
                fire.setPos(from.subtract(side.scale(0.6D)));
                player.level().addFreshEntity(fire);
            }
            case 66 -> {
                TangDagger dagger = new TangDagger(player.level(), shooter, TangRules.FIVE, 1, 0);
                dagger.setPos(from);
                dagger.setDeltaMovement(dir.normalize().scale(1.4D));
                dagger.setMode(TangDagger.STRAIGHT);
                dagger.speed = 1.4D;
                dagger.life = 40;
                player.level().addFreshEntity(dagger);
            }
            default -> {
                for (int k = 0; k < 6; k++) {
                    // Все шесть — в мастера (разные точки выпуска, сходятся на нём): лишние должны пройти.
                    double off = (k - 2.5D) * 0.5D;
                    Vec3 o = from.add(side.scale(off)).add(0.0D, 0.15D * (k % 2), 0.0D);
                    arrow(player, shooter, o, to.subtract(o), 0.0D);
                }
            }
        }
    }

    private static void arrow(ServerPlayer player, LivingEntity shooter, Vec3 from, Vec3 dir, double lift) {
        net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(player.level(),
                from.x, from.y, from.z, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
        arrow.setOwner(shooter);
        arrow.shoot(dir.x, dir.y + dir.horizontalDistance() * (0.02D + lift), dir.z, 1.9F, 0.0F);
        player.level().addFreshEntity(arrow);
    }

    private SeaExecutor() {
    }
}
