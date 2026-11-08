package io.github.verycooltimo.murim.entity;

import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.DifficultyInstance;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.SpawnGroupData;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Бандит-мечник: дао, три простых удара с читаемым замахом ({@link BanditMove}), откат
 * после удара, «оступился» от сильного попадания в замах. Между ударами продолжает сближение
 * и прикрывается мечом (variant A). Каждый пятый — элитный: знает рывок-разрез ци с голубым следом.
 */
public class BanditSwordsman extends Bandit {

    /** Перезарядка обычного удара после отката, тиков (сервер). */
    private int attackCooldown = 20;
    private int dashCooldown = 40;
    private int strafeDir = 1;
    private int strafeTicks;
    private boolean dashHit;
    private Vec3 dashDir = Vec3.ZERO;
    private BanditMove planned;
    /** Тиков уклонения вбок (читает замах техники игрока), сервер. */
    private int dodgeTicks;
    private int dodgeDir = 1;
    private int guardRecovery = 4;
    private long noticedPress = Long.MIN_VALUE;
    private long observeAt = Long.MAX_VALUE;
    private long observedOffset = Long.MIN_VALUE;
    private long nextOffset;
    private int repeatedRhythm;

    /** Server-only observation, delayed 4–6 ticks: no reading the player's future input. */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) return;
        var guard = io.github.verycooltimo.murim.combat.SwordGuard.state(this);
        LivingEntity target = getTarget();
        if (target instanceof Player player) {
            var seen = io.github.verycooltimo.murim.combat.SwordGuard.state(player);
            if (seen.press >= 0 && seen.press != noticedPress && observeAt == Long.MAX_VALUE) {
                noticedPress = seen.press;
                nextOffset = seen.press - seen.swing;
                observeAt = level().getGameTime() + 4 + random.nextInt(3);
            }
            if (level().getGameTime() >= observeAt) {
                repeatedRhythm = observedOffset != Long.MIN_VALUE && Math.abs(nextOffset - observedOffset) <= 1
                        ? repeatedRhythm + 1 : 0;
                observedOffset = nextOffset;
                observeAt = Long.MAX_VALUE;
            }
        } else {
            repeatedRhythm = 0;
        }
        boolean defend = target != null && target.isAlive() && (state() == IDLE || state() == RECOVER)
                && io.github.verycooltimo.murim.world.camp.CampFight.mayFight(this);
        if (defend && !guard.held && state() == RECOVER && random.nextFloat() >= 0.35F)
            guard.spent = true; // Usually ordinary block; sometimes a fresh post-swing deflection.
        io.github.verycooltimo.murim.combat.SwordGuard.hold(this, defend);
        io.github.verycooltimo.murim.combat.SwordGuard.sync(this);
    }

    /** Normal contacts repeat in 10–12 ticks; qi dash keeps its established timing. */
    public int duelPhaseTicks(int phase) {
        if (move() == BanditMove.QI_DASH) return switch (phase) {
            case WINDUP -> move().windup();
            case STRIKE -> move().strike();
            default -> move().recover();
        };
        return switch (phase) {
            case WINDUP -> 6;
            case STRIKE -> 2;
            default -> guardRecovery;
        };
    }

    public BanditSwordsman(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder attributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 24.0D)
                .add(Attributes.MOVEMENT_SPEED, 0.30D)
                .add(Attributes.FOLLOW_RANGE, 24.0D)
                .add(Attributes.ARMOR, 2.0D)
                .add(Attributes.ATTACK_DAMAGE, BanditMove.CHOP.damage());
    }

    @Override
    protected void registerGoals() {
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(2, new DuelGoal());
        // Лагерь: в бою двое, остальные ждут кольцом (docs/design/24-bandit-camp.md §3).
        goalSelector.addGoal(3, new CampWaitGoal(this));
        goalSelector.addGoal(5, new CampIdleGoal(this));
        goalSelector.addGoal(6, new WaterAvoidingRandomStrollGoal(this, 0.8D));
        goalSelector.addGoal(7, new LookAtPlayerGoal(this, Player.class, 8.0F));
        goalSelector.addGoal(8, new RandomLookAroundGoal(this));
        targetSelector.addGoal(1, new HurtByTargetGoal(this));
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true));
    }

    @Override
    public SpawnGroupData finalizeSpawn(ServerLevelAccessor level, DifficultyInstance difficulty, MobSpawnType spawnType,
                                        SpawnGroupData data) {
        SpawnGroupData out = super.finalizeSpawn(level, difficulty, spawnType, data);
        if (!campSpawn() && level.getRandom().nextFloat() < BanditMove.ELITE_CHANCE) {
            makeElite();
        }
        return out;
    }

    /** Элитный: рывок-разрез ци и чуть больше здоровья. */
    public void makeElite() {
        setElite(true);
        java.util.Objects.requireNonNull(getAttribute(Attributes.MAX_HEALTH)).setBaseValue(32.0D);
        setHealth(getMaxHealth());
        refreshAura();
    }

    /**
     * Главарь лагеря (docs/design/24-bandit-camp.md §2; не босс M4): второсортный мастер — ци,
     * 70 здоровья, броня 6, удары ×1,35, рывок ци вдвое чаще, своё имя над головой.
     */
    public void makeChief(net.minecraft.network.chat.Component name) {
        setElite(true);
        setChiefFlag(true);
        java.util.Objects.requireNonNull(getAttribute(Attributes.MAX_HEALTH)).setBaseValue(70.0D);
        java.util.Objects.requireNonNull(getAttribute(Attributes.ARMOR)).setBaseValue(6.0D);
        setHealth(getMaxHealth());
        setCustomName(name);
        setCustomNameVisible(true);
        refreshAura();
    }

    /** Стенд: первый удар не раньше чем через {@code ticks} (чтобы он пришёлся на замах техники). */
    public void delayFirstAttack(int ticks) {
        attackCooldown = ticks;
        dashCooldown = Math.max(dashCooldown, ticks);
    }

    /** Стенд: принудительный удар (MURIM_CAPTURE_BANDIT_MOVE), иначе выбор по дистанции. */
    private static BanditMove forced() {
        String raw = System.getenv("MURIM_CAPTURE_BANDIT_MOVE");
        if (raw == null || !Boolean.getBoolean("murim.capture")) {
            return null;
        }
        return switch (raw.trim()) {
            case "chop" -> BanditMove.CHOP;
            case "sweep" -> BanditMove.SWEEP;
            case "thrust" -> BanditMove.THRUST;
            case "dash" -> BanditMove.QI_DASH;
            default -> null;
        };
    }

    // ------------------------------------------------------------------ реакция

    @Override
    public boolean hurt(DamageSource source, float amount) {
        int before = state();
        boolean hurt = super.hurt(source, amount);
        if (hurt && !level().isClientSide && isAlive()) {
            if (before == WINDUP && amount >= BanditMove.POISE) {
                // Сильный удар в замах сбивает его: бандит оступается, удар не выходит.
                setState(STAGGER, null);
                getNavigation().stop();
            } else if (before == IDLE) {
                // Слабое попадание — не срыв, но и не мгновенный ответ: короткая пауза.
                attackCooldown = Math.max(attackCooldown, 6);
            }
        }
        return hurt;
    }

    @Override
    protected void onStunEnded() {
        attackCooldown = 10;
    }

    // ------------------------------------------------------------------ бой

    /**
     * Поединок: держать дистанцию, пока удар не готов; готов — сблизиться и ударить с замахом.
     * Весь цикл (замах → удар → откат) идёт отсюда, поэтому оглушение (ИИ выключен) его
     * естественно останавливает, а {@link Bandit#tick} сбрасывает начатое.
     */
    private final class DuelGoal extends Goal {

        DuelGoal() {
            setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
        }

        @Override
        public boolean canUse() {
            LivingEntity t = getTarget();
            return t != null && t.isAlive() && state() != STUN
                    && io.github.verycooltimo.murim.world.camp.CampFight.mayFight(BanditSwordsman.this);
        }

        @Override
        public boolean canContinueToUse() {
            LivingEntity t = getTarget();
            boolean busy = state() == WINDUP || state() == STRIKE || state() == RECOVER;
            return state() != STUN && (busy || (t != null && t.isAlive() && !(t instanceof Player p && (p.isCreative() || p.isSpectator()))
                    && io.github.verycooltimo.murim.world.camp.CampFight.mayFight(BanditSwordsman.this)));
        }

        @Override
        public boolean requiresUpdateEveryTick() {
            return true;
        }

        @Override
        public void stop() {
            if (state() != STUN && state() != IDLE) {
                setState(IDLE, null);
            }
            getNavigation().stop();
            setAggressive(false);
        }

        @Override
        public void tick() {
            LivingEntity t = getTarget();
            switch (state()) {
                case WINDUP -> windup(t);
                case STRIKE -> strike(t);
                case RECOVER -> {
                    if (t != null) keepDistance(t, distanceTo(t));
                    if (stateTick >= duelPhaseTicks(RECOVER)) {
                        setState(IDLE, null);
                        // Adapt only after a repeated observed rhythm; keep pressure after misses too.
                        attackCooldown = repeatedRhythm >= 2 ? 2 + random.nextInt(2) : 0;
                        if (repeatedRhythm >= 2) repeatedRhythm = 0;
                    }
                }
                case STAGGER -> {
                    getNavigation().stop();
                    if (stateTick >= BanditMove.STAGGER_TICKS) {
                        setState(IDLE, null);
                        attackCooldown = 10;
                    }
                }
                default -> approach(t);
            }
        }

        private void approach(LivingEntity t) {
            if (t == null) {
                return;
            }
            setAggressive(true);
            getLookControl().setLookAt(t, 30.0F, 30.0F);
            double d = distanceTo(t);
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            if (dashCooldown > 0) {
                dashCooldown--;
            }
            BanditMove force = forced();
            boolean sees = getSensing().hasLineOfSight(t);
            // ИИ (автор 03.10: «поработай над ИИ»): бандит читает замах техники игрока вблизи и
            // уходит вбок-назад, а не стоит под ударом. Не каждый раз — примерно в половине случаев.
            if (dodgeTicks > 0) {
                dodgeTicks--;
                getNavigation().stop();
                getMoveControl().strafe(-0.6F, 0.9F * dodgeDir);
                faceTowards(t, 30.0F);
                return;
            }
            if (force == null && t instanceof Player p && d < 6.0D && techniqueWindup(p) && random.nextFloat() < 0.06F) {
                dodgeTicks = 10 + random.nextInt(6);
                dodgeDir = random.nextBoolean() ? 1 : -1;
                log("уклон от замаха техники");
                return;
            }
            // Техника ци: с дистанции, если видит цель.
            if (isElite() && dashCooldown == 0 && attackCooldown == 0 && sees && (force == null || force == BanditMove.QI_DASH)
                    && d >= BanditMove.DASH_MIN && d <= BanditMove.DASH_MAX && Math.abs(t.getY() - getY()) < 1.5D) {
                startWindup(BanditMove.QI_DASH);
                return;
            }
            if (attackCooldown > 0) {
                keepDistance(t, d);
                return;
            }
            // Удар выбирается один раз, когда он готов, и бандит подходит на его дистанцию.
            if (planned == null) {
                planned = force != null && force != BanditMove.QI_DASH ? force : BanditMove.pick(random.nextFloat());
            }
            if (sees && d <= planned.reach() - 0.4D && Math.abs(t.getY() - getY()) < 1.5D) {
                BanditMove next = planned;
                planned = null;
                startWindup(next);
                return;
            }
            getNavigation().moveTo(t, 1.15D);
        }

        /** Continue closing and circling during recovery; never retreat after every hit. */
        private void keepDistance(LivingEntity t, double d) {
            keepDistance(t, d, 30.0F);
        }

        private void keepDistance(LivingEntity t, double d, float turn) {
            if (--strafeTicks <= 0) {
                strafeTicks = 20 + random.nextInt(30);
                strafeDir = random.nextBoolean() ? 1 : -1;
            }
            if (d > 2.7D) {
                getNavigation().moveTo(t, 1.0D);
                return;
            }
            getNavigation().stop();
            float forward = d > 2.1D ? 0.3F : 0.0F;
            getMoveControl().strafe(forward, 0.45F * strafeDir);
            faceTowards(t, turn);
        }

        private void startWindup(BanditMove m) {
            io.github.verycooltimo.murim.combat.SwordGuard.hold(BanditSwordsman.this, false);
            io.github.verycooltimo.murim.combat.SwordGuard.swing(BanditSwordsman.this);
            guardRecovery = 2 + random.nextInt(3);
            setState(WINDUP, m);
            playSound(m == BanditMove.QI_DASH ? io.github.verycooltimo.murim.registry.ModSounds.QI_CHARGE.get()
                    : io.github.verycooltimo.murim.registry.ModSounds.BANDIT_WINDUP.get(),
                    m == BanditMove.QI_DASH ? 0.8F : 0.9F, 0.93F + 0.14F * getRandom().nextFloat());
        }

        private void windup(LivingEntity t) {
            if (t != null) keepDistance(t, distanceTo(t), move() == BanditMove.QI_DASH ? 20.0F : 9.0F);
            if (stateTick >= duelPhaseTicks(WINDUP)) {
                setState(STRIKE, move());
                dashHit = false;
                if (move() == BanditMove.QI_DASH) {
                    Vec3 to = t == null ? Vec3.directionFromRotation(0.0F, yBodyRot)
                            : new Vec3(t.getX() - getX(), 0.0D, t.getZ() - getZ());
                    dashDir = to.lengthSqr() < 1.0E-4D ? Vec3.directionFromRotation(0.0F, yBodyRot) : to.normalize();
                    playSound(io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING_HEAVY.get(), 1.0F, 1.05F + 0.1F * getRandom().nextFloat());
                }
            }
        }

        private void strike(LivingEntity t) {
            BanditMove m = move();
            if (m == BanditMove.QI_DASH) {
                if (stateTick <= BanditMove.DASH_TICKS) {
                    setDeltaMovement(dashDir.x * BanditMove.DASH_SPEED, Math.min(0.0D, getDeltaMovement().y), dashDir.z * BanditMove.DASH_SPEED);
                    hurtMarked = true;
                    if (!dashHit && t != null && getBoundingBox().inflate(m.reach() * 0.5D).intersects(t.getBoundingBox())) {
                        dashHit = true;
                        hit(t, m);
                    }
                } else {
                    setDeltaMovement(getDeltaMovement().multiply(0.2D, 1.0D, 0.2D));
                }
            } else {
                getNavigation().stop();
                if (stateTick == 1) {
                    playSound(io.github.verycooltimo.murim.registry.ModSounds.SWORD_SWING_HEAVY.get(), 0.95F, 0.9F + 0.1F * getRandom().nextFloat());
                    if (t != null && m.reaches(t.getX() - getX(), t.getY() - getY(), t.getZ() - getZ(), yBodyRot)
                            && getSensing().hasLineOfSight(t)) {
                        hit(t, m);
                    } else {
                        log("промах {}: цель {} блоков, dy {}", m.clip(),
                                t == null ? "-" : String.format("%.2f", Math.sqrt(distanceToSqr(t.getX(), getY(), t.getZ()))),
                                t == null ? "-" : String.format("%.2f", t.getY() - getY()));
                    }
                }
            }
            if (stateTick >= duelPhaseTicks(STRIKE)) {
                setState(RECOVER, m);
                if (m == BanditMove.QI_DASH) {
                    dashCooldown = isChief() ? BanditMove.DASH_COOLDOWN * 2 / 3 : BanditMove.DASH_COOLDOWN;
                }
            }
        }

        private void hit(LivingEntity t, BanditMove m) {
            // Урон через Casters (docs/design/23 С0): источник — удар моба, сила — от ци бандита.
            float dmg = m.damage() * (float) damageScale();
            boolean done = t.hurt(io.github.verycooltimo.murim.technique.Casters.attack(BanditSwordsman.this), dmg);
            log("удар {} по {}: {} (урон {})", m.clip().isEmpty() ? "_chop" : m.clip(), t.getName().getString(), done ? "попал" : "погашен", m.damage());
            if (done) {
                setLastHurtMob(t);
                double kx = t.getX() - getX(), kz = t.getZ() - getZ();
                t.knockback(m == BanditMove.CHOP ? 0.3D : 0.45D, -kx, -kz);
            }
        }

        private boolean techniqueWindup(Player p) {
            io.github.verycooltimo.murim.combat.TechniqueState st = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.TECHNIQUE_STATE);
            if (!st.isActive()) {
                return false;
            }
            io.github.verycooltimo.murim.technique.TechniqueDefinition d = io.github.verycooltimo.murim.technique.TechniqueLoader.get(st.techniqueId());
            return d != null && st.tick() < d.startTickOf(io.github.verycooltimo.murim.combat.TechniquePhase.IMPACT);
        }

        private void faceTowards(LivingEntity t, float maxStep) {
            float want = (float) (Mth.atan2(t.getZ() - getZ(), t.getX() - getX()) * Mth.RAD_TO_DEG) - 90.0F;
            float yaw = Mth.approachDegrees(yBodyRot, want, maxStep);
            setYRot(yaw);
            yBodyRot = yaw;
            yHeadRot = Mth.approachDegrees(yHeadRot, want, maxStep * 2.0F);
        }
    }
}
