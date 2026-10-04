package io.github.verycooltimo.murim.technique;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.TangPayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.EntityTickEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Сервер Скрытого Оружия Клана Тан (см. {@link TangRules}, docs/design/techniques/tang-daggers-spec.md).
 *
 * <p>Состояние каста — вложение игрока {@code TANG} (не сохраняется, клиенту не синхронизируется: клиент
 * видит сущности-кинжалы и пакеты {@link TangPayload}): {форма, слой, тик от IMPACT или −1, id цели,
 * цель цепи Громов, длина цепи, попаданий, главное попадание было 0/1, затем пары {id, число попаданий}}.
 * Яд «Пожинающий Душу» — вложение цели {@code TANG_POISON}: {осталось импульсов, id мастера, урон, до импульса}.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class TangExecutor {

    private static final int PAIRS = 8;
    private static final int SIZE = PAIRS + 24;

    private TangExecutor() {
    }

    // ------------------------------------------------------------------ старт

    /** Начало IMPACT формы: запомнить каст; кинжалы выпускаются по шкале в тике игрока. */
    public static boolean start(ServerPlayer player, ResourceLocation id) {
        int form = TangRules.form(id);
        if (form < 0) {
            return false;
        }
        int layer = Math.max(0, MasteryService.layer(player, id));
        double[] s = new double[SIZE];
        s[0] = form;
        s[1] = layer;
        s[2] = 0;
        LivingEntity target = choose(player, form);
        s[3] = target == null ? -1 : target.getId();
        s[4] = -1;
        for (int i = 8; i < SIZE; i += 2) {
            s[i] = -1;
        }
        player.setData(ModAttachments.TANG, s);
        release(player, s, 0);
        return false;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        double[] s = player.getData(ModAttachments.TANG);
        if (s.length < SIZE || s[2] < 0) {
            return;
        }
        if (!player.isAlive()) {
            s[2] = -1;
            return;
        }
        int since = (int) s[2] + 1;
        s[2] = since > 120 ? -1 : since;
        release(player, s, since);
        // Семь Звёзд: вспышка фиксирует точку горла, без второго нажатия — схождение в срок.
        if ((int) s[0] == TangRules.STARS && since == TangRules.STAR_FLARE) {
            flare(player, s);
        }
        if ((int) s[0] == TangRules.STARS && since == TangRules.STAR_STRIKE) {
            strike(player, s);
        }
        // Двенадцатый с неба: мастер не разбивается о землю после взлёта.
        if ((int) s[0] == TangRules.TWELVE && since >= TangRules.LEAP && since < TangRules.LEAP + 60) {
            player.fallDistance = 0.0F;
        }
    }

    /** Выпуски по шкале формы на тике {@code since} от IMPACT. */
    private static void release(ServerPlayer player, double[] s, int since) {
        int form = (int) s[0];
        int layer = (int) s[1];
        double h = base(player, TangRules.technique(form)) * TangRules.power(layer);
        LivingEntity target = s[3] >= 0 && player.level().getEntity((int) s[3]) instanceof LivingEntity t && t.isAlive() ? t : null;
        switch (form) {
            case TangRules.FIVE -> {
                for (int k = 0; k < TangRules.fiveCount(layer); k++) {
                    if (TangRules.fiveRelease(k, layer) == since) {
                        fiveShot(player, layer, k, h);
                    }
                }
            }
            case TangRules.TWELVE -> {
                if (since == 0) {
                    twelve(player, layer, h, target);
                }
                if (TangRules.sky(layer) && since == TangRules.LEAP) {
                    leap(player, s);
                }
                if (TangRules.sky(layer) && since == TangRules.SKY_THROW) {
                    skyThrow(player, layer, h, s);
                }
            }
            case TangRules.STARS -> {
                // Из двух рукавов по очереди: кинжал за тик (3 + 4 за 7 тиков, codex 03.10).
                if (since < TangRules.starCount(layer)) {
                    stars(player, layer, h, target, since);
                }
            }
            case TangRules.THREE -> {
                if (since == 0) {
                    three(player, layer, h);
                }
            }
            case TangRules.FLASH -> {
                if (since == 0) {
                    flash(player, layer, h, false);
                }
                // Слой 8: второй кинжал «без ци» в тени первого (гл. 196).
                if (since == 2 && TangRules.shadowed(layer)) {
                    flash(player, layer, h, true);
                }
            }
            case TangRules.RETURN -> {
                if (since == 0) {
                    sleeveReturn(player, layer, h);
                }
            }
            default -> {
                if (since == 0) {
                    carp(player, layer, h, target);
                }
            }
        }
    }

    // ------------------------------------------------------------------ формы

    /**
     * Пять Громов: пять кинжалов почти разом, веером в разные стороны, каждый доворачивает к цели
     * и приходит со своей стороны (автор 03.10). Цель выбирается в момент выпуска — игрок ведёт прицел.
     */
    private static void fiveShot(ServerPlayer player, int layer, int k, double h) {
        Vec3 hand = hand(player, true);
        LivingEntity t = TargetLock.locked(player, TangRules.FIVE_RANGE);
        if (t == null) {
            t = nearestInCone(player, TangRules.FIVE_RANGE, 30.0D);
        }
        double speed = TangRules.fiveSpeed(k, layer);
        Vec3 aim = t != null ? TangDagger.throat(t) : lookPoint(player, TangRules.FIVE_RANGE);
        Vec3 axis = aim.subtract(hand).normalize();
        double[] fan = TangRules.FIVE_FAN[k];
        // Слой 0 — один прямой кинжал; веер раскрывается со слоя 1.
        Vec3 dir = layer <= 0 ? axis : rotate(axis, fan[0], fan[1]);
        TangDagger d = new TangDagger(player.level(), player, TangRules.FIVE, layer, k);
        d.setPos(hand.add(dir.scale(0.25D)));
        d.setDeltaMovement(dir.scale(speed));
        d.setMode(TangDagger.STEER);
        d.speed = speed;
        d.turn = TangRules.FIVE_TURN;
        d.aim = aim;
        d.damage = h;
        d.chainSlot = k;
        d.life = (int) Math.ceil(TangRules.FIVE_RANGE / speed) + 8;
        d.setTarget(t);
        face(d, dir);
        player.level().addFreshEntity(d);
        send(player, TangRules.FIVE, TangPayload.SHOT, layer, hand, dir, k, 0);
    }

    /**
     * Три Лезвия Одного Мгновения (гл. 195, попытка 3): прямое и два по бокам, боковые быстрее — все три
     * приходят в один тик в точку, где цель БУДЕТ (упреждение по её скорости). Ответ — остановиться
     * или развернуться в момент броска; шаг вбок не спасает: бока закрыты дугами.
     */
    private static void three(ServerPlayer player, int layer, double h) {
        Vec3 hand = hand(player, true);
        LivingEntity t = TargetLock.locked(player, TangRules.THREE_RANGE);
        if (t == null) {
            t = nearestInCone(player, TangRules.THREE_RANGE, 30.0D);
        }
        Vec3 to;
        int ticks;
        if (t != null) {
            Vec3 at = TangDagger.throat(t);
            ticks = TangRules.threeTicks(at.distanceTo(hand));
            Vec3 v = t.getDeltaMovement();
            to = at.add(new Vec3(v.x, 0.0D, v.z).scale(ticks));
        } else {
            to = lookPoint(player, 12.0D);
            ticks = TangRules.threeTicks(to.distanceTo(hand));
        }
        Vec3 axis = to.subtract(hand);
        Vec3 side = new Vec3(-axis.z, 0.0D, axis.x);
        side = side.lengthSqr() < 1.0E-6D ? new Vec3(1.0D, 0.0D, 0.0D) : side.normalize();
        double bow = TangRules.THREE_BOW * axis.length();
        int n = TangRules.threeCount(layer);
        for (int i = 0; i < n; i++) {
            int k = n == 2 ? i * 2 : i;
            TangDagger d = new TangDagger(player.level(), player, TangRules.THREE, layer, k);
            d.setPos(hand);
            d.setMode(TangDagger.PATH);
            d.pathFrom = hand;
            d.pathTo = to;
            d.pathSide = side;
            d.pathBow = layer < 3 ? bow * 0.5D : bow;
            d.pathK = k;
            d.pathTicks = ticks;
            d.kinked = k == 2 && TangRules.kink(layer);
            d.damage = h;
            d.life = ticks + 20;
            d.setTarget(t);
            Vec3 first = TangRules.threePoint(k, 1.0D / ticks, hand, to, side, d.pathBow, d.kinked).subtract(hand);
            d.setDeltaMovement(first);
            face(d, first.normalize());
            player.level().addFreshEntity(d);
            send(player, TangRules.THREE, TangPayload.SHOT, layer, hand, first.normalize(), k, ticks);
        }
    }

    /**
     * Молниеносное Похищение Жизни (섬전탈명, гл. 195): «не было огромной силы… просто скорость. Кинжал,
     * исчезнувший после того как его выпустили… появился прямо перед». Перебивает замах врага.
     */
    private static void flash(ServerPlayer player, int layer, double h, boolean shadow) {
        Vec3 hand = hand(player, true);
        LivingEntity t = TargetLock.locked(player, TangRules.FLASH_RANGE);
        if (t == null) {
            // 30°, как помощь прицела у остальных форм: на 20° цель чуть сбоку не бралась и кинжал уходил мимо.
            t = nearestInCone(player, TangRules.FLASH_RANGE, 30.0D);
        }
        Vec3 aim;
        if (t != null) {
            Vec3 at = TangDagger.throat(t);
            double time = at.distanceTo(hand) / TangRules.FLASH_SPEED;
            Vec3 v = t.getDeltaMovement();
            aim = at.add(new Vec3(v.x, 0.0D, v.z).scale(time));
        } else {
            aim = lookPoint(player, TangRules.FLASH_RANGE);
        }
        Vec3 dir = aim.subtract(hand).normalize();
        TangDagger d = new TangDagger(player.level(), player, TangRules.FLASH, layer, shadow ? 1 : 0);
        d.setPos(hand);
        d.setMode(TangDagger.STRAIGHT);
        d.speed = TangRules.FLASH_SPEED;
        d.setDeltaMovement(dir.scale(TangRules.FLASH_SPEED));
        d.setFlag(4, TangRules.vanish(layer));
        d.aim = aim;
        d.damage = h;
        d.life = (int) Math.ceil(TangRules.FLASH_RANGE / TangRules.FLASH_SPEED) + 2;
        d.setTarget(t);
        face(d, dir);
        player.level().addFreshEntity(d);
        if (!shadow) {
            send(player, TangRules.FLASH, TangPayload.SHOT, layer, hand, dir, 0, 0);
        }
    }

    /**
     * Возврат Лезвий в Рукав (гл. 195: «Все метательные лезвия, лежавшие на земле, были возвращены в его
     * рукав»): воткнутые промахи срываются разом и летят по прямым к мастеру, режа всех на линиях.
     */
    private static void sleeveReturn(ServerPlayer player, int layer, double h) {
        List<TangDagger> stuck = new ArrayList<>(player.serverLevel().getEntitiesOfClass(TangDagger.class,
                player.getBoundingBox().inflate(TangRules.RETURN_RANGE),
                d -> d.getOwner() == player && d.isAlive() && d.mode() == TangDagger.STUCK
                        && d.distanceTo(player) <= TangRules.RETURN_RANGE));
        stuck.sort(Comparator.comparingDouble(d -> d.distanceToSqr(player)));
        int n = Math.min(stuck.size(), TangRules.returnCount(layer));
        Vec3 home = player.position().add(0.0D, player.getBbHeight() * 0.6D, 0.0D);
        for (int i = 0; i < n; i++) {
            TangDagger d = stuck.get(i);
            Vec3 via = null;
            if (layer >= 6) {
                // Загиб к врагу у линии (не дальше 15°): промах становится вторым броском.
                Vec3 line = home.subtract(d.position()).normalize();
                double best = Math.cos(Math.toRadians(TangRules.RETURN_BEND));
                for (LivingEntity e : candidates(player, TangRules.RETURN_RANGE)) {
                    Vec3 to = TangDagger.throat(e).subtract(d.position());
                    double len = to.length();
                    if (len > 0.5D && len < d.position().distanceTo(home)) {
                        double c = to.scale(1.0D / len).dot(line);
                        if (c > best) {
                            best = c;
                            via = TangDagger.throat(e);
                        }
                    }
                }
            }
            d.returnToSleeve(h, via);
            send(player, TangRules.RETURN, TangPayload.RECALL, layer, d.position(), Vec3.ZERO, d.getId(), 1);
        }
    }

    /** Только для стенда: воткнуть {@code n} кинжалов игрока веером позади точки {@code centre}. */
    public static void spawnStuckForCapture(ServerPlayer player, Vec3 centre, int n) {
        Vec3 back = new Vec3(centre.x - player.getX(), 0.0D, centre.z - player.getZ()).normalize();
        Vec3 side = new Vec3(-back.z, 0.0D, back.x);
        for (int i = 0; i < n; i++) {
            double u = n == 1 ? 0.0D : i / (double) (n - 1) - 0.5D;
            Vec3 at = centre.add(back.scale(3.0D + 1.5D * Math.abs(u))).add(side.scale(u * 7.0D)).add(0.0D, 0.15D, 0.0D);
            TangDagger d = new TangDagger(player.level(), player, TangRules.FIVE, 7, i % 5);
            d.setPos(at);
            d.setMode(TangDagger.STUCK);
            d.life = TangRules.STUCK_TICKS;
            face(d, back.add(0.0D, -1.2D, 0.0D).normalize());
            player.level().addFreshEntity(d);
        }
    }

    /** Двенадцать: веер по дугам на все цели в конусе; захваченной — больше. */
    private static void twelve(ServerPlayer player, int layer, double h, LivingEntity locked) {
        int n = TangRules.twelveCount(layer);
        List<LivingEntity> targets = new ArrayList<>();
        if (locked != null) {
            targets.add(locked);
        }
        for (LivingEntity e : candidates(player, TangRules.TWELVE_RANGE)) {
            if (e != locked && TargetLock.inCone(player, e, TangRules.TWELVE_CONE * 0.5D) && player.hasLineOfSight(e)) {
                targets.add(e);
            }
        }
        targets.sort(Comparator.comparingDouble(e -> e == locked ? -1.0D : e.distanceToSqr(player)));
        Vec3 look = player.getLookAngle();
        Vec3 hand = hand(player, true);
        for (int k = 0; k < n; k++) {
            LivingEntity t = null;
            if (!targets.isEmpty()) {
                // Захваченной — каждый второй из первых восьми; дальше по кругу.
                if (locked != null && targets.size() > 1 && k < 8 && k % 2 == 0) {
                    t = locked;
                } else {
                    t = targets.get((k + (locked != null && targets.size() > 1 ? 1 : 0)) % targets.size());
                }
            }
            double yaw = TangRules.fanYaw(k, n);
            double pitch = ((k * 0.37D) % 1.0D - 0.5D) * 24.0D;
            Vec3 dir = rotate(look, yaw, pitch);
            Vec3 aim = t != null ? TangDagger.throat(t) : lookPoint(player, 16.0D).add(dir.subtract(look).scale(3.0D));
            double speed = TangRules.twelveSpeed(k);
            TangDagger d = new TangDagger(player.level(), player, TangRules.TWELVE, layer, k);
            d.setPos(hand.add(dir.scale(0.3D)));
            d.setDeltaMovement(dir.scale(speed));
            d.setMode(layer <= 0 ? TangDagger.STRAIGHT : TangDagger.STEER);
            if (layer <= 0) {
                d.setDeltaMovement(aim.subtract(hand).normalize().scale(speed));
            }
            d.speed = speed;
            d.turn = TangRules.TWELVE_TURN;
            d.aim = aim;
            d.damage = h;
            d.life = 50;
            d.setTarget(t);
            face(d, dir);
            player.level().addFreshEntity(d);
            send(player, TangRules.TWELVE, TangPayload.SHOT, layer, hand, dir, k, 0);
        }
    }

    /** Двенадцатый с неба (рефы «12th dagger technique»): мастер взлетает к солнцу. */
    private static void leap(ServerPlayer player, double[] s) {
        LivingEntity t = s[3] >= 0 && player.level().getEntity((int) s[3]) instanceof LivingEntity le && le.isAlive() ? le : null;
        if (t == null) {
            t = nearestInCone(player, TangRules.TWELVE_RANGE, TangRules.TWELVE_CONE * 0.5D);
        }
        if (t == null) {
            return;
        }
        s[3] = t.getId();
        // Под крышей не взлетает: нужен простор над головой.
        Vec3 head = player.position().add(0.0D, player.getBbHeight(), 0.0D);
        BlockHitResult roof = player.level().clip(new ClipContext(head, head.add(0.0D, TangRules.LEAP_HEIGHT, 0.0D),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        double reach = roof.getType() == HitResult.Type.MISS ? TangRules.LEAP_HEIGHT : roof.getLocation().y - head.y - 0.3D;
        if (reach < 2.0D) {
            s[3] = -1;
            return;
        }
        io.github.verycooltimo.murim.combat.FootworkService.sendDash(player, new Vec3(0.0D, 1.0D, 0.0D), reach, TangRules.LEAP_TICKS);
        send(player, TangRules.TWELVE, TangPayload.LEAP, (int) s[1], player.position(), new Vec3(0.0D, 1.0D, 0.0D), t.getId(), 0);
    }

    /** Бросок двенадцатого сверху в главную цель. */
    private static void skyThrow(ServerPlayer player, int layer, double h, double[] s) {
        LivingEntity t = s[3] >= 0 && player.level().getEntity((int) s[3]) instanceof LivingEntity le && le.isAlive() ? le : null;
        if (t == null) {
            return;
        }
        Vec3 hand = hand(player, true);
        Vec3 aim = TangDagger.throat(t);
        Vec3 dir = aim.subtract(hand).normalize();
        TangDagger d = new TangDagger(player.level(), player, TangRules.TWELVE, layer, 11);
        d.setFlag(1, true);
        d.setPos(hand);
        d.setDeltaMovement(dir.scale(TangRules.SKY_SPEED));
        d.setMode(TangDagger.STEER);
        d.speed = TangRules.SKY_SPEED;
        d.turn = 3.0D;
        d.aim = aim;
        d.damage = h;
        d.life = 30;
        d.setTarget(t);
        face(d, dir);
        player.level().addFreshEntity(d);
        send(player, TangRules.TWELVE, TangPayload.SHOT, layer, hand, dir, 11, 1);
    }

    /** Семь Звёзд: из обоих рукавов по дугам в точки-звёзды вокруг цели. */
    private static void stars(ServerPlayer player, int layer, double h, LivingEntity t, int only) {
        int n = TangRules.starCount(layer);
        Vec3 centre = t != null ? t.position().add(0.0D, t.getBbHeight() * 0.55D, 0.0D) : lookPoint(player, 10.0D);
        Vec3 back = new Vec3(centre.x - player.getX(), 0.0D, centre.z - player.getZ());
        back = back.lengthSqr() < 1.0E-6D ? player.getLookAngle() : back.normalize();
        boolean air = t != null && !t.onGround();
        long now = player.level().getGameTime();
        for (int k = only; k <= only && k < n; k++) {
            boolean right = k % 2 == 0;
            Vec3 sleeve = hand(player, right);
            Vec3 side = new Vec3(-back.z, 0.0D, back.x).scale(right ? -1.0D : 1.0D);
            Vec3 dir = side.scale(0.8D).add(back.scale(0.6D)).add(0.0D, 0.25D + 0.08D * k, 0.0D).normalize();
            TangDagger d = new TangDagger(player.level(), player, TangRules.STARS, layer, k);
            d.setPos(sleeve);
            d.setDeltaMovement(dir.scale(1.6D));
            d.speed = 1.3D + 0.1D * (k % 3);
            d.back = back;
            d.air = air;
            d.aim = centre;
            d.damage = h;
            d.life = TangRules.STAR_STRIKE + 30;
            d.setTarget(t);
            if (layer <= 0) {
                // Слой 0 — три голых кинжала прямо в цель.
                Vec3 aim = t != null ? TangDagger.throat(t) : centre;
                d.setMode(TangDagger.STEER);
                d.turn = 6.0D;
                d.speed = 1.6D;
                d.aim = aim;
                d.setDeltaMovement(aim.subtract(sleeve).normalize().scale(1.6D));
            } else {
                d.setMode(TangDagger.TO_STAR);
                d.starOffset = TangRules.starOffset(k, back, air);
                d.strikeAt = now + TangRules.STAR_STRIKE - only;
            }
            face(d, dir);
            player.level().addFreshEntity(d);
            send(player, TangRules.STARS, TangPayload.SHOT, layer, sleeve, dir, k, 0);
        }
    }

    /** Тёмный Взрыв: один кинжал с ладони, медленный «карп». */
    private static void carp(ServerPlayer player, int layer, double h, LivingEntity t) {
        Vec3 palm = hand(player, true).add(0.0D, 0.15D, 0.0D);
        // Без цели — кинжал уходит на 12 блоков по взгляду и там зависает: его можно отозвать по новой линии.
        Vec3 aim = t != null ? TangDagger.throat(t) : lookPoint(player, 12.0D);
        Vec3 dir = aim.subtract(palm).normalize();
        TangDagger d = new TangDagger(player.level(), player, TangRules.BURST, layer, 0);
        d.setPos(palm);
        d.aim = aim;
        d.damage = h;
        d.life = 140;
        d.setTarget(t);
        if (layer <= 0) {
            // Слой 0 — обычный бросок по прямой.
            d.setMode(TangDagger.STRAIGHT);
            d.speed = 1.8D;
            d.setDeltaMovement(dir.scale(1.8D));
            d.life = 20;
        } else {
            d.setMode(TangDagger.CARP);
            d.base = palm;
            d.carpDir = dir;
            d.setDeltaMovement(dir.scale(TangRules.CARP_SPEED));
            if (t != null && !t.onGround()) {
                TargetLock.freeze(t, TangRules.CARP_TICKS + 14);
            }
        }
        face(d, dir);
        player.level().addFreshEntity(d);
        send(player, TangRules.BURST, TangPayload.SHOT, layer, palm, dir, 0, 0);
    }

    // ------------------------------------------------------------------ события кинжала

    /** Касание сущности по факту. @return true — кинжал останавливается. */
    static boolean onHit(TangDagger d, Entity e, Vec3 point) {
        if (!(d.getOwner() instanceof ServerPlayer player) || !(e instanceof LivingEntity t)) {
            return true;
        }
        int form = d.form();
        int layer = d.layer();
        double[] s = player.getData(ModAttachments.TANG);
        Vec3 dir = d.getDeltaMovement().lengthSqr() < 1.0E-6D ? player.getLookAngle() : d.getDeltaMovement().normalize();
        if (d.mode() == TangDagger.RECALL) {
            // Отзыв/возврат режет всех на линии и не останавливается.
            hurt(player, d, t, d.damage * d.recallDmg);
            send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), 1);
            return false;
        }
        switch (form) {
            case TangRules.THREE -> {
                // Три в один миг: считаем касания одной цели в пределах одного тика.
                long now = player.level().getGameTime();
                int count = 1;
                if (s.length >= SIZE && (int) s[0] == TangRules.THREE) {
                    count = s[4] == t.getId() && now - (long) s[6] <= 1 ? (int) s[5] + 1 : 1;
                    s[4] = t.getId();
                    s[5] = count;
                    s[6] = now;
                }
                hurt(player, d, t, d.damage * TangRules.THREE_DMG);
                boolean main = count >= 3;
                if (main) {
                    stun(t, TangRules.THREE_STUN);
                }
                if (!t.onGround()) {
                    TargetLock.freeze(t, 10);
                }
                send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), main ? 2 : count > 1 ? 1 : 0);
                return true;
            }
            case TangRules.FLASH -> {
                hurt(player, d, t, d.damage * TangRules.FLASH_DMG);
                boolean cut = TangRules.interrupt(layer) && interrupt(t);
                send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), cut ? 2 : 1);
                return true;
            }
            case TangRules.FIVE -> {
                int chain = 1;
                if (s.length >= SIZE && (int) s[0] == TangRules.FIVE) {
                    chain = s[4] == t.getId() ? (int) s[5] + 1 : 1;
                    s[4] = t.getId();
                    s[5] = chain;
                }
                double dmg = d.damage * TangRules.FIVE_DMG * (1.0D + TangRules.FIVE_CHAIN * (chain - 1));
                boolean landed = hurt(player, d, t, dmg);
                // Гл. 195: 4-й разрушает защиту — щит выключен, как от топора.
                if (chain == 4 && t instanceof Player p && p.isBlocking()) {
                    p.disableShield();
                }
                boolean main = chain >= 5 && layer >= 4;
                if (main) {
                    stun(t, TangRules.FIVE_STUN);
                }
                if (!t.onGround()) {
                    TargetLock.freeze(t, 10);
                }
                send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), main ? 2 : landed && chain > 1 ? 1 : 0);
                return true;
            }
            case TangRules.TWELVE -> {
                if (d.sky()) {
                    hurt(player, d, t, d.damage * TangRules.SKY_DMG);
                    stun(t, layer >= 8 ? 24 : 16);
                    send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), 2);
                    return true;
                }
                int hits = bump(s, t.getId());
                double dmg = d.damage * (hits > TangRules.TWELVE_FULL_HITS ? TangRules.TWELVE_EXTRA : TangRules.TWELVE_DMG);
                hurt(player, d, t, dmg);
                if (hits == 1 && !(t instanceof Player)) {
                    t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, 8, 3, false, false, false));
                }
                if (TangRules.poison(layer)) {
                    // Яд на кромках: магия раз в 20 тиков, берёт и нежить.
                    t.setData(ModAttachments.TANG_POISON, new double[] {TangRules.TWELVE_POISON_PULSES, player.getId(),
                            d.damage * TangRules.TWELVE_POISON, 20});
                }
                if (!t.onGround()) {
                    TargetLock.freeze(t, 10);
                }
                send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), hits > 1 ? 1 : 0);
                return true;
            }
            case TangRules.STARS -> {
                int hits = bump(s, t.getId());
                hurt(player, d, t, d.damage * TangRules.STAR_DMG);
                boolean main = hits == 5 && s.length >= SIZE && s[7] < 0.5D;
                if (main) {
                    s[7] = 1;
                    stun(t, 20);
                }
                send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), main ? 2 : 0);
                return true;
            }
            default -> {
                if (d.mode() == TangDagger.HANG || d.mode() == TangDagger.FALL) {
                    return false;
                }
                if (layer <= 0 || d.index() == 1) {
                    hurt(player, d, t, d.damage);
                    send(player, form, TangPayload.HIT, layer, point, dir, t.getId(), 0);
                    return true;
                }
                explode(player, d, point, t, d.mode() == TangDagger.CARP ? 0.6D : 1.0D);
                return true;
            }
        }
    }

    /** Блок на пути. @return true — кинжал исчезает (взрыв или отзыв о стену), false — втыкается. */
    static boolean onBlock(TangDagger d, BlockHitResult hit) {
        if (!(d.getOwner() instanceof ServerPlayer player)) {
            return true;
        }
        Vec3 dir = d.getDeltaMovement().lengthSqr() < 1.0E-6D ? Vec3.ZERO : d.getDeltaMovement().normalize();
        if (d.form() == TangRules.BURST && d.layer() > 0 && d.index() == 0 && (d.mode() == TangDagger.BURST || d.mode() == TangDagger.CARP)) {
            explode(player, d, hit.getLocation(), null, d.mode() == TangDagger.CARP ? 0.6D : 1.0D);
            return true;
        }
        if (d.mode() != TangDagger.FALL) {
            send(player, d.form(), TangPayload.CLANG, d.layer(), hit.getLocation(), dir, hit.getDirection().get3DDataValue(), 0);
        }
        // Отзыв, упёршийся в стену, падает и тоже втыкается.
        return false;
    }

    /**
     * Срыв замаха (Похищение Жизни, слой 5+): моб теряет атаку — оглушение; игрок — техника в подготовке
     * гасится. @return true — было что сорвать.
     */
    private static boolean interrupt(LivingEntity t) {
        if (t instanceof ServerPlayer p) {
            io.github.verycooltimo.murim.combat.TechniqueState st = p.getData(ModAttachments.TECHNIQUE_STATE);
            if (st.isActive()) {
                TechniqueDefinition def = TechniqueLoader.get(st.techniqueId());
                io.github.verycooltimo.murim.combat.TechniquePhase ph = def == null ? null : def.phaseAt(st.tick());
                if (ph == io.github.verycooltimo.murim.combat.TechniquePhase.RITUAL || ph == io.github.verycooltimo.murim.combat.TechniquePhase.WINDUP) {
                    io.github.verycooltimo.murim.combat.TechniqueService.cancel(p);
                    return true;
                }
            }
            return false;
        }
        stun(t, TangRules.FLASH_STUN);
        if (t instanceof net.minecraft.world.entity.Mob mob) {
            mob.getNavigation().stop();
        }
        return true;
    }

    static void starPlaced(TangDagger d) {
        if (d.getOwner() instanceof ServerPlayer player) {
            send(player, d.form(), TangPayload.STAR, d.layer(), d.position(), Vec3.ZERO, d.index(), d.targetId());
        }
    }

    static void threadCut(TangDagger d) {
        if (d.getOwner() instanceof ServerPlayer player) {
            send(player, d.form(), TangPayload.CUT, d.layer(), d.position(), Vec3.ZERO, d.index(), 0);
        }
    }

    static void burstStart(TangDagger d, Vec3 dir) {
        if (d.getOwner() instanceof ServerPlayer player) {
            send(player, d.form(), TangPayload.BURST, d.layer(), d.position(), dir, d.getId(), d.doubled() ? 1 : 0);
        }
    }

    static void hang(TangDagger d) {
        if (d.getOwner() instanceof ServerPlayer player) {
            send(player, d.form(), TangPayload.HANG, d.layer(), d.position(), Vec3.ZERO, d.getId(), 0);
        }
    }

    static void caught(TangDagger d) {
        if (d.getOwner() instanceof ServerPlayer player) {
            send(player, d.form(), TangPayload.CAUGHT, d.layer(), d.position(), Vec3.ZERO, d.getId(), 0);
        }
    }

    static void knockedDown(TangDagger d) {
        if (d.getOwner() instanceof ServerPlayer player) {
            send(player, d.form(), TangPayload.DOWN, d.layer(), d.position(), Vec3.ZERO, d.getId(), 0);
        }
    }

    // ------------------------------------------------------------------ второе R

    /**
     * Повторное R по форме, пока её кинжалы живы: у Семи Звёзд — «удар сейчас», у Тёмного Взрыва —
     * отзыв зависшего кинжала. @return true — нажатие съедено командой, техника не запускается.
     */
    public static boolean command(ServerPlayer player, ResourceLocation id) {
        int form = TangRules.form(id);
        if (form != TangRules.STARS && form != TangRules.BURST) {
            return false;
        }
        List<TangDagger> mine = player.serverLevel().getEntitiesOfClass(TangDagger.class, player.getBoundingBox().inflate(64.0D),
                d -> d.getOwner() == player && d.form() == form && d.isAlive());
        if (form == TangRules.STARS) {
            double[] s = player.getData(ModAttachments.TANG);
            boolean waiting = mine.stream().anyMatch(d -> d.mode() == TangDagger.STAR || d.mode() == TangDagger.TO_STAR);
            if (!waiting || s.length < SIZE || (int) s[0] != TangRules.STARS) {
                return false;
            }
            if (s[2] >= TangRules.STAR_EARLY && s[2] < TangRules.STAR_SET) {
                flare(player, s);
                strike(player, s);
            }
            return true;
        }
        boolean any = false;
        for (TangDagger d : mine) {
            if (d.mode() == TangDagger.CARP && TangRules.doubled(d.layer()) && d.boostAt == Long.MAX_VALUE && !d.doubled()) {
                // Второй кинжал вдогонку первому (гл. 196): долетит — рывок вдвое быстрее.
                Vec3 hand = hand(player, true);
                Vec3 to = d.position().add(d.getDeltaMovement().scale(2.0D));
                Vec3 dir = to.subtract(hand).normalize();
                int ticks = Math.max(1, (int) Math.ceil(to.distanceTo(hand) / TangRules.SECOND_SPEED));
                TangDagger second = new TangDagger(player.level(), player, TangRules.BURST, d.layer(), 1);
                second.setPos(hand);
                second.setMode(TangDagger.STRAIGHT);
                second.speed = TangRules.SECOND_SPEED;
                second.setDeltaMovement(dir.scale(TangRules.SECOND_SPEED));
                second.damage = d.damage * 0.3D;
                second.life = ticks;
                face(second, dir);
                player.level().addFreshEntity(second);
                d.boostAt = player.level().getGameTime() + ticks;
                send(player, form, TangPayload.SHOT, d.layer(), hand, dir, 1, 2);
                any = true;
                continue;
            }
            if (d.mode() == TangDagger.HANG && TangRules.recall(d.layer())) {
                d.recall();
                send(player, form, TangPayload.RECALL, d.layer(), d.position(), Vec3.ZERO, d.getId(), 0);
                any = true;
            }
        }
        return any;
    }

    /** Вспышка-телеграф: точка горла фиксируется у всех вставших звёзд — дальше они не ведут цель. */
    private static void flare(ServerPlayer player, double[] s) {
        Vec3 at = null;
        for (TangDagger d : player.serverLevel().getEntitiesOfClass(TangDagger.class, player.getBoundingBox().inflate(64.0D),
                x -> x.getOwner() == player && x.form() == TangRules.STARS && x.isAlive() && x.mode() == TangDagger.STAR)) {
            LivingEntity t = d.target();
            d.aim = t != null ? TangDagger.throat(t) : d.aim;
            at = d.aim;
        }
        if (at != null) {
            send(player, TangRules.STARS, TangPayload.FLARE, (int) s[1], at, Vec3.ZERO, (int) s[3], 0);
        }
    }

    /** Звёзды сходятся сейчас: вставшие — в горло, ещё летящие — следом. */
    private static void strike(ServerPlayer player, double[] s) {
        long now = player.level().getGameTime();
        Vec3 at = null;
        for (TangDagger d : player.serverLevel().getEntitiesOfClass(TangDagger.class, player.getBoundingBox().inflate(64.0D),
                x -> x.getOwner() == player && x.form() == TangRules.STARS && x.isAlive())) {
            if (d.mode() == TangDagger.STAR) {
                d.strikeAt = now;
            } else if (d.mode() == TangDagger.TO_STAR) {
                // Не вставшая звезда падает: ранний удар слабее (spec §3).
                d.setMode(TangDagger.FALL);
                d.life = d.tickCount + 20;
            }
            LivingEntity t = d.target();
            if (t != null) {
                at = TangDagger.throat(t);
            }
        }
        s[2] = Math.max(s[2], TangRules.STAR_STRIKE);
        if (at != null) {
            send(player, TangRules.STARS, TangPayload.STRIKE, (int) s[1], at, Vec3.ZERO, (int) s[3], 0);
        }
    }

    // ------------------------------------------------------------------ урон

    /** База урона: {@link TechniqueDamage#base}, без оружия — как от железного клинка (spec §0). */
    public static double base(ServerPlayer player, ResourceLocation id) {
        double b = TechniqueDamage.base(player, id);
        double hand = player.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE);
        return hand >= TangRules.DAGGER_HAND ? b : b / Math.max(1.0D, hand) * TangRules.DAGGER_HAND;
    }

    private static boolean hurt(ServerPlayer player, TangDagger d, LivingEntity t, double dmg) {
        t.invulnerableTime = 0;
        // «Сила броска падает с расстоянием» (гл. 195) — у всех форм.
        dmg *= TangRules.falloff(d.travelled);
        // Источник снарядный: щит закрывает по направлению, защита от снарядов работает.
        boolean landed = t.hurt(player.damageSources().mobProjectile(d, player), (float) dmg);
        if (landed) {
            MasteryService.onHit(player, d.technique, t);
        }
        return landed;
    }

    /** Взрыв ци Тёмного Взрыва: цель, брызги по радиусу при прямой видимости, отброс. */
    private static void explode(ServerPlayer player, TangDagger d, Vec3 at, LivingEntity main, double scale) {
        double k = scale * (d.doubled() ? 1.25D : 1.0D);
        Vec3 dir = d.getDeltaMovement().lengthSqr() < 1.0E-6D ? player.getLookAngle() : d.getDeltaMovement().normalize();
        if (main != null) {
            hurt(player, d, main, d.damage * TangRules.BURST_DMG * k);
            stun(main, 16);
            push(main, at, dir, 0.9D);
        }
        AABB box = new AABB(at, at).inflate(TangRules.SPLASH_RADIUS);
        for (LivingEntity e : player.serverLevel().getEntitiesOfClass(LivingEntity.class, box,
                x -> x != player && x != main && x.isAlive() && !x.isSpectator()
                        && !(x instanceof net.minecraft.world.entity.decoration.ArmorStand))) {
            Vec3 c = e.position().add(0.0D, e.getBbHeight() * 0.5D, 0.0D);
            double r = c.distanceTo(at);
            if (r > TangRules.SPLASH_RADIUS || !clear(player, at, c)) {
                continue;
            }
            double f = TangRules.SPLASH_DMG + (TangRules.SPLASH_EDGE - TangRules.SPLASH_DMG) * (r / TangRules.SPLASH_RADIUS);
            hurt(player, d, e, d.damage * f * k);
            push(e, at, c.subtract(at), 0.6D);
        }
        send(player, TangRules.BURST, TangPayload.EXPLODE, d.layer(), at, dir, main == null ? -1 : main.getId(),
                main != null ? 2 : 0);
    }

    private static void push(LivingEntity e, Vec3 at, Vec3 dir, double strength) {
        Vec3 d = new Vec3(dir.x, 0.0D, dir.z);
        d = d.lengthSqr() < 1.0E-6D ? new Vec3(e.getX() - at.x, 0.0D, e.getZ() - at.z) : d;
        if (d.lengthSqr() < 1.0E-6D) {
            return;
        }
        double resist = e.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.KNOCKBACK_RESISTANCE);
        Vec3 v = d.normalize().scale(strength * (1.0D - resist)).add(0.0D, 0.15D, 0.0D);
        e.setDeltaMovement(e.getDeltaMovement().add(v));
        e.hurtMarked = true;
    }

    /** Оглушение = замедление ≥ 4 (TargetLock выключает ИИ моба): моб {@code ticks}, игрок 0,6 с, босс 0,5 с. */
    private static void stun(LivingEntity t, int ticks) {
        boolean boss = t.getType().is(net.neoforged.neoforge.common.Tags.EntityTypes.BOSSES);
        int n = t instanceof Player ? 12 : boss ? 10 : ticks;
        t.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, n, 4, false, false, false));
        if (t instanceof net.minecraft.world.entity.Mob mob && !boss) {
            mob.getNavigation().stop();
        }
    }

    /** Счёт попаданий по цели в касте; @return число с этим. */
    private static int bump(double[] s, int id) {
        if (s.length < SIZE) {
            return 1;
        }
        s[6]++;
        for (int i = 8; i < SIZE; i += 2) {
            if ((int) s[i] == id) {
                return (int) ++s[i + 1];
            }
            if (s[i] < 0) {
                s[i] = id;
                s[i + 1] = 1;
                return 1;
            }
        }
        return 1;
    }

    // ------------------------------------------------------------------ яд

    @SubscribeEvent
    static void onEntityTick(EntityTickEvent.Post event) {
        if (!(event.getEntity() instanceof LivingEntity t) || t.level().isClientSide() || !t.hasData(ModAttachments.TANG_POISON)) {
            return;
        }
        double[] p = t.getData(ModAttachments.TANG_POISON);
        if (p[0] <= 0) {
            return;
        }
        if (!t.isAlive()) {
            p[0] = 0;
            return;
        }
        if (--p[3] > 0) {
            return;
        }
        p[3] = 20;
        p[0]--;
        Entity owner = t.level().getEntity((int) p[1]);
        t.invulnerableTime = 0;
        // Магия: яд Тан берёт и нежить (ванильный яд её лечит).
        t.hurt(owner != null ? t.damageSources().indirectMagic(owner, owner) : t.damageSources().magic(), (float) p[2]);
        if (owner instanceof ServerPlayer player) {
            send(player, TangRules.TWELVE, TangPayload.POISON, 0, t.position().add(0.0D, t.getBbHeight() * 0.6D, 0.0D),
                    Vec3.ZERO, t.getId(), (int) p[0]);
        }
    }

    // ------------------------------------------------------------------ помощники

    /** Цель формы: захваченная Z, иначе ближайшая в конусе с прямой видимостью. */
    private static LivingEntity choose(ServerPlayer player, int form) {
        double range = form == TangRules.FIVE ? TangRules.FIVE_RANGE : form == TangRules.TWELVE ? TangRules.TWELVE_RANGE
                : form == TangRules.STARS ? TangRules.STAR_RANGE : TangRules.BURST_RANGE;
        LivingEntity t = TargetLock.locked(player, range);
        return t != null ? t : nearestInCone(player, range, form == TangRules.TWELVE ? TangRules.TWELVE_CONE * 0.5D : 30.0D);
    }

    private static LivingEntity nearestInCone(ServerPlayer player, double range, double half) {
        LivingEntity best = null;
        double bd = Double.MAX_VALUE;
        for (LivingEntity e : candidates(player, range)) {
            double d = e.distanceToSqr(player);
            if (d < bd && TargetLock.inCone(player, e, half) && player.hasLineOfSight(e)) {
                bd = d;
                best = e;
            }
        }
        return best;
    }

    private static List<LivingEntity> candidates(ServerPlayer player, double range) {
        return player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(range),
                e -> e != player && e.isAlive() && !e.isSpectator() && e.distanceTo(player) <= range
                        && !(e instanceof net.minecraft.world.entity.decoration.ArmorStand));
    }

    /** Точка взгляда: первый блок по лучу или конец дальности. */
    private static Vec3 lookPoint(ServerPlayer player, double range) {
        Vec3 eye = player.getEyePosition();
        Vec3 end = eye.add(player.getLookAngle().scale(range));
        BlockHitResult hit = player.level().clip(new ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player));
        return hit.getType() == HitResult.Type.MISS ? end : hit.getLocation();
    }

    private static boolean clear(ServerPlayer player, Vec3 a, Vec3 b) {
        return player.level().clip(new ClipContext(a, b, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, player))
                .getType() == HitResult.Type.MISS;
    }

    /** Кисть/рукав: правая при взгляде (−sin, cos) смотрит в (−cos, −sin) — как у плеча в RainVfx. */
    static Vec3 hand(Player player, boolean right) {
        double r = Math.toRadians(player.getYRot());
        Vec3 side = new Vec3(-Math.cos(r), 0.0D, -Math.sin(r)).scale(right ? 0.38D : -0.38D);
        return player.getEyePosition().add(0.0D, -0.45D, 0.0D).add(player.getLookAngle().scale(0.45D)).add(side);
    }

    /** Повернуть направление на рысканье/тангаж (градусы) относительно него самого. */
    private static Vec3 rotate(Vec3 look, double yawDeg, double pitchDeg) {
        float yaw = (float) (Math.toDegrees(Math.atan2(-look.x, look.z)) + yawDeg);
        float pitch = (float) (Math.toDegrees(-Math.asin(Math.max(-1.0D, Math.min(1.0D, look.y)))) - pitchDeg);
        return Vec3.directionFromRotation(pitch, yaw);
    }

    private static void face(TangDagger d, Vec3 dir) {
        d.setXRot((float) (Math.atan2(dir.y, dir.horizontalDistance()) * 180.0D / Math.PI));
        d.setYRot((float) (Math.atan2(dir.x, dir.z) * 180.0D / Math.PI));
    }

    private static void send(ServerPlayer player, int form, int stage, int layer, Vec3 pos, Vec3 dir, int a, int b) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, new TangPayload(player.getId(), form, stage, layer, pos, dir, a, b));
    }
}
