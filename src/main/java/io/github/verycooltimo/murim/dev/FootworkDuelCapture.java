package io.github.verycooltimo.murim.dev;

import io.github.verycooltimo.murim.combat.*;
import io.github.verycooltimo.murim.mastery.*;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.technique.SevenStarsRules;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.entity.monster.Vindicator;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import io.github.verycooltimo.murim.entity.Bandit;
import io.github.verycooltimo.murim.entity.BanditMove;
import io.github.verycooltimo.murim.entity.BanditSwordsman;
import net.minecraft.world.InteractionHand;

/** Dev-only integrated stand. Called on the server executor, once for each filmed tick.
 * API verified in /tmp/mr-ref-1121/minecraft-src: EntityGetter#getEntitiesOfClass;
 * LivingEntity#swing, Mob#doHurtTarget/setNoAi; Entity#moveTo; ServerPlayer#serverLevel.
 * Also checked: Mob#setPersistenceRequired/setAggressive/lookAt/setItemSlot;
 * Entity#addTag/getTags/setDeltaMovement/discard; LivingEntity#swinging/swingTime,
 * setYBodyRot/setYHeadRot/getAttribute/setHealth/getMaxHealth; ServerLevel#addFreshEntity;
 * monster/Zombie and Vindicator constructors; EntityType#VINDICATOR, Items#IRON_AXE/IRON_SWORD.
 * NeoForge: /tmp/mr-ref-1121/neoforge-src attachment/IAttachmentHolder#getData/setData.
 * Uses the real combat services; no forced acceptance or cooldown reset. */
