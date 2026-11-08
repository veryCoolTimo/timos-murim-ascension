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
import java.util.Optional;

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
    /** A member led out of a closed place has this long to follow (ticks); then the guard goes back to his post. */
    public static final int LEAD_PATIENCE = 400;
    /** The keeper of a door (the guard of that very post) notices who is inside from this far, with or without a line of sight. */
    public static final double KEEPER_RANGE = 30.0D;
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
        /** When a guard last offered to lead this member out of a place that is not yet his. */
        long lastLeadOut = Long.MIN_VALUE / 2;
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
        if (p.isCreative() || p.isSpectator() || !p.isAlive() || PenanceService.sentenced(p) || SectWar.atWar(p)
                || io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.active(p)
                || io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.releasedOutsider(p)) {
            return;
        }
        long now = p.level().getGameTime();
        reprimand(p);
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
        // A newcomer who walks up to the head or an elder is met by a guard, with no right click (playtest 19.10: this call was
        // lost in the walking rework of 14.10, and the junior stood next to the head with no guard in sight).
        DialogueService.interceptNearElder(p);
        // A member who simply walks around or plays elsewhere is never fought for it (no blow, no merit, no sentence); a closed
        // place is still shown to him politely: the guard warns, steps in the way and leads him out of THAT place, no further.
        boolean inHall = layout.inside("main_hall", p.position(), 0);
        boolean gentle = inHall || s.member() && !repeatOffender(p);
        List<SectAccess.Rule> in = SectAccess.forbidden(layout, p.position(), standing, s.flags(), period, 0.0D);
        // A gate invitation permits the ordinary route to the mentor, never elder rooms or the vault. It holds from the
        // word at the gate, not from the moment a guide has arrived: the newcomer who walks in ahead of him is not an intruder.
        boolean invited = s.has(SectArrival.INVITED) && !s.member();
        if (invited)
            in = in.stream().filter(r -> !SectAccess.GROUNDS.equals(r.zone())).toList();
        if (t.escort != null && t.escort.escortGoal().forPlayer(p)) {
            if (gentle || t.escort.escortGoal().forcedFor(p)) {
                // Led out of the place, not out of the sect: done when he is out, or when he does not follow for a while.
                if (in.isEmpty() || now - t.escortAt >= LEAD_PATIENCE) {
                    t.escort.escortGoal().cancel();
                    t.escort = null;
                    t.strikes = 0;
                    t.inside = 0;
                    t.last = in.isEmpty() ? "led_out" : "declined";
                }
                return;
            }
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
            // A member walking past a door is not warned (nothing has been done yet); only the one who goes in.
            // A member walking past a closed place is left alone, but the main hall has guards at its doors who turn the junior back.
            List<SectAccess.Rule> near = SectAccess.forbidden(layout, p.position(), standing, s.flags(), period, SectAccess.WARN);
            if (gentle) near = near.stream().filter(r -> "main_hall".equals(r.id())).toList();
            if (invited) {
                near = near.stream().filter(r -> !SectAccess.GROUNDS.equals(r.zone())).toList();
            }
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
                        if (gentle) {
                            // no penalty: a polite word and a step in the way (the doors of the main hall, author 22.10)
                            say(p, "murim.sect.guard.hall_turn", guard, r, ChatFormatting.YELLOW);
                            SectChatter.sayIfQuiet(guard, SectBubbles.Group.GUARD_TURN_BACK.pick(p, guard.getRandom()), 60);
                        } else {
                            say(p, standing == SectStanding.OUTSIDER ? "murim.sect.guard.warn_outsider" : "murim.sect.guard.warn",
                                    guard, r, ChatFormatting.YELLOW);
                        }
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
        SectDisciple guard = watcher(p, night, r);
        if (guard == null) {
            // Никто не видит — прошёл (ночью, присев, за спиной у стражи).
            return;
        }
        if (t.inside == 0 && (!gentle || now - t.warned.getOrDefault(r.id(), Long.MIN_VALUE / 2) >= WARN_EVERY)) {
            t.last = "warn";
            t.warned.put(r.id(), now);
            guard.faceEntity(p, 20);
            guard.gesture("shake");
            if (gentle) {
                // A member is not an outsider: «not yet for you», with a word over the guard's head (playtest 19.10).
                say(p, "murim.sect.guard.warn", guard, r, ChatFormatting.YELLOW);
                SectChatter.sayIfQuiet(guard, SectBubbles.Group.GUARD_TURN_BACK.pick(p, guard.getRandom()), 60);
            } else {
                say(p, "murim.sect.guard.warn_outsider", guard, r, ChatFormatting.YELLOW);
            }
        }
        t.inside += PERIOD;
        Vec3 c = SectAccess.center(layout, r, p.position());
        guard.block(p, c, 60);
        if ("main_hall".equals(r.id())) {
            if (t.inside >= 100) leadOut(p, guard, r, layout, t, now);
            return;
        }
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
        if ("main_hall".equals(r.id()) || SectService.state(p).member() && !repeatOffender(p)) {
            leadOut(p, guard, r, layout, t, now);
            return;
        }
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

    /**
     * Closed places: members are offered a guide out. The Main Hall uses the existing forced, collision-aware escort
     * for every intruder, without combat or a penalty. A repeat that day earns a senior's spoken reprimand.
     */
    static void leadOut(ServerPlayer p, SectDisciple guard, SectAccess.Rule r, SectLayout layout, Trespass t, long now) {
        if (now - t.lastLeadOut < Config.SECT_GUARD_REPEAT.get()) {
            return;
        }
        Vec3 out = outside(layout, r, p);
        boolean hall = "main_hall".equals(r.id());
        if (out != null && guard.escortGoal().begin(p, out, hall)) {
            if (hall) {
                long day = SectSchedule.day(p.level().getDayTime());
                String stamp = "murim_hall_led_day";
                if (p.getPersistentData().contains(stamp) && p.getPersistentData().getLong(stamp) == day) {
                    p.getPersistentData().putBoolean("murim_hall_reprimand", true);
                    reprimand(p);
                }
                p.getPersistentData().putLong(stamp, day);
            }
            t.strikes = 0; t.inside = 0;
            t.lastLeadOut = now; t.escortAt = now; t.leftDuringEscort = false;
            t.escort = guard; t.last = "escort";
            say(p, "murim.sect.guard.lead_out", guard, r, ChatFormatting.YELLOW);
        }
    }

    private static void reprimand(ServerPlayer p) {
        if (!p.getPersistentData().getBoolean("murim_hall_reprimand")) return;
        SectDisciple senior = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(48),
                        n -> n.isAlive() && SectSocial.seniority(n) >= SectRank.TRUSTED.ordinal())
                .stream().min(java.util.Comparator.comparingDouble(n -> n.distanceToSqr(p))).orElse(null);
        if (senior == null) return;
        SectChatter.send(senior, "murim.sect.guard.hall_repeat", Component.translatable("murim.sect.guard.hall_repeat"));
        p.getPersistentData().remove("murim_hall_reprimand");
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
        SectNotice.chat(p, Component.translatable("murim.sect.guard.defeated", guard.getName()));
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
        if ("main_hall".equals(r.id())) {
            double[] half = layout.half("main_hall");
            Vec3 front = layout.at("main_hall", 0, -(half == null ? 10 : half[1]) - 4);
            return front == null ? null : SectLife.stand(p.level(), front);
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
        return watcher(p, night, null);
    }

    /**
     * Same, for a player standing in the place of {@code rule}: by day the guard whose post is THAT place (the treasury guard at
     * the treasury, the hall guard at the hall, Gyeong Tak at the gate) notices who is inside without a line of sight, from
     * {@link #KEEPER_RANGE} (playtest 19.10: «охранник просто снаружи остаётся, ему пофиг» — his post is at the edge of the court,
     * a wall between him and the player, and a line of sight was all he went by). At night only the sight counts, as before.
     */
    public static SectDisciple watcher(ServerPlayer p, boolean night, SectAccess.Rule rule) {
        double range = SectAccess.sight(night, p.isCrouching(), p.isInvisible());
        double search = rule == null || night ? range : Math.max(range, KEEPER_RANGE);
        SectDisciple best = null;
        double bestD = Double.MAX_VALUE;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(search),
                d -> d.isAlive() && guards(d) && !d.escorting() && d.guardTarget() == null && !d.dormant() && d.talkingTo() < 0 && d.spar() == SectDisciple.Spar.NONE)) {
            double dist = d.distanceTo(p);
            boolean keeper = rule != null && !night && !p.isInvisible() && keeps(d, rule);
            if (dist >= bestD || dist > (keeper ? KEEPER_RANGE : range) || !keeper && !d.getSensing().hasLineOfSight(p)) {
                continue;
            }
            if (!keeper && night && dist > SectAccess.CLOSE && !facing(d, p, SectAccess.NIGHT_HALF_ANGLE)) {
                continue;
            }
            best = d;
            bestD = dist;
        }
        return best;
    }

    /**
     * This guard's post is the place of the rule, by day: the hall guard (post 0) keeps the main hall, the treasury guard
     * (post 1) the treasury, Gyeong Tak the sect grounds beyond the gate ({@link SectRota}).
     */
    static boolean keeps(SectDisciple d, SectAccess.Rule rule) {
        Optional<SectRoster> m = d.member();
        if (m.isEmpty()) {
            return false;
        }
        if ("gyeong_tak".equals(m.get().key())) {
            return SectAccess.GROUNDS.equals(rule.zone());
        }
        SectRoster k = SectRota.keeper(rule.zone(), d.level().getDayTime());
        return k != null && k.equals(m.get());
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
        Component line = Component.translatable(key, guard.getName(), Component.translatable(r.nameKey())).withStyle(style);
        // A warning is routine; the escort out and the attack are the guard's sentence and stay readable.
        if (key.endsWith("walk_out") || key.endsWith("attack")) SectNotice.chat(p, line);
        else SectNotice.bar(p, line);
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
