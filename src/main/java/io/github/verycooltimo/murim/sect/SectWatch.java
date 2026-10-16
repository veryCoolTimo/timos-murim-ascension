package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.neoforged.neoforge.event.entity.living.LivingDamageEvent;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Restricted-zone enforcement: warning, walking escort, refusal/return combat, defeat and walking expulsion.
 * Members without repeated recent sentences are never attacked for roaming. No absence penalties. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectWatch {

    /** Проверка раз в столько тиков. */
    public static final int PERIOD = 10;
    /** Толчков до поединка (или до вывода за ворота). */
    public static final int STRIKES = 3;
    /** Тиков внутри до того же. */
    public static final int LINGER = 300;
    /** Не чаще: толчок, предупреждение о месте. */
    static final int PUSH_EVERY = 20;
    public static final int WARN_EVERY = 600;
    /** После поединка за то же — в этот срок уже вывод без поединка. */
    static final int REPEAT = 2400;
    /** Штраф заслуг за силовой вход. */
    public static final int PENALTY = 5;
    /** Охранник отталкивает, если игрок не дальше. */
    static final double REACH = 2.6D;
    /** Заслуги за врага, убитого на земле секты, и предел в день. */
    static final int KILL_CONTRIBUTION = 1;
    static final int KILL_CAP = 5;
    private static final String KILL_TAG = "murim_sect_kills";

    /** Что было у нарушителя. */
    public static final class Trespass {
        final Map<String, Long> warned = new HashMap<>();
        int strikes;
        int inside;
        long lastPush = Long.MIN_VALUE / 2;
        long lastEscalation = Long.MIN_VALUE / 2;
        /** Был внутри законно (до ночи): до этого тика — только просьба выйти, без толчков. */
        long graceUntil = Long.MIN_VALUE / 2;
        /** Последняя проверка: стоял в закрытом месте, но по праву. */
        boolean legal;
        SectDisciple escort;
        long escortAt;
        boolean leftDuringEscort;
        /** Чем кончилось последнее нарушение: {@code warn}, {@code block}, {@code spar}, {@code escort}, {@code expel}. */
        String last = "";

        public int strikes() {
            return strikes;
        }

        public String last() {
            return last;
        }
    }

    private SectWatch() {
    }

    static Trespass trespass(ServerPlayer p) {
        return SectLife.data(p.serverLevel()).trespass.computeIfAbsent(p.getUUID(), u -> new Trespass());
    }

    /** Для тестов и команды: что было у игрока. */
    public static Trespass of(ServerPlayer p) {
        return trespass(p);
    }

    public static SectStanding standing(ServerPlayer p) {
        return SectStanding.of(p.getData(ModAttachments.SECT), p.getData(ModAttachments.PROFILE).rank());
    }

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % PERIOD != 7) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(event.getServer());
        if (site == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        SectLayout layout = SectLayout.hua(site);
        for (ServerPlayer p : level.players()) {
            if (SectTerritory.contains(site, p.getX(), p.getY(), p.getZ())) {
                check(p, layout);
            }
        }
    }

    /** Одна проверка игрока: раз в {@link #PERIOD} тиков. */
    public static void check(ServerPlayer p, SectLayout layout) {
        if (p.isCreative() || p.isSpectator() || !p.isAlive() || PenanceService.sentenced(p)
                || io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.active(p)
                || io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.releasedOutsider(p)) {
            return;
        }
        long now = p.level().getGameTime();
        if (p.getPersistentData().getLong("murim_sect_defeated_until") > now) return;
        SectSchedule.Period period = SectSchedule.at(p.level().getDayTime());
        boolean night = period == SectSchedule.Period.NIGHT;
        SectState s = p.getData(ModAttachments.SECT);
        SectStanding standing = SectStanding.of(s, p.getData(ModAttachments.PROFILE).rank());
        Trespass t = trespass(p);
        boolean summoned = s.has(io.github.verycooltimo.murim.sect.seal.PenanceService.SUMMONED);
        if (summoned) {
            t.legal = false;
            t.graceUntil = Long.MIN_VALUE / 2;
        }
        if (inGuardSpar(p) || p.getData(ModAttachments.DIALOGUE).active()) {
            return;
        }
        // A member who simply walks around or plays elsewhere is never an enforcement target.
        if (s.member() && !repeatOffender(p)) {
            t.strikes = 0; t.inside = 0;
            if (t.escort != null && t.escort.escortGoal().forPlayer(p)) t.escort.escortGoal().cancel();
            t.escort = null;
            return;
        }
        List<SectAccess.Rule> in = SectAccess.forbidden(layout, p.position(), standing, s.flags(), period, 0.0D);
        // A gate invitation permits the ordinary route to the mentor, never elder rooms or the vault.
        if (s.has(SectArrival.INVITED) && p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64),
                d -> d.escortGoal().forPlayer(p)).stream().anyMatch(d -> d.role().third()))
            in = in.stream().filter(r -> !SectAccess.GROUNDS.equals(r.zone())).toList();
        if (t.escort != null && t.escort.escortGoal().forPlayer(p)) {
            if (in.isEmpty()) t.leftDuringEscort = true;
            if (!in.isEmpty() && (t.leftDuringEscort || now - t.escortAt >= Config.SECT_GUARD_REFUSAL.get())
                    && !"defeated".equals(t.last)) {
                attack(p, t.escort, layout, in.get(0), t, now);
            }
            return;
        }
        t.escort = null;
        if (in.isEmpty()) {
            t.legal = insideAny(layout, p.position());
            t.inside = 0;
            if (t.strikes > 0 && now - t.lastPush > 1200) {
                t.strikes = 0;
            }
            List<SectAccess.Rule> near = SectAccess.forbidden(layout, p.position(), standing, s.flags(), period, SectAccess.WARN);
            if (!near.isEmpty()) {
                SectAccess.Rule r = near.get(0);
                long last = t.warned.getOrDefault(r.id(), Long.MIN_VALUE / 2);
                if (now - last >= WARN_EVERY) {
                    SectDisciple guard = watcher(p, night);
                    if (guard != null) {
                        t.warned.put(r.id(), now);
                        t.last = "warn";
                        guard.block(p, SectAccess.center(layout, r, p.position()), 60);
                        guard.gesture("shake");
                        say(p, standing == SectStanding.OUTSIDER ? "murim.sect.guard.warn_outsider" : "murim.sect.guard.warn",
                                guard, r, ChatFormatting.YELLOW);
                    }
                }
            }
            return;
        }
        SectAccess.Rule r = in.get(0);
        if (t.legal) {
            // Был здесь по праву, а место закрылось (наступила ночь): полминуты на выход, без толчков (codex 04.10).
            t.legal = false;
            t.graceUntil = now + WARN_EVERY / 2;
            SectDisciple g = watcher(p, false);
            if (g != null) {
                say(p, "murim.sect.guard.closing", g, r, ChatFormatting.YELLOW);
            }
        }
        if (now < t.graceUntil) {
            return;
        }
        SectDisciple guard = watcher(p, night);
        if (guard == null) {
            // Никто не видит — прошёл (ночью, присев, за спиной у стражи).
            return;
        }
        if (t.inside == 0) {
            t.last = "warn";
            t.warned.put(r.id(), now);
            guard.faceEntity(p, 20);
            guard.gesture("shake");
            say(p, "murim.sect.guard.warn_outsider", guard, r, ChatFormatting.YELLOW);
        }
        t.inside += PERIOD;
        Vec3 c = SectAccess.center(layout, r, p.position());
        guard.block(p, c, 60);
        // Words first, no instantaneous expulsions or repeated damage while an escort is active.
        if (t.inside >= 100 && guard.distanceTo(p) <= REACH && now - t.lastPush >= 60) {
            t.strikes++;
            t.lastPush = now;
            guard.gesture("point");
            say(p, "murim.sect.guard.block", guard, r, ChatFormatting.GOLD);
        }
        if (t.strikes >= Config.SECT_GUARD_STRIKES.get() || t.inside >= Config.SECT_GUARD_LINGER.get()) {
            escalate(p, guard, r, layout, standing, t, now);
        }
    }

    /** Отталкивает от центра закрытого места: мягко, по горизонтали, с небольшим подскоком. */
    static void push(ServerPlayer p, Vec3 center) {
        Vec3 away = new Vec3(p.getX() - center.x, 0.0D, p.getZ() - center.z);
        if (away.lengthSqr() < 1.0E-4D) {
            away = Vec3.directionFromRotation(0.0F, p.getYRot() + 180.0F);
        }
        away = away.normalize().scale(0.7D);
        p.push(away.x, 0.25D, away.z);
        // Скорость игрока задаёт клиент: hurtMarked отправляет ему новую скорость (как отбрасывание).
        p.hurtMarked = true;
    }

    /** First intrusion: walk out. Refusal or a quick return: nonlethal enforcement. */
    static void escalate(ServerPlayer p, SectDisciple guard, SectAccess.Rule r, SectLayout layout, SectStanding standing,
                         Trespass t, long now) {
        if (SectService.state(p).member() && !repeatOffender(p)) return;
        if (repeatOffender(p) || now - t.lastEscalation < Config.SECT_GUARD_REPEAT.get()) {
            attack(p, guard, layout, r, t, now);
            return;
        }
        Vec3 out = outsideGate(layout, p);
        if (out == null) out = outside(layout, r, p);
        if (guard.escortGoal().begin(p, out, false)) {
            t.strikes = 0; t.inside = 0;
            t.lastEscalation = now; t.escortAt = now; t.leftDuringEscort = false;
            t.escort = guard; t.last = "escort";
            say(p, "murim.sect.guard.walk_out", guard, r, ChatFormatting.GOLD);
        }
    }

    private static void attack(ServerPlayer p, SectDisciple guard, SectLayout layout, SectAccess.Rule r, Trespass t, long now) {
        if (SectService.state(p).member() && !repeatOffender(p)) return;
        Vec3 out = outsideGate(layout, p);
        if (out == null) out = outside(layout, r, p);
        if (guard.guardGoal().begin(p, out)) {
            t.strikes = 0; t.inside = 0; t.escort = null;
            t.lastEscalation = now; t.last = "attack";
            say(p, "murim.sect.guard.attack", guard, r, ChatFormatting.RED);
        }
    }

    public static boolean repeatOffender(ServerPlayer p) {
        return SectService.state(p).member() && PenanceService.recentSentences(p) >= Config.SECT_JAIL_THRESHOLD.get();
    }
    public static float guardFloor(ServerPlayer p) { return Math.max(1, p.getMaxHealth() * 0.25F); }

    public static void defeated(ServerPlayer p, SectDisciple guard) {
        Trespass t = trespass(p);
        t.last = "defeated"; t.escort = guard; t.escortAt = p.level().getGameTime();
        t.strikes = 0; t.inside = 0;
        p.getPersistentData().putLong("murim_sect_defeated_until", p.level().getGameTime() + 200);
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.MOVEMENT_SLOWDOWN,
                100, 2, false, false, true));
        p.addEffect(new net.minecraft.world.effect.MobEffectInstance(net.minecraft.world.effect.MobEffects.WEAKNESS,
                200, 1, false, false, true));
        p.displayClientMessage(Component.translatable("murim.sect.guard.defeated", guard.getName()), false);
    }

    /** Clamp after armour and other modifiers. Enforcement never uses lethal technique executors. */
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void guardDamage(LivingDamageEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getSource().getEntity() instanceof SectDisciple guard
                && guard.guardTarget() == p) {
            event.setNewDamage(Math.max(0, Math.min(event.getNewDamage(), p.getHealth() - guardFloor(p))));
        } else if (event.getEntity() instanceof SectDisciple guard && guard.guardTarget() != null && guard.guardTarget() == event.getSource().getEntity()) {
            event.setNewDamage(Math.max(0, Math.min(event.getNewDamage(), guard.getHealth() - 1)));
        }
    }
    @SubscribeEvent(priority = net.neoforged.bus.api.EventPriority.LOWEST)
    static void guardDamaged(LivingDamageEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getSource().getEntity() instanceof SectDisciple guard
                && guard.guardTarget() == p && p.getHealth() <= guardFloor(p) + 0.01F) {
            guard.guardGoal().defeated();
        } else if (event.getEntity() instanceof SectDisciple guard && guard.guardTarget() != null && guard.getHealth() <= 1.01F) {
            ServerPlayer p = guard.guardTarget();
            guard.guardGoal().cancel();
            Trespass t = trespass(p);
            t.graceUntil = p.level().getGameTime() + Config.SECT_GUARD_REPEAT.get();
            t.last = "guard_defeated";
            p.getPersistentData().putLong("murim_sect_defeated_until", p.level().getGameTime() + Config.SECT_GUARD_REPEAT.get());
        }
    }

    /** Точка за пределами места: от его центра через игрока, на край и ещё четыре блока. */
    static Vec3 outside(SectLayout layout, SectAccess.Rule r, ServerPlayer p) {
        Vec3 c = SectAccess.center(layout, r, p.position());
        if (c == null) {
            return null;
        }
        double[] h = SectAccess.GROUNDS.equals(r.zone()) ? new double[] {8.0D, 8.0D} : layout.half(r.zone());
        double reach = (h == null ? 8.0D : Math.max(h[0], h[1])) + 4.0D;
        Vec3 dir = new Vec3(p.getX() - c.x, 0.0D, p.getZ() - c.z);
        dir = dir.lengthSqr() < 1.0E-4D ? new Vec3(0.0D, 0.0D, -1.0D) : dir.normalize();
        return SectLife.stand(p.level(), c.add(dir.scale(reach)));
    }

    /** За воротами секты, на лестнице: оттуда чужак пришёл. */
    public static Vec3 outsideGate(SectLayout layout, ServerPlayer p) {
        double[] h = layout.half("sect_gate");
        Vec3 at = layout.at("sect_gate", 0.0D, -(h == null ? 5.0D : h[1]) - 4.0D);
        return at == null ? null : SectLife.stand(p.level(), at);
    }

    /** Стоит в любом закрытом месте (неважно, можно ли ему). */
    static boolean insideAny(SectLayout layout, Vec3 pos) {
        for (SectAccess.Rule r : SectAccess.RULES) {
            if (!SectAccess.GROUNDS.equals(r.zone()) && SectAccess.inside(layout, r, pos, 0.0D)) {
                return true;
            }
        }
        return false;
    }

    /** Игрок сейчас в поединке с охранником (охрана ждёт его конца). */
    static boolean inGuardSpar(ServerPlayer p) {
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(40.0D),
                d -> d.spar() != SectDisciple.Spar.NONE && p.getUUID().equals(d.partner()))) {
            return d.isAlive();
        }
        return false;
    }

    /** Кто из охраны видит игрока (ближайший): дальность по свету и позе, ночью — только перед собой. */
    public static SectDisciple watcher(ServerPlayer p, boolean night) {
        double range = SectAccess.sight(night, p.isCrouching(), p.isInvisible());
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range),
                d -> d.isAlive() && guards(d) && !d.escorting() && d.guardTarget() == null && !d.dormant() && d.talkingTo() < 0 && d.spar() == SectDisciple.Spar.NONE)) {
            double dist = d.distanceTo(p);
            if (dist > range || dist >= bestD || !d.getSensing().hasLineOfSight(p)) {
                continue;
            }
            if (night && dist > SectAccess.CLOSE && !facing(d, p, SectAccess.NIGHT_HALF_ANGLE)) {
                continue;
            }
            best = d;
            bestD = dist;
        }
        return best;
    }

    /** Стоит на страже: дежурный второго поколения на смене (или ждёт сменщика, SectRota) и Гён Так (днём у ворот). */
    public static boolean guards(SectDisciple d) {
        return SectLife.onWatch(d) || "gyeong_tak".equals(d.memberKey());
    }

    /** Игрок перед охранником в пределах угла. */
    static boolean facing(SectDisciple d, ServerPlayer p, double halfAngle) {
        double want = Mth.atan2(p.getZ() - d.getZ(), p.getX() - d.getX()) * Mth.RAD_TO_DEG - 90.0D;
        return Math.abs(Mth.wrapDegrees(want - d.getYHeadRot())) <= halfAngle;
    }

    private static void say(ServerPlayer p, String key, SectDisciple guard, SectAccess.Rule r, ChatFormatting style) {
        if (key.contains("warn") || key.endsWith("block"))
            p.getPersistentData().putLong("murim_sect_warning_until", p.level().getGameTime() + Config.SECT_RAMPAGE_WINDOW.get());
        p.displayClientMessage(Component.translatable(key, guard.getName(), Component.translatable(r.nameKey())).withStyle(style), false);
    }

    // ------------------------------------------------------------------ заслуги за защиту горы

    /** Враг, убитый учеником на земле секты: +1 заслуга, не больше пяти в день. */
    @SubscribeEvent
    static void onDeath(LivingDeathEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer p) || !(event.getEntity() instanceof Enemy)
                || event.getEntity() instanceof SectDisciple || p.level().dimension() != Level.OVERWORLD) {
            return;
        }
        if (!p.getData(ModAttachments.SECT).member()) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(p.serverLevel().getServer());
        if (site == null || !SectTerritory.contains(site, event.getEntity().getX(), event.getEntity().getY(), event.getEntity().getZ())) {
            return;
        }
        long day = SectSchedule.day(p.level().getDayTime());
        CompoundTag tag = p.getPersistentData().getCompound(KILL_TAG);
        if (tag.getLong("day") != day) {
            tag = new CompoundTag();
            tag.putLong("day", day);
        }
        int n = tag.getInt("count");
        if (n < KILL_CAP) {
            tag.putInt("count", n + 1);
            SectService.contribute(p, KILL_CONTRIBUTION);
        }
        p.getPersistentData().put(KILL_TAG, tag);
    }
}
