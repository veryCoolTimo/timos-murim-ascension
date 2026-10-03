package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.network.RiverPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.Optional;

/**
 * Сервер «Опадающих Лепестков, Перекрывающих Реку» (см. {@link RiverRules}). Состояние — вложение
 * {@code RIVER}: {кисть x, y, z, прицел x, y, z, слой, id выбранной цели или −1, тик контакта или −1,
 * id цели контакта или −1, урон в руке H, взрыв 0/1, дальность}.
 *
 * <p>На тике {@link RiverRules#AIM} точка прицела фиксируется (захваченная цель первой, иначе
 * ближайшая в конусе взгляда, в том числе в небе). На контакте поток проверяет весь отрезок
 * кисть → прицел: первый блок останавливает его, первая сущность на пути становится целью контакта
 * и замирает до взрыва (в воздухе — висит). Промах — без урона и без ударных эффектов. Взрыв —
 * основная цель 8H, остальные в радиусе 3 при прямой видимости от узла 3H → 1,5H.
 */
public final class RiverExecutor {

    /** Начало IMPACT (T = {@link RiverRules#AIM}): прицел и цель фиксируются. */
    public static boolean start(ServerPlayer player, ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double base = TechniqueDamage.base(player, id);
        Vec3 look = player.getLookAngle();
        if (layer <= 0) {
            // Слой 0 — учебный укол в ближнем секторе, без эффектов (как у Ливня).
            boolean hit = false;
            double cos = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(PlumRules.TRAINING_REACH + 1.0D))) {
                if (BehaviorExecutor.inArc(player.getEyePosition(), look, t.getBoundingBox(), PlumRules.TRAINING_REACH, cos)
                        && t.hurt(player.damageSources().playerAttack(player), (float) (base * RiverRules.DMG_TRAINING))) {
                    hit = true;
                }
            }
            return hit;
        }
        Vec3 hand = hand(player);
        LivingEntity target = TargetLock.locked(player, RiverRules.RANGE + 2.0D);
        if (target == null) {
            double best = Double.MAX_VALUE;
            double cone = Math.cos(Math.toRadians(RiverRules.CONE));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(RiverRules.RANGE + 0.5D))) {
                Vec3 to = TargetLock.centre(t).subtract(player.getEyePosition());
                double d = to.length();
                if (d > RiverRules.RANGE || !io.github.verycooltimo.murim.combat.TargetLock.inCone(player, t, RiverRules.CONE) || !player.hasLineOfSight(t)) {
                    continue;
                }
                if (d < best) {
                    best = d;
                    target = t;
                }
            }
        }
        Vec3 aim;
        if (target != null) {
            aim = TargetLock.centre(target);
        } else {
            // Без цели — выпуск по взгляду на 16 блоков: поток рассыпается, урона нет.
            aim = player.getEyePosition().add(look.scale(16.0D));
        }
        double dist = aim.distanceTo(hand);
        player.setData(ModAttachments.RIVER, new double[] {hand.x, hand.y, hand.z, aim.x, aim.y, aim.z, layer,
                target == null ? -1 : target.getId(), -1, -1, base, 0, dist});
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new RiverPayload(player.getId(), hand, aim, layer, RiverPayload.AIM, target == null ? -1 : target.getId()));
        return false;
    }

    /** Кисть с мечом: чуть ниже и правее глаз, перед грудью. */
    static Vec3 hand(ServerPlayer player) {
        Vec3 look = player.getLookAngle();
        Vec3 flat = new Vec3(look.x, 0.0D, look.z);
        flat = flat.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : flat.normalize();
        Vec3 right = flat.cross(new Vec3(0.0D, 1.0D, 0.0D)).normalize();
        return player.getEyePosition().add(0.0D, -0.4D, 0.0D).add(right.scale(0.3D)).add(flat.scale(0.6D));
    }

    /** Шкала после прицела: контакт, удержание, взрыв. */
    public static void tick(ServerPlayer player, ResourceLocation id, int since) {
        double[] r = player.getData(ModAttachments.RIVER);
        int layer = (int) r[6];
        if (layer <= 0 || r.length < 13) {
            return;
        }
        int t = since + RiverRules.AIM;
        Vec3 hand = new Vec3(r[0], r[1], r[2]);
        Vec3 aim = new Vec3(r[3], r[4], r[5]);
        if (r[8] < 0 && t == RiverRules.contact(r[12])) {
            contact(player, r, hand, aim, t, layer);
            return;
        }
        if (r[8] >= 0 && r[9] >= 0 && r[11] < 0.5D && t == RiverRules.BURST) {
            r[11] = 1.0D;
            player.setData(ModAttachments.RIVER, r);
            burst(player, id, r, hand, layer);
        }
    }

    /** Поток проходит отрезок кисть → прицел: блок останавливает, первая сущность на пути — цель контакта. */
    private static void contact(ServerPlayer player, double[] r, Vec3 hand, Vec3 aim, int t, int layer) {
        // API: reference/minecraft-src/net/minecraft/world/level/BlockGetter.java#clip
        var wall = player.level().clip(new ClipContext(hand, aim, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        Vec3 end = wall.getType() == HitResult.Type.MISS ? aim : wall.getLocation();
        // Цель прицела стоит в точке прицела и слегка шире радиуса потока: попадание «в упор» засчитывается.
        Vec3 dir = end.subtract(hand);
        Vec3 stretched = dir.lengthSqr() < 1.0E-6D ? end : end.add(dir.normalize().scale(0.6D));
        LivingEntity hit = null;
        double best = Double.MAX_VALUE;
        AABB sweep = new AABB(hand, stretched).inflate(RiverRules.STREAM_RADIUS + 1.0D);
        for (LivingEntity e : candidates(player, sweep)) {
            // API: reference/minecraft-src/net/minecraft/world/phys/AABB.java#clip
            Optional<Vec3> at = e.getBoundingBox().inflate(RiverRules.STREAM_RADIUS).clip(hand, stretched);
            if (at.isEmpty()) {
                continue;
            }
            double d = at.get().distanceToSqr(hand);
            if (d < best) {
                best = d;
                hit = e;
            }
        }
        r[8] = t;
        if (hit == null) {
            player.setData(ModAttachments.RIVER, r);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new RiverPayload(player.getId(), hand, end, layer, RiverPayload.MISS, -1));
            return;
        }
        r[9] = hit.getId();
        player.setData(ModAttachments.RIVER, r);
        // «Перекрывает реку»: поток останавливает натиск цели; в воздухе она висит. Заморозка
        // кончается ровно на тике взрыва — отброс взрыва уже не гасится.
        boolean boss = hit.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        int hold = RiverRules.BURST - t;
        TargetLock.freeze(hit, boss ? Math.min(RiverRules.BOSS_HOLD, hold) : hold);
        if (hit instanceof net.minecraft.world.entity.Mob mob && !boss) {
            mob.getNavigation().stop();
        }
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new RiverPayload(player.getId(), hand, TargetLock.centre(hit), layer, RiverPayload.CONTACT, hit.getId()));
    }

    /** Звёздный взрыв: основная цель 8H, остальные в радиусе 3 при видимости от узла. */
    private static void burst(ServerPlayer player, ResourceLocation id, double[] r, Vec3 hand, int layer) {
        if (!(player.level().getEntity((int) r[9]) instanceof LivingEntity primary) || !primary.isAlive()) {
            return;
        }
        Vec3 node = TargetLock.centre(primary);
        // Цель могли утащить далеко (телепорт) — поток не тянется за ней.
        if (node.distanceTo(hand) > RiverRules.RANGE + 6.0D) {
            return;
        }
        double base = r[10] * RiverRules.power(layer);
        Vec3 axis = node.subtract(hand);
        boolean any = false;
        primary.invulnerableTime = 0;
        if (primary.hurt(player.damageSources().playerAttack(player), (float) (base * RiverRules.DMG_PRIMARY))) {
            any = true;
            io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, primary);
            knock(primary, axis, RiverRules.PUSH_PRIMARY);
        }
        for (LivingEntity e : candidates(player, new AABB(node, node).inflate(RiverRules.BURST_RADIUS + 1.0D))) {
            if (e == primary) {
                continue;
            }
            Vec3 c = TargetLock.centre(e);
            double d = c.distanceTo(node);
            if (d > RiverRules.BURST_RADIUS || !visible(player, node, c)) {
                continue;
            }
            e.invulnerableTime = 0;
            if (e.hurt(player.damageSources().playerAttack(player), (float) (base * RiverRules.areaShare(d)))) {
                any = true;
                knock(e, d < 0.1D ? axis : c.subtract(node), RiverRules.PUSH_AREA);
            }
        }
        if (any) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new RiverPayload(player.getId(), hand, node, layer, RiverPayload.BURST, primary.getId()));
        }
    }

    private static void knock(LivingEntity e, Vec3 dir, double speed) {
        double resistance = e.getAttributeValue(Attributes.KNOCKBACK_RESISTANCE);
        e.setDeltaMovement(RiverRules.push(dir, speed, resistance));
        e.hurtMarked = true;
    }

    private static boolean visible(ServerPlayer player, Vec3 from, Vec3 to) {
        return player.level().clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    private static List<LivingEntity> candidates(ServerPlayer player, AABB box) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                c -> c != player && c.isAlive() && !c.isSpectator() && !(c instanceof ArmorStand));
    }

    private RiverExecutor() {
    }
}
