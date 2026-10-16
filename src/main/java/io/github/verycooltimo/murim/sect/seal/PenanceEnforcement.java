package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.Config;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.combat.FootworkService;
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

    /** Count attack attempts before the disciple's invulnerability/nonlethal clamp, but never consensual spar hits. */
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
        p.displayClientMessage(Component.translatable("murim.penance.rampage").withStyle(ChatFormatting.DARK_RED), false);
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
        io.github.verycooltimo.murim.technique.DemonicExecutor.clear(p);
        hold(p);
        sync(p, elder.getId());
        p.level().playSound(null, p.blockPosition(), SoundEvents.WARDEN_SONIC_CHARGE, SoundSource.HOSTILE, 1, 0.65F);
        p.displayClientMessage(Component.translatable("murim.penance.suppressed", elder.getName()).withStyle(ChatFormatting.DARK_RED), false);
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
            if (p.tickCount % 10 == 0) approach(p);
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
                p.displayClientMessage(Component.translatable("murim.penance.cell_unavailable").withStyle(ChatFormatting.RED), true);
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
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    static void damage(LivingIncomingDamageEvent event) {
        if (event.getSource().getEntity() instanceof ServerPlayer p && pinned(p)) event.setCanceled(true);
    }
    @SubscribeEvent
    static void killed(LivingDeathEvent event) {
        if (event.getEntity() instanceof SectDisciple && event.getSource().getEntity() instanceof ServerPlayer p) rampage(p);
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
            copyPersistent(event.getOriginal().getPersistentData(), p.getPersistentData());
        }
    }
    static void copyPersistent(CompoundTag from, CompoundTag to) {
        for (String key : new String[] {TAG, ASSAULTS, EXIT, "murim_penance", "murim_penance_history", "murim_sect_offences",
                "murim_sect_defeated_until", "murim_sect_warning_until"})
            if (from.contains(key)) to.put(key, from.get(key).copy());
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
