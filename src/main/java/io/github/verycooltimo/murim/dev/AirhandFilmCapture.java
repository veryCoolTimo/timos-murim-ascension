package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.entity.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.monster.*;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;

/** Dev-only fixtures. API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/:
 * Entity (position/moveTo/tags/movement), LivingEntity (equipment/swing), Mob (AI/damage),
 * Level#setBlock, Block#defaultBlockState, BlockPos#containing, AbstractSkeleton#performRangedAttack,
 * ServerPlayer#teleportTo, ServerLevel#addFreshEntity. Attachments: NeoForge IAttachmentHolder.
 * Real services own all acceptance, collision, damage and movement decisions. */
public final class AirhandFilmCapture {
    private static final String TAG = "murim_airhand_film";
    public static void tick(ServerPlayer p, String scene, ResourceLocation form, String script, int age) {
        if (!Boolean.getBoolean("murim.capture")) return;
        var cues = AirhandCapturePlan.parse(script);
        if (age % 10 == 0) MurimMod.LOGGER.info("CAPTURE-SCENE-DRIVER {} tick={}", scene, age);
        var level = p.serverLevel();
        // API: minecraft-src ServerPlayer#setGameMode; LivingEntity#setHealth/getMaxHealth/addEffect;
        // MobEffectInstance(Holder, int, int, boolean, boolean, boolean), MobEffects#DAMAGE_RESISTANCE.
        // Resistance IV leaves real damage possible; creative or Resistance V hides contacts.
        if (age == 0) {
            p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
            p.addEffect(new net.minecraft.world.effect.MobEffectInstance(
                    net.minecraft.world.effect.MobEffects.DAMAGE_RESISTANCE, 1400, 3, false, false, false));
        }
        p.setHealth(p.getMaxHealth());
        var enemies = level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48), e -> e.getTags().contains(TAG));
        for (var cue : cues) {
            if (cue.tick() != age) continue;
            boolean accepted = true;
            String[] reason = {"fixture_action_failed"};
            try {
                switch (cue.action()) {
                    case "stage" -> {
                        if (scene.equals("full_bloom") && "1".equals(System.getenv("MURIM_CAPTURE_PLUM_CAMERA"))) {
                            // The round-4 camera gate already checked this exact swordsman and mark.
                            // Replacing him after frame zero invalidates that gate and loses the stand's HP.
                            // API: MC EntityGetter#getEntitiesOfClass(Class,AABB), Entity#addTag/setCustomName,
                            // Component#literal; supplied read-only 1.21.1 sources.
                            var staged = level.getEntitiesOfClass(BanditSwordsman.class, p.getBoundingBox().inflate(12));
                            accepted = staged.size() == 1;
                            reason[0] = "expected_one_staged_swordsman";
                            if (accepted) {
                                var actor = staged.getFirst();
                                actor.addTag(TAG);
                                actor.setCustomName(net.minecraft.network.chat.Component.literal(TAG));
                            }
                            break;
                        }
                        for (var old : level.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(48))) old.discard();
                        for (var old : level.getEntitiesOfClass(TrainingDummy.class, p.getBoundingBox().inflate(48))) old.discard();
                        boolean ledge = scene.contains("floating"), rising = scene.contains("rising");
                        if (ledge || rising) {
                            // Axis-aligned fixture; the film script fixes yaw at zero (+Z).
                            // Clear the -X camera corridor, including any pre-existing stand wall.
                            double y = Math.floor(p.getY());
                            for (int x = -8; x < 0; x++) for (int z = -8; z <= 20; z++)
                                for (int h = 0; h < 28; h++)
                                    level.setBlock(BlockPos.containing(p.getX() + x, y + h, p.getZ() + z), Blocks.AIR.defaultBlockState(), 3);
                            for (int x = 0; x <= 4; x++) for (int z = 2; z <= 8; z++)
                                for (int h = 0; h < (ledge ? 12 : 6); h++)
                                    level.setBlock(BlockPos.containing(p.getX() + x, y + h, p.getZ() + z), Blocks.STONE.defaultBlockState(), 3);
                            if (ledge) p.teleportTo(p.getX(), y + 12, p.getZ() + 7);
                        } else {
                            boolean leaf = scene.equals("bamboo_wind_leaf");
                            int count = scene.contains("rain") ? 3 : 1;
                            for (int i = 0; i < count; i++) {
                                boolean archer = scene.contains("rain") || leaf;
                                Mob actor = archer ? new FilmSkeleton(level)
                                        : scene.equals("full_bloom") ? new BanditSwordsman(
                                                io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), level)
                                        : new Actor(level);
                                actor.setNoAi(true); actor.setPersistenceRequired(); actor.addTag(TAG);
                                // API: MC Entity#setCustomName uses SynchedEntityData; tags themselves do not sync.
                                actor.setCustomName(net.minecraft.network.chat.Component.literal(TAG));
                                actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(archer ? Items.BOW : Items.IRON_SWORD));
                                double distance = archer ? 5 : scene.equals("full_bloom") ? 4 : scene.equals("bamboo_bending") ? 5 : 2;
                                actor.moveTo(p.getX() + (leaf ? 2 : (i - (count - 1) / 2.0) * .7), p.getY(), p.getZ() + distance, 180, 0);
                                if (scene.equals("full_bloom")) {
                                    // full-19 film: one swordsman on the same 28-degree axis as DevSetupEvents,
                                    // no overlapping zombie or foreground dummy hiding the destination.
                                    double axis = Math.toRadians(28);
                                    actor.moveTo(p.getX() - Math.sin(axis) * distance, p.getY(),
                                            p.getZ() + Math.cos(axis) * distance, 208, 0);
                                }
                                if (!level.addFreshEntity(actor)) { accepted = false; reason[0] = "spawn_rejected"; }
                            }
                        }
                    }
                    case "step_off" -> {
                        // API: /tmp/mr-ref-1121/minecraft-src/net/minecraft/server/level/ServerPlayer.java#teleportTo
                        // and world/entity/Entity.java#setOnGround. Move beyond the ledge's +Z edge.
                        p.teleportTo(p.getX(), p.getY(), p.getZ() + 3);
                        p.setOnGround(false);
                    }
                    case "cast" -> {
                        if (!enemies.isEmpty()) TargetLock.set(p, enemies.getFirst().getId());
                        if (FragranceAirService.isForm(form)) {
                            reason[0] = FragranceAirService.requestResult(p, form);
                            accepted = reason[0] == null;
                        } else accepted = TechniqueService.tryStart(p, form, 1, why -> reason[0] = why);
                    }
                    case "release" -> {
                        if (form.equals(FragranceAirService.FLOATING)) {
                            reason[0] = FragranceAirService.releaseResult(p);
                            accepted = reason[0] == null;
                        }
                        else if (form.equals(io.github.verycooltimo.murim.technique.FullBloomExecutor.ID))
                            io.github.verycooltimo.murim.technique.FullBloomExecutor.hold(p, false);
                        else io.github.verycooltimo.murim.technique.BambooExecutor.release(p);
                    }
                    case "shoot" -> {
                        reason[0] = "no_fixture_enemies";
                        accepted = !enemies.isEmpty();
                        for (var enemy : enemies) if (enemy instanceof Skeleton skeleton) skeleton.performRangedAttack(p, 1);
                    }
                    case "rush" -> {
                        reason[0] = "no_fixture_enemies";
                        accepted = !enemies.isEmpty();
                        for (var enemy : enemies) {
                            if (enemy instanceof Actor actor) actor.rush(p);
                            else enemy.setDeltaMovement(p.position().subtract(enemy.position()).normalize().scale(.45));
                        }
                    }
                    case "swing", "hit" -> {
                        reason[0] = "no_fixture_enemies";
                        accepted = !enemies.isEmpty();
                        for (var enemy : enemies) {
                            if (enemy instanceof Actor actor) actor.pose(cue.action().equals("swing") ? Bandit.WINDUP : Bandit.STRIKE);
                            enemy.swing(InteractionHand.MAIN_HAND);
                            if (cue.action().equals("hit")) {
                                var bamboo = p.hasData(ModAttachments.BAMBOO_HAND) ? p.getData(ModAttachments.BAMBOO_HAND) : null;
                                boolean spent = bamboo != null && bamboo.spent;
                                var bloom = p.getData(ModAttachments.FULL_BLOOM);
                                double reserve = bloom.reserve;
                                boolean damaged = enemy.doHurtTarget(p);
                                boolean defended = (bamboo != null && !spent && bamboo.spent) || bloom.reserve < reserve;
                                if (!damaged && !defended) { accepted = false; reason[0] = "damage_rejected_no_defense"; }
                            }
                        }
                    }
                    default -> throw new IllegalStateException(cue.action());
                }
            } catch (RuntimeException exception) {
                accepted = false;
                reason[0] = "exception_" + exception.getClass().getSimpleName();
                MurimMod.LOGGER.error("CAPTURE-SCENE " + scene + " failed cue=" + cue.action() + " tick=" + age, exception);
            }
            if (accepted) MurimMod.LOGGER.info("CAPTURE-SCENE {} cue={} tick={} accepted=true", scene, cue.action(), age);
            else MurimMod.LOGGER.info("CAPTURE-SCENE {} cue={} tick={} accepted=false reason={}",
                    scene, cue.action(), age, reason[0]);
        }
    }
    /** Same skeleton release, with zero stand spread so the quota can be filmed reliably.
     * API: AbstractSkeleton#getArrow, Projectile#shoot, Entity#getY(double). */
    private static final class FilmSkeleton extends Skeleton {
        FilmSkeleton(net.minecraft.server.level.ServerLevel level) { super(EntityType.SKELETON, level); }
        @Override public void performRangedAttack(LivingEntity target, float power) {
            var arrow = getArrow(new ItemStack(Items.ARROW), power, getMainHandItem());
            double dx = target.getX() - getX(), dz = target.getZ() - getZ();
            double dy = target.getY(.3333333333333333) - arrow.getY();
            arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * .2, dz, 1.6F, 0);
            level().addFreshEntity(arrow);
        }
    }
    private static final class Actor extends BanditSwordsman {
        Actor(net.minecraft.server.level.ServerLevel level) {
            super(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), level);
        }
        private ServerPlayer rushTarget;
        private boolean thrown, landed;
        void pose(int state) { setState(state, BanditMove.CHOP); }
        void rush(ServerPlayer player) {
            rushTarget = player;
            setNoAi(false);
            setState(Bandit.STRIKE, BanditMove.QI_DASH);
        }
        // Mob#customServerAiStep runs before LivingEntity#travel. The fixture's rush uses real
        // travel; Stun alone owns AI disabling and throw physics after the confirmed contact.
        @Override protected void customServerAiStep() {
            if (rushTarget == null) { super.customServerAiStep(); return; }
            if (!thrown) {
                setState(Bandit.STRIKE, BanditMove.QI_DASH);
                setDeltaMovement(rushTarget.position().subtract(position()).multiply(1, 0, 1).normalize().scale(.45));
            }
        }
        @Override public void tick() {
            super.tick();
            if (rushTarget == null) return;
            if (Stun.isStunned(this)) {
                thrown = true;
                if (onGround() && !landed && getDeltaMovement().horizontalDistanceSqr() < .04) {
                    landed = true;
                    MurimMod.LOGGER.info("CAPTURE-BAMBOO-THROW landed=true at={}", position());
                }
            } else if (thrown && landed) {
                setNoAi(true);
                setDeltaMovement(net.minecraft.world.phys.Vec3.ZERO);
            }
        }
    }
    private AirhandFilmCapture() { }
}
