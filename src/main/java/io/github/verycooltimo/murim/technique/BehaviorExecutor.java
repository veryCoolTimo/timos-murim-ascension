package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Исполняет поведение техники на сервере.
 *
 * <p>Единственное место, где данные превращаются в игровое действие. Новый тип поведения
 * добавляется сюда и в {@link TechniqueBehavior}; техника, собранная из существующих типов,
 * не требует ни строчки Java.
 *
 * <p>Все проверки целей общие для всех типов: не сам применяющий, живой, не зритель.
 * {@code isAttackable} у {@code LivingEntity} всегда true и ничего не фильтрует.
 */
public final class BehaviorExecutor {

    /**
     * Максимум целей, обрабатываемых одним воздействием.
     *
     * <p>Ограничение бюджета, а не баланса: без него техника в толпе мобов превращается
     * в проверку сотен хитбоксов за тик. Рекомендация из обсуждения бюджета — не более
     * 24 проверок области за тик на обычную технику.
     */
    private static final int MAX_TARGETS = 24;

    /** @return true, если хоть одна цель получила урон */
    public static boolean execute(ServerPlayer player, TechniqueDefinition definition) {
        TechniqueBehavior behavior = definition.behavior();
        // Слой освоения меняет силу: корявая техника слабее, обжитая — сильнее (§3г).
        float power = (float) io.github.verycooltimo.murim.mastery.MasteryRules.powerFactor(
                Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, definition.id())),
                definition.layers());
        if (behavior instanceof TechniqueBehavior.MeleeArc melee) {
            return meleeArc(player, melee, definition.id(), power);
        }
        if (behavior instanceof TechniqueBehavior.ProjectileFan fan) {
            return projectileFan(player, fan, definition.id(), power);
        }
        if (behavior instanceof TechniqueBehavior.Dash dash) {
            return dash(player, dash, definition.id(), power);
        }
        if (behavior instanceof TechniqueBehavior.PalmBlast palm) {
            return palmBlast(player, palm, definition.id(), power);
        }
        if (behavior instanceof TechniqueBehavior.Step) {
            step(player, definition.id());
            return false;
        }
        MurimMod.LOGGER.error("Тип поведения {} не реализован у техники {}",
                behavior.type(), definition.id());
        return false;
    }

    /**
     * Шаг: рывок по горизонтали взгляда до первой стены (луч по блокам — старый рывок
     * телепортировал сквозь стены), неуязвимость на высоких слоях, пакет для эффекта.
     */
    public static void step(ServerPlayer player, net.minecraft.resources.ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return;
        }
        Vec3 dir = flat.normalize();
        Vec3 start = player.position();
        double distance = StepRules.distance(layer);
        // Луч на уровне пояса: упираемся в стену, а не проходим сквозь неё.
        Vec3 from = start.add(0.0D, 0.9D, 0.0D);
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                from, from.add(dir.scale(distance)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        double reach = wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                ? distance : Math.max(0.0D, wall.getLocation().distanceTo(from) - 0.5D);
        Vec3 end = start.add(dir.scale(reach));
        player.teleportTo(end.x, end.y, end.z);
        int iframes = StepRules.invulnerableTicks(layer);
        if (iframes > 0) {
            player.invulnerableTime = Math.max(player.invulnerableTime, iframes);
        }
        io.github.verycooltimo.murim.mastery.MasteryService.onMiss(player, id);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.StepPayload(player.getId(), start, player.position(), player.getYRot(), layer));
    }

    private static boolean meleeArc(ServerPlayer player, TechniqueBehavior.MeleeArc melee,
                                    net.minecraft.resources.ResourceLocation id, float power) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double cosLimit = Math.cos(Math.toRadians(melee.arcDegrees() / 2.0D));
        AABB search = new AABB(eye, eye).inflate(melee.reach());

        boolean anyHit = false;
        int processed = 0;
        for (LivingEntity target : candidates(player, search)) {
            if (++processed > MAX_TARGETS) {
                break;
            }
            if (!inArc(eye, look, target.getBoundingBox(), melee.reach(), cosLimit)) {
                continue;
            }
            if (target.hurt(player.damageSources().playerAttack(player), melee.damage() * power)) {
                anyHit = true;
                // Пережитое для освоения (docs/design/19 §3г).
                io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, target);
            }
        }
        return anyHit;
    }

    private static boolean projectileFan(ServerPlayer player, TechniqueBehavior.ProjectileFan fan,
                                         net.minecraft.resources.ResourceLocation id, float power) {
        Vec3 look = player.getLookAngle();
        Vec3 origin = player.getEyePosition().subtract(0.0D, 0.15D, 0.0D);
        boolean spawned = false;
        for (int i = 0; i < fan.count(); i++) {
            // Веер раскрывается симметрично: при одном снаряде он летит строго вперёд.
            double t = fan.count() == 1 ? 0.0D : (i / (double) (fan.count() - 1)) - 0.5D;
            double yaw = Math.toRadians(fan.spreadDegrees() * t);
            Vec3 direction = rotateAroundY(look, yaw).normalize();

            WedgeProjectile wedge = new WedgeProjectile(player.level(), player,
                    fan.damage() * power, fan.lifetimeTicks());
            wedge.technique = id;
            wedge.setPos(origin.x, origin.y, origin.z);
            wedge.setDeltaMovement(direction.scale(fan.speed()));
            spawned |= player.level().addFreshEntity(wedge);
        }
        // Попадание снарядов наступит позже: сам запуск попаданием не считается,
        // иначе hit stop сработал бы в момент броска, а не удара.
        return false;
    }

    private static boolean dash(ServerPlayer player, TechniqueBehavior.Dash dash,
                                net.minecraft.resources.ResourceLocation id, float power) {
        Vec3 start = player.position();
        // По горизонтали: рывок вверх по взгляду превращался бы в полёт.
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        if (flat.lengthSqr() < 1.0E-6D) {
            return false;
        }
        Vec3 direction = flat.normalize();
        Vec3 wanted = start.add(direction.scale(dash.distance()));

        // Движение через штатное перемещение сущности: так рывок упирается в стены,
        // а не проносит игрока сквозь них.
        player.teleportTo(wanted.x, wanted.y, wanted.z);
        Vec3 finish = player.position();

        AABB path = new AABB(start, finish).inflate(dash.radius());
        boolean anyHit = false;
        int processed = 0;
        for (LivingEntity target : candidates(player, path)) {
            if (++processed > MAX_TARGETS) {
                break;
            }
            if (target.hurt(player.damageSources().playerAttack(player), dash.damage() * power)) {
                anyHit = true;
                io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, target);
            }
        }
        return anyHit;
    }

    /**
     * Ладонный выброс: конус вблизи, отравление и обездвиживание.
     *
     * <p>Стан реализован эффектами замедления и слабости, а не запретом ввода: отнимать
     * у игрока управление — тяжёлое решение, и для моба оно достигается дешевле.
     * Цель остаётся на месте, но продолжает существовать как участник боя.
     */
    /**
     * Захват ладони в начале техники (автор 01.10): ближайшая цель в конусе не дальше
     * {@code reach} — игрок делает рывок к ней, а цель сразу замедлена на всё время
     * техники и не может выйти из удара. Дальше {@code reach} рывка нет.
     *
     * <p>Рывок — один толчок: на земле скорость за тик умножается на 0.546 (трение блока
     * 0.6 × сопротивление 0.91), и весь путь равен v / (1 − 0.546). Так игрок к удару стоит
     * вплотную к цели, а не бьёт её с двух блоков по воздуху.
     *
     * @param techniqueTicks длительность техники — столько цель удерживается
     */
    public static void palmLunge(ServerPlayer player, TechniqueBehavior.PalmBlast palm, int techniqueTicks) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double cosLimit = Math.cos(Math.toRadians(palm.arcDegrees() / 2.0D));
        AABB search = new AABB(eye, eye).inflate(palm.reach());
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        for (LivingEntity candidate : candidates(player, search)) {
            if (!inArc(eye, look, candidate.getBoundingBox(), palm.reach(), cosLimit)) {
                continue;
            }
            double d = candidate.distanceToSqr(player);
            if (d < best) {
                best = d;
                target = candidate;
            }
        }
        if (target == null) {
            return;
        }
        // Цель удержана: сильное замедление на всё время техники, инерция погашена.
        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, techniqueTicks, 6, false, true, true));
        target.setDeltaMovement(0.0D, target.getDeltaMovement().y, 0.0D);
        target.hurtMarked = true;

        Vec3 toTarget = new Vec3(target.getX() - player.getX(), 0.0D, target.getZ() - player.getZ());
        double gap = toTarget.length() - (target.getBbWidth() / 2.0D + player.getBbWidth() / 2.0D) - 0.25D;
        if (gap <= 0.1D || toTarget.lengthSqr() < 1.0E-6D) {
            return;
        }
        double speed = gap * (1.0D - 0.546D);
        Vec3 push = toTarget.normalize().scale(speed);
        player.setDeltaMovement(push.x, player.getDeltaMovement().y, push.z);
        // Сервер двигает игрока только через пакет движения клиенту.
        player.hurtMarked = true;
    }

    private static boolean palmBlast(ServerPlayer player, TechniqueBehavior.PalmBlast palm,
                                     net.minecraft.resources.ResourceLocation id, float power) {
        Vec3 eye = player.getEyePosition();
        Vec3 look = player.getLookAngle();
        double cosLimit = Math.cos(Math.toRadians(palm.arcDegrees() / 2.0D));
        AABB search = new AABB(eye, eye).inflate(palm.reach());

        boolean anyHit = false;
        int processed = 0;
        for (LivingEntity target : candidates(player, search)) {
            if (++processed > MAX_TARGETS) {
                break;
            }
            if (!inArc(eye, look, target.getBoundingBox(), palm.reach(), cosLimit)) {
                continue;
            }
            if (!target.hurt(player.damageSources().playerAttack(player), palm.damage() * power)) {
                continue;
            }
            anyHit = true;
            io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, target);
            // Точка контакта уходит на клиент: брызги яда рисуются ТАМ, где удар
            // состоялся. Без этого выброс возникал из воздуха независимо от попадания —
            // прямое замечание автора по кадрам.
            net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(
                    player,
                    new io.github.verycooltimo.murim.network.TechniqueHitPayload(
                            player.getId(),
                            target.getX(), target.getY() + target.getBbHeight() * 0.55D,
                            target.getZ(), target.getBbHeight()));
            if (palm.poisonSeconds() > 0) {
                target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.POISON,
                        palm.poisonSeconds() * 20, 1, false, true, true));
            }
            if (palm.stunTicks() > 0) {
                target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                        palm.stunTicks(), 6, false, true, true));
                target.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                        net.minecraft.world.effect.MobEffects.WEAKNESS,
                        palm.stunTicks(), 2, false, true, true));
                // Гасим текущее движение, иначе цель по инерции продолжает уезжать.
                target.setDeltaMovement(0.0D, target.getDeltaMovement().y, 0.0D);
                target.hurtMarked = true;
            }
        }
        return anyHit;
    }

    private static List<LivingEntity> candidates(ServerPlayer player, AABB box) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                candidate -> candidate != player && candidate.isAlive() && !candidate.isSpectator());
    }

    private static Vec3 rotateAroundY(Vec3 vector, double radians) {
        double cos = Math.cos(radians);
        double sin = Math.sin(radians);
        return new Vec3(vector.x * cos + vector.z * sin, vector.y, -vector.x * sin + vector.z * cos);
    }

    /**
     * Попадает ли хитбокс цели в конус поражения.
     *
     * <p>Считается по ближайшей точке хитбокса, а не по центру: у крупной цели центр может
     * оказаться вне дуги, пока туша стоит вплотную.
     *
     * <p>Публичный намеренно: это чистая геометрия без зависимости от мира, и она покрыта
     * юнит-тестами из соседнего пакета.
     */
    public static boolean inArc(Vec3 eye, Vec3 look, AABB box, double reach, double cosLimit) {
        double x = Mth.clamp(eye.x, box.minX, box.maxX);
        double y = Mth.clamp(eye.y, box.minY, box.maxY);
        double z = Mth.clamp(eye.z, box.minZ, box.maxZ);
        Vec3 nearest = new Vec3(x, y, z);
        Vec3 offset = nearest.subtract(eye);
        double distanceSqr = offset.lengthSqr();
        if (distanceSqr > reach * reach) {
            return false;
        }
        if (distanceSqr < 1.0E-8D) {
            // Цель вплотную: направление не определено, но удар очевидно достаёт.
            return true;
        }
        return offset.normalize().dot(look) >= cosLimit;
    }

    private BehaviorExecutor() {
    }
}
