package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.TraversePayloads;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.FootworkFamily;
import io.github.verycooltimo.murim.technique.TechniqueBehavior;
import io.github.verycooltimo.murim.technique.TechniqueDefinition;
import io.github.verycooltimo.murim.technique.TechniqueLoader;
import io.github.verycooltimo.murim.technique.TraverseRules;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Шаги — семейства подтехник в одном слоте (автор 02.10; docs/design/21-footwork-families.md).
 * Клиент присылает R вместе с контекстом ввода (спринт, присед, направления), сервер выбирает
 * подтехнику по таблице приоритетов и проверяет слой:
 *
 * <ol>
 *   <li>в воздухе — поворот (Хуашань L3+, Бог Ветров L7+);</li>
 *   <li>идёт Тень и любой R — выйти из Тени;</li>
 *   <li>присед — Хуашань: уклонение назад/по направлению; Бог Ветров: Шаг Тени (L1+);</li>
 *   <li>спринт + вперёд — бег (Хуашань L1+, Бог Ветров L0+), повторно — стоп;</li>
 *   <li>идёт бег и простой R — стоп;</li>
 *   <li>влево/вправо/назад — уклонение в эту сторону;</li>
 *   <li>иначе — Хуашань: шаг вперёд по взгляду (сближение); Бог Ветров: Шаг Смерти к цели в
 *       конусе 30° (L3+; вперёд+R — сквозь неё), без цели — Миг вперёд по движению или назад.</li>
 * </ol>
 *
 * <p>Бег — ванильная физика с временными модификаторами скорости, ступени и безопасного
 * падения; толчки (перелёт, стена, поворот) — серверный импульс после проверки и оплаты.
 * Закрытая форма не подменяется другой: игрок видит, какой слой нужен.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class FootworkService {

    public static final int SPRINT = 1;
    public static final int SNEAK = 2;
    public static final int FORWARD = 4;
    public static final int BACK = 8;
    public static final int LEFT = 16;
    public static final int RIGHT = 32;
    /** Клиент видел опору в момент нажатия прыжка (сервер может отставать на тик). */
    public static final int GROUNDED = 64;

    private static final ResourceLocation SPEED = id("traverse_speed");
    private static final ResourceLocation STEP = id("traverse_step");
    private static final ResourceLocation FALL = id("traverse_fall");

    private static final int ACTIVE = 0;
    private static final int LAYER = 1;
    private static final int RUN_LEFT = 2;
    private static final int KICKS_USED = 3;
    private static final int LAST_LEAP = 4;
    private static final int AIR_USED = 5;
    private static final int FAMILY = 6;
    private static final int LAST_GROUND = 7;
    private static final int NO_SPRINT = 8;
    private static final int PAID = 9;
    private static final int GROUND_STREAK = 10;
    private static final int SHADOW_READY = 11;

    private static final int RUN = 1;
    private static final int SHADOW = 2;

    private static final ResourceLocation SNEAK_SPEED = id("shadow_sneak");

    /** Тиков без спринта на земле подряд, после которых бег гаснет. */
    private static final int GRACE = 6;
    /** Тиков подряд на настоящей опоре, после которых возвращаются стена и поворот в воздухе. */
    private static final int GROUND_RESET = 4;

    /** Семейство техники шага или null, если техника — не шаг. */
    public static FootworkFamily family(TechniqueDefinition def) {
        if (def == null) {
            return null;
        }
        if (def.behavior() instanceof TechniqueBehavior.Footwork f) {
            return FootworkFamily.byId(f.family());
        }
        if (def.behavior() instanceof TechniqueBehavior.Step) {
            return FootworkFamily.HUASHAN;
        }
        return null;
    }

    public static boolean isRunning(ServerPlayer player) {
        return player.getData(ModAttachments.TRAVERSE)[ACTIVE] == RUN;
    }

    public static boolean inShadow(ServerPlayer player) {
        return player.getData(ModAttachments.TRAVERSE)[ACTIVE] == SHADOW;
    }

    /** R на технике шага с контекстом ввода. */
    public static void request(ServerPlayer player, ResourceLocation techniqueId, int input) {
        TechniqueDefinition def = TechniqueLoader.get(techniqueId);
        FootworkFamily family = family(def);
        if (family == null) {
            TechniqueService.tryStart(player, techniqueId);
            return;
        }
        if (!MasteryService.knows(player, techniqueId)) {
            TechniqueService.tryStart(player, techniqueId);
            return;
        }
        int layer = Math.max(0, MasteryService.layer(player, techniqueId));
        Vec3 forward = horizontal(player.getLookAngle());
        Vec3 right = new Vec3(-forward.z, 0.0D, forward.x);
        Vec3 move = Vec3.ZERO;
        if ((input & FORWARD) != 0) {
            move = move.add(forward);
        }
        if ((input & BACK) != 0) {
            move = move.subtract(forward);
        }
        if ((input & RIGHT) != 0) {
            move = move.add(right);
        }
        if ((input & LEFT) != 0) {
            move = move.subtract(right);
        }
        boolean sideOrBack = (input & (BACK | LEFT | RIGHT)) != 0 && move.lengthSqr() > 1.0E-6D;
        long now = player.serverLevel().getGameTime();
        int[] s = player.getData(ModAttachments.TRAVERSE);
        boolean inAir = !player.onGround() && now - s[LAST_GROUND] > 2;
        String mode = def.behavior() instanceof TechniqueBehavior.Footwork f ? f.mode()
                : def.behavior() instanceof TechniqueBehavior.Step ? "evade" : "";
        if (!mode.isEmpty() && !inAir) {
            runForm(player, techniqueId, family, layer, mode, input, forward, move, sideOrBack);
            return;
        }

        if (inAir) {
            if (family.canAirTurn(layer)) {
                airTurn(player, family, layer, move.lengthSqr() > 1.0E-6D ? move.normalize() : forward);
            } else {
                locked(player, "air_turn", family);
            }
            return;
        }
        if (inShadow(player)) {
            stop(player);
            return;
        }
        if ((input & SNEAK) != 0) {
            if (family == FootworkFamily.HUASHAN) {
                dash(player, techniqueId, sideOrBack ? move.normalize() : forward.scale(-1.0D));
            } else if (layer >= 1) {
                startShadow(player, techniqueId, layer);
            } else {
                locked(player, "shadow", family);
            }
            return;
        }
        if ((input & SPRINT) != 0 && (input & FORWARD) != 0) {
            if (isRunning(player)) {
                stop(player);
            } else if (family.canRun(layer)) {
                startRun(player, techniqueId, family, layer);
            } else {
                locked(player, "run", family);
            }
            return;
        }
        if (isRunning(player)) {
            stop(player);
            return;
        }
        if (sideOrBack) {
            dash(player, techniqueId, move.normalize());
            return;
        }
        if (family == FootworkFamily.HUASHAN) {
            // Сближение: прежний шаг по взгляду (серия на высшем слое — тот же путь).
            dash(player, techniqueId, forward);
            return;
        }
        // Шаг Смерти (L3+): цель в конусе 30° до 8 блоков — к ней; вперёд+R — сквозь неё.
        net.minecraft.world.entity.LivingEntity target = layer >= 3 ? softTarget(player) : null;
        if (target != null) {
            Vec3 to = target.position().subtract(player.position());
            Vec3 dir = horizontal(to);
            double half = target.getBbWidth() / 2.0D + player.getBbWidth() / 2.0D;
            double flat = Math.sqrt(to.x * to.x + to.z * to.z);
            double reach = (input & FORWARD) != 0 ? flat + half + 1.2D : flat - half - 0.2D;
            reach = Math.max(0.5D, Math.min(io.github.verycooltimo.murim.technique.ShadowRules.deathDistance(layer), reach));
            player.setData(ModAttachments.FOOTWORK_DIR, new float[] {(float) dir.x, (float) dir.z, 1.0F, (float) reach});
            TechniqueService.tryStart(player, techniqueId);
            return;
        }
        dash(player, techniqueId, (input & FORWARD) != 0 ? forward : forward.scale(-1.0D));
    }

    /**
     * Форма стиля шагов, выбранная на кольце: R делает именно её, направление и цель лишь
     * задают путь (03.10). Бег и тень — переключатели: повторное R выключает.
     */
    private static void runForm(ServerPlayer player, ResourceLocation id, FootworkFamily family, int layer, String mode,
                                int input, Vec3 forward, Vec3 move, boolean sideOrBack) {
        switch (mode) {
            case "run" -> {
                if (isRunning(player)) {
                    stop(player);
                } else {
                    if (inShadow(player)) {
                        stop(player);
                    }
                    startRun(player, id, family, Math.max(layer, family == FootworkFamily.HUASHAN ? 1 : 0));
                }
            }
            case "shadow" -> {
                if (inShadow(player)) {
                    stop(player);
                } else {
                    if (isRunning(player)) {
                        stop(player);
                    }
                    startShadow(player, id, Math.max(1, layer));
                }
            }
            case "death", "behind" -> {
                net.minecraft.world.entity.LivingEntity target = softTarget(player);
                if (target == null) {
                    dash(player, id, sideOrBack ? move.normalize() : forward);
                    return;
                }
                Vec3 to = target.position().subtract(player.position());
                Vec3 dir = horizontal(to);
                double flat = Math.sqrt(to.x * to.x + to.z * to.z);
                double half = target.getBbWidth() / 2.0D + player.getBbWidth() / 2.0D;
                double reach;
                if ("behind".equals(mode)) {
                    // Аромат за спиной: дугой обойти цель и выйти сбоку-сзади в 1,5–2 блоках;
                    // сторону обхода задаёт A/D (по умолчанию — слева).
                    Vec3 side = new Vec3(-dir.z, 0.0D, dir.x).scale((input & RIGHT) != 0 ? 1.0D : -1.0D);
                    Vec3 end = target.position().add(dir.scale(half + 0.8D)).add(side.scale(1.4D));
                    Vec3 path = end.subtract(player.position());
                    dir = horizontal(path);
                    reach = Math.min(6.0D, Math.sqrt(path.x * path.x + path.z * path.z));
                } else {
                    reach = Math.min(io.github.verycooltimo.murim.technique.ShadowRules.deathDistance(Math.max(3, layer)), flat + half + 1.2D);
                }
                player.setData(ModAttachments.FOOTWORK_DIR, new float[] {(float) dir.x, (float) dir.z, 1.0F, (float) Math.max(0.5D, reach)});
                TechniqueService.tryStart(player, id);
            }
            default -> {
                if (isRunning(player) || inShadow(player)) {
                    stop(player);
                }
                dash(player, id, sideOrBack ? move.normalize() : forward.scale(-1.0D));
            }
        }
    }

    /** Рывок техники в заданном направлении: направление запоминается до конца её серии. */
    private static void dash(ServerPlayer player, ResourceLocation id, Vec3 dir) {
        player.setData(ModAttachments.FOOTWORK_DIR, new float[] {(float) dir.x, (float) dir.z, 1.0F, 0.0F});
        TechniqueService.tryStart(player, id);
    }

    /** Направление рывка, заданное контекстом, или null — тогда по взгляду. */
    public static Vec3 dashDirection(ServerPlayer player) {
        float[] d = player.getData(ModAttachments.FOOTWORK_DIR);
        return d[2] != 0.0F ? new Vec3(d[0], 0.0D, d[1]) : null;
    }

    /**
     * Шаг Мига Бога Ветров (фаза удара техники): короткий рывок по направлению контекста до
     * первой стены, окно уклонения по таблице, без урона.
     */
    public static void windEvade(ServerPlayer player, ResourceLocation id) {
        int layer = Math.max(0, MasteryService.layer(player, id));
        int tier = FootworkFamily.WIND_GOD.tier(layer);
        Vec3 dir = dashDirection(player);
        if (dir == null) {
            dir = horizontal(player.getLookAngle()).scale(-1.0D);
        }
        Vec3 start = player.position();
        float override = player.getData(ModAttachments.FOOTWORK_DIR)[3];
        boolean death = override > 0.0F;
        double distance = death ? override : FootworkFamily.evadeDistance(tier);
        Vec3 from = start.add(0.0D, 0.9D, 0.0D);
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                from, from.add(dir.scale(distance)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        double reach = wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                ? distance : Math.max(0.0D, wall.getLocation().distanceTo(from) - 0.5D);
        sendDash(player, dir, reach, death ? 6 : 5);
        // Смерть — проход для атаки, без окна уклонения; Миг — уклонение.
        int iframes = death ? 0 : FootworkFamily.evadeInvulnerable(tier);
        if (iframes > 0) {
            player.invulnerableTime = Math.max(player.invulnerableTime, iframes);
        }
        MasteryService.onMiss(player, id);
        send(player, death ? 8 : 5, layer, dir.scale(reach));
    }

    /**
     * Рывок шага: не телепорт, а движение самого игрока за {@code ticks} тиков — клиент проходит
     * путь своей физикой (коллизии, камера), сервер лишь заранее обрезал путь первой стеной.
     */
    public static void sendDash(ServerPlayer player, Vec3 dir, double reach, int ticks) {
        if (reach < 0.05D) {
            return;
        }
        PacketDistributor.sendToPlayer(player, new TraversePayloads.Dash((float) dir.x, (float) dir.z, (float) reach, ticks));
    }

    /**
     * Мягкое выделение цели для Шага Смерти: ближайший враждебный в 8 блоках, не дальше 30° от
     * взгляда и в прямой видимости. Мирное животное целью не считается.
     */
    private static net.minecraft.world.entity.LivingEntity softTarget(ServerPlayer player) {
        Vec3 look = horizontal(player.getLookAngle());
        double cos = Math.cos(Math.toRadians(30.0D));
        net.minecraft.world.entity.LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.LivingEntity e : player.level().getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, player.getBoundingBox().inflate(8.0D),
                e -> e != player && e.isAlive() && e instanceof net.minecraft.world.entity.monster.Enemy)) {
            Vec3 to = e.position().subtract(player.position());
            double d = to.length();
            if (d > 8.0D || horizontal(to).dot(look) < cos || !player.hasLineOfSight(e)) {
                continue;
            }
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** Шаг Тени: присед, медленный тихий ход; мобы замечают с меньшего радиуса. */
    private static void startShadow(ServerPlayer player, ResourceLocation technique, int layer) {
        long now = player.serverLevel().getGameTime();
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (now < (long) s[SHADOW_READY]) {
            return;
        }
        if (isRunning(player)) {
            stop(player);
        }
        if (!pay(player, io.github.verycooltimo.murim.technique.ShadowRules.entryQi(layer))) {
            player.displayClientMessage(Component.translatable("murim.technique.no_qi"), true);
            return;
        }
        int[] n = player.getData(ModAttachments.TRAVERSE).clone();
        n[ACTIVE] = SHADOW;
        n[LAYER] = layer;
        n[FAMILY] = FootworkFamily.WIND_GOD.ordinal();
        n[RUN_LEFT] = io.github.verycooltimo.murim.technique.ShadowRules.duration(layer);
        n[PAID] = 0;
        n[NO_SPRINT] = 0;
        player.setData(ModAttachments.TRAVERSE, n);
        player.setData(ModAttachments.FOOTWORK_TECH, technique.toString());
        double factor = Math.min(1.0D, io.github.verycooltimo.murim.technique.ShadowRules.speed(layer)
                / io.github.verycooltimo.murim.technique.ShadowRules.WALK);
        modify(player, Attributes.SNEAKING_SPEED, SNEAK_SPEED, Math.max(0.0D, factor - 0.3D), AttributeModifier.Operation.ADD_VALUE);
        send(player, 6, layer, Vec3.ZERO);
    }

    /** Поддерживаемые наблюдатели Тени — у их ИИ проверено первичное обнаружение. */
    private static boolean supported(net.minecraft.world.entity.Entity e) {
        net.minecraft.world.entity.EntityType<?> t = e.getType();
        return t == net.minecraft.world.entity.EntityType.ZOMBIE || t == net.minecraft.world.entity.EntityType.SKELETON
                || t == net.minecraft.world.entity.EntityType.CREEPER;
    }

    /**
     * Новое обнаружение игрока в Тени: ближайшие N поддерживаемых мобов (N по слою) замечают его
     * только с уменьшенного радиуса. Уже начатую погоню не сбрасываем, остальные видят как обычно.
     */
    @SubscribeEvent
    static void onTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (!(event.getNewAboutToBeSetTarget() instanceof ServerPlayer player) || !inShadow(player)
                || !(event.getEntity() instanceof net.minecraft.world.entity.Mob mob) || !supported(mob)
                || mob.getTarget() == player) {
            return;
        }
        int layer = player.getData(ModAttachments.TRAVERSE)[LAYER];
        java.util.List<net.minecraft.world.entity.Mob> observers = player.level().getEntitiesOfClass(
                net.minecraft.world.entity.Mob.class,
                player.getBoundingBox().inflate(io.github.verycooltimo.murim.technique.ShadowRules.OBSERVER_RANGE),
                FootworkService::supported);
        observers.sort(java.util.Comparator.<net.minecraft.world.entity.Mob>comparingDouble(player::distanceToSqr)
                .thenComparing(net.minecraft.world.entity.Entity::getUUID));
        int rank = observers.indexOf(mob);
        if (rank < 0 || rank >= io.github.verycooltimo.murim.technique.ShadowRules.observers(layer)) {
            return;
        }
        boolean bright = player.level().getMaxLocalRawBrightness(player.blockPosition()) >= 12;
        double radius = io.github.verycooltimo.murim.technique.ShadowRules.radius(
                mob.getAttributeValue(Attributes.FOLLOW_RANGE), layer, bright);
        if (mob.distanceTo(player) > radius) {
            event.setCanceled(true);
        }
    }

    /** Атака снимает и бег, и Тень — до самого удара. */
    @SubscribeEvent
    static void onAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getData(ModAttachments.TRAVERSE)[ACTIVE] != 0) {
            stop(player);
        }
    }

    /** Включить бег. Оплата — потиково, первый тик проверяется до выдачи бонусов. */
    public static void startRun(ServerPlayer player, ResourceLocation technique, FootworkFamily family, int layer) {
        int tier = family.tier(layer);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (profile.circulating() < TraverseRules.qiPerSecond(tier) / 20.0D) {
            player.displayClientMessage(Component.translatable("murim.technique.no_qi"), true);
            return;
        }
        int[] s = player.getData(ModAttachments.TRAVERSE).clone();
        s[ACTIVE] = 1;
        s[LAYER] = layer;
        s[FAMILY] = family.ordinal();
        s[RUN_LEFT] = TraverseRules.maxTicks(tier);
        s[NO_SPRINT] = 0;
        s[PAID] = 0;
        player.setData(ModAttachments.TRAVERSE, s);
        player.setData(ModAttachments.FOOTWORK_TECH, technique.toString());
        modify(player, Attributes.MOVEMENT_SPEED, SPEED, TraverseRules.speedBonus(tier), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        double step = TraverseRules.stepHeight(tier) - 0.6D;
        if (step > 0.0D) {
            modify(player, Attributes.STEP_HEIGHT, STEP, step, AttributeModifier.Operation.ADD_VALUE);
        }
        modify(player, Attributes.SAFE_FALL_DISTANCE, FALL, TraverseRules.safeFall(tier) - 3.0D, AttributeModifier.Operation.ADD_VALUE);
        send(player, 1, layer, new Vec3(family.ordinal(), 0.0D, 0.0D));
    }

    public static void stop(ServerPlayer player) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == 0) {
            return;
        }
        int[] n = s.clone();
        boolean wasShadow = n[ACTIVE] == SHADOW;
        n[ACTIVE] = 0;
        if (wasShadow) {
            n[SHADOW_READY] = (int) (player.serverLevel().getGameTime()
                    + io.github.verycooltimo.murim.technique.ShadowRules.cooldown(n[LAYER]));
        }
        player.setData(ModAttachments.TRAVERSE, n);
        remove(player, Attributes.MOVEMENT_SPEED, SPEED);
        remove(player, Attributes.STEP_HEIGHT, STEP);
        remove(player, Attributes.SNEAKING_SPEED, SNEAK_SPEED);
        // Безопасное падение держится до первой посадки: прыжок мог начаться на последнем тике бега.
        if (player.onGround()) {
            remove(player, Attributes.SAFE_FALL_DISTANCE, FALL);
        }
        send(player, wasShadow ? 7 : 0, n[LAYER], Vec3.ZERO);
    }

    /** Полная очистка: смерть, выход, смена мира — снимает и бонус падения. */
    private static void hardCleanup(ServerPlayer player) {
        stop(player);
        remove(player, Attributes.SAFE_FALL_DISTANCE, FALL);
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        int[] s = player.getData(ModAttachments.TRAVERSE);
        int[] n = s.clone();
        if (player.onGround()) {
            n[GROUND_STREAK] = now - s[LAST_GROUND] == 1 ? s[GROUND_STREAK] + 1 : 1;
            n[LAST_GROUND] = (int) now;
            // Заряды стены и воздуха возвращает только настоящая опора несколько тиков подряд.
            if (n[GROUND_STREAK] >= GROUND_RESET) {
                n[KICKS_USED] = 0;
                n[AIR_USED] = 0;
            }
        } else {
            n[GROUND_STREAK] = 0;
        }
        player.setData(ModAttachments.TRAVERSE, n);
        if (n[ACTIVE] == 0) {
            AttributeInstance fall = player.getAttribute(Attributes.SAFE_FALL_DISTANCE);
            if (player.onGround() && fall != null && fall.hasModifier(FALL)) {
                fall.removeModifier(FALL);
            }
            return;
        }
        if (n[ACTIVE] == SHADOW) {
            shadowTick(player, n, now);
            return;
        }
        try {
            int[] r = n.clone();
            int tier = FootworkFamily.values()[r[FAMILY]].tier(r[LAYER]);
            double cost = TraverseRules.qiPerSecond(tier) / 20.0D;
            DantianProfile profile = player.getData(ModAttachments.PROFILE);
            if (!player.isSprinting() && player.onGround()) {
                r[NO_SPRINT]++;
            } else {
                r[NO_SPRINT] = 0;
            }
            if (r[RUN_LEFT] <= 0 || r[NO_SPRINT] > GRACE || profile.circulating() < cost || player.isPassenger()
                    || player.isFallFlying() || player.isInWater() || !player.isAlive() || player.isShiftKeyDown()) {
                player.setData(ModAttachments.TRAVERSE, r);
                stop(player);
                ProfileNetwork.sync(player);
                return;
            }
            player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
            r[RUN_LEFT]--;
            r[PAID]++;
            if (r[PAID] % 20 == 0) {
                // Освоение: одно событие практики на секунду реального бега.
                ProfileNetwork.sync(player);
                ResourceLocation tech = runningTechnique(player);
                if (tech != null) {
                    MasteryService.onMiss(player, tech);
                }
            }
            player.setData(ModAttachments.TRAVERSE, r);
        } catch (RuntimeException e) {
            MurimMod.LOGGER.error("Сбой шага у {}", player.getGameProfile().getName(), e);
            hardCleanup(player);
        }
    }

    /**
     * Тень держится, пока игрок приседает на земле, не спринтует, есть ци и время; касание
     * поддерживаемого моба ближе 1,5 блока раскрывает.
     */
    private static void shadowTick(ServerPlayer player, int[] n, long now) {
        int[] r = n.clone();
        double cost = io.github.verycooltimo.murim.technique.ShadowRules.qiPerSecond(r[LAYER]) / 20.0D;
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        boolean contact = !player.level().getEntitiesOfClass(net.minecraft.world.entity.Mob.class,
                player.getBoundingBox().inflate(io.github.verycooltimo.murim.technique.ShadowRules.CONTACT),
                FootworkService::supported).isEmpty();
        // Тень — форма на кольце (03.10): держится своё время, приседать не нужно; гаснет по
        // времени, повторным R, бегом, контактом или когда кончилась ци.
        if (r[RUN_LEFT] <= 0 || player.isSprinting() || now - r[LAST_GROUND] > 2
                || profile.circulating() < cost || contact || !player.isAlive() || player.isPassenger()) {
            player.setData(ModAttachments.TRAVERSE, r);
            stop(player);
            ProfileNetwork.sync(player);
            return;
        }
        player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
        r[RUN_LEFT]--;
        r[PAID]++;
        if (r[PAID] % 20 == 0) {
            ProfileNetwork.sync(player);
            ResourceLocation tech = runningTechnique(player);
            if (tech != null) {
                MasteryService.onMiss(player, tech);
            }
        }
        player.setData(ModAttachments.TRAVERSE, r);
    }

    /** Техника шага, которая держит бег. */
    private static ResourceLocation runningTechnique(ServerPlayer player) {
        String id = player.getData(ModAttachments.FOOTWORK_TECH);
        return id.isEmpty() ? null : ResourceLocation.tryParse(id);
    }

    /**
     * Прыжок во время бега: на земле — перелёт (Хуашань и Бог Ветров с L2), в воздухе у стены —
     * отталкивание (только Бог Ветров, L4+). Поворот в воздухе теперь на R, не на прыжке.
     */
    public static void jump(ServerPlayer player, boolean clientGrounded) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == 0) {
            return;
        }
        FootworkFamily family = FootworkFamily.values()[s[FAMILY]];
        int layer = s[LAYER];
        int tier = family.tier(layer);
        Vec3 look = horizontal(player.getLookAngle());
        long now = player.serverLevel().getGameTime();
        // Клиент шлёт прыжок в момент отрыва: сервер мог ещё не увидеть землю — верим опоре
        // не старше трёх тиков, а не вертикальной скорости (после стены она тоже положительна).
        boolean grounded = player.onGround() || clientGrounded && now - s[LAST_GROUND] <= 3;
        int[] n = s.clone();
        if (grounded) {
            if (!family.canLeap(layer) || now - (long) n[LAST_LEAP] < TraverseRules.LEAP_COOLDOWN
                    || !pay(player, TraverseRules.LEAP_QI)) {
                return;
            }
            n[LAST_LEAP] = (int) now;
            player.setDeltaMovement(look.scale(TraverseRules.leapHorizontal(Math.max(1, tier)))
                    .add(0.0D, TraverseRules.leapVertical(Math.max(1, tier)), 0.0D));
            player.hurtMarked = true;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 2, layer, look);
            return;
        }
        Vec3 wall = wallNormal(player);
        if (wall != null && n[KICKS_USED] < family.wallKicks(layer)) {
            if (!pay(player, TraverseRules.WALL_KICK_QI)) {
                return;
            }
            n[KICKS_USED]++;
            Vec3 along = look.subtract(wall.scale(look.dot(wall)));
            Vec3 v = wall.scale(TraverseRules.WALL_KICK_OUT).add(along.scale(0.25D)).add(0.0D, TraverseRules.WALL_KICK_UP, 0.0D);
            player.setDeltaMovement(v);
            player.hurtMarked = true;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 3, layer, wall);
        }
    }

    /** Поворот в воздухе по R: одна горизонтальная смена направления за отрыв, высота сохраняется. */
    private static void airTurn(ServerPlayer player, FootworkFamily family, int layer, Vec3 dir) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[AIR_USED] != 0) {
            return;
        }
        if (!pay(player, TraverseRules.AIR_CORRECTION_QI)) {
            player.displayClientMessage(Component.translatable("murim.technique.no_qi"), true);
            return;
        }
        int[] n = s.clone();
        n[AIR_USED] = 1;
        Vec3 d = player.getDeltaMovement();
        player.setDeltaMovement(dir.scale(TraverseRules.AIR_CORRECTION_SPEED).add(0.0D, d.y, 0.0D));
        player.hurtMarked = true;
        player.setData(ModAttachments.TRAVERSE, n);
        send(player, 4, layer, dir);
    }

    private static void locked(ServerPlayer player, String form, FootworkFamily family) {
        player.displayClientMessage(Component.translatable("murim.footwork.locked",
                Component.translatable("murim.footwork." + form)), true);
    }

    /** Нормаль ближайшей стены сбоку или спереди (до 0,4 блока от хитбокса), иначе null. */
    private static Vec3 wallNormal(ServerPlayer player) {
        for (Direction dir : Direction.Plane.HORIZONTAL) {
            Vec3 probe = player.position().add(dir.getStepX() * 0.7D, 0.9D, dir.getStepZ() * 0.7D);
            BlockPos pos = BlockPos.containing(probe);
            if (player.level().getBlockState(pos).isFaceSturdy(player.level(), pos, dir.getOpposite())) {
                return new Vec3(-dir.getStepX(), 0.0D, -dir.getStepZ());
            }
        }
        return null;
    }

    private static boolean pay(ServerPlayer player, double cost) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (profile.circulating() < cost) {
            return false;
        }
        player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
        ProfileNetwork.sync(player);
        return true;
    }

    /** Урон сбивает бег: шаг-путешествие — не уклонение. */
    @SubscribeEvent
    static void onDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity() instanceof ServerPlayer player && player.getData(ModAttachments.TRAVERSE)[ACTIVE] != 0) {
            stop(player);
        }
    }

    @SubscribeEvent
    static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            hardCleanup(player);
        }
    }

    @SubscribeEvent
    static void onDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            hardCleanup(player);
        }
    }

    @SubscribeEvent
    static void onRespawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            hardCleanup(player);
        }
    }

    private static Vec3 horizontal(Vec3 v) {
        Vec3 h = new Vec3(v.x, 0.0D, v.z);
        return h.lengthSqr() < 1.0E-6D ? new Vec3(0.0D, 0.0D, 1.0D) : h.normalize();
    }

    private static void modify(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id, double amount,
                               AttributeModifier.Operation op) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst == null) {
            return;
        }
        inst.removeModifier(id);
        inst.addTransientModifier(new AttributeModifier(id, amount, op));
    }

    private static void remove(ServerPlayer player, Holder<Attribute> attribute, ResourceLocation id) {
        AttributeInstance inst = player.getAttribute(attribute);
        if (inst != null) {
            inst.removeModifier(id);
        }
    }

    private static void send(ServerPlayer player, int kind, int layer, Vec3 dir) {
        PacketDistributor.sendToPlayersTrackingEntityAndSelf(player,
                new TraversePayloads.Event(player.getId(), kind, layer, (float) dir.x, (float) dir.z));
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private FootworkService() {
    }
}
