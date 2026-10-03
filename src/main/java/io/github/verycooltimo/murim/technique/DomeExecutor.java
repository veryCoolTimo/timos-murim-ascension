package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TechniqueState;
import io.github.verycooltimo.murim.network.DomePayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.ProjectileImpactEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Сервер Купола Цветущей Сливы (см. {@link DomeRules}). Состояние — вложение {@code DOME}:
 * {центр x, y, z, направление x, z, наклон, слой, пул, пул макс., разрушен 0/1}.
 *
 * <p>Барьер сажается в начале каста ({@link #begin}): центр и направление фиксируются в мире.
 * Каждый тик до конца подпитки: снаряды, пересекающие готовую дугу снаружи, гасятся; мобы
 * у дуги отталкиваются наружу; урон из-за дуги отменяется ({@link #onIncomingDamage}), пока
 * хватает пула. Пул исчерпан — сеть рвётся; мастер вышел из-за щита — распад.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class DomeExecutor {

    private static final int PX = 0;
    private static final int FX = 3;
    private static final int TILT = 5;
    private static final int LAYER = 6;
    private static final int POOL = 7;
    private static final int POOL_MAX = 8;
    private static final int BROKEN = 9;

    /** Начало каста: направление — захваченная цель, иначе ближайший враг под взглядом, иначе взгляд. */
    public static void begin(ServerPlayer player, ResourceLocation id) {
        int layer = Math.max(0, io.github.verycooltimo.murim.mastery.MasteryService.layer(player, id));
        Vec3 look = player.getLookAngle();
        Vec3 chest = player.position().add(0.0D, DomeRules.PIVOT_Y, 0.0D);
        LivingEntity target = io.github.verycooltimo.murim.combat.TargetLock.locked(player, 32.0D);
        Vec3 forward;
        double elevation;
        if (target != null) {
            Vec3 to = io.github.verycooltimo.murim.combat.TargetLock.centre(target).subtract(chest);
            forward = to;
            elevation = Math.toDegrees(Math.atan2(to.y, Math.hypot(to.x, to.z)));
        } else {
            forward = look;
            elevation = -player.getXRot();
        }
        // Против врага в небе форма наклоняется к нему; до 20° — стволы из земли.
        double tilt = elevation > 20.0D ? Math.min(55.0D, elevation - 10.0D) : 0.0D;
        DomeRules.Frame f = new DomeRules.Frame(player.position(), forward, tilt);
        double pool = DomeRules.pool(layer, player.getMaxHealth());
        player.setData(ModAttachments.DOME, new double[] {f.pivot().x, f.pivot().y, f.pivot().z,
                f.forward().x, f.forward().z, f.tilt(), layer, pool, pool, 0});
        if (layer > 0) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new DomePayload(player.getId(), f.pivot(), f.forward(), (float) f.tilt(), layer, DomePayload.BEGIN));
        }
    }

    /** IMPACT — смыкание сети: удар не наносится; подпитка замедляет мастера до конца удержания. */
    public static boolean raise(ServerPlayer player) {
        double[] d = player.getData(ModAttachments.DOME);
        if (d[LAYER] > 0.5D && d[BROKEN] < 0.5D) {
            player.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                    DomeRules.HOLD_END - DomeRules.RAISE, 3, false, false, false));
        }
        return false;
    }

    private static DomeRules.Frame frame(double[] d) {
        return new DomeRules.Frame(new Vec3(d[PX], d[PX + 1], d[PX + 2]), new Vec3(d[FX], 0.0D, d[FX + 1]), d[TILT]);
    }

    /** Барьер держит: техника идёт, это Купол, не разрушен и подпитка не кончилась. */
    private static boolean holding(ServerPlayer player, int[] tickOut) {
        TechniqueState state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (!state.isActive() || state.techniqueId() == null) {
            return false;
        }
        TechniqueDefinition def = TechniqueLoader.all().get(state.techniqueId());
        if (def == null || !(def.behavior() instanceof TechniqueBehavior.PlumDome)) {
            return false;
        }
        tickOut[0] = state.tick();
        double[] d = player.getData(ModAttachments.DOME);
        return d[BROKEN] < 0.5D && state.tick() < DomeRules.HOLD_END;
    }

    /** Каждый тик техники (от начала): снаряды, оттеснение, выход мастера из-за щита. */
    public static void tick(ServerPlayer player, ResourceLocation id, int tick) {
        captureVolley(player, tick);
        double[] d = player.getData(ModAttachments.DOME);
        int layer = (int) d[LAYER];
        if (layer <= 0 || d[BROKEN] > 0.5D || tick >= DomeRules.HOLD_END || tick < DomeRules.SWINGS[0]) {
            return;
        }
        DomeRules.Frame f = frame(d);
        double[] me = f.local(player.position());
        if (Math.sqrt(me[0] * me[0] + me[2] * me[2]) > DomeRules.LEAVE && tick > DomeRules.SWINGS[0] + DomeRules.GROW) {
            // Мастер вышел из-за щита: подпитки нет — сеть распадается.
            d[BROKEN] = 1.0D;
            player.setData(ModAttachments.DOME, d);
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                    new DomePayload(player.getId(), player.position(), Vec3.ZERO, 0.0F, layer, DomePayload.LOST));
            return;
        }
        AABB box = new AABB(f.pivot(), f.pivot()).inflate(DomeRules.RADIUS + 6.0D);
        // Снаряды: путь за тик (прошлый и следующий) пересекает готовую дугу снаружи — стоп.
        for (Projectile p : player.serverLevel().getEntitiesOfClass(Projectile.class, box,
                p -> p.isAlive() && p.getOwner() != player)) {
            Vec3 now = p.position();
            Vec3 prev = new Vec3(p.xo, p.yo, p.zo);
            Vec3 next = now.add(p.getDeltaMovement());
            double u = DomeRules.crossing(layer, f, prev, now, tick);
            Vec3 at = u >= 0.0D ? prev.lerp(now, u) : null;
            if (at == null) {
                u = DomeRules.crossing(layer, f, now, next, tick);
                at = u >= 0.0D ? now.lerp(next, u) : null;
            }
            if (at != null) {
                stop(player, d, layer, p, at);
                if (d[BROKEN] > 0.5D) {
                    return;
                }
            }
        }
        // Стена: живые у дуги снаружи (и проникшие внутрь) отталкиваются по радиусу наружу.
        for (LivingEntity e : player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                e -> e != player && e.isAlive() && !e.isSpectator() && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand))) {
            double[] l = f.local(e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D));
            double r = DomeRules.rho(l);
            double reach = DomeRules.RADIUS + 0.35D + e.getBbWidth() * 0.5D;
            if (r > reach || r < 0.6D || Math.abs(DomeRules.angle(l)) > DomeRules.SECTOR
                    || l[1] < -1.0D || l[1] > DomeRules.HEIGHT * DomeRules.scale(layer) + 1.0D
                    || !DomeRules.covered(layer, DomeRules.angle(l), tick)) {
                continue;
            }
            Vec3 out = f.side().scale(l[0]).add(f.ahead().scale(l[2]));
            out = new Vec3(out.x, 0.0D, out.z);
            if (out.lengthSqr() < 1.0E-6D) {
                continue;
            }
            out = out.normalize();
            Vec3 v = e.getDeltaMovement();
            double inward = -v.dot(out);
            e.setDeltaMovement(v.add(out.scale(Math.max(0.0D, inward) + 0.28D)).add(0.0D, 0.04D, 0.0D));
            e.hurtMarked = true;
        }
    }

    /**
     * Стенд (MURIM_CAPTURE_VOLLEY=1, только при съёмке): цель стреляет в мастера стрелами —
     * канон «Стена останавливает кинжалы» проверяется на кадрах.
     */
    private static final int[] VOLLEY = {18, 34, 42, 50, 58, 64, 70, 76, 84};

    private static void captureVolley(ServerPlayer player, int tick) {
        if (!"true".equals(System.getProperty("murim.capture")) || !"1".equals(System.getenv("MURIM_CAPTURE_VOLLEY"))) {
            return;
        }
        boolean fire = false;
        for (int v : VOLLEY) {
            fire |= v == tick;
        }
        if (!fire) {
            return;
        }
        LivingEntity shooter = null;
        double best = 20.0D;
        for (LivingEntity e : player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(16.0D),
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
        Vec3 to = player.position().add(0.0D, 1.2D + 0.4D * Math.sin(tick), 0.0D);
        net.minecraft.world.entity.projectile.Arrow arrow = new net.minecraft.world.entity.projectile.Arrow(player.level(),
                from.x, from.y, from.z, new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.ARROW), null);
        arrow.setOwner(shooter);
        Vec3 dir = to.subtract(from);
        arrow.shoot(dir.x, dir.y + dir.horizontalDistance() * 0.02D, dir.z, 2.2F, 0.0F);
        player.level().addFreshEntity(arrow);
        MurimMod.LOGGER.info("Купол-стенд: стрела на тике {} от {}", tick, shooter.getName().getString());
    }

    /** Снаряд остановлен сетью: исчезает, пул снимается по его силе. */
    private static void stop(ServerPlayer player, double[] d, int layer, Projectile p, Vec3 at) {
        double cost = p instanceof AbstractArrow arrow ? arrow.getBaseDamage() * Math.max(1.0D, p.getDeltaMovement().length()) : 3.0D;
        Vec3 from = p.getDeltaMovement().lengthSqr() > 1.0E-6D ? p.getDeltaMovement().normalize() : Vec3.ZERO;
        p.discard();
        absorb(player, d, layer, cost, at, from);
    }

    /** Списать пул и сообщить клиенту: погашено или сеть порвалась. */
    private static double absorb(ServerPlayer player, double[] d, int layer, double cost, Vec3 at, Vec3 from) {
        double taken = Math.min(cost, d[POOL]);
        d[POOL] -= taken;
        boolean broke = d[POOL] <= 1.0E-3D;
        if (broke) {
            d[BROKEN] = 1.0D;
        }
        player.setData(ModAttachments.DOME, d);
        MurimMod.LOGGER.info("Купол: погашено {} (пул {} / {}){}", String.format("%.1f", cost), String.format("%.1f", d[POOL]),
                String.format("%.1f", d[POOL_MAX]), broke ? ", сеть порвана" : "");
        float share = (float) Math.min(1.0D, cost / Math.max(1.0D, d[POOL_MAX]));
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new DomePayload(player.getId(), at, from, share, layer, broke ? DomePayload.BREAK : DomePayload.BLOCK));
        player.level().playSound(null, at.x, at.y, at.z, net.minecraft.sounds.SoundEvents.SHIELD_BLOCK,
                net.minecraft.sounds.SoundSource.PLAYERS, 0.7F, broke ? 0.6F : 1.3F);
        return cost - taken;
    }

    /**
     * Урон из-за дуги гасится, пока хватает пула; остаток проходит. Высокий приоритет: отменённый
     * урон не сбивает технику и не будит другие реакции (они не получают отменённое событие).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onIncomingDamage(LivingIncomingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        int[] tick = new int[1];
        if (!holding(player, tick)) {
            return;
        }
        // API: reference/minecraft-src/net/minecraft/world/damagesource/DamageSource.java#getSourcePosition
        Vec3 src = event.getSource().getSourcePosition();
        if (src == null) {
            return;
        }
        double[] d = player.getData(ModAttachments.DOME);
        int layer = (int) d[LAYER];
        DomeRules.Frame f = frame(d);
        double[] me = f.local(player.position());
        double[] l = f.local(src);
        if (Math.sqrt(me[0] * me[0] + me[2] * me[2]) > DomeRules.LEAVE || !DomeRules.outside(Math.max(1, layer), l)) {
            return;
        }
        if (layer <= 0) {
            // Слой 0 — учебный блок: только ослабить удар спереди.
            if (tick[0] >= DomeRules.TRAINING_FROM && tick[0] <= DomeRules.TRAINING_TO) {
                event.setAmount((float) (event.getAmount() * DomeRules.TRAINING_FACTOR));
            }
            return;
        }
        if (!DomeRules.covered(layer, DomeRules.angle(l), tick[0])) {
            return;
        }
        Vec3 at = DomeRules.contact(layer, f, src);
        Vec3 from = at.subtract(src);
        double rest = absorb(player, d, layer, event.getAmount(), at, from.lengthSqr() > 1.0E-6D ? from.normalize() : Vec3.ZERO);
        if (rest <= 1.0E-3D) {
            event.setCanceled(true);
        } else {
            event.setAmount((float) rest);
        }
    }

    /** Запасной путь: снаряд, долетевший до мастера сквозь готовую дугу (быстрый, в обход тика). */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onProjectileImpact(ProjectileImpactEvent event) {
        if (!(event.getRayTraceResult() instanceof EntityHitResult hit) || !(hit.getEntity() instanceof ServerPlayer player)
                || event.getProjectile().getOwner() == player) {
            return;
        }
        int[] tick = new int[1];
        if (!holding(player, tick)) {
            return;
        }
        double[] d = player.getData(ModAttachments.DOME);
        int layer = (int) d[LAYER];
        if (layer <= 0) {
            return;
        }
        Projectile p = event.getProjectile();
        DomeRules.Frame f = frame(d);
        Vec3 back = p.position().subtract(p.getDeltaMovement().scale(4.0D));
        double[] l = f.local(back);
        if (!DomeRules.outside(layer, l) || !DomeRules.covered(layer, DomeRules.angle(l), tick[0])) {
            return;
        }
        event.setCanceled(true);
        stop(player, d, layer, p, DomeRules.contact(layer, f, back));
    }

    /** Меч занят подпиткой: мастер не бьёт, пока сеть держится. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    static void onAttack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            int[] tick = new int[1];
            if (holding(player, tick) && player.getData(ModAttachments.DOME)[LAYER] > 0.5D) {
                event.setCanceled(true);
            }
        }
    }

    private DomeExecutor() {
    }
}