public final class FootworkDuelCapture {
    public static void tick(ServerPlayer player, String script, int age) {
        if (!Boolean.getBoolean("murim.capture")) return;
        var plan = FootworkCapturePlan.sequence(script);
        String kind = FootworkCapturePlan.enemy(System.getenv().getOrDefault("MURIM_CAPTURE_DUEL_ENEMY", "bandit"));
        var level = player.serverLevel();
        if (age == 0) {
            player.setData(ModAttachments.MASTERY, prepareMastery(player.getData(ModAttachments.MASTERY), script));
            var basic = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");
            player.setData(ModAttachments.LOADOUT, player.getData(ModAttachments.LOADOUT).withFoundation(java.util.Optional.of(basic)));
            for (var old : level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32))) old.discard();
            Mob actor = switch (kind) {
                case "bandit" -> new CaptureBandit(level);
                case "vindicator" -> new Vindicator(EntityType.VINDICATOR, level);
                default -> new Zombie(level);
            };
            actor.setNoAi(true);
            actor.setPersistenceRequired();
            actor.addTag("murim_capture_duel");
            actor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(kind.equals("vindicator") ? Items.IRON_AXE : Items.IRON_SWORD));
            var at = player.position().add(player.getLookAngle().multiply(1, 0, 1).normalize().scale(2));
            actor.moveTo(at.x, at.y, at.z, player.getYRot() + 180, 0);
            actor.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH).setBaseValue(200);
            actor.setHealth(actor.getMaxHealth());
            if (!level.addFreshEntity(actor)) throw new IllegalStateException("Cannot stage duel enemy");
        }
        var enemies = level.getEntitiesOfClass(Mob.class, player.getBoundingBox().inflate(32),
                mob -> mob.getTags().contains("murim_capture_duel"));
        if (enemies.size() != 1) throw new IllegalStateException("Duel needs exactly one staged attacker");
        var enemy = enemies.getFirst();
        boolean retreating = plan.stream().anyMatch(c -> c.action().equals("enemy_retreat") && age >= c.tick() && age <= c.tick() + 5);
        Vec3 away = enemy.position().subtract(player.position()).multiply(1, 0, 1).normalize();
        enemy.setDeltaMovement(retreating ? away.scale(.08) : Vec3.ZERO);
        if (retreating && !Stun.isFrozen(enemy) && plan.stream().anyMatch(c -> c.action().equals("enemy_retreat") && age > c.tick() && age < c.tick() + 5)) {
            int retreatAge = plan.stream().filter(c -> c.action().equals("enemy_retreat") && age >= c.tick() && age < c.tick() + 5)
                    .mapToInt(c -> age - c.tick()).findFirst().orElse(0);
            // Five collision-aware impulses total 3.5 blocks; shallow hop returns to the paving.
            enemy.move(net.minecraft.world.entity.MoverType.SELF, away.scale(.7).add(0, retreatAge < 2 ? .12 : -.08, 0));
        }
        if (plan.stream().anyMatch(c -> c.action().equals("enemy_retreat") && age == c.tick() + 5)
                && enemy instanceof CaptureBandit bandit) bandit.pose(Bandit.RECOVER);
        if (!Stun.isFrozen(enemy) && enemy.getData(ModAttachments.WIND_GOD).rootOwner == null) {
            enemy.lookAt(player, 360, 360);
            enemy.setYBodyRot(enemy.getYRot());
            enemy.setYHeadRot(enemy.getYRot());
        }
        // Hold the vanilla swing marker throughout the authored telegraph: Weight Shift records
        // pending melee attacks at its start. The bandit pose itself comes from its existing clip.
        boolean winding = plan.stream().anyMatch(c -> c.action().equals("enemy_swing")
                && age >= c.tick() && age < c.tick() + FootworkCapturePlan.ENEMY_WINDUP);
        if (winding) { enemy.swinging = true; enemy.swingTime = 0; }
        for (var cue : plan) {
            if (cue.tick() != age) continue;
            boolean accepted = true;
            String reason = "";
            if (cue.action().equals("enemy_retreat")) {
                float health = enemy.getHealth();
                player.attack(enemy);
                accepted = enemy.getHealth() < health;
                reason = "player_attack_did_not_damage_enemy";
                if (accepted) {
                    if (enemy instanceof CaptureBandit bandit) bandit.pose(Bandit.STAGGER);
                    enemy.move(net.minecraft.world.entity.MoverType.SELF, away.scale(.7).add(0, .12, 0));
                    enemy.setDeltaMovement(away.scale(.08));
                }
            } else if (cue.action().equals("enemy_swing")) {
                if (enemy instanceof CaptureBandit bandit) bandit.pose(Bandit.WINDUP);
                enemy.setAggressive(true);
                enemy.swing(InteractionHand.MAIN_HAND);
            } else if (cue.action().equals("enemy_hit")) {
                if (enemy instanceof CaptureBandit bandit) bandit.pose(Bandit.STRIKE);
                enemy.swing(InteractionHand.MAIN_HAND);
                // Real incoming damage event decides the timed dodge and its opening.
                accepted = enemy.doHurtTarget(player);
                reason = "enemy_hit_cancelled_or_invulnerable";
            } else {
                String path = cue.action().equals("falling_petal") ? "falling_petal_sword" : cue.action();
                var id = ResourceLocation.fromNamespaceAndPath("murim", path);
                boolean step = SevenStarsRules.isForm(id);
                int slot = step ? 1 : 0;
                // Keep the footwork slot equipped while casting the sword.
                var loadout = player.getData(ModAttachments.LOADOUT).with(slot, java.util.Optional.of(id));
                player.setData(ModAttachments.LOADOUT, loadout);
                LoadoutService.sync(player);
                TargetLock.set(player, enemy.getId());
                if (step) {
                    reason = FootworkService.request(player, id, SevenStarsRules.follows(id) ? FootworkService.FORWARD : FootworkService.LEFT);
                    accepted = reason == null;
                }
                else {
                    String[] swordReason = {"sword_footwork_dispatch_refused"};
                    accepted = TechniqueService.tryStart(player, id, 1, why -> swordReason[0] = why);
                    reason = swordReason[0];
                    io.github.verycooltimo.murim.MurimMod.LOGGER.info("CAPTURE-DUEL sword={} accepted={} tick={}", id, accepted, age);
                }
            }
            if (accepted) io.github.verycooltimo.murim.MurimMod.LOGGER.info(
                    "CAPTURE-DUEL cue={} tick={} accepted=true", cue.action(), age);
            else io.github.verycooltimo.murim.MurimMod.LOGGER.info(
                    "CAPTURE-DUEL cue={} tick={} accepted=false reason={}", cue.action(), age, reason);
        }
        if (plan.stream().anyMatch(c -> c.action().equals("enemy_hit") && age == c.tick() + 2)) {
            if (enemy instanceof CaptureBandit bandit && !Stun.isStunned(enemy)) bandit.pose(Bandit.IDLE);
            enemy.setAggressive(false);
        }
    }

    /** Grant the scripted forms and the actual foundation, even when its subforms are already known. */
    public static MasteryState prepareMastery(MasteryState mastery, String script) {
        for (var cue : FootworkCapturePlan.sequence(script)) {
            if (cue.action().startsWith("enemy_")) continue;
            String path = cue.action().equals("falling_petal") ? "falling_petal_sword" : cue.action();
            var id = ResourceLocation.fromNamespaceAndPath("murim", path);
            mastery = mastery.with(id, TechniqueProgress.learned(12, Arts.cap(id)));
        }
        var basic = ResourceLocation.fromNamespaceAndPath("murim", "six_harmonies");
        // with(artId, ...) only updates shared progress if a subform is already known.
        // Grant the actual foundation explicitly, independent of learnAll's map order.
        var known = new java.util.HashMap<>(mastery.techniques());
        known.put(basic, TechniqueProgress.learned(12, 12));
        mastery = new MasteryState(known, mastery.wisdom(), mastery.comprehended(), mastery.fired());
        return mastery;
    }

    /** Dev subclass exposes existing synced animation states without adding release hooks. */
    private static final class CaptureBandit extends BanditSwordsman {
        CaptureBandit(net.minecraft.server.level.ServerLevel level) {
            super(io.github.verycooltimo.murim.registry.ModEntities.BANDIT_SWORDSMAN.get(), level);
        }
        void pose(int state) { setState(state, BanditMove.CHOP); }
    }
    private FootworkDuelCapture() { }
}
