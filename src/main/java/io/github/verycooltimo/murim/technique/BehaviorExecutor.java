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
        if (behavior instanceof TechniqueBehavior.PlumSlash) {
            return plumSlash(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumWhirlwind) {
            return whirlStart(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.Footwork) {
            io.github.verycooltimo.murim.combat.FootworkService.windEvade(player, definition.id());
            return false;
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
        // Направление задаёт контекст ввода (уклонение влево/вправо/назад), иначе — взгляд.
        Vec3 context = io.github.verycooltimo.murim.combat.FootworkService.dashDirection(player);
        Vec3 look = context != null ? context : player.getLookAngle();
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
        // Не телепорт: игрок сам проносится за 5 тиков (FootworkMotion на клиенте).
        io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, dir, reach, 5);
        int iframes = StepRules.invulnerableTicks(layer);
        if (iframes > 0) {
            player.invulnerableTime = Math.max(player.invulnerableTime, iframes);
        }
        io.github.verycooltimo.murim.mastery.MasteryService.onMiss(player, id);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.StepPayload(player.getId(), start, end, player.getYRot(), layer));
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

    /**
     * «Разрез» Семи Цветков Сливы. Слой 0 — учебный удар в ближнем секторе. Со слоя 1 —
     * коридор вперёд: длина обрезается первой стеной на высоте груди, цель задевается, если её
     * хитбокс пересекает коридор; каждая — один раз. Урон — базовый урон в руке × коэффициент.
     */
    private static boolean plumSlash(ServerPlayer player, net.minecraft.resources.ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double base = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        float damage = (float) (base * PlumRules.coefficient(layer));
        Vec3 origin = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        forward = forward.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double length = PlumRules.length(layer);
        if (layer > 0) {
            Vec3 chest = origin.add(0.0D, 1.2D, 0.0D);
            net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                    chest, chest.add(forward.scale(0.4D + length)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, player));
            if (wall.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
                length = Math.max(0.5D, wall.getLocation().distanceTo(chest) - 0.4D);
            }
        }
        double reach = layer > 0 ? 0.4D + length : PlumRules.TRAINING_REACH;
        AABB search = new AABB(origin, origin).inflate(reach + 1.0D, PlumRules.height(layer) + 1.0D, reach + 1.0D);
        double cosLimit = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
        boolean anyHit = false;
        int hits = 0;
        for (LivingEntity target : candidates(player, search)) {
            if (hits >= 6) {
                break;
            }
            AABB box = target.getBoundingBox();
            boolean inside;
            if (layer <= 0) {
                inside = inArc(player.getEyePosition(), look, box, PlumRules.TRAINING_REACH, cosLimit);
            } else {
                Vec3 c = box.getCenter().subtract(origin);
                double half = Math.max(box.getXsize(), box.getZsize()) / 2.0D;
                double s = c.dot(forward);
                double r = c.dot(right);
                inside = s >= 0.4D - half && s <= 0.4D + length + half
                        && Math.abs(r) <= PlumRules.width(layer) / 2.0D + half
                        && box.maxY >= origin.y && box.minY <= origin.y + PlumRules.height(layer);
            }
            if (!inside) {
                continue;
            }
            if (target.hurt(player.damageSources().playerAttack(player), damage)) {
                hits++;
                anyHit = true;
                // Толчок вперёд по коридору, не подброс в воздух (спецификация §2.4).
                target.push(forward.x * 0.2D, 0.06D, forward.z * 0.2D);
                target.hurtMarked = true;
                // Попал первый удар — противник оглушён до падения дерева (автор 02.10).
                if (layer >= 1) {
                    stagger(target);
                }
                io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, target);
            }
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.PlumSlashPayload(player.getId(), origin,
                        player.getYRot(), layer, (float) length));
        return anyHit;
    }

    /**
     * Оглушение: стоит и не бьёт (замедление до неподвижности, слабость — без урона). Обычный
     * моб — до падения дерева, игрок — 0,6 с, босс — полсекунды.
     */
    private static void stagger(LivingEntity target) {
        boolean boss = target.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        int ticks = target instanceof net.minecraft.world.entity.player.Player ? PlumRules.STAGGER_PVP_TICKS
                : boss ? PlumRules.STAGGER_BOSS_TICKS : PlumRules.STAGGER_TICKS;
        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, ticks, 9, false, false, false));
        target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, ticks, 9, false, false, false));
        if (target instanceof net.minecraft.world.entity.Mob mob && !boss) {
            mob.getNavigation().stop();
        }
    }

    /**
     * Падение дерева: полоса за основанием ствола вперёд на высоту дерева, шириной ~3,6 блока;
     * урон — урон в руке × коэффициент падения, по одному разу на цель.
     */
    public static void plumFall(ServerPlayer player, net.minecraft.resources.ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double coefficient = PlumRules.fallCoefficient(layer);
        if (coefficient <= 0.0D) {
            return;
        }
        float damage = (float) (player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE) * coefficient);
        Vec3 origin = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        forward = forward.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : forward.normalize();
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        double base = PlumRules.trunkOffset(PlumRules.length(layer));
        double reach = base + PlumRules.treeHeight(layer) * 0.9D;
        AABB search = new AABB(origin, origin).inflate(reach + 1.0D, 3.0D, reach + 1.0D);
        int hits = 0;
        for (LivingEntity target : candidates(player, search)) {
            if (hits >= 8) {
                break;
            }
            AABB box = target.getBoundingBox();
            Vec3 c = box.getCenter().subtract(origin);
            double half = Math.max(box.getXsize(), box.getZsize()) / 2.0D;
            double s = c.dot(forward);
            if (s < base - 0.8D - half || s > reach + half || Math.abs(c.dot(right)) > 2.2D + half
                    || box.minY > origin.y + 2.5D) {
                continue;
            }
            if (target.hurt(player.damageSources().playerAttack(player), damage)) {
                hits++;
                // Отбрасывает до ~2 блоков вперёд по линии падения.
                target.push(forward.x * 0.6D, 0.25D, forward.z * 0.6D);
                target.hurtMarked = true;
                io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, target);
            }
        }
        scorch(player, origin, forward, right, base, reach, layer);
    }

    /**
     * Борозда от упавшего дерева: по полосе падения (ширина ~4) ломаются верхние природные
     * блоки — земля, песок, гравий, камень, до 48 штук, без дропа. Руды, контейнеры и всё
     * остальное не трогаются; выключается настройкой {@code techniqueTerrainDamage}.
     */
    private static void scorch(ServerPlayer player, Vec3 origin, Vec3 forward, Vec3 right, double from, double to, int layer) {
        if (layer < 2 || !io.github.verycooltimo.murim.Config.TECHNIQUE_TERRAIN.get()) {
            return;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        java.util.Random r = new java.util.Random(player.getId() * 31L + level.getGameTime());
        int broken = 0;
        // Автор 02.10: «слишком много блоков взрывается» — борозда только под стволом,
        // рваная, до 14 блоков на 4-м слое.
        int budget = Math.min(16, 3 * layer + 2);
        for (double s = from; s <= to && broken < budget; s += 0.9D) {
            for (double w = -0.6D; w <= 0.6D && broken < budget; w += 0.6D) {
                if (r.nextDouble() > 0.45D - 0.15D * Math.abs(w)) {
                    continue;
                }
                Vec3 at = origin.add(forward.scale(s)).add(right.scale(w + (r.nextDouble() - 0.5D) * 0.4D));
                net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(at.x, origin.y - 0.5D, at.z);
                for (int dy = 1; dy >= -1; dy--) {
                    net.minecraft.core.BlockPos p = pos.above(dy);
                    net.minecraft.world.level.block.state.BlockState state = level.getBlockState(p);
                    if (state.isAir() || !level.getBlockState(p.above()).isAir()) {
                        continue;
                    }
                    if (natural(state) && level.mayInteract(player, p)) {
                        level.destroyBlock(p, false, player);
                        broken++;
                    }
                    break;
                }
            }
        }
    }

    private static boolean natural(net.minecraft.world.level.block.state.BlockState state) {
        return state.is(net.minecraft.tags.BlockTags.DIRT) || state.is(net.minecraft.tags.BlockTags.SAND)
                || state.is(net.minecraft.world.level.block.Blocks.GRAVEL) || state.is(net.minecraft.tags.BlockTags.BASE_STONE_OVERWORLD)
                || state.is(net.minecraft.world.level.block.Blocks.SNOW) || state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)
                || state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS);
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

    // ------------------------------------------------------------------ Вихрь Цветущей Сливы

    /**
     * Разрез вверх Вихря: центр зоны — цель по взгляду до 6 блоков (иначе 4 вперёд), фиксируется
     * здесь и дальше не преследует. Слой 0 — учебный удар в ближнем секторе.
     */
    private static boolean whirlStart(ServerPlayer player, net.minecraft.resources.ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double base = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        Vec3 origin = player.position();
        Vec3 look = player.getLookAngle();
        Vec3 forward = new Vec3(look.x, 0.0D, look.z);
        forward = forward.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : forward.normalize();
        if (layer <= 0) {
            boolean hit = false;
            double cos = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(PlumRules.TRAINING_REACH + 1.0D))) {
                if (inArc(player.getEyePosition(), look, t.getBoundingBox(), PlumRules.TRAINING_REACH, cos)
                        && t.hurt(player.damageSources().playerAttack(player), (float) base)) {
                    hit = true;
                }
            }
            return hit;
        }
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        double cone = Math.cos(Math.toRadians(30.0D));
        for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(6.5D))) {
            Vec3 to = t.position().subtract(origin);
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            double d = flat.length();
            if (d < 0.5D || d > 6.0D || flat.normalize().dot(forward) < cone || !player.hasLineOfSight(t)) {
                continue;
            }
            if (d < best) {
                best = d;
                target = t;
            }
        }
        Vec3 centre = target != null ? new Vec3(target.getX(), origin.y, target.getZ()) : origin.add(forward.scale(4.0D));
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, new double[] {
                centre.x, centre.y, centre.z, player.getYRot(), layer, 0, 0, target == null ? -1 : target.getId()});
        // Разрез вверх: столп из пола у центра — задевает стоящих рядом с ним.
        boolean hit = false;
        for (LivingEntity t : whirlInside(player, centre, 1.4D, layer)) {
            hit |= whirlHurt(player, id, t, base * WhirlRules.DMG_SLASH);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.WhirlPayload(player.getId(), centre, player.getYRot(), layer, 0));
        return hit;
    }

    /** Шкала Вихря после Разреза (тики {@code since}), см. WhirlRules. */
    public static void whirlTick(ServerPlayer player, net.minecraft.resources.ResourceLocation id, int since) {
        double[] w = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL);
        int layer = (int) w[4];
        if (layer <= 0) {
            return;
        }
        Vec3 centre = new Vec3(w[0], w[1], w[2]);
        double r = WhirlRules.radius(layer);
        double base = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        // Столпы: касание кольца столпов на 1-м и 3-м взмахе.
        if (WhirlRules.pillars(layer) > 0 && (since == WhirlRules.WALL_STROKES[0] || since == WhirlRules.WALL_STROKES[2])) {
            for (LivingEntity t : whirlInside(player, centre, r + 1.2D, layer)) {
                double d = flatDistance(t.position(), centre);
                if (Math.abs(d - r) < 1.2D) {
                    whirlHurt(player, id, t, base * WhirlRules.DMG_WALL);
                }
            }
        }
        // Стены держат: изнутри наружу не выйти, пока стоят (32–58), — мягкий толчок внутрь.
        if (WhirlRules.walls(layer) && since >= WhirlRules.WALL_STROKES[2] && since < WhirlRules.SHATTER) {
            double shell = since < WhirlRules.CONVERGE ? r
                    : r * (1.0D - 0.45D * Math.min(1.0D, (since - WhirlRules.CONVERGE) / 10.0D));
            for (LivingEntity t : whirlInside(player, centre, shell + 1.0D, layer)) {
                double d = flatDistance(t.position(), centre);
                if (d > shell - 0.6D && d < shell + 1.0D) {
                    Vec3 in = centre.subtract(t.position());
                    in = new Vec3(in.x, 0.0D, in.z).normalize().scale(0.18D);
                    t.setDeltaMovement(t.getDeltaMovement().multiply(0.3D, 1.0D, 0.3D).add(in));
                    t.hurtMarked = true;
                }
            }
            if (since == WhirlRules.CONVERGE + 10) {
                for (LivingEntity t : whirlInside(player, centre, r, layer)) {
                    whirlHurt(player, id, t, base * WhirlRules.DMG_CONVERGE);
                }
            }
        }
        // Вихрь: притяжение к центру и восемь импульсов урона.
        if (WhirlRules.whirl(layer) && since >= WhirlRules.SHATTER && since < WhirlRules.WHIRL_END + 6) {
            double ramp = Math.min(1.0D, (since - WhirlRules.SHATTER) / 10.0D)
                    * Math.min(1.0D, (WhirlRules.WHIRL_END + 6 - since) / 6.0D);
            for (LivingEntity t : whirlInside(player, centre, r + 1.0D, layer)) {
                Vec3 to = centre.subtract(t.position());
                Vec3 flat = new Vec3(to.x, 0.0D, to.z);
                double d = flat.length();
                if (d < 1.2D) {
                    continue;
                }
                boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
                double k = (t instanceof net.minecraft.world.entity.player.Player ? 0.6D : boss ? 0.2D : 1.0D) * ramp
                        * Math.min(1.0D, (d - 1.2D) / 1.0D);
                Vec3 in = flat.normalize();
                Vec3 tangent = new Vec3(-in.z, 0.0D, in.x);
                Vec3 add = in.scale(0.025D * k).add(tangent.scale(0.012D * k));
                Vec3 v = t.getDeltaMovement().add(add);
                double hv = Math.sqrt(v.x * v.x + v.z * v.z);
                if (hv > WhirlRules.MAX_PULL) {
                    v = new Vec3(v.x / hv * WhirlRules.MAX_PULL, v.y, v.z / hv * WhirlRules.MAX_PULL);
                }
                t.setDeltaMovement(v);
                t.hurtMarked = true;
            }
            for (int p : WhirlRules.PULSES) {
                if (p == since) {
                    for (LivingEntity t : whirlInside(player, centre, r, layer)) {
                        whirlHurt(player, id, t, base * WhirlRules.DMG_PULSE);
                    }
                }
            }
            if (since == WhirlRules.WHIRL + 12) {
                whirlCuts(player, centre, r, layer);
            }
        }
        // Финал: плавный проход сбоку цели за спину (рывок без телепорта), урон по факту.
        if (WhirlRules.finale(layer) && since == WhirlRules.PASS) {
            LivingEntity target = w[7] >= 0 && player.level().getEntity((int) w[7]) instanceof LivingEntity le && le.isAlive()
                    && flatDistance(le.position(), centre) < r + 2.0D ? le : null;
            Vec3 aim = target != null ? target.position() : centre;
            Vec3 to = aim.subtract(player.position());
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            if (flat.lengthSqr() > 1.0E-4D) {
                Vec3 dir = flat.normalize();
                Vec3 side = new Vec3(-dir.z, 0.0D, dir.x);
                Vec3 dest = aim.add(dir.scale(2.0D)).add(side.scale(1.0D));
                Vec3 path = dest.subtract(player.position());
                Vec3 pathFlat = new Vec3(path.x, 0.0D, path.z);
                double reach = Math.min(8.0D, pathFlat.length());
                io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, pathFlat.normalize(), reach, WhirlRules.PASS_TICKS);
                w[0] = player.getX();
                w[2] = player.getZ();
                w[1] = pathFlat.normalize().x;
                w[3] = pathFlat.normalize().z;
                w[6] = reach;
                // Старт и направление прохода хранятся до проверки попадания; центр больше не нужен.
                player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, w);
            }
        }
        if (WhirlRules.finale(layer) && since == WhirlRules.PASS + WhirlRules.PASS_TICKS / 2 && w[6] > 0.0D) {
            Vec3 start = new Vec3(w[0], player.getY(), w[2]);
            Vec3 dir = new Vec3(w[1], 0.0D, w[3]);
            for (LivingEntity t : candidates(player, new AABB(start, start).inflate(w[6] + 2.0D, 3.0D, w[6] + 2.0D))) {
                Vec3 rel = t.position().subtract(start);
                double along = rel.x * dir.x + rel.z * dir.z;
                double off = Math.abs(rel.x * dir.z - rel.z * dir.x);
                if (along >= 0.0D && along <= w[6] && off < 1.8D) {
                    if (whirlHurt(player, id, t, base * WhirlRules.DMG_PASS)) {
                        t.push(dir.x * 0.4D, 0.1D, dir.z * 0.4D);
                        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                                new io.github.verycooltimo.murim.network.WhirlPayload(player.getId(),
                                        t.position().add(0.0D, t.getBbHeight() * 0.6D, 0.0D), player.getYRot(), layer, 1));
                    }
                }
            }
            w[6] = 0.0D;
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, w);
        }
    }

    private static List<LivingEntity> whirlInside(ServerPlayer player, Vec3 centre, double radius, int layer) {
        double h = WhirlRules.height(layer);
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class,
                new AABB(centre.x - radius, centre.y - 1.0D, centre.z - radius, centre.x + radius, centre.y + h, centre.z + radius),
                t -> t != player && t.isAlive() && !t.isSpectator() && flatDistance(t.position(), centre) <= radius);
    }

    private static double flatDistance(Vec3 a, Vec3 b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return Math.sqrt(dx * dx + dz * dz);
    }

    /**
     * Урон Вихря: частые импульсы — своя защита от повторного урона (ванильные полсекунды
     * неуязвимости съели бы половину ударов). Первое попадание оглушает.
     */
    private static boolean whirlHurt(ServerPlayer player, net.minecraft.resources.ResourceLocation id, LivingEntity t, double amount) {
        t.invulnerableTime = 0;
        if (!t.hurt(player.damageSources().playerAttack(player), (float) amount)) {
            return false;
        }
        double[] w = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL);
        if (w[5] < 0.5D) {
            w[5] = 1.0D;
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, w);
            whirlStagger(t);
        }
        io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, t);
        return true;
    }

    private static void whirlStagger(LivingEntity t) {
        boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        int ticks = t instanceof net.minecraft.world.entity.player.Player ? 12 : boss ? 10 : WhirlRules.END;
        t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, ticks, 3, false, false, false));
        t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, ticks, 9, false, false, false));
    }

    /** Глубокие разрезы вихря по земле: ≤14 природных блоков, по касательным дугам внутри зоны. */
    private static void whirlCuts(ServerPlayer player, Vec3 centre, double r, int layer) {
        if (layer < 4 || !io.github.verycooltimo.murim.Config.TECHNIQUE_TERRAIN.get()) {
            return;
        }
        net.minecraft.server.level.ServerLevel level = player.serverLevel();
        java.util.Random rnd = new java.util.Random(player.getId() * 131L + level.getGameTime());
        int budget = Math.min(14, 2 * layer);
        int broken = 0;
        for (int tries = 0; tries < 60 && broken < budget; tries++) {
            double a = rnd.nextDouble() * Math.PI * 2.0D;
            double rr = r * (0.35D + 0.6D * rnd.nextDouble());
            net.minecraft.core.BlockPos pos = net.minecraft.core.BlockPos.containing(centre.x + Math.cos(a) * rr, centre.y - 0.5D, centre.z + Math.sin(a) * rr);
            net.minecraft.world.level.block.state.BlockState state = level.getBlockState(pos);
            // Под мастером и под живыми не ломаем: на стенде мастер проваливался сквозь пол.
            boolean underSomeone = !level.getEntitiesOfClass(LivingEntity.class,
                    new AABB(pos.above()).inflate(0.6D, 0.0D, 0.6D), LivingEntity::isAlive).isEmpty();
            if (!underSomeone && !state.isAir() && level.getBlockState(pos.above()).isAir() && natural(state) && level.mayInteract(player, pos)) {
                level.destroyBlock(pos, false, player);
                broken++;
            }
        }
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
