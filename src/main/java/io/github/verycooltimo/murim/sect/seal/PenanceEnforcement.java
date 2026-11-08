package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectNotice;
import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FootworkService;
import io.github.verycooltimo.murim.combat.TargetLock;
import io.github.verycooltimo.murim.combat.TechniqueService;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.AuraGustPayload;
import io.github.verycooltimo.murim.network.ChannelSafe;
import io.github.verycooltimo.murim.network.PenancePayload;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectWatch;
import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.EntityTeleportEvent;
import net.neoforged.neoforge.event.entity.EntityTravelToDimensionEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.living.LivingEntityUseItemEvent;
import net.neoforged.neoforge.event.entity.living.LivingIncomingDamageEvent;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** Persistent hard sentence, independent of the optional light-offence dialogue and of membership. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PenanceEnforcement {
    static final String TAG = "murim_penance_enforcement";
    static final String ASSAULTS = "murim_penance_assaults";
    static final String EXIT = "murim_penance_exit";
    /** The vanilla swing of this very tick: who was struck. The sword sweep around him is not an attack on his neighbours. */
    static final String SWING = "murim_penance_swing";
    /** A technique started this recently (ticks) still owns the player's hits: sustained arts, late projectiles. */
    static final int TECHNIQUE_TAIL = 400;
    private static final ResourceLocation SPEED = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "penance_pin");
    private static final Set<UUID> TRANSFERRING = new HashSet<>();

    private PenanceEnforcement() { }

    private static boolean exempt(ServerPlayer p) { return p.isCreative() || p.isSpectator(); }
    public static boolean active(ServerPlayer p) { return !exempt(p) && p.getPersistentData().contains(TAG); }
    public static boolean pinned(ServerPlayer p) { return active(p) && p.getPersistentData().getCompound(TAG).getBoolean("pinned"); }
    public static boolean confined(ServerPlayer p) { return !exempt(p) && PenanceService.sentenced(p); }
    public static boolean releasedOutsider(ServerPlayer p) { return p.getPersistentData().getBoolean(EXIT); }
    public static boolean teleportBlocked(ServerPlayer p) {
        return !TRANSFERRING.contains(p.getUUID()) && (pinned(p) || confined(p));
    }

    /** Internal prison transports must pass dimension/teleport hooks; ordinary escape attempts cannot. */
    static void transport(ServerPlayer p, Runnable move) {
        TRANSFERRING.add(p.getUUID());
        try { move.run(); } finally { TRANSFERRING.remove(p.getUUID()); }
    }

    /**
     * Is a hit on a sect brother a deliberate attack (author 18.10: «убрать» the accidental brawl penalty)? Only these count
     * towards the rampage and the light offence; the splash of an area technique, a sword sweep or a stray projectile
     * does not, so a technique used near sect people never starts a sentence.
     *
     * <ol>
     *   <li>The fight with an enforcing guard is the player's own: always deliberate.</li>
     *   <li>A vanilla swing resolving this tick: deliberate only for the one struck ({@link #swing}).</li>
     *   <li>Anything a technique did (a technique runs or ran moments ago, a projectile, a non-melee damage type):
     *       deliberate only for the target the player locked on to ({@link TargetLock}).</li>
     *   <li>Otherwise a plain direct melee blow (scripted hits, foreign attack mods): deliberate.</li>
     * </ol>
     */
    public static boolean deliberate(ServerPlayer p, SectDisciple victim, DamageSource source) {
        if (victim.guardTarget() == p) return true;
        long now = p.level().getGameTime();
        CompoundTag swing = p.getPersistentData().getCompound(SWING);
        if (swing.hasUUID("victim") && swing.getLong("time") == now) return swing.getUUID("victim").equals(victim.getUUID());
        var state = p.getData(ModAttachments.TECHNIQUE_STATE);
        boolean melee = source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == p;
        boolean technique = state.isActive() || !melee
                || state.lastStartGameTime() > Long.MIN_VALUE / 2 && now - state.lastStartGameTime() <= TECHNIQUE_TAIL;
        return !technique || TargetLock.locked(p, TargetLock.RANGE) == victim;
    }

    /** Did a technique (or a projectile, a non-melee blow) do this? Then the blow is an attack with the art, not a scuffle. */
    public static boolean techniqueHit(ServerPlayer p, DamageSource source) {
        long now = p.level().getGameTime();
        var state = p.getData(ModAttachments.TECHNIQUE_STATE);
        boolean melee = source.is(DamageTypes.PLAYER_ATTACK) && source.getDirectEntity() == p;
        return state.isActive() || !melee
                || state.lastStartGameTime() > Long.MIN_VALUE / 2 && now - state.lastStartGameTime() <= TECHNIQUE_TAIL;
    }

    /** Records the vanilla swing: {@code Player#attack} posts this event and then hurts the target within the same tick. */
    static void markSwing(ServerPlayer p, net.minecraft.world.entity.Entity target) {
        CompoundTag swing = new CompoundTag();
        swing.putUUID("victim", target.getUUID());
        swing.putLong("time", p.level().getGameTime());
        p.getPersistentData().put(SWING, swing);
    }

    /** Count deliberate attack attempts before the disciple's invulnerability/nonlethal clamp, but never consensual spar hits. */
    public static void assault(ServerPlayer p, SectDisciple victim, float damage) {
        if (exempt(p) || active(p) || PenanceService.sentenced(p)) return;
        long now = p.level().getGameTime();
        CompoundTag history = p.getPersistentData().getCompound(ASSAULTS);
        ListTag hits = history.getList("hits", Tag.TAG_COMPOUND);
        Set<UUID> targets = new HashSet<>();
        for (int i = hits.size() - 1; i >= 0; i--) {
            CompoundTag hit = hits.getCompound(i);
            if (now < hit.getLong("time") || now - hit.getLong("time") > Config.SECT_RAMPAGE_WINDOW.get()) hits.remove(i);
            else if (hit.hasUUID("victim")) targets.add(hit.getUUID("victim"));
        }
        CompoundTag hit = new CompoundTag();
        hit.putLong("time", now);
        hit.putUUID("victim", victim.getUUID());
        hits.add(hit);
        targets.add(victim.getUUID());
        history.put("hits", hits);
        p.getPersistentData().put(ASSAULTS, history);
        // The first deliberate blow of a flurry is the warning: the counters below need a second one. A member hears it
        // from the offence record ({@code PenanceService#offence}); everybody else is told here.
        if (hits.size() == 1 && !p.getData(ModAttachments.SECT).member())
            io.github.verycooltimo.murim.sect.SectNotice.bar(p, Component.translatable("murim.penance.hand_warning", victim.getName()).withStyle(ChatFormatting.GOLD));
        boolean enforcing = victim.guardTarget() == p;
        boolean escorted = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64),
                d -> d.escortGoal().forPlayer(p) && (d.escortGoal().forcedFor(p) || !p.getData(ModAttachments.SECT).member())).size() > 0;
        if (hits.size() >= Config.SECT_RAMPAGE_HITS.get() || targets.size() >= Config.SECT_RAMPAGE_TARGETS.get()
                || enforcing && damage >= Config.SECT_RAMPAGE_GUARD_DAMAGE.get()
                || p.getPersistentData().getLong("murim_sect_defeated_until") > now
                || p.getPersistentData().getLong("murim_sect_warning_until") > now || escorted) {
            rampage(p);
        }
    }

    public static void rampage(ServerPlayer p) {
        if (exempt(p) || active(p) || PenanceService.sentenced(p)) return;
        CompoundTag tag = new CompoundTag();
        p.getPersistentData().put(TAG, tag);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(PenanceService.SUMMONED));
        var session = p.getData(ModAttachments.DIALOGUE);
        if (session.active()) {
            if (p.level().getEntity(session.npc()) instanceof io.github.verycooltimo.murim.npc.DialogueActor guest)
                io.github.verycooltimo.murim.sect.GuestDialogueService.close(p, guest);
            else DialogueService.close(p, p.level().getEntity(session.npc()) instanceof SectDisciple d ? d : null);
        }
        // End enforcement combat and walking expulsion before the elder takes over.
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(Config.SECT_ELDER_RANGE.get()))) {
            d.stopSparForPenance(p);
            if (d.guardTarget() == p) d.guardGoal().cancel();
            if (d.escortGoal().forPlayer(p)) d.escortGoal().cancel();
            if (d.approachingDialogue(p, PenanceService.DIALOGUE)) d.clearBlock();
        }
        SectNotice.chat(p, Component.translatable("murim.penance.rampage").withStyle(ChatFormatting.DARK_RED));
        approach(p);
    }

    /** Stable reservation: nearest available elder first, leader only if no elder can come. */
    private static SectDisciple approach(ServerPlayer p) {
        var npcs = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(Config.SECT_ELDER_RANGE.get()),
                d -> d.isAlive() && !d.wounded() && !d.escorting()
                        && (d.suppressionGoal().forPlayer(p) || d.free() && !d.blocking())
                        && (d.role() == SectRole.ELDER || d.role() == SectRole.LEADER));
        SectDisciple elder = npcs.stream().filter(d -> d.suppressionGoal().forPlayer(p)).findFirst().orElse(null);
        if (elder == null) elder = npcs.stream().min(Comparator
                .comparingInt((SectDisciple d) -> d.role() == SectRole.ELDER ? 0 : 1)
                .thenComparingDouble(d -> d.distanceToSqr(p))).orElse(null);
        if (elder != null && elder.suppressionGoal().begin(p)) {
            CompoundTag tag = p.getPersistentData().getCompound(TAG);
            tag.putUUID("elder", elder.getUUID());
            p.getPersistentData().put(TAG, tag);
            return elder;
        }
        return null; // The warrant remains saved until an elder is available.
    }

    /** Called only on arrival. The sentence has no accept/refuse option. */
    public static void pin(ServerPlayer p, SectDisciple elder) {
        if (!active(p) || pinned(p) || elder.level() != p.level() || elder.distanceToSqr(p) > 16
                || !elder.getSensing().hasLineOfSight(p)) return;
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        tag.putBoolean("pinned", true);
        tag.putUUID("elder", elder.getUUID());
        tag.putInt("remaining", Config.SECT_SUPPRESSION_TICKS.get());
        tag.putString("dimension", p.level().dimension().location().toString());
        tag.putDouble("x", p.getX()); tag.putDouble("y", p.getY()); tag.putDouble("z", p.getZ());
        p.getPersistentData().put(TAG, tag);
        elder.suppressionGoal().fullAura();
        TechniqueService.cancel(p);
        FootworkService.stop(p);
        hold(p);
        sync(p, elder.getId());
        p.level().playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1, 0.65F);
        SectNotice.chat(p, Component.translatable("murim.penance.suppressed", elder.getName()).withStyle(ChatFormatting.DARK_RED));
    }

    private static void sync(ServerPlayer p, int source) { ChannelSafe.send(p, new PenancePayload(pinned(p), source)); }

    private static void hold(ServerPlayer p) {
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
        var level = dim == null ? null : p.server.getLevel(ResourceKey.create(Registries.DIMENSION, dim));
        Vec3 at = new Vec3(tag.getDouble("x"), tag.getDouble("y"), tag.getDouble("z"));
        if (level != null && (p.level() != level || p.position().distanceToSqr(at) > 0.0001D))
            transport(p, () -> p.teleportTo(level, at.x, at.y, at.z, p.getYRot(), p.getXRot()));
        p.setDeltaMovement(Vec3.ZERO);
        p.hurtMarked = true;
        p.fallDistance = 0;
        p.stopUsingItem();
        p.stopRiding();
        p.stopFallFlying();
        p.setSprinting(false);
        p.setShiftKeyDown(true);
        p.setForcedPose(Pose.CROUCHING);
        var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.addOrUpdateTransientModifier(new AttributeModifier(SPEED, -1, AttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        p.setData(ModAttachments.PRESSURE, 1.0F);
        TechniqueService.cancel(p);
        FootworkService.stop(p);
    }

    static void clear(ServerPlayer p) {
        // Cancel a reservation even if the elder moved outside the search range or into another dimension.
        for (var level : p.server.getAllLevels()) for (var entity : level.getAllEntities())
            if (entity instanceof SectDisciple d && d.suppressionGoal().forPlayer(p)) d.suppressionGoal().cancel();
        p.getPersistentData().remove(TAG);
        p.getPersistentData().remove(ASSAULTS);
        p.setForcedPose(null);
        p.setShiftKeyDown(false);
        var speed = p.getAttribute(Attributes.MOVEMENT_SPEED);
        if (speed != null) speed.removeModifier(SPEED);
        sync(p, -1);
    }

    /** Called every online tick. Remaining time is decremented, so disconnecting cannot serve the pin. */
    public static void tick(ServerPlayer p) {
        if (exempt(p)) {
            if (p.getPersistentData().contains(TAG)) clear(p);
            return;
        }
        if (!active(p)) return;
        if (!pinned(p)) {
            // Retry immediately while no elder is assigned; a newly available head must take over.
            // API: Minecraft 1.21.1 CompoundTag#hasUUID (read-only /tmp/mr-ref-1121/minecraft-src).
            if (!p.getPersistentData().getCompound(TAG).hasUUID("elder") || p.tickCount % 10 == 0) approach(p);
            return;
        }
        hold(p);
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        var entity = tag.hasUUID("elder") ? p.serverLevel().getEntity(tag.getUUID("elder")) : null;
        int source = entity instanceof SectDisciple d && d.isAlive() ? d.getId() : -1;
        if (p.tickCount % 10 == 0) {
            if (!(entity instanceof SectDisciple d) || !d.suppressionGoal().forPlayer(p)) {
                SectDisciple replacement = approach(p);
                if (replacement != null) {
                    replacement.suppressionGoal().fullAura();
                    source = replacement.getId();
                }
            }
            sync(p, source);
            if (source >= 0) ChannelSafe.nearEntity(p, new AuraGustPayload(source, p.getId(), 1.0F, p.tickCount % 20 == 0), true);
        }
        int remaining = Math.max(0, tag.getInt("remaining") - 1);
        tag.putInt("remaining", remaining);
        p.getPersistentData().put(TAG, tag);
        if (remaining == 0 && p.tickCount % 10 == 0) {
            if (!PenanceService.sentence(p) && p.tickCount % 100 == 0)
                PlayerJournal.refuse(p, Component.translatable("murim.penance.cell_unavailable").withStyle(ChatFormatting.RED));
            // No cave: stay pinned and retry; closing the screen or disconnecting never releases the warrant.
        }
    }

    static void escortOnRelease(ServerPlayer p) {
        if (!p.getData(ModAttachments.SECT).member()) p.getPersistentData().putBoolean(EXIT, true);
    }

    private static void exit(ServerPlayer p) {
        if (!p.getPersistentData().getBoolean(EXIT) || active(p) || confined(p) || exempt(p)) return;
        var layout = SectLife.layout(p.serverLevel());
        if (layout == null) return;
        Vec3 gate = SectWatch.outsideGate(layout, p);
        if (gate == null) return;
        if (p.position().distanceToSqr(gate) <= 4) { p.getPersistentData().remove(EXIT); return; }
        var guards = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(Config.SECT_ELDER_RANGE.get()),
                d -> d.isAlive() && (d.role() == SectRole.GUARD || SectWatch.guards(d) || d.role() == SectRole.ELDER || d.role() == SectRole.LEADER));
        if (guards.stream().anyMatch(d -> d.escortGoal().forPlayer(p))) return;
        guards.stream().filter(d -> d.free() && !d.wounded() && !d.escorting()).min(Comparator.comparingDouble(d -> d.distanceToSqr(p)))
                .ifPresent(d -> d.escortGoal().begin(p, gate, true));
    }

    @SubscribeEvent
    static void preTick(PlayerTickEvent.Pre event) {
        if (event.getEntity() instanceof ServerPlayer p && p.isAlive()) {
            tick(p);
            PenanceService.keepInCell(p);
        }
    }
    @SubscribeEvent
    static void postTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && p.isAlive()) {
            if (pinned(p)) hold(p);
            if (p.tickCount % 20 == 0) exit(p);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void attack(AttackEntityEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && pinned(p)) event.setCanceled(true);
    }
    /** The swing is recorded for every target, so a sweep that reaches a brother beside a zombie is not his attack. */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    static void swing(AttackEntityEvent event) {
        if (!event.isCanceled() && event.getEntity() instanceof ServerPlayer p) markSwing(p, event.getTarget());
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void damage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer p && pinned(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    static void killed(LivingDeathEvent event) {
        // A person of the roster killed is war with the whole sect (SectWar), not a rampage for the elders to pin.
        if (event.getEntity() instanceof SectDisciple d && d.memberKey().isEmpty() && event.getSource().getEntity() instanceof ServerPlayer p) rampage(p);
    }
    // PlayerInteractEvent is abstract: the bus only accepts its concrete subclasses.
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void leftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && (pinned(p) || confined(p))) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void rightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && pinned(p)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void entityInteract(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer p && pinned(p)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void entityInteractSpecific(PlayerInteractEvent.EntityInteractSpecific event) {
        if (event.getEntity() instanceof ServerPlayer p && pinned(p)) event.setCanceled(true);
    }
    private static boolean blockedItem(ServerPlayer p, net.minecraft.world.item.ItemStack item) {
        return pinned(p) || confined(p) && (item.is(Items.ENDER_PEARL) || item.is(Items.CHORUS_FRUIT));
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void rightClick(PlayerInteractEvent.RightClickItem event) {
        if (event.getEntity() instanceof ServerPlayer p && blockedItem(p, event.getItemStack())) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void use(LivingEntityUseItemEvent.Start event) {
        if (event.getEntity() instanceof ServerPlayer p && blockedItem(p, event.getItem())) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void breakBlock(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p && (pinned(p) || confined(p))) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void teleport(EntityTeleportEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && teleportBlocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void dimension(EntityTravelToDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && teleportBlocked(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    static void clone(PlayerEvent.Clone event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            if (event.isWasDeath() && event.getOriginal() instanceof ServerPlayer old) {
                io.github.verycooltimo.murim.quest.QuestScenes.abandon(old);
            }
            copyPersistent(event.getOriginal().getPersistentData(), p.getPersistentData(), event.isWasDeath());
        }
    }
    /**
     * Death does not carry the persistent data over by itself. Everything the mod keeps there is progress or world state (penance,
     * quest scenes and timers, offices, social memory, goal talk, the vault trial, the strike drill, one-time hints) and survives
     * the respawn; only a running fight scene (its foes are gone) and a junk-art backlash end with the body.
     */
    static void copyPersistent(CompoundTag from, CompoundTag to) { copyPersistent(from, to, false); }
    static void copyPersistent(CompoundTag from, CompoundTag to, boolean death) {
        for (String key : from.getAllKeys()) {
            if (death && (io.github.verycooltimo.murim.quest.QuestScenes.diesWithBody(key) || "murim_junk_backlash".equals(key))) continue;
            to.put(key, from.get(key).copy());
        }
    }
    private static void restore(ServerPlayer p) {
        if (TRANSFERRING.contains(p.getUUID())) return;
        if (pinned(p)) { hold(p); sync(p, -1); }
        else sync(p, -1);
        PenanceService.keepInCell(p);
    }
    @SubscribeEvent static void login(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) restore(p);
    }
    @SubscribeEvent static void respawn(PlayerEvent.PlayerRespawnEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            restore(p);
            PenanceService.respawnInCell(p);
        }
    }
    @SubscribeEvent static void changedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) restore(p);
    }
}
