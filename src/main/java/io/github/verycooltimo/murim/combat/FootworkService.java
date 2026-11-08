package io.github.verycooltimo.murim.combat;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.cultivation.QiRules;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.TraversePayloads;
import io.github.verycooltimo.murim.profile.DantianProfile;
import io.github.verycooltimo.murim.profile.ProfileNetwork;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.technique.FootworkFamily;
import io.github.verycooltimo.murim.technique.SevenStarsRules;
import io.github.verycooltimo.murim.network.TechniqueEventPayload;
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
import net.minecraft.world.entity.LivingEntity;
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

/** Server authority for the selected footwork form.
 * Design: docs/design/techniques/hua-footwork-design-1031.md, work items 1–2.
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
    /** Passive sprint request: start the selected run, never toggle it off. */
    public static final int PASSIVE = 128;

    private static final ResourceLocation SPEED = id("traverse_speed");
    private static final ResourceLocation STEP = id("traverse_step");
    private static final ResourceLocation FALL = id("traverse_fall");

    private static final int ACTIVE = 0;
    private static final int TIER = 1;
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
    private static final int SHADOW_ENTRY = 3;
    // Transient server-only movement; clients receive the existing traverse events.
    private record FormMotion(ResourceLocation form, Vec3 start, Vec3 end, int age, int windup, int movement, int recovery, boolean practice, java.util.Set<java.util.UUID> hit,
                              net.minecraft.world.entity.LivingEntity target, java.util.List<Vec3> route) {
        FormMotion(ResourceLocation form, Vec3 start, Vec3 end, int age, int windup, int movement, int recovery,
                   boolean practice, java.util.Set<java.util.UUID> hit, LivingEntity target) {
            this(form, start, end, age, windup, movement, recovery, practice, hit, target, java.util.List.of());
        }
        FormMotion(ResourceLocation form, Vec3 start, Vec3 end, int age, int windup, int movement, int recovery,
                   boolean practice, java.util.Set<java.util.UUID> hit) {
            this(form, start, end, age, windup, movement, recovery, practice, hit, null);
        }
    }
    private static final java.util.Map<ServerPlayer, FormMotion> MOTIONS = new java.util.WeakHashMap<>();
    static java.util.List<Vec3> approvedRoute(ServerPlayer player) {
        var motion = MOTIONS.get(player);
        return motion == null ? java.util.List.of() : motion.route();
    }
    private static final java.util.Map<ServerPlayer, Integer> FLASH_PROTECTION = new java.util.WeakHashMap<>();

    public static boolean active(ServerPlayer player) {
        return DipperService.active(player) || player.getData(ModAttachments.TRAVERSE)[ACTIVE] != 0 || MOTIONS.containsKey(player)
                || FragranceAirService.state(player).form != null;
    }

    public static boolean sevenStarsActive(ServerPlayer player) {
        FormMotion motion = MOTIONS.get(player);
        return DipperService.active(player) || motion != null && SevenStarsRules.isForm(motion.form());
    }

    private static void cancelMotion(ServerPlayer player) {
        DipperService.end(player, false);
        player.getData(ModAttachments.WIND_GOD).chasingSword = null;
        FormMotion cancelled = MOTIONS.remove(player);
        if (cancelled != null && SevenStarsRules.isForm(cancelled.form())) sevenEvent(player, cancelled.form(), TechniqueEventPayload.Event.CANCELLED);
        Integer previous = FLASH_PROTECTION.remove(player);
        // Preserve a newer vanilla damage-immunity window; revoke only the short step window.
        if (previous != null && player.invulnerableTime <= 4) player.invulnerableTime = Math.min(player.invulnerableTime, previous);
    }

    public static boolean selected(ServerPlayer player, ResourceLocation form) {
        if (form == null) return false;
        var loadout = player.getData(ModAttachments.LOADOUT);
        for (int slot = 0; slot < io.github.verycooltimo.murim.mastery.LoadoutService.openSlots(player); slot++) {
            if (loadout.at(slot).filter(form::equals).isPresent()) return true;
        }
        return false;
    }

    public static void selectionChanged(ServerPlayer player) {
        FragranceAirService.selectionChanged(player);
        ResourceLocation form = runningTechnique(player);
        if (player.getData(ModAttachments.TRAVERSE)[ACTIVE] != 0 && form != null && !selected(player, form)) hardCleanup(player);
        FormMotion motion = MOTIONS.get(player);
        if (motion != null && !selected(player, motion.form())) cancelMotion(player);
        var state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (state.isActive() && family(TechniqueLoader.get(state.techniqueId())) != null
                && !selected(player, state.techniqueId())) TechniqueService.cancel(player);
    }

    private static boolean selectedRun(ServerPlayer player) {
        ResourceLocation form = runningTechnique(player);
        TechniqueDefinition def = form == null ? null : TechniqueLoader.get(form);
        return form != null && selected(player, form) && def != null
                && def.behavior() instanceof TechniqueBehavior.Footwork f && "run".equals(f.mode());
    }

    private static final ResourceLocation SHADOW_SPEED = id("shadow_speed");

    /** God Wind reaches its table speed ×1.6 over the first second. */
    private static final int WIND_RAMP = TraverseRules.WIND_RAMP_TICKS;

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
        return player.getData(ModAttachments.TRAVERSE)[ACTIVE] == SHADOW
                && selected(player, runningTechnique(player));
    }

    /** R на технике шага с контекстом ввода. Null means accepted; a refusal returns its failed gate. */
    public static String request(ServerPlayer player, ResourceLocation techniqueId, int input) {
        if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(player)) return "pinned";
        TechniqueDefinition def = TechniqueLoader.get(techniqueId);
        FootworkFamily family = family(def);
        if (family == null || !MasteryService.knows(player, techniqueId)) {
            String[] reason = {"technique_dispatch_refused"};
            return TechniqueService.tryStart(player, techniqueId, 1, why -> reason[0] = why) ? null : reason[0];
        }
        selectionChanged(player);
        if (!selected(player, techniqueId)) return "form_not_equipped";
        int executionTier = Math.max(0, MasteryService.executionTier(player, techniqueId));
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
        if ("shadow".equals(mode) && inShadow(player) && (input & PASSIVE) == 0) {
            stop(player);
            return "footwork_toggle_or_preconditions";
        }
        if (FragranceAirService.isForm(techniqueId)) {
            if ((input & PASSIVE) == 0) FragranceAirService.request(player, techniqueId);
            return "footwork_toggle_or_preconditions";
        }
        if (inAir) {
            if ("run".equals(mode) && family.canAirTurn(executionTier)) {
                airTurn(player, family, executionTier, move.lengthSqr() > 1.0E-6D ? move.normalize() : forward);
            }
            return "airborne";
        }
        if ((input & PASSIVE) != 0 && (!"run".equals(mode) || !player.isSprinting())) return "invalid_passive_input";
        if (SevenStarsRules.dipper(techniqueId)) return DipperService.start(player);
        if (SevenStarsRules.isForm(techniqueId)) {
            if ((input & PASSIVE) != 0 || (!SevenStarsRules.follows(techniqueId) && move.lengthSqr() < 1e-6))
                return "step_requires_direction";
            return startSevenStars(player, techniqueId, move.lengthSqr() > 1e-6 ? move.normalize() : forward.scale(-1));
        }
        if (!mode.isEmpty()) {
            Long before = player.getData(ModAttachments.COOLDOWNS).get(techniqueId);
            runForm(player, techniqueId, family, executionTier, mode, input, forward, move, sideOrBack);
            return !java.util.Objects.equals(before, player.getData(ModAttachments.COOLDOWNS).get(techniqueId))
                    ? null : "footwork_preconditions_or_cooldown";
        }
        return "missing_footwork_mode";
    }

    /**
     * Форма стиля шагов, выбранная на кольце: R делает именно её, направление и цель лишь
     * задают путь (03.10). Бег и тень — переключатели: повторное R выключает.
     */
    private static void runForm(ServerPlayer player, ResourceLocation id, FootworkFamily family, int executionTier, String mode,
                                int input, Vec3 forward, Vec3 move, boolean sideOrBack) {
        switch (mode) {
            case "run" -> {
                // Автобег по спринту не гасит Тень: в Тени бегают (автор 03.10: «не надо приседать, ты бегать должен»).
                if ((input & PASSIVE) != 0 && (isRunning(player) || inShadow(player))) {
                    return;
                }
                if (isRunning(player)) {
                    stop(player);
                } else {
                    if (inShadow(player)) {
                        stop(player);
                    }
                    startRun(player, id, family, Math.max(executionTier, family == FootworkFamily.HUASHAN ? 1 : 0));
                }
            }
            case "shadow" -> {
                if ((input & PASSIVE) != 0) return;
                if (player.getData(ModAttachments.TRAVERSE)[ACTIVE] == SHADOW
                        || player.getData(ModAttachments.TRAVERSE)[ACTIVE] == SHADOW_ENTRY) stop(player);
                else startShadow(player, id, MasteryService.stars(player, id));
            }
            case "death", "behind" -> {
                if (family == FootworkFamily.WIND_GOD) {
                    startDeath(player, id);
                    return;
                }
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
                    reach = Math.min(io.github.verycooltimo.murim.technique.ShadowRules.deathDistance(Math.max(3, executionTier)), flat + half + 1.2D);
                }
                player.setData(ModAttachments.FOOTWORK_DIR, new float[] {(float) dir.x, (float) dir.z, 1.0F, (float) Math.max(0.5D, reach)});
                TechniqueService.tryStart(player, id);
            }
            default -> {
                if (isRunning(player) || inShadow(player)) {
                    stop(player);
                }
                if (family == FootworkFamily.WIND_GOD && (input & FORWARD) != 0
                        && (input & (BACK | LEFT | RIGHT)) == 0) {
                    // Negative reach marks forward Flash; resolve its target at the movement phase.
                    // This context stays server-side; the existing motion/event packets carry the result.
                    player.setData(ModAttachments.FOOTWORK_DIR, new float[] {(float) forward.x, (float) forward.z, 1, -1});
                    TechniqueService.tryStart(player, id);
                } else {
                    dash(player, id, sideOrBack ? move.normalize() : forward.scale(-1.0D));
                }
            }
        }
    }

    /** Buffer a linked sword until the four-tick lunge has actually reached its endpoint. */
    static boolean bufferChasingSword(ServerPlayer player, ResourceLocation sword) {
        var motion = MOTIONS.get(player);
        if (motion == null || !SevenStarsRules.follows(motion.form())
                || motion.age() >= motion.windup() + motion.movement()) return false;
        player.getData(ModAttachments.WIND_GOD).chasingSword = sword;
        return true;
    }

    static void endSevenForSword(ServerPlayer player) { cancelMotion(player); }

    /** Confirmed recent hit plus outward velocity: a stationary lock never grants pursuit.
     * API: /tmp/mr-ref-1121/minecraft-src Entity#position/getDeltaMovement/getUUID; Vec3#dot. */
    static LivingEntity chasingTarget(ServerPlayer player) {
        var seven = player.getData(ModAttachments.WIND_GOD);
        // API: /tmp/mr-ref-1121/minecraft-src ServerLevel#getEntity(UUID).
        var entity = seven.chasedFoe == null ? null : player.serverLevel().getEntity(seven.chasedFoe);
        if (!(entity instanceof LivingEntity target) || !target.isAlive() || !player.hasLineOfSight(target)
                || !SevenStarsRules.within(player.level().getGameTime(), seven.chasedHit, 40)) return null;
        Vec3 away = target.position().subtract(player.position());
        double distance = away.length();
        return distance >= 3 && distance <= 6 && target.getDeltaMovement().dot(away.normalize()) > .02 ? target : null;
    }

    private static String startSevenStars(ServerPlayer player, ResourceLocation form, Vec3 direction) {
        boolean follow = SevenStarsRules.follows(form);
        boolean link = TechniqueService.canLinkStep(player) || follow
                && !player.getData(ModAttachments.TECHNIQUE_STATE).isActive()
                && SevenStarsRules.within(player.level().getGameTime(), player.getData(ModAttachments.WIND_GOD).chasedHit, 8);
        if (!selected(player, form)) return "form_not_equipped";
        if (!(link ? canSustainBody(player) : canSustain(player))) return "body_or_active_technique_blocks_step";
        if (Stun.isStunned(player)) return "stunned";
        if (active(player)) return "footwork_already_active";
        int stars = MasteryService.stars(player, form);
        long now = player.serverLevel().getGameTime();
        Long previous = player.getData(ModAttachments.COOLDOWNS).get(form);
        if (previous != null && now - previous < SevenStarsRules.COOLDOWN) return "step_cooldown";
        long last = player.getData(ModAttachments.TECHNIQUE_STATE).lastStartGameTime();
        if (!link && !TechniqueService.offCooldown(now, last, TechniqueService.gapAfter(player.getData(ModAttachments.COOLDOWNS), last))) return "global_technique_gap";
        double distance = follow ? Math.min(2, SevenStarsRules.distance(stars, true)) : SevenStarsRules.distance(stars, false);
        var target = follow ? chasingTarget(player) : null;
        if (follow) {
            direction = horizontal(player.getLookAngle());
            if (target != null) {
                direction = horizontal(target.position().subtract(player.position()));
                distance = Math.max(0, target.position().subtract(player.position()).dot(direction)
                        - (target.getBbWidth() + player.getBbWidth()) / 2 - 1.1);
            }
        }
        if (!pay(player, SevenStarsRules.qi(stars)
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player))) return "insufficient_qi";
        TechniqueService.linkStep(player);
        var seven = player.getData(ModAttachments.WIND_GOD);
        seven.sevenStart = now;
        seven.chasingLink = follow;
        seven.shiftStart = follow ? Long.MIN_VALUE : now;
        seven.shiftOrigin = player.position();
        seven.pendingSwings.clear();
        // API: MC 1.21.1 EntityGetter#getEntitiesOfClass, LivingEntity#swinging, Entity#getUUID.
        if (!follow) for (var attacker : player.serverLevel().getEntitiesOfClass(LivingEntity.class, player.getBoundingBox().inflate(6)))
            if (attacker != player && attacker.swinging) seven.pendingSwings.add(attacker.getUUID());
        var cooldowns = new java.util.HashMap<>(player.getData(ModAttachments.COOLDOWNS));
        cooldowns.put(form, now);
        player.setData(ModAttachments.COOLDOWNS, cooldowns);
        MOTIONS.put(player, new FormMotion(form, player.position(), player.position().add(direction.scale(distance)),
                0, 2, SevenStarsRules.movement(follow), SevenStarsRules.recovery(follow), false, new java.util.HashSet<>(), target));
        ChannelSafe.send(player, new TraversePayloads.SevenRoute(
                java.util.List.of(player.position(), player.position().add(direction.scale(distance))),
                SevenStarsRules.movement(follow), target == null ? -1 : target.getId(), now + 2));
        io.github.verycooltimo.murim.sect.SectBouts.onCast(player);
        sevenEvent(player, form, TechniqueEventPayload.Event.STARTED);
        return null;
    }

    static void sevenEvent(ServerPlayer player, ResourceLocation form, TechniqueEventPayload.Event event) {
        var motion = MOTIONS.get(player);
        // API: MC 1.21.1 Entity#getLookAngle; Vec3 public x/z fields.
        // Use the authoritative path so observers select the same side before movement starts.
        var look = player.getLookAngle();
        boolean rightStep = motion != null && !SevenStarsRules.follows(form) && !SevenStarsRules.dipper(form)
                && -(motion.end().x - motion.start().x) * look.z
                + (motion.end().z - motion.start().z) * look.x > 0;
        var payload = new TechniqueEventPayload(event, form, player.getId(), 0,
                MasteryService.executionTier(player, form), rightStep);
        // API: NeoForge 21.1 PacketDistributor.java:101 (tracking + self); channel guard matches send().
        if (player.connection != null && player.connection.hasChannel(payload)) PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload);
    }

    /** API: MC 1.21.1 EntityGetter#getEntitiesOfClass; AABB#expandTowards/inflate/clip.
     * Sweep bodies before Entity#move (which clips blocks, but does not clip entities).
     */
    private static Vec3 sevenImpulse(ServerPlayer player, FormMotion motion, Vec3 next) {
        Vec3 from = player.position();
        Vec3 delta = next.subtract(from);
        double length = delta.length();
        if (length < 1e-8) return Vec3.ZERO;
        Vec3 direction = delta.scale(1 / length);
        var target = motion.target();
        if (target != null && target.isAlive()) {
            Vec3 facing = target.position().subtract(from);
            player.setYRot((float) Math.toDegrees(Math.atan2(-facing.x, facing.z)));
            double toward = facing.dot(direction);
            if (SevenStarsRules.follows(motion.form()) && toward > 0) length = Math.min(length, Math.max(0, toward
                    - (target.getBbWidth() + player.getBbWidth()) / 2 - 1.1));
        }
        Vec3 eye = from.add(0, 0.9, 0);
        for (var body : player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                player.getBoundingBox().expandTowards(delta).inflate(1), e -> e != player && e.isAlive() && !e.isSpectator())) {
            var box = body.getBoundingBox().inflate(player.getBbWidth() / 2 + 0.05, 0, player.getBbWidth() / 2 + 0.05);
            if (box.contains(eye)) { length = 0; break; }
            var contact = box.clip(eye, eye.add(delta));
            if (contact.isPresent()) length = Math.min(length, Math.max(0, contact.get().distanceTo(eye) - 0.05));
        }
        if (target != null && !SevenStarsRules.follows(motion.form())) {
            double safe = 0;
            for (double probe = Math.min(.1, length); probe <= length + 1e-8; probe = Math.min(length, probe + .1)) {
                if (!safeRoute(player, java.util.List.of(from, from.add(direction.scale(probe))))) break;
                safe = probe;
                if (probe >= length) break;
            }
            length = safe;
        }
        return direction.scale(length);
    }

    /** Canon breach: three wind-up ticks and six collision-aware movement ticks. */
    private static void startDeath(ServerPlayer player, ResourceLocation form) {
        int stars = MasteryService.stars(player, form);
        if (stars < 3 || !selected(player, form) || !canSustain(player) || MOTIONS.containsKey(player)) return;
        long now = player.serverLevel().getGameTime();
        Long previous = player.getData(ModAttachments.COOLDOWNS).get(form);
        if (previous != null && now - previous < io.github.verycooltimo.murim.technique.ShadowRules.deathCooldown(stars)) return;
        double range = io.github.verycooltimo.murim.technique.ShadowRules.deathDistance(stars);
        var target = softTarget(player, range);
        Vec3 path = target != null && player.distanceTo(target) <= range && player.hasLineOfSight(target)
                && horizontal(target.position().subtract(player.position())).dot(horizontal(player.getLookAngle())) >= Math.cos(Math.toRadians(30))
                ? horizontal(target.position().subtract(player.position())) : horizontal(player.getLookAngle());
        if (target != null && (player.distanceTo(target) > range || !player.hasLineOfSight(target)
                || horizontal(target.position().subtract(player.position())).dot(horizontal(player.getLookAngle())) < Math.cos(Math.toRadians(30)))) target = null;
        double distance = target == null ? 4 : player.distanceTo(target) + 1.25;
        var wind = player.getData(ModAttachments.WIND_GOD);
        wind.chain.clear();
        if (target != null) {
            wind.chain.add(target);
            while (wind.chain.size() < io.github.verycooltimo.murim.technique.ShadowRules.chainTargets(stars)) {
                var last = wind.chain.get(wind.chain.size() - 1);
                var next = player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                        last.getBoundingBox().inflate(5), e -> e != player && e.isAlive()
                                && e instanceof net.minecraft.world.entity.monster.Enemy && !wind.chain.contains(e)
                                && e.distanceTo(last) <= 5 && last.hasLineOfSight(e))
                        .stream().min(java.util.Comparator.comparingDouble(last::distanceToSqr)).orElse(null);
                if (next == null) break;
                wind.chain.add(next);
            }
        }
        if (!pay(player, io.github.verycooltimo.murim.technique.ShadowRules.deathQi(stars)
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player))) {
            PlayerJournal.refuse(player, Component.translatable("murim.technique.no_qi"));
            return;
        }
        stop(player);
        wind.comboUntil = now + 30;
        MOTIONS.put(player, new FormMotion(form, player.position(), player.position().add(path.scale(distance)), 0, 3, 6, 0, true, new java.util.HashSet<>()));
        var cooldowns = new java.util.HashMap<>(player.getData(ModAttachments.COOLDOWNS));
        cooldowns.put(form, now);
        player.setData(ModAttachments.COOLDOWNS, cooldowns);
        io.github.verycooltimo.murim.sect.SectBouts.onCast(player);
        send(player, 11, stars, Vec3.ZERO);
    }

    private static void tickMotion(ServerPlayer player) {
        FormMotion motion = MOTIONS.get(player);
        if (motion == null) return;
        if (!selected(player, motion.form()) || !player.isAlive() || player.isPassenger()) {
            cancelMotion(player);
            return;
        }
        boolean seven = SevenStarsRules.isForm(motion.form());
        boolean ownerRoute = seven || family(TechniqueLoader.get(motion.form())) == FootworkFamily.HUASHAN;
        if (seven && (!canSustain(player) || Stun.isStunned(player))) { cancelMotion(player); return; }
        if (seven && motion.target() != null && motion.target().isAlive()) {
            Vec3 facing = motion.target().position().subtract(player.position());
            player.setYRot((float) Math.toDegrees(Math.atan2(-facing.x, facing.z)));
        }
        int age = motion.age() + 1;
        Vec3 end = motion.end();
        if (seven && (age <= motion.windup() || age > motion.windup() + motion.movement())) {
            Vec3 anchor = age <= motion.windup() ? motion.start() : end;
            player.move(net.minecraft.world.entity.MoverType.SELF,
                    sevenImpulse(player, motion, new Vec3(anchor.x, player.getY(), anchor.z)).add(0, -0.001, 0));
            player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
        }
        if (age > motion.windup() && age <= motion.windup() + motion.movement()) {
            Vec3 next = motion.route().isEmpty()
                    ? motion.start().add(motion.end().subtract(motion.start()).scale((age - motion.windup()) / (double) motion.movement()))
                    : SevenStarsRules.routePoint(motion.route(), (age - motion.windup()) / (double) motion.movement());
            Vec3 before = player.position();
            // Same collision-aware mover as combat/Stun; walls shorten each server impulse.
            Vec3 impulse = seven ? sevenImpulse(player, motion, next) : next.subtract(before);
            // API: MC 1.21.1 Entity.java:619 move(MoverType, Vec3), clips block collision shapes.
            // Tiny downward probe preserves real floor contact (Entity#move clears onGround for y=0).
            if (motion.route().isEmpty()) {
                player.move(net.minecraft.world.entity.MoverType.SELF, seven ? impulse.add(0, -0.001, 0) : impulse);
            } else {
                // Follow corners as part of the sweep, never a chord through the attacker's body.
                for (int sample = 1; sample <= 64; sample++) {
                    double fraction = (age - motion.windup() - 1 + sample / 64.0) / motion.movement();
                    Vec3 point = SevenStarsRules.routePoint(motion.route(), fraction);
                    Vec3 part = sevenImpulse(player, motion, point);
                    if (!safeRoute(player, java.util.List.of(player.position(), player.position().add(part)))) break;
                    player.move(net.minecraft.world.entity.MoverType.SELF, part.add(0, -.001 / 64, 0));
                    if (player.position().distanceTo(point) > .1) break;
                }
            }
            if (seven) {
                player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
                int stars = MasteryService.stars(player, motion.form());
                if (age == motion.windup() + 1 && !SevenStarsRules.follows(motion.form())) send(player, 17, stars, impulse);
            }
            if (motion.practice()) deathContacts(player, motion, before, player.position());
            // Unrelated God Wind forms retain their existing presentation.
            if (!ownerRoute && motion.route().isEmpty() && player.connection != null)
                player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            if (seven && age == motion.windup() + motion.movement() && !motion.route().isEmpty()) send(player, 17, MasteryService.stars(player, motion.form()), Vec3.ZERO);
            if (motion.practice() && player.position().distanceToSqr(before) > 1e-8)
                send(player, 8, MasteryService.stars(player, motion.form()), player.position().subtract(before));
            if (motion.practice() && motion.hit().isEmpty() && age == motion.windup() + motion.movement()) MasteryService.onMiss(player, motion.form());
            if (player.position().distanceTo(next) > 0.1D) {
                if (seven || !motion.route().isEmpty()) FootworkCorrections.blocked(player);
                age = motion.windup() + motion.movement();
                if (seven) end = player.position();
                if (motion.practice()) player.getData(ModAttachments.WIND_GOD).chain.clear();
            }
            if (seven && SevenStarsRules.follows(motion.form()) && age == motion.windup() + motion.movement())
                send(player, 14, MasteryService.stars(player, motion.form()), player.position());
        }
        if (seven && SevenStarsRules.follows(motion.form()) && age >= motion.windup() + motion.movement()) {
            var state = player.getData(ModAttachments.WIND_GOD);
            var sword = state.chasingSword;
            state.chasingSword = null;
            if (sword != null) {
                MOTIONS.put(player, new FormMotion(motion.form(), motion.start(), end, age, motion.windup(),
                        motion.movement(), motion.recovery(), motion.practice(), motion.hit(), motion.target(), motion.route()));
                if (TechniqueService.tryStart(player, sword)) return;
            }
        }
        if (age >= motion.windup() + motion.movement() + motion.recovery()) {
            var chain = player.getData(ModAttachments.WIND_GOD).chain;
            if (motion.practice() && !chain.isEmpty()) {
                var last = chain.remove(0);
                Vec3 facing = last.position().subtract(player.position());
                player.setYRot((float) Math.toDegrees(Math.atan2(-facing.x, facing.z)));
                if (player.connection != null) player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
                if (!chain.isEmpty() && chain.get(0).isAlive()) {
                    var next = chain.get(0);
                    Vec3 direction = horizontal(next.position().subtract(player.position()));
                    MOTIONS.put(player, new FormMotion(motion.form(), player.position(), next.position().add(direction.scale(1.25)),
                            0, 0, 6, 0, true, motion.hit()));
                    return;
                }
            }
            if (seven) {
                MasteryService.onMiss(player, motion.form());
                sevenEvent(player, motion.form(), TechniqueEventPayload.Event.FINISHED);
            }
            MOTIONS.remove(player);
            FLASH_PROTECTION.remove(player);
        }
        else MOTIONS.put(player, new FormMotion(motion.form(), motion.start(), end, age, motion.windup(), motion.movement(), motion.recovery(), motion.practice(), motion.hit(), motion.target(), motion.route()));
    }

    /** Swept capsule on the actual, block-clipped segment; each victim belongs to this dash once. */
    private static void deathContacts(ServerPlayer player, FormMotion motion, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        if (delta.lengthSqr() < 1.0E-8) return;
        var chain = player.getData(ModAttachments.WIND_GOD).chain;
        if (chain.isEmpty()) return; // A targetless charge is movement only.
        var victim = chain.get(0);
        double t = Math.max(0, Math.min(1, victim.position().subtract(from).dot(delta) / delta.lengthSqr()));
        Vec3 nearest = from.add(delta.scale(t));
        if (!victim.isAlive() || victim.position().distanceToSqr(nearest) > 1
                || motion.hit().contains(victim.getUUID()) || !player.hasLineOfSight(victim)) return;
        if (victim instanceof net.minecraft.world.entity.player.Player p && !player.canHarmPlayer(p)) return;
        motion.hit().add(victim.getUUID());
        if (victim instanceof net.minecraft.world.entity.player.Player p) p.disableShield();
        SwordGuard.breakGuard(victim, 60);
        int stars = MasteryService.stars(player, motion.form());
        float damage = (float) (player.getAttributeValue(Attributes.ATTACK_DAMAGE)
                * io.github.verycooltimo.murim.technique.ShadowRules.deathDamage(stars));
        // API: TangExecutor uses attributed indirectMagic; vanilla magic's damage type bypasses armor.
        // Apply exposure AFTER the breach so it cannot multiply its own opening strike.
        if (!SwordGuard.unguarded(victim, () -> victim.hurt(player.damageSources().indirectMagic(player, player), damage))) return;
        victim.getData(ModAttachments.WIND_GOD).exposed.put(player.getUUID(),
                new WindGodState.Exposure(player.level().getGameTime() + 60,
                        io.github.verycooltimo.murim.technique.ShadowRules.exposedBonus(stars)));
        io.github.verycooltimo.murim.technique.Casters.onHit(player, motion.form(), victim);
        send(player, 10, victim.getId(), Vec3.ZERO);
        ChannelSafe.nearEntity(victim, new TraversePayloads.Event(victim.getId(), 13, 0, 0, 0), true);
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
        if (SevenStarsRules.isForm(id)) return;
        if (!selected(player, id)) return;
        TechniqueDefinition check = TechniqueLoader.get(id);
        if (check != null && check.behavior() instanceof TechniqueBehavior.Footwork f && "death".equals(f.mode())) return;
        int executionTier = Math.max(0, MasteryService.executionTier(player, id));
        Vec3 dir = dashDirection(player);
        if (dir == null) {
            dir = horizontal(player.getLookAngle()).scale(-1.0D);
        }
        Vec3 start = player.position();
        float override = player.getData(ModAttachments.FOOTWORK_DIR)[3];
        TechniqueDefinition definition = TechniqueLoader.get(id);
        boolean huashan = family(definition) == FootworkFamily.HUASHAN;
        boolean death = huashan && override > 0.0F;
        double distance = death ? override : FootworkFamily.windEvadeDistance(executionTier);
        if (!huashan && override < 0.0F) {
            var target = softTarget(player, distance + 2.0D);
            // softTarget also accepts a lock outside the cone; forward Flash must not follow it.
            // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/entity/Entity.java
            // #position, #getBbWidth, #setYRot; LivingEntity.java#hasLineOfSight.
            if (target instanceof net.minecraft.world.entity.monster.Enemy && player.hasLineOfSight(target)) {
                Vec3 to = target.position().subtract(start);
                Vec3 toward = horizontal(to);
                if (toward.dot(dir) >= Math.cos(Math.toRadians(30.0D))) {
                    dir = toward;
                    double half = (target.getBbWidth() + player.getBbWidth()) / 2.0D;
                    distance = Math.max(0.0D, Math.sqrt(to.x * to.x + to.z * to.z) - half - 0.9D);
                    player.setYRot((float) Math.toDegrees(Math.atan2(-dir.x, dir.z)));
                }
            }
        }
        player.setData(ModAttachments.FOOTWORK_DIR, new float[] {0, 0, 0, 0});
        Vec3 from = start.add(0.0D, 0.9D, 0.0D);
        net.minecraft.world.phys.BlockHitResult wall = player.level().clip(new net.minecraft.world.level.ClipContext(
                from, from.add(dir.scale(distance)), net.minecraft.world.level.ClipContext.Block.COLLIDER,
                net.minecraft.world.level.ClipContext.Fluid.NONE, player));
        double reach = wall.getType() == net.minecraft.world.phys.HitResult.Type.MISS
                ? distance : Math.max(0.0D, wall.getLocation().distanceTo(from) - 0.5D);
        // Every movement impulse rechecks the server selection, including Lightning Flash.
        if (death) {
            var target = softTarget(player);
            Vec3 end = start.add(dir.scale(reach));
            var route = new java.util.ArrayList<Vec3>();
            Vec3 centre = target == null ? start.lerp(end, .5) : target.position();
            Vec3 initial = start.subtract(centre), finish = end.subtract(centre);
            double a0 = Math.atan2(initial.z, initial.x), a1 = Math.atan2(finish.z, finish.x);
            double turn = Math.atan2(Math.sin(a1-a0), Math.cos(a1-a0));
            double r0 = Math.hypot(initial.x, initial.z), r1 = Math.hypot(finish.x, finish.z);
            route.add(start);
            for (int i = 1; i <= 24; i++) {
                double u = i / 24.0, angle = a0 + turn * u, radius = r0 + (r1-r0)*u;
                Vec3 point = new Vec3(centre.x + Math.cos(angle)*radius, start.y,
                        centre.z + Math.sin(angle)*radius);
                if (!safeRoute(player, java.util.List.of(route.getLast(), point))) break;
                route.add(point);
            }
            if (route.size() < 2) route.add(start);
            MOTIONS.put(player, new FormMotion(id, start, route.getLast(), 0, 0, 6, 0, false,
                    new java.util.HashSet<>(), target, route));
            var approved = new TraversePayloads.SevenRoute(route, 6, target == null ? -1 : target.getId(),
                    player.level().getGameTime(), player.getId(), executionTier);
            if (player.connection != null && player.connection.hasChannel(approved))
                PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, approved);
        } else {
            MOTIONS.put(player, new FormMotion(id, start, start.add(dir.scale(reach)), 0, 0, 5, 0, false, new java.util.HashSet<>()));
            if (huashan) ChannelSafe.send(player, new TraversePayloads.SevenRoute(java.util.List.of(start, start.add(dir.scale(reach))),
                    5, -1, player.level().getGameTime()));
        }
        // Смерть — проход для атаки, без окна уклонения; Миг — уклонение.
        int iframes = death ? 0 : FootworkFamily.windEvadeInvulnerable(executionTier);
        if (iframes > player.invulnerableTime) {
            FLASH_PROTECTION.put(player, player.invulnerableTime);
            player.invulnerableTime = Math.max(player.invulnerableTime, iframes);
        }
        // TechniqueService records God Wind practice once after the impact.
        if (huashan) {
            MasteryService.onMiss(player, id);
        }
        // Хуашань (Аромат за спиной) — свой вид события: у формы своё зрелище, не белая линия Смерти.
        if (!death) send(player, huashan ? 9 : 5, executionTier, dir.scale(reach));
    }

    /**
     * Рывок шага: не телепорт, а движение самого игрока за {@code ticks} тиков — клиент проходит
     * путь своей физикой (коллизии, камера), сервер лишь заранее обрезал путь первой стеной.
     */
    public static void sendDash(ServerPlayer player, Vec3 dir, double reach, int ticks) {
        if (reach < 0.05D) {
            return;
        }
        // ChannelSafe checks the negotiated channel, including channel-less GameTest connections.
        io.github.verycooltimo.murim.network.ChannelSafe.send(player,
                new TraversePayloads.Dash((float) dir.x, (float) dir.y, (float) dir.z, (float) reach, ticks));
    }

    /**
     * Мягкое выделение цели для Шага Смерти: ближайший враждебный в 8 блоках, не дальше 30° от
     * взгляда и в прямой видимости. Мирное животное целью не считается.
     */
    private static net.minecraft.world.entity.LivingEntity softTarget(ServerPlayer player) {
        return softTarget(player, 8);
    }

    static net.minecraft.world.entity.LivingEntity softTarget(ServerPlayer player, double range) {
        net.minecraft.world.entity.LivingEntity locked = TargetLock.locked(player, range);
        if (locked != null) {
            return locked;
        }
        Vec3 look = horizontal(player.getLookAngle());
        double cos = Math.cos(Math.toRadians(30.0D));
        net.minecraft.world.entity.LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.LivingEntity e : player.level().getEntitiesOfClass(
                net.minecraft.world.entity.LivingEntity.class, player.getBoundingBox().inflate(range),
                e -> e != player && e.isAlive() && e instanceof net.minecraft.world.entity.monster.Enemy)) {
            Vec3 to = e.position().subtract(player.position());
            double d = to.length();
            if (d > range || horizontal(to).dot(look) < cos || !player.hasLineOfSight(e)) {
                continue;
            }
            if (d < bestDist) {
                bestDist = d;
                best = e;
            }
        }
        return best;
    }

    /** Fast, unseen movement; entry discards pursuit and synchronizes the silhouette. */
    private static void startShadow(ServerPlayer player, ResourceLocation technique, int executionTier) {
        if (executionTier < 1) {
            locked(player, "shadow", FootworkFamily.WIND_GOD);
            return;
        }
        if (!selected(player, technique) || !canSustain(player)) {
            return;
        }
        long now = player.serverLevel().getGameTime();
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (now < (long) s[SHADOW_READY]) {
            return;
        }
        if (isRunning(player)) {
            stop(player);
        }
        if (!pay(player, io.github.verycooltimo.murim.technique.ShadowRules.entryQi(executionTier)
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player))) {
            PlayerJournal.refuse(player, Component.translatable("murim.technique.no_qi"));
            return;
        }
        int[] n = player.getData(ModAttachments.TRAVERSE).clone();
        n[ACTIVE] = SHADOW;
        n[TIER] = executionTier;
        n[FAMILY] = FootworkFamily.WIND_GOD.ordinal();
        n[RUN_LEFT] = io.github.verycooltimo.murim.technique.ShadowRules.duration(executionTier);
        n[PAID] = 0;
        n[NO_SPRINT] = 4;
        player.setData(ModAttachments.TRAVERSE, n);
        player.setData(ModAttachments.FOOTWORK_TECH, technique.toString());
        io.github.verycooltimo.murim.sect.SectBouts.onCast(player);
        modify(player, Attributes.MOVEMENT_SPEED, SHADOW_SPEED,
                io.github.verycooltimo.murim.technique.ShadowRules.speedBonus(executionTier), AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        var wind = player.getData(ModAttachments.WIND_GOD);
        wind.comboUntil = Long.MIN_VALUE;
        wind.clones.forEach(io.github.verycooltimo.murim.entity.ShadowClone::dissolve);
        wind.clones.clear();
        var enemies = new java.util.ArrayList<net.minecraft.world.entity.Mob>();
        for (var entity : player.serverLevel().getAllEntities()) {
            if (entity instanceof net.minecraft.world.entity.Mob mob && mob.isAlive()
                    && (mob.getTarget() == player || mob instanceof net.minecraft.world.entity.monster.Enemy && mob.distanceTo(player) <= 8)) enemies.add(mob);
        }
        enemies.sort(java.util.Comparator.comparingDouble(player::distanceToSqr));
        if (!enemies.isEmpty()) {
            for (int i = 0; i < io.github.verycooltimo.murim.technique.ShadowRules.clones(executionTier); i++) {
                var clone = io.github.verycooltimo.murim.registry.ModEntities.SHADOW_CLONE.get().create(player.level());
                if (clone == null) continue;
                clone.moveTo(player.getX(), player.getY(), player.getZ(), player.getYRot(), 0);
                clone.begin(player, enemies.get(i % enemies.size()), executionTier);
                player.serverLevel().addFreshEntity(clone);
                wind.clones.add(clone);
            }
            int index = 0;
            for (var mob : enemies) if (mob.getTarget() == player)
                mob.setTarget(wind.clones.isEmpty() ? null : wind.clones.get(index++ % wind.clones.size()));
        }
        send(player, 6, executionTier, Vec3.ZERO);
    }

    @SubscribeEvent
    static void onTarget(net.neoforged.neoforge.event.entity.living.LivingChangeTargetEvent event) {
        if (event.getNewAboutToBeSetTarget() instanceof ServerPlayer player && inShadow(player)) {
            var clones = player.getData(ModAttachments.WIND_GOD).clones;
            clones.removeIf(c -> !c.belongsTo(player));
            // API: NeoForge 1.21.1 LivingChangeTargetEvent#setNewAboutToBeSetTarget.
            event.setNewAboutToBeSetTarget(clones.stream().min(
                    java.util.Comparator.comparingDouble(event.getEntity()::distanceToSqr)).orElse(null));
        }
    }

    private static boolean overwhelmed(ServerPlayer player, int stars) {
        return player.level().getEntitiesOfClass(net.minecraft.world.entity.LivingEntity.class,
                player.getBoundingBox().inflate(io.github.verycooltimo.murim.technique.ShadowRules.OBSERVER_RANGE),
                e -> e != player && e.isAlive() && e instanceof net.minecraft.world.entity.monster.Enemy && e.distanceTo(player) <= io.github.verycooltimo.murim.technique.ShadowRules.OBSERVER_RANGE
                        && e.hasLineOfSight(player) && horizontal(e.getLookAngle()).dot(horizontal(player.position().subtract(e.position()))) >= 0.5)
                .size() > io.github.verycooltimo.murim.technique.ShadowRules.observers(stars);
    }

    /** Атака снимает и бег, и Тень — до самого удара. */
    @SubscribeEvent
    static void onAttack(net.neoforged.neoforge.event.entity.player.AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) FragranceAirService.attacking(player);
        if (event.getEntity() instanceof ServerPlayer player && player.getData(ModAttachments.TRAVERSE)[ACTIVE] != 0) {
            if (inShadow(player) && event.getTarget() instanceof net.minecraft.world.entity.LivingEntity victim
                    && victim instanceof net.minecraft.world.entity.Mob mob
                    && mob.getTarget() instanceof io.github.verycooltimo.murim.entity.ShadowClone clone && clone.belongsTo(player)) {
                var wind = player.getData(ModAttachments.WIND_GOD);
                wind.shadowVictim = victim.getId();
                wind.shadowStrikeAt = player.level().getGameTime();
            }
            stop(player);
        }
    }

    /** Accepted casts consume the opener, refused casts leave it available. */
    public static int consumeCombo(ServerPlayer player, TechniqueDefinition technique) {
        var wind = player.getData(ModAttachments.WIND_GOD);
        if (player.level().getGameTime() >= wind.comboUntil) return 0;
        wind.comboUntil = Long.MIN_VALUE;
        return technique.startTickOf(TechniquePhase.IMPACT);
    }

    @SubscribeEvent
    static void exposedDamage(LivingIncomingDamageEvent event) {
        if (event.getEntity().level().isClientSide || !(event.getSource().getEntity() instanceof ServerPlayer player)) return;
        var victim = event.getEntity();
        long now = victim.level().getGameTime();
        var wind = victim.hasData(ModAttachments.WIND_GOD) ? victim.getData(ModAttachments.WIND_GOD) : new WindGodState();
        wind.exposed.values().removeIf(exposure -> exposure.until() <= now);
        var exposure = wind.exposed.get(player.getUUID());
        if (exposure != null) event.setAmount((float) (event.getAmount() * (1 + exposure.bonus())));
        var attack = player.getData(ModAttachments.WIND_GOD);
        if (attack.shadowVictim == victim.getId() && attack.shadowStrikeAt == now) {
            attack.shadowVictim = -1;
            event.setAmount(event.getAmount() * 1.5F);
        }
    }

    // API: NeoForge 1.21.1 LivingShieldBlockEvent#setBlocked (verified upstream source).
    @SubscribeEvent
    static void exposedShield(net.neoforged.neoforge.event.entity.living.LivingShieldBlockEvent event) {
        if (!event.getEntity().level().isClientSide && exposed(event.getEntity())) event.setBlocked(false);
    }

    public static boolean exposed(net.minecraft.world.entity.LivingEntity entity) {
        long now = entity.level().getGameTime();
        return entity.hasData(ModAttachments.WIND_GOD) && entity.getData(ModAttachments.WIND_GOD).exposed.values().stream().anyMatch(e -> e.until() > now);
    }

    /** Включить бег. Оплата — потиково, первый тик проверяется до выдачи бонусов. */
    public static void startRun(ServerPlayer player, ResourceLocation technique, FootworkFamily family, int executionTier) {
        TechniqueDefinition definition = TechniqueLoader.get(technique);
        if (definition == null || !(definition.behavior() instanceof TechniqueBehavior.Footwork form)
                || !"run".equals(form.mode()) || !MasteryService.knows(player, technique)) return;
        if (!selected(player, technique) || !family.canRun(executionTier) || !canSustain(player)
                || player.serverLevel().getGameTime() < player.getData(ModAttachments.FOOTWORK_RUN_READY)[family.ordinal()]) {
            return;
        }
        int tier = family.tier(executionTier);
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (!QiRules.has(player, profile, runQiPerTick(player, family, executionTier))) {
            PlayerJournal.refuse(player, Component.translatable("murim.technique.no_qi"));
            return;
        }
        player.getData(ModAttachments.WIND_GOD).comboUntil = Long.MIN_VALUE;
        int[] s = player.getData(ModAttachments.TRAVERSE).clone();
        s[ACTIVE] = 1;
        s[TIER] = executionTier;
        s[FAMILY] = family.ordinal();
        // Tempered body runs longer: +3 % per body level (docs/design/27-body-training.md §3).
        s[RUN_LEFT] = (int) Math.round(TraverseRules.maxTicks(family, executionTier)
                * io.github.verycooltimo.murim.training.TrainingService.footworkStamina(player));
        s[NO_SPRINT] = 0;
        s[PAID] = 0;
        player.setData(ModAttachments.TRAVERSE, s);
        player.setData(ModAttachments.FOOTWORK_TECH, technique.toString());
        // A bout with a condition ("only the Six Harmonies") counts footwork as a cast.
        io.github.verycooltimo.murim.sect.SectBouts.onCast(player);
        // Тропа аромата быстрее таблицы на 40 % (автор 03.10: «быстрее бег»); Молния разгоняется в onTick.
        modify(player, Attributes.MOVEMENT_SPEED, SPEED,
                TraverseRules.acceleratedSpeed(family, executionTier, 0) / TraverseRules.VANILLA_SPRINT - 1.0D,
                AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
        double step = TraverseRules.stepHeight(tier) - 0.6D;
        if (step > 0.0D) {
            modify(player, Attributes.STEP_HEIGHT, STEP, step, AttributeModifier.Operation.ADD_VALUE);
        }
        modify(player, Attributes.SAFE_FALL_DISTANCE, FALL, TraverseRules.safeFall(tier) - 3.0D, AttributeModifier.Operation.ADD_VALUE);
        send(player, 1, executionTier, new Vec3(family.ordinal(), 0.0D, 0.0D));
    }

    public static void stop(ServerPlayer player) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == 0) {
            return;
        }
        int[] n = s.clone();
        boolean wasShadow = n[ACTIVE] == SHADOW || n[ACTIVE] == SHADOW_ENTRY;
        n[ACTIVE] = 0;
        if (!wasShadow) {
            long[] ready = player.getData(ModAttachments.FOOTWORK_RUN_READY).clone();
            FootworkFamily family = FootworkFamily.values()[n[FAMILY]];
            ready[family.ordinal()] = player.serverLevel().getGameTime() + TraverseRules.cooldown(family, n[TIER]);
            player.setData(ModAttachments.FOOTWORK_RUN_READY, ready);
        }
        if (wasShadow) {
            n[SHADOW_READY] = (int) (player.serverLevel().getGameTime()
                    + io.github.verycooltimo.murim.technique.ShadowRules.cooldown(n[TIER]));
        }
        player.setData(ModAttachments.TRAVERSE, n);
        remove(player, Attributes.MOVEMENT_SPEED, SPEED);
        remove(player, Attributes.MOVEMENT_SPEED, SHADOW_SPEED);
        remove(player, Attributes.STEP_HEIGHT, STEP);
        remove(player, Attributes.SAFE_FALL_DISTANCE, FALL);
        send(player, wasShadow ? 7 : 0, wasShadow ? MasteryService.executionTier(player, runningTechnique(player)) : n[TIER], Vec3.ZERO);
        player.setData(ModAttachments.FOOTWORK_TECH, "");
    }

    /** Полная очистка: смерть, выход, смена мира — снимает и бонус падения. */
    public static void cancel(ServerPlayer player) {
        hardCleanup(player);
        var state = player.getData(ModAttachments.TECHNIQUE_STATE);
        if (state.isActive() && family(TechniqueLoader.get(state.techniqueId())) != null) TechniqueService.cancel(player);
    }

    private static void hardCleanup(ServerPlayer player) {
        FragranceAirService.cleanup(player);
        cancelMotion(player);
        stop(player);
        var wind = player.getData(ModAttachments.WIND_GOD);
        wind.clones.forEach(io.github.verycooltimo.murim.entity.ShadowClone::dissolve);
        wind.clones.clear(); wind.chain.clear(); wind.comboUntil = Long.MIN_VALUE; wind.shadowVictim = -1;
        wind.clearSeven();
        remove(player, Attributes.MOVEMENT_SPEED, SHADOW_SPEED);
        remove(player, Attributes.SAFE_FALL_DISTANCE, FALL);
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            tick(player);
        }
    }

    /** Same authoritative tick used by the event and GameTests. */
    static void tick(ServerPlayer player) {
        selectionChanged(player);
        FragranceAirService.tick(player);
        DipperService.tick(player);
        tickMotion(player);
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
        if (n[ACTIVE] == SHADOW_ENTRY) {
            shadowTick(player, n, now);
            if (player.getData(ModAttachments.TRAVERSE)[ACTIVE] == 0) return;
            if (--n[NO_SPRINT] <= 0) {
                n[ACTIVE] = SHADOW;
                send(player, 6, MasteryService.executionTier(player, runningTechnique(player)), Vec3.ZERO);
            }
            player.setData(ModAttachments.TRAVERSE, n);
            return;
        }
        if (n[ACTIVE] == SHADOW || n[ACTIVE] == SHADOW_ENTRY) {
            shadowTick(player, n, now);
            return;
        }
        try {
            int[] r = n.clone();
            FootworkFamily family = FootworkFamily.values()[r[FAMILY]];
            double cost = runQiPerTick(player, family, r[TIER]);
            DantianProfile profile = player.getData(ModAttachments.PROFILE);
            if (!player.isSprinting() && player.onGround()) {
                r[NO_SPRINT]++;
            } else {
                r[NO_SPRINT] = 0;
            }
            if (!selectedRun(player) || r[RUN_LEFT] <= 0 || r[NO_SPRINT] > GRACE || !QiRules.has(player, profile, cost) || player.isPassenger()
                    || player.isFallFlying() || player.isInWater() || !player.isAlive() || player.isShiftKeyDown()) {
                player.setData(ModAttachments.TRAVERSE, r);
                stop(player);
                ProfileNetwork.sync(player);
                return;
            }
            player.setData(ModAttachments.PROFILE, QiRules.charge(player, profile, cost));
            r[RUN_LEFT]--;
            r[PAID]++;
            if (r[FAMILY] == FootworkFamily.WIND_GOD.ordinal() && r[PAID] <= WIND_RAMP) {
                // Шаг Молнии разгоняется за секунду до ×1,6 крейсерской (автор 03.10: «нужно быстрее»).
                modify(player, Attributes.MOVEMENT_SPEED, SPEED,
                        TraverseRules.acceleratedSpeed(family, r[TIER], r[PAID]) / TraverseRules.VANILLA_SPRINT - 1.0D,
                        AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL);
            }
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

    /** Movement and jumps are safe; too many facing observers break concealment. */
    private static void shadowTick(ServerPlayer player, int[] n, long now) {
        int[] r = n.clone();
        double cost = io.github.verycooltimo.murim.technique.ShadowRules.qiPerSecond(r[TIER])
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player) / 20.0D;
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (!selected(player, runningTechnique(player)) || r[RUN_LEFT] <= 0
                || !QiRules.has(player, profile, cost) || overwhelmed(player, r[TIER]) || !player.isAlive() || player.isPassenger()) {
            player.setData(ModAttachments.TRAVERSE, r);
            stop(player);
            ProfileNetwork.sync(player);
            return;
        }
        if (r[ACTIVE] == SHADOW_ENTRY) return;
        player.setData(ModAttachments.PROFILE, QiRules.charge(player, profile, cost));
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
        if (io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(player)) return;
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[ACTIVE] == SHADOW || s[ACTIVE] == SHADOW_ENTRY) {
            return;
        }
        if (s[ACTIVE] != RUN || !selectedRun(player)) {
            selectionChanged(player);
            return;
        }
        FootworkFamily family = FootworkFamily.values()[s[FAMILY]];
        int executionTier = s[TIER];
        int tier = family.tier(executionTier);
        Vec3 look = horizontal(player.getLookAngle());
        long now = player.serverLevel().getGameTime();
        // Клиент шлёт прыжок в момент отрыва: сервер мог ещё не увидеть землю — верим опоре
        // не старше трёх тиков, а не вертикальной скорости (после стены она тоже положительна).
        boolean grounded = player.onGround() || clientGrounded && now - s[LAST_GROUND] <= 3;
        int[] n = s.clone();
        if (grounded) {
            if (!family.canLeap(executionTier) || now - (long) n[LAST_LEAP] < TraverseRules.LEAP_COOLDOWN
                    || !pay(player, extraQi(player, family, executionTier, TraverseRules.LEAP_QI))) {
                return;
            }
            n[LAST_LEAP] = (int) now;
            player.setDeltaMovement(look.scale(TraverseRules.leapHorizontal(Math.max(1, tier)))
                    .add(0.0D, TraverseRules.leapVertical(Math.max(1, tier)), 0.0D));
            player.hurtMarked = true;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 2, executionTier, look);
            return;
        }
        Vec3 wall = wallNormal(player);
        if (wall != null && n[KICKS_USED] < family.wallKicks(executionTier)) {
            if (!pay(player, extraQi(player, family, executionTier, TraverseRules.WALL_KICK_QI))) {
                return;
            }
            n[KICKS_USED]++;
            Vec3 along = look.subtract(wall.scale(look.dot(wall)));
            Vec3 v = wall.scale(TraverseRules.WALL_KICK_OUT).add(along.scale(0.25D)).add(0.0D, TraverseRules.WALL_KICK_UP, 0.0D);
            player.setDeltaMovement(v);
            player.hurtMarked = true;
            player.setData(ModAttachments.TRAVERSE, n);
            send(player, 3, executionTier, wall);
        }
    }

    /** Поворот в воздухе по R: одна горизонтальная смена направления за отрыв, высота сохраняется. */
    private static void airTurn(ServerPlayer player, FootworkFamily family, int executionTier, Vec3 dir) {
        int[] s = player.getData(ModAttachments.TRAVERSE);
        if (s[AIR_USED] != 0) {
            return;
        }
        if (!pay(player, extraQi(player, family, executionTier, TraverseRules.AIR_CORRECTION_QI))) {
            PlayerJournal.refuse(player, Component.translatable("murim.technique.no_qi"));
            return;
        }
        int[] n = s.clone();
        n[AIR_USED] = 1;
        Vec3 d = player.getDeltaMovement();
        player.setDeltaMovement(dir.scale(TraverseRules.AIR_CORRECTION_SPEED).add(0.0D, d.y, 0.0D));
        player.hurtMarked = true;
        player.setData(ModAttachments.TRAVERSE, n);
        send(player, 4, executionTier, dir);
    }

    private static void locked(ServerPlayer player, String form, FootworkFamily family) {
        PlayerJournal.refuse(player, Component.translatable("murim.footwork.locked",
                Component.translatable("murim.footwork." + form)));
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

    /** Sustained prices already include mastery in their tables; only pills multiply them. */
    public static double runQiPerTick(ServerPlayer player, FootworkFamily family, int executionTier) {
        return TraverseRules.qiPerSecond(family, executionTier)
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player) / 20.0D;
    }

    private static double extraQi(ServerPlayer player, FootworkFamily family, int executionTier, double base) {
        ResourceLocation form = ResourceLocation.fromNamespaceAndPath("murim", switch (family) {
            case HUASHAN -> "dark_fragrance_step";
            case WIND_GOD -> "wind_god_steps";
        });
        var progress = player.getData(ModAttachments.MASTERY).progress(form);
        double mastery = progress == null
                ? io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(executionTier, family.executionSteps())
                : io.github.verycooltimo.murim.mastery.MasteryRules.costFactor(progress.stars(), io.github.verycooltimo.murim.mastery.Arts.cap(form));
        return base * mastery
                * io.github.verycooltimo.murim.cultivation.PillService.costFactor(player);
    }

    private static boolean canSustainBody(ServerPlayer player) {
        return player.isAlive() && !player.isRemoved() && !player.isSpectator()
                && !io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.pinned(player)
                && player.getData(ModAttachments.PROFILE).isAwakened()
                && AuraService.pressure(player) < AuraPressure.TECHNIQUE_LOCK;
    }
    static boolean canSustain(ServerPlayer player) {
        return canSustainBody(player) && !player.getData(ModAttachments.TECHNIQUE_STATE).isActive();
    }

    static boolean pay(ServerPlayer player, double cost) {
        DantianProfile profile = player.getData(ModAttachments.PROFILE);
        if (!QiRules.has(player, profile, cost)) {
            return false;
        }
        if (!QiRules.free(player)) {
            player.setData(ModAttachments.PROFILE, profile.withCirculating(profile.circulating() - cost));
            ProfileNetwork.sync(player);
        }
        return true;
    }

    /** API: NeoForge 21.1 LivingIncomingDamageEvent (cancellable); MC DamageSource#is/getDirectEntity.
     * Snapshotting swings at push-off prevents a later attack from becoming a free dodge. */
    static boolean timedDodge(LivingIncomingDamageEvent event) {
        if (event.isCanceled() || !(event.getEntity() instanceof ServerPlayer player) || !player.isAlive() || Stun.isStunned(player)) return false;
        var seven = player.getData(ModAttachments.WIND_GOD);
        long now = player.serverLevel().getGameTime();
        FormMotion motion = MOTIONS.get(player);
        if (motion == null || !SevenStarsRules.isForm(motion.form()) || SevenStarsRules.follows(motion.form()) || SevenStarsRules.dipper(motion.form())
                || !(event.getSource().is(net.minecraft.world.damagesource.DamageTypes.MOB_ATTACK)
                || event.getSource().is(net.minecraft.world.damagesource.DamageTypes.PLAYER_ATTACK))
                || !(event.getSource().getDirectEntity() instanceof LivingEntity attacker)) return false;
        boolean timed = SevenStarsRules.within(now, seven.shiftStart, 6)
                && seven.pendingSwings.remove(attacker.getUUID());
        seven.shiftStart = Long.MIN_VALUE;
        seven.pendingSwings.clear();
        if (!timed) {
            seven.opening = Long.MIN_VALUE;
            cancelMotion(player);
            player.setDeltaMovement(0, player.getDeltaMovement().y, 0);
            if (player.connection != null) player.connection.teleport(player.getX(), player.getY(), player.getZ(), player.getYRot(), player.getXRot());
            return true;
        }
        seven.opening = now;
        event.setCanceled(true);
        Stun.apply(attacker, SevenStarsRules.stunTicks(MasteryService.stars(player, motion.form())));
        var route = dodgeRoute(player, attacker, motion.end().subtract(motion.start()));
        if (!route.isEmpty()) {
            MOTIONS.put(player, new FormMotion(motion.form(), player.position(), route.getLast(),
                    0, 0, SevenStarsRules.movement(false), motion.recovery(), false, motion.hit(), attacker, route));
            ChannelSafe.send(player, new TraversePayloads.SevenRoute(route, SevenStarsRules.movement(false), attacker.getId(), now));
        } else {
            // Keep the plain step's destination/timing, with the same safety checks and facing.
            MOTIONS.put(player, new FormMotion(motion.form(), motion.start(), motion.end(), motion.age(),
                    motion.windup(), motion.movement(), motion.recovery(), motion.practice(), motion.hit(), attacker));
        }
        send(player, 16, 0, seven.shiftOrigin.subtract(player.position()));
        return true;
    }

    /** API verified in /tmp/mr-ref-1121/minecraft-src: CollisionGetter#noCollision(Entity,AABB)/noBlockCollision,
     * LevelReader#containsAnyLiquid, AABB#move/deflate, Vec3#lerp/distanceTo.
     * Reuses the body sweep in sevenImpulse and the supported-box check used by Falling Petal.
     * Every segment is sampled, so a clear endpoint cannot permit crossing a wall or a deep ledge. */
    static java.util.List<Vec3> dodgeRoute(ServerPlayer player, LivingEntity attacker, Vec3 step) {
        Vec3 forward = horizontal(attacker.getLookAngle());
        Vec3 left = new Vec3(forward.z, 0, -forward.x);
        Vec3 preferred = left.scale(step.dot(left) >= 0 ? 1 : -1);
        Vec3 centre = new Vec3(attacker.getX(), player.getY(), attacker.getZ());
        var routes = new java.util.ArrayList<java.util.List<Vec3>>();
        routes.add(java.util.List.of(player.position(), centre.add(preferred.scale(1.2))));
        routes.add(java.util.List.of(player.position(), centre.add(preferred.scale(-1.2))));
        for (int side : new int[] {1, -1}) {
            Vec3 flank = preferred.scale(side * 2.2);
            // Leave body clearance around side-wall corners on the final diagonal.
            // At depth 1.8 the centre clears, but the player's box still clips the wall.
            routes.add(java.util.List.of(player.position(), centre.add(flank).add(forward.scale(1.2)),
                    centre.add(flank).subtract(forward.scale(2.4)), centre.subtract(forward.scale(1.2))));
        }
        int choice = SevenStarsRules.firstFree(routes.stream().map(r -> safeRoute(player, r)).toList());
        return choice < 0 ? java.util.List.of() : routes.get(choice);
    }

    static boolean safeRoute(ServerPlayer player, java.util.List<Vec3> route) {
        for (int i = 1; i < route.size(); i++) {
            Vec3 from = route.get(i - 1), to = route.get(i);
            int samples = Math.max(1, (int) Math.ceil(from.distanceTo(to) / .1));
            for (int n = 1; n <= samples; n++) {
                Vec3 at = from.lerp(to, n / (double) samples);
                var box = player.getBoundingBox().move(at.subtract(player.position())).deflate(.001);
                var support = new net.minecraft.world.phys.AABB(box.minX, box.minY - 1.01, box.minZ,
                        box.maxX, box.minY, box.maxZ);
                if (!player.level().noCollision(player, box) || player.level().containsAnyLiquid(box)
                        || player.level().containsAnyLiquid(support)
                        || player.level().noBlockCollision(player, support)) return false;
            }
        }
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
    static void tracking(PlayerEvent.StartTracking event) {
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof ServerPlayer subject && inShadow(subject))
            ChannelSafe.send(viewer, new TraversePayloads.Event(subject.getId(), 6, subject.getData(ModAttachments.TRAVERSE)[TIER], 0, 0));
        if (event.getEntity() instanceof ServerPlayer viewer && event.getTarget() instanceof net.minecraft.world.entity.LivingEntity victim && exposed(victim)) {
            long until = victim.getData(ModAttachments.WIND_GOD).exposed.values().stream().mapToLong(WindGodState.Exposure::until).max().orElse(0);
            ChannelSafe.send(viewer, new TraversePayloads.Event(victim.getId(), 13, (int) (until - victim.level().getGameTime()), 0, 0));
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

    static void send(ServerPlayer player, int kind, int executionTier, Vec3 dir) {
        TraversePayloads.Event payload = new TraversePayloads.Event(player.getId(), kind, executionTier, (float) dir.x, (float) dir.z);
        // As with mastery sync, mock GameTest connections have no mod channel.
        if (player.connection != null && player.connection.hasChannel(payload)) {
            PacketDistributor.sendToPlayersTrackingEntityAndSelf(player, payload);
        }
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    private FootworkService() {
    }
}
