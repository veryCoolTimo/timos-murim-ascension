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
        if (behavior instanceof TechniqueBehavior.PlumExecution) {
            return execStart(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumRush) {
            return rushStart(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumRainfall) {
            return RainExecutor.start(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumExplosion) {
            return ExplosionExecutor.strike(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumRiver) {
            return RiverExecutor.start(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumScatter) {
            return ScatterExecutor.start(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.PlumDome) {
            return DomeExecutor.raise(player);
        }
        if (behavior instanceof TechniqueBehavior.PlumSea) {
            return SeaExecutor.release(player);
        }
        if (behavior instanceof TechniqueBehavior.PlumShower) {
            return ShowerExecutor.start(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.TangDaggers) {
            return TangExecutor.start(player, definition.id());
        }
        if (behavior instanceof TechniqueBehavior.FallingPetal) {
            return FallingPetalExecutor.start(player, definition.id());
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
    public static boolean plumSlash(LivingEntity player, net.minecraft.resources.ResourceLocation id) {
        int layer = Casters.layer(player, id);
        double base = TechniqueDamage.base(player, id);
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
                        // Ниже ног тоже (M1, 03.10: бандит в яме под мастером не задевался вовсе —
                        // попадало только падающее дерево, без оглушения): до PlumRules.BELOW блоков вниз.
                        && box.maxY >= origin.y - PlumRules.BELOW && box.minY <= origin.y + PlumRules.height(layer);
            }
            if (!inside) {
                continue;
            }
            if (target.hurt(Casters.attack(player), damage)) {
                hits++;
                anyHit = true;
                // Толчок вперёд по коридору, не подброс в воздух (спецификация §2.4).
                target.push(forward.x * 0.2D, 0.06D, forward.z * 0.2D);
                target.hurtMarked = true;
                // Попал первый удар — противник оглушён до падения дерева (автор 02.10).
                if (layer >= 1) {
                    stagger(target);
                }
                Casters.onHit(player, id, target);
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
    public static void plumFall(LivingEntity player, net.minecraft.resources.ResourceLocation id) {
        int layer = Casters.layer(player, id);
        double coefficient = PlumRules.fallCoefficient(layer);
        if (coefficient <= 0.0D) {
            return;
        }
        float damage = (float) (TechniqueDamage.base(player, id) * coefficient);
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
            if (target.hurt(Casters.attack(player), damage)) {
                hits++;
                // Отбрасывает до ~2 блоков вперёд по линии падения.
                target.push(forward.x * 0.6D, 0.25D, forward.z * 0.6D);
                target.hurtMarked = true;
                Casters.onHit(player, id, target);
            }
        }
        scorch(player, origin, forward, right, base, reach, layer);
    }

    /**
     * Борозда от упавшего дерева: по полосе падения (ширина ~4) ломаются верхние природные
     * блоки — земля, песок, гравий, камень, до 48 штук, без дропа. Руды, контейнеры и всё
     * остальное не трогаются; выключается настройкой {@code techniqueTerrainDamage}.
     */
    private static void scorch(LivingEntity player, Vec3 origin, Vec3 forward, Vec3 right, double from, double to, int layer) {
        if (layer < 2 || !io.github.verycooltimo.murim.Config.TECHNIQUE_TERRAIN.get()) {
            return;
        }
        net.minecraft.server.level.ServerLevel level = Casters.level(player);
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
                    if (natural(state) && Casters.mayBreak(player, p)) {
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
        double base = TechniqueDamage.base(player, id);
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
        // Цель — по взгляду до 9 блоков; вихрь строится ВОКРУГ МАСТЕРА (автор 02.10), к цели потом
        // идут два мини-урагана.
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        double cone = Math.cos(Math.toRadians(30.0D));
        for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(12.5D))) {
            Vec3 to = t.position().subtract(origin);
            Vec3 flat = new Vec3(to.x, 0.0D, to.z);
            double d = flat.length();
            // Стойки для брони — не противники (на стенде они ближе цели и перехватывали выбор).
            if (t instanceof net.minecraft.world.entity.decoration.ArmorStand
                    || d < 0.5D || d > 12.0D || flat.normalize().dot(forward) < cone || !player.hasLineOfSight(t)) {
                continue;
            }
            if (d < best) {
                best = d;
                target = t;
            }
        }
        Vec3 aim = target != null ? new Vec3(target.getX(), origin.y, target.getZ()) : origin.add(forward.scale(6.0D));
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, new double[] {
                origin.x, origin.y, origin.z, player.getYRot(), layer, 0, 0, target == null ? -1 : target.getId(),
                aim.x, aim.y, aim.z});
        // Разрез вверх (w5): столп из пола перед мастером.
        boolean hit = false;
        for (LivingEntity t : whirlInside(player, origin.add(forward.scale(1.5D)), 1.4D, layer)) {
            hit |= whirlHurt(player, id, t, base * WhirlRules.DMG_SLASH);
        }
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.WhirlPayload(player.getId(), origin, aim, player.getYRot(), layer, 0));
        return hit;
    }

    /** Шкала Вихря после Разреза (тики {@code since}), см. WhirlRules. */
    public static void whirlTick(ServerPlayer player, net.minecraft.resources.ResourceLocation id, int since) {
        double[] w = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL);
        int layer = (int) w[4];
        if (layer <= 0 || w.length < 11) {
            return;
        }
        Vec3 centre = new Vec3(w[0], w[1], w[2]);
        // Рукава и финал идут за живой целью, а не в точку старта.
        if (w[7] >= 0 && player.level().getEntity((int) w[7]) instanceof LivingEntity live && live.isAlive()
                && since < WhirlRules.PASS && flatDistance(live.position(), centre) < 16.0D) {
            w[8] = live.getX();
            w[10] = live.getZ();
        }
        Vec3 aim = new Vec3(w[8], w[9], w[10]);
        double r = WhirlRules.radius(layer);
        double base = TechniqueDamage.base(player, id);
        // Столпы и стены — вокруг мастера: задевают тех, кто подошёл вплотную.
        if (WhirlRules.pillars(layer) > 0 && (since == WhirlRules.WALL_STROKES[0] || since == WhirlRules.WALL_STROKES[2])) {
            for (LivingEntity t : whirlInside(player, centre, r + 1.2D, layer)) {
                if (Math.abs(flatDistance(t.position(), centre) - r) < 1.2D) {
                    whirlHurt(player, id, t, base * WhirlRules.DMG_WALL);
                }
            }
        }
        if (WhirlRules.walls(layer) && since == WhirlRules.CONVERGE + 12) {
            for (LivingEntity t : whirlInside(player, centre, r, layer)) {
                whirlHurt(player, id, t, base * WhirlRules.DMG_CONVERGE);
            }
        }
        if (WhirlRules.whirl(layer)) {
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
        // Два мини-урагана: идут к цели и сквозь неё; кто внутри — получает удар и его крутит.
        if (WhirlRules.finale(layer) && since >= WhirlRules.TORNADO_GO && since <= WhirlRules.TORNADO_END) {
            for (int which = -1; which <= 1; which += 2) {
                Vec3 at = WhirlRules.tornado(centre, aim, r, which, since);
                for (LivingEntity t : whirlInside(player, at, 1.4D, layer)) {
                    Vec3 in = at.subtract(t.position());
                    Vec3 tan = new Vec3(-in.z, 0.0D, in.x).normalize().scale(0.08D * which);
                    t.setDeltaMovement(t.getDeltaMovement().multiply(0.6D, 1.0D, 0.6D)
                            .add(new Vec3(in.x, 0.0D, in.z).scale(0.15D)).add(tan).add(0.0D, 0.02D, 0.0D));
                    t.hurtMarked = true;
                    if (since % WhirlRules.TORNADO_PULSE == 0) {
                        whirlHurt(player, id, t, base * WhirlRules.DMG_PULSE);
                    }
                }
            }
        }
        // Финал (автор 02.10: «чётко к противнику и за спину чётко»): рывок прямо на противника
        // с остановкой в метре перед ним, затем точный перенос за его спину и удар.
        if (WhirlRules.finale(layer) && since == WhirlRules.PASS) {
            LivingEntity target = w[7] >= 0 && player.level().getEntity((int) w[7]) instanceof LivingEntity le && le.isAlive() ? le : null;
            Vec3 goal = target != null ? target.position() : aim;
            Vec3 flat = new Vec3(goal.x - player.getX(), 0.0D, goal.z - player.getZ());
            if (flat.lengthSqr() > 1.0E-4D && flat.length() < 14.0D) {
                Vec3 dir = flat.normalize();
                double reach = Math.max(0.0D, flat.length() - 1.1D);
                io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, dir, reach, WhirlRules.PASS_TICKS - 2);
                w[1] = dir.x;
                w[3] = dir.z;
                w[6] = 1.0D;
                player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, w);
            }
        }
        if (WhirlRules.finale(layer) && since == WhirlRules.PASS + WhirlRules.PASS_TICKS - 1 && w[6] > 0.0D) {
            LivingEntity target = w[7] >= 0 && player.level().getEntity((int) w[7]) instanceof LivingEntity le && le.isAlive() ? le : null;
            Vec3 dir = new Vec3(w[1], 0.0D, w[3]);
            if (target != null && flatDistance(target.position(), player.position()) < 4.0D) {
                Vec3 behind = target.position().add(dir.scale(1.6D + target.getBbWidth() * 0.5D));
                float yaw = (float) Math.toDegrees(Math.atan2(-dir.x, dir.z));
                AABB box = player.getBoundingBox().move(behind.subtract(player.position()));
                if (player.serverLevel().noCollision(player, box)) {
                    // API: reference/minecraft-src/net/minecraft/server/level/ServerPlayer.java#teleportTo(ServerLevel,double,double,double,float,float)
                    player.teleportTo(player.serverLevel(), behind.x, target.getY(), behind.z, yaw, player.getXRot());
                }
                if (whirlHurt(player, id, target, base * WhirlRules.DMG_PASS)) {
                    target.push(-dir.x * 0.25D, 0.08D, -dir.z * 0.25D);
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                            new io.github.verycooltimo.murim.network.WhirlPayload(player.getId(),
                                    target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D), aim, player.getYRot(), layer, 1));
                }
            }
            w[6] = 0.0D;
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.WHIRL, w);
        }
    }

    // ------------------------------------------------------------------ Натиск Цветущей Сливы

    /** Выпуск урагана: направление по взгляду фиксируется здесь; цель — первая на пути. */
    public static boolean rushStart(LivingEntity player, net.minecraft.resources.ResourceLocation id) {
        int layer = Casters.layer(player, id);
        double base = TechniqueDamage.base(player, id);
        Vec3 look = player.getLookAngle();
        Vec3 f = new Vec3(look.x, 0.0D, look.z);
        f = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        if (layer <= 0) {
            boolean hit = false;
            double cos = Math.cos(Math.toRadians(PlumRules.TRAINING_ARC / 2.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(PlumRules.TRAINING_REACH + 1.0D))) {
                if (inArc(player.getEyePosition(), look, t.getBoundingBox(), PlumRules.TRAINING_REACH, cos)
                        && t.hurt(Casters.attack(player), (float) base)) {
                    hit = true;
                }
            }
            return hit;
        }
        Vec3 o = player.position();
        Vec3 axis0 = o.add(0.0D, 1.2D, 0.0D);
        // Наводка (03.10 — в любом направлении): захваченная цель, иначе ближайший противник в
        // конусе 30° взгляда до 16 блоков, в том числе выше или ниже — ураган летит прямо на него.
        LivingEntity aim = Casters.target(player, RushRules.RANGE + 4.0D);
        if (aim == null) {
            double best = Double.MAX_VALUE;
            double cone = Math.cos(Math.toRadians(30.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(RushRules.RANGE + 0.5D))) {
                Vec3 to = io.github.verycooltimo.murim.combat.TargetLock.centre(t).subtract(player.getEyePosition());
                double d = to.length();
                if (t instanceof net.minecraft.world.entity.decoration.ArmorStand || d > RushRules.RANGE
                        || !Casters.inCone(player, t, 30.0D) || !player.hasLineOfSight(t)) {
                    continue;
                }
                if (d < best) {
                    best = d;
                    aim = t;
                }
            }
        }
        Vec3 f3 = f;
        if (aim != null) {
            Vec3 to = io.github.verycooltimo.murim.combat.TargetLock.centre(aim).subtract(axis0);
            if (to.lengthSqr() > 1.0E-4D) {
                f3 = to.normalize();
            }
        }
        // 14 — id наводки (встречные до неё волочатся ураганом), 15 — тик укола, 16 — число
        // захваченных встречных, 17… — их id.
        double[] rd = new double[17 + RUSH_CATCH_MAX];
        double[] head = {o.x, o.y, o.z, f3.x, f3.z, layer, -1, -1, 0, 0, 0, 0, 0, f3.y, aim == null ? -1 : aim.getId(), -1, 0};
        System.arraycopy(head, 0, rd, 0, head.length);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH, rd);
        // Направление урагана — точка {@code o + f} во втором векторе пакета (клиент берёт разность).
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.RushPayload(player.getId(), o, o.add(f3), (float) Math.toDegrees(Math.atan2(-f3.x, f3.z)), layer, 0));
        return false;
    }

    /** Полёт урагана, обволакивание, рывок и укол, см. RushRules. */
    public static void rushTick(LivingEntity player, net.minecraft.resources.ResourceLocation id, int since) {
        double[] r = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH);
        int layer = (int) r[5];
        if (layer <= 0) {
            return;
        }
        double base = TechniqueDamage.base(player, id)
                * RushRules.power(layer);
        Vec3 o = new Vec3(r[0], r[1], r[2]);
        Vec3 f = new Vec3(r[3], r.length > 13 ? r[13] : 0.0D, r[4]);
        f = f.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : f.normalize();
        Vec3 axis0 = o.add(0.0D, 1.2D, 0.0D);
        if (r[6] < 0.0D) {
            // Полёт: передний край проверяется непрерывно, между прошлым и текущим тиком.
            double from = RushRules.head(since - 1) - 0.4D;
            double to = RushRules.head(since);
            Vec3 mid = axis0.add(f.scale((from + to) * 0.5D));
            LivingEntity hit = null;
            double best = Double.MAX_VALUE;
            for (LivingEntity t : candidates(player, new AABB(mid, mid).inflate(RushRules.HALF_WIDTH + 1.5D, RushRules.HALF_WIDTH + 2.5D, RushRules.HALF_WIDTH + 1.5D))) {
                if (t instanceof net.minecraft.world.entity.decoration.ArmorStand) {
                    continue;
                }
                // 3D: расстояние от оси урагана до центра цели (03.10 — цель может быть в небе).
                Vec3 rel = io.github.verycooltimo.murim.combat.TargetLock.centre(t).subtract(axis0);
                double along = rel.dot(f);
                double off = rel.subtract(f.scale(along)).length();
                if (along >= from - 0.5D && along <= to + 0.5D && off <= RushRules.HALF_WIDTH + Math.max(t.getBbWidth(), t.getBbHeight()) * 0.5D && along < best) {
                    // Наводка есть — встречные до неё не останавливают ураган, а волочатся им.
                    if (r.length > 16 && r[14] >= 0.0D && t.getId() != (int) r[14]) {
                        continue;
                    }
                    best = along;
                    hit = t;
                }
            }
            rushDrag(player, id, r, axis0, f, to, base, hit);
            if (hit != null) {
                r[6] = since;
                r[7] = hit.getId();
                r[8] = hit.getX();
                r[9] = hit.getY();
                r[10] = hit.getZ();
                player.setData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH, r);
                // Цель висит, пока ураган держит её и мастер не уколол (в воздухе — без падения).
                io.github.verycooltimo.murim.combat.TargetLock.freeze(hit, RushRules.thrust((int) r[6]) + 2);
                rushFling(r, f, player);
                rushHurt(player, id, hit, base * RushRules.DMG_FIRST, r);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new io.github.verycooltimo.murim.network.RushPayload(player.getId(), o, hit.position(), 0.0F, layer, 1));
            }
            return;
        }
        int t = since - (int) r[6];
        LivingEntity target = r[7] >= 0 && player.level().getEntity((int) r[7]) instanceof LivingEntity le && le.isAlive() ? le : null;
        Vec3 c = new Vec3(r[8], r[9], r[10]);
        // Обволакивание: держит у точки, два новых удара.
        if (target != null && t < RushRules.WRAP && target.position().distanceTo(c) < 2.5D) {
            Vec3 pull = c.subtract(target.position());
            target.setDeltaMovement(target.getDeltaMovement().multiply(0.4D, 1.0D, 0.4D).add(pull.x * 0.15D, 0.0D, pull.z * 0.15D));
            target.hurtMarked = true;
            if (t == RushRules.PULSES[1] || t == RushRules.PULSES[2]) {
                rushHurt(player, id, target, base * RushRules.DMG_PULSE, r);
            }
        }
        int wrapSince = (int) r[6];
        // Рывок издалека: прямо к цели, с боковым смещением, и за неё на 1,3 блока.
        if (t == RushRules.dash(wrapSince) && target != null) {
            // Рывок в 3D: к цели и за неё, на её высоте (в воздухе — взлетает, потом падает).
            Vec3 aim = target.position();
            Vec3 dir = aim.subtract(player.position());
            if (dir.lengthSqr() > 1.0E-4D && dir.length() < RushRules.RANGE + 6.0D) {
                dir = dir.normalize();
                Vec3 flatDir = new Vec3(dir.x, 0.0D, dir.z);
                flatDir = flatDir.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : flatDir.normalize();
                Vec3 dest = aim.add(flatDir.scale(target.getBbWidth() * 0.5D + 1.3D)).add(new Vec3(-flatDir.z, 0.0D, flatDir.x).scale(0.6D));
                Vec3 path = dest.subtract(player.position());
                Casters.dash(player, path.normalize(), path.length(), RushRules.DASH_TICKS);
            }
        }
        // Окно укола: позиция игрока на сервере отстаёт от плавного рывка на пару тиков.
        // Отброшенный уколом моб снова оглушён, когда приземлился (в воздухе стан подвесил бы его:
        // без ИИ моб не двигается), — до конца техники, не меньше 0,5 с.
        if (target != null && r.length > 15 && r[15] >= 0.0D && t >= (int) r[15] + 4 && t <= (int) r[15] + 30
                && target.onGround() && !target.hasEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN)
                && !(target instanceof net.minecraft.world.entity.player.Player)
                && !target.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES)) {
            int left = Math.max(10, RushRules.end(wrapSince) - t);
            r[15] = -100.0D;
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH, r);
            target.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, left, 9, false, false, false));
        }
        int thrust = RushRules.thrust(wrapSince);
        if (t >= thrust - 2 && t <= thrust + 6 && r.length > 12 && r[12] < 0.5D && target != null
                && target.position().distanceTo(player.position()) < 3.2D) {
            r[12] = 1.0D;
            if (rushHurt(player, id, target, base * RushRules.DMG_THRUST, r)) {
                rushKnock(player, target, r, t);
                net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                        new io.github.verycooltimo.murim.network.RushPayload(player.getId(), o,
                                target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D), 0.0F, layer, 2));
            }
        }
    }

    private static final int RUSH_CATCH_MAX = 8;

    /**
     * Ураган волочит встречных (автор 03.10: «жёстче»): каждый, кого накрыл рукав до наводки,
     * один раз получает скользящий удар и дальше тащится головой урагана вперёд и по кругу.
     * Без оглушения — иначе стан гасит скорость (TargetLock.stunTick); стан даётся при сбросе.
     */
    private static void rushDrag(LivingEntity player, net.minecraft.resources.ResourceLocation id, double[] r, Vec3 axis0, Vec3 f,
                                 double head, double base, LivingEntity stop) {
        if (r.length <= 16 + RUSH_CATCH_MAX) {
            return;
        }
        Vec3 tip = axis0.add(f.scale(head));
        for (LivingEntity t : candidates(player, new AABB(tip, tip).inflate(RushRules.CATCH_WIDTH + 1.0D, RushRules.CATCH_WIDTH + 1.5D, RushRules.CATCH_WIDTH + 1.0D))) {
            if (t == stop || t instanceof net.minecraft.world.entity.decoration.ArmorStand || (r[14] >= 0.0D && t.getId() == (int) r[14])) {
                continue;
            }
            Vec3 rel = io.github.verycooltimo.murim.combat.TargetLock.centre(t).subtract(axis0);
            double along = rel.dot(f);
            Vec3 radial = rel.subtract(f.scale(along));
            if (along < head - 3.0D || along > head + 0.8D || radial.length() > RushRules.CATCH_WIDTH + t.getBbWidth() * 0.5D) {
                continue;
            }
            boolean known = false;
            for (int i = 0; i < (int) r[16]; i++) {
                known |= (int) r[17 + i] == t.getId();
            }
            if (!known) {
                if ((int) r[16] >= RUSH_CATCH_MAX) {
                    continue;
                }
                r[17 + (int) r[16]] = t.getId();
                r[16] += 1.0D;
                player.setData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH, r);
                t.invulnerableTime = 0;
                if (t.hurt(Casters.attack(player), (float) (base * RushRules.DMG_GRAZE))) {
                    Casters.onHit(player, id, t);
                }
            }
            // Тащит вперёд со скоростью головы и закручивает вокруг оси (боковая составляющая).
            Vec3 spin = f.cross(radial.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 1.0D, 0.0D) : radial.normalize()).scale(0.25D);
            Vec3 toAxis = radial.scale(-0.12D);
            t.setDeltaMovement(f.scale(RushRules.SPEED * 0.95D).add(spin).add(toAxis).add(0.0D, 0.08D, 0.0D));
            t.hurtMarked = true;
            t.fallDistance = 0.0F;
        }
    }

    /** Ураган ударил в наводку: волочёных раскидывает в стороны и оглушает. */
    private static void rushFling(double[] r, Vec3 f, LivingEntity player) {
        if (r.length <= 16 + RUSH_CATCH_MAX) {
            return;
        }
        Vec3 side = new Vec3(-f.z, 0.0D, f.x);
        for (int i = 0; i < (int) r[16]; i++) {
            if (player.level().getEntity((int) r[17 + i]) instanceof LivingEntity t && t.isAlive()) {
                double sgn = (i % 2 == 0) ? 1.0D : -1.0D;
                t.setDeltaMovement(f.scale(0.5D).add(side.scale(0.9D * sgn)).add(0.0D, 0.45D, 0.0D));
                t.hurtMarked = true;
                boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
                if (!boss && !(t instanceof net.minecraft.world.entity.player.Player)) {
                    t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, 30, 9, false, false, false));
                }
            }
        }
    }

    /**
     * Отброс уколом: цель срывается с заморозки и улетает по ходу рывка с подбросом; оглушение
     * снимается на полёт (stunTick гасит скорость) и возвращается через 7 тиков (см. rushTick).
     */
    private static void rushKnock(LivingEntity player, LivingEntity target, double[] r, int t) {
        Vec3 dir = target.position().subtract(new Vec3(r[0], r[1], r[2]));
        dir = new Vec3(dir.x, 0.0D, dir.z);
        dir = dir.lengthSqr() < 1.0E-4D ? new Vec3(r[3], 0.0D, r[4]) : dir.normalize();
        boolean boss = target.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        double k = boss ? 0.3D : 1.0D;
        target.setData(io.github.verycooltimo.murim.registry.ModAttachments.FROZEN, new long[] {target.level().getGameTime(),
                target.getData(io.github.verycooltimo.murim.registry.ModAttachments.FROZEN)[1]});
        target.removeEffect(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN);
        target.setDeltaMovement(dir.scale(RushRules.KNOCK * k).add(0.0D, RushRules.LIFT * k, 0.0D));
        target.hurtMarked = true;
        r[15] = t;
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH, r);
    }

    private static boolean rushHurt(LivingEntity player, net.minecraft.resources.ResourceLocation id, LivingEntity t, double amount, double[] r) {
        t.invulnerableTime = 0;
        if (!t.hurt(Casters.attack(player), (float) amount)) {
            return false;
        }
        if (r[11] < 0.5D) {
            r[11] = 1.0D;
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.RUSH, r);
            boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            int ticks = t instanceof net.minecraft.world.entity.player.Player ? 12 : boss ? 10 : RushRules.end(r[6] < 0.0D ? 0 : (int) r[6]);
            t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, ticks, 9, false, false, false));
            t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, ticks, 9, false, false, false));
        }
        Casters.onHit(player, id, t);
        return true;
    }

    // ------------------------------------------------------------------ Казнь Цветущей Сливы

    /** Выход клонов: цель по взгляду до 6 блоков фиксируется вместе с точкой у её стоп. */
    private static boolean execStart(ServerPlayer player, net.minecraft.resources.ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        double base = TechniqueDamage.base(player, id);
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
        // Захваченная цель (03.10), иначе ближайшая в конусе 30° взгляда до 12 блоков — в том
        // числе выше или ниже: в небе она замирает, и клоны идут к ней на её высоту.
        LivingEntity target = io.github.verycooltimo.murim.combat.TargetLock.locked(player, 16.0D);
        if (target == null) {
            double best = Double.MAX_VALUE;
            double cone = Math.cos(Math.toRadians(30.0D));
            for (LivingEntity t : candidates(player, player.getBoundingBox().inflate(12.5D))) {
                Vec3 to = io.github.verycooltimo.murim.combat.TargetLock.centre(t).subtract(player.getEyePosition());
                double d = to.length();
                // Стойки для брони — не противники (на стенде они ближе цели и перехватывали выбор).
                if (t instanceof net.minecraft.world.entity.decoration.ArmorStand
                        || d > 12.0D || !io.github.verycooltimo.murim.combat.TargetLock.inCone(player, t, 30.0D) || !player.hasLineOfSight(t)) {
                    continue;
                }
                if (d < best) {
                    best = d;
                    target = t;
                }
            }
        }
        if (target != null) {
            io.github.verycooltimo.murim.combat.TargetLock.freeze(target, ExecRules.FINAL + 12);
        }
        Vec3 centre = target != null ? target.position() : origin.add(forward.scale(4.0D));
        double baseAngle = Math.atan2(origin.z - centre.z, origin.x - centre.x);
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.EXEC, new double[] {
                centre.x, centre.y, centre.z, baseAngle, layer, 0, target == null ? -1 : target.getId(), 0, origin.x, origin.z});
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new io.github.verycooltimo.murim.network.ExecPayload(player.getId(), origin, centre, (float) baseAngle, layer, 0));
        return false;
    }

    /** Шкала Казни после выхода клонов, см. ExecRules. */
    public static void execTick(ServerPlayer player, net.minecraft.resources.ResourceLocation id, int since) {
        double[] e = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.EXEC);
        int layer = (int) e[4];
        if (layer <= 0) {
            return;
        }
        double base = TechniqueDamage.base(player, id);
        LivingEntity target = e[6] >= 0 && player.level().getEntity((int) e[6]) instanceof LivingEntity le && le.isAlive() ? le : null;
        // Клоны ведут живую позицию цели: отошла или подошла — заход идёт за ней.
        if (target != null && target.position().distanceTo(player.position()) < 18.0D) {
            e[0] = target.getX();
            e[1] = target.getY();
            e[2] = target.getZ();
        }
        Vec3 centre = new Vec3(e[0], e[1], e[2]);
        // Контакт каждого клона: цель у точки сбора — удар (без отбрасывания), метка на финал.
        for (int i = 0; i < ExecRules.clones(layer); i++) {
            if (since == ExecRules.contact(i) && target != null) {
                if (execHurt(player, id, target, base * ExecRules.DMG_CLONE)) {
                    e[7] += 1.0D;
                }
            }
        }
        // Оригинал плавно проходит сбоку цели и встаёт за её спиной.
        if (ExecRules.finale(layer) && since == ExecRules.DASH) {
            Vec3 aim = target != null ? target.position() : centre;
            Vec3 from = new Vec3(e[8], player.getY(), e[9]);
            Vec3 dir = new Vec3(aim.x - from.x, 0.0D, aim.z - from.z);
            if (dir.lengthSqr() > 1.0E-4D) {
                dir = dir.normalize();
                // Дальше клонов: те садятся в ≤2,2 блока, оригинал — в 4,5; на высоте цели
                // (в небе — взлетает к ней и после падает).
                Vec3 dest = aim.add(dir.scale(4.5D));
                Vec3 path = dest.subtract(player.position());
                io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, path.normalize(), path.length(), ExecRules.DASH_TICKS);
            }
        }
        // Казнь: шесть разрезов разом — одним событием урона по числу попавших клонов.
        if (since == ExecRules.FINAL) {
            int marks = (int) e[7];
            if (ExecRules.finale(layer) && target != null && marks > 0) {
                if (execHurt(player, id, target, base * ExecRules.DMG_FINAL * marks)) {
                    net.neoforged.neoforge.network.PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                            new io.github.verycooltimo.murim.network.ExecPayload(player.getId(), player.position(),
                                    target.position().add(0.0D, target.getBbHeight() * 0.6D, 0.0D), (float) e[3], layer, 1));
                }
            }
            e[7] = 0.0D;
        }
        player.setData(io.github.verycooltimo.murim.registry.ModAttachments.EXEC, e);
    }

    private static boolean execHurt(ServerPlayer player, net.minecraft.resources.ResourceLocation id, LivingEntity t, double amount) {
        t.invulnerableTime = 0;
        if (!t.hurt(player.damageSources().playerAttack(player), (float) amount)) {
            return false;
        }
        t.setDeltaMovement(t.getDeltaMovement().multiply(0.2D, 1.0D, 0.2D));
        t.hurtMarked = true;
        double[] e = player.getData(io.github.verycooltimo.murim.registry.ModAttachments.EXEC);
        if (e[5] < 0.5D) {
            e[5] = 1.0D;
            player.setData(io.github.verycooltimo.murim.registry.ModAttachments.EXEC, e);
            boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
            int ticks = t instanceof net.minecraft.world.entity.player.Player ? 12 : boss ? 10 : ExecRules.END;
            t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN, ticks, 9, false, false, false));
            t.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS, ticks, 9, false, false, false));
        }
        io.github.verycooltimo.murim.mastery.MasteryService.onHit(player, id, t);
        return true;
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

    private static List<LivingEntity> candidates(LivingEntity player, AABB box) {
        return Casters.level(player).getEntitiesOfClass(LivingEntity.class, box,
                candidate -> candidate != player && candidate.isAlive() && !candidate.isSpectator()
                        && (!(player instanceof Casters.Caster c) || c.canHit(candidate)));
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
