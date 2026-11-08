package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.npc.GuestFighter;
import io.github.verycooltimo.murim.quest.def.QuestDef;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import io.github.verycooltimo.murim.sect.SectService;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The generic scene steps of the quest constructor: {@code fight} (a group of weak foes, with a person to protect or not),
 * {@code escort} (a member leads the player to a person or place), {@code timer} and {@code hand_in}. Dialogue starts them with
 * the actions {@code quest_fight}, {@code quest_escort} and {@code quest_hand_in}; nothing here knows a quest by name.
 *
 * <p>State is in the player's persistent data (no static fields). A scene never takes anything from the player: if it fails
 * (the protected person falls, the time runs out, the player dies or leaves) the foes go away and the dialogue offers it again.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QuestScenes {

    private static final String SCENE = "murim_scene", TIMERS = "murim_timers";
    /** A foe at or below this share of his health gives up and leaves: nobody is killed for a side quest. */
    public static final float YIELD = 0.4F;
    /** Foes hit for no more than this (a hit of a quest foe is a warning, not a danger). */
    public static final double FOE_DAMAGE = 2.5D;
    private static final String FOE_TAG = "murim_quest_foe";

    private QuestScenes() {
    }

    /** Dialogue entry for the quest actions. Null: not one of ours. True: done. False: the dialogue stays on its node. */
    public static Boolean action(ServerPlayer p, Mob npc, String type, String id) {
        return switch (type) {
            case "quest_fight" -> startFight(p, id);
            case "quest_escort" -> startEscort(p, id);
            case "quest_hand_in" -> handIn(p, id);
            default -> null;
        };
    }

    public static boolean active(ServerPlayer p) {
        return p.getPersistentData().contains(SCENE);
    }

    /** True when the player's running scene removed the guest of this profile id (only his own scene replaces him). */
    public static boolean replaces(ServerPlayer p, String profile) {
        return active(p) && profile.equals(p.getPersistentData().getCompound(SCENE).getString("replace"));
    }

    static SectDisciple member(ServerLevel level, Vec3 near, String key) {
        List<SectDisciple> all = level.getEntitiesOfClass(SectDisciple.class, new AABB(BlockPos.containing(near)).inflate(160, 80, 160),
                d -> key.equals(d.memberKey()) && d.isAlive());
        SectDisciple best = null;
        for (SectDisciple d : all) if (best == null || d.distanceToSqr(near) < best.distanceToSqr(near)) best = d;
        return best;
    }

    // ------------------------------------------------------------------ fight

    public static boolean startFight(ServerPlayer p, String id) {
        var located = QuestLoader.fight(id);
        if (located == null || active(p) || !QuestService.passes(p, located.step().gate())) return false;
        QuestDef.Fight f = located.step();
        ServerLevel level = p.serverLevel();
        // the attackers come only where the protected person lies: at his quest spot, not wherever the player finds him
        if (f.protect().isPresent()) {
            SectDisciple who = member(level, p.position(), f.protect().get());
            if (who == null || !who.questHeld() || who.distanceToSqr(p) > 24 * 24) return false;
        }
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putLong("start", level.getGameTime());
        t.putString("dim", level.dimension().location().toString());
        ListTag foes = new ListTag();
        t.put("foes", foes);
        t.put("done", new ListTag());
        // the scene exists before the first foe joins the world, so the join check knows them
        p.getPersistentData().put(SCENE, t);
        int n = 0;
        for (String spec : f.foes()) {
            String[] parts = spec.split("\\*");
            ResourceLocation rl = ResourceLocation.tryParse(parts[0]);
            int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 1;
            if (rl == null || !BuiltInRegistries.ENTITY_TYPE.containsKey(rl)) continue;
            for (int i = 0; i < count; i++, n++) {
                double a = (n * 2.4D) + level.random.nextDouble();
                BlockPos want = BlockPos.containing(p.getX() + Math.cos(a) * 7, p.getY(), p.getZ() + Math.sin(a) * 7);
                BlockPos feet = ClueSites.freeFloor(level, want, 3);
                if (feet == null) feet = p.blockPosition();
                Entity e = BuiltInRegistries.ENTITY_TYPE.get(rl).create(level);
                if (!(e instanceof Mob mob)) continue;
                mob.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, level.random.nextFloat() * 360, 0);
                mob.setPersistenceRequired();
                mob.addTag(FOE_TAG);
                var dmg = mob.getAttribute(Attributes.ATTACK_DAMAGE);
                if (dmg != null && dmg.getBaseValue() > FOE_DAMAGE) dmg.setBaseValue(FOE_DAMAGE);
                f.foeName().ifPresent(k -> mob.setCustomName(Component.translatable(k)));
                mob.setTarget(p);
                level.addFreshEntity(mob);
                foes.add(NbtUtils.createUUID(mob.getUUID()));
            }
        }
        f.replace().ifPresent(prof -> t.putString("replace", prof));
        f.replace().ifPresent(prof -> level.getEntitiesOfClass(GuestFighter.class, p.getBoundingBox().inflate(32),
                g -> g.profile().id.equals(prof)).forEach(Entity::discard));
        f.protect().ifPresent(key -> {
            t.putString("protect", key);
            SectDisciple d = member(level, p.position(), key);
            Vec3 shelter = f.shelter().map(m -> QuestMarkers.resolve(level, m)).map(b -> Vec3.atBottomCenterOf(b)).orElse(null);
            if (d != null && shelter != null) d.escortGoal().begin(p, shelter, false);
        });
        f.shelter().ifPresent(m -> t.putString("shelter", m));
        return true;
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        if (p.tickCount % 10 == 3 && active(p)) tick(p);
        if (p.tickCount % 20 == 9) tickTimers(p);
    }

    /** One look at the running scene: win, escape or give up. */
    public static void tick(ServerPlayer p) {
        CompoundTag t = p.getPersistentData().getCompound(SCENE);
        var located = QuestLoader.fight(t.getString("id"));
        if (located == null) {
            p.getPersistentData().remove(SCENE);
            return;
        }
        QuestDef.Fight f = located.step();
        ServerLevel level = p.serverLevel();
        if (!level.dimension().location().toString().equals(t.getString("dim"))) {
            // the player left the scene's dimension: the scene is over, its foes go when they next load
            cancelAway(p, t);
            return;
        }
        int left = 0;
        ListTag done = t.getList("done", 11);
        for (var tag : t.getList("foes", 11)) {
            UUID id = NbtUtils.loadUUID(tag);
            if (isDone(done, id)) continue;
            Entity e = level.getEntity(id);
            if (e instanceof LivingEntity le && !le.isAlive()) {
                markDone(t, id);
                continue;
            }
            if (!(e instanceof LivingEntity le)) {
                // not found is not beaten: it may sit in an unloaded chunk
                left++;
                continue;
            }
            if (le.getHealth() <= le.getMaxHealth() * YIELD) {
                flee(level, le);
                markDone(t, id);
                continue;
            }
            left++;
        }
        SectDisciple prot = t.contains("protect") ? member(level, p.position(), t.getString("protect")) : null;
        if (left == 0) {
            finish(p, t, f.onWin(), "murim.scene.win");
            return;
        }
        if (prot != null && t.contains("shelter")) {
            BlockPos shelter = QuestMarkers.resolve(level, t.getString("shelter"));
            if (shelter != null && prot.distanceToSqr(Vec3.atBottomCenterOf(shelter)) <= (double) f.shelterRadius() * f.shelterRadius()) {
                discardFoes(level, t);
                finish(p, t, f.onEscape(), "murim.scene.escape");
                return;
            }
        }
        boolean lost = !p.isAlive() || t.contains("protect") && (prot == null || !prot.isAlive())
                || level.getGameTime() - t.getLong("start") > f.limitTicks();
        if (lost) {
            discardFoes(level, t);
            if (prot != null) {
                prot.heal(prot.getMaxHealth());
                prot.escortGoal().cancel();
            }
            p.getPersistentData().remove(SCENE);
            PlayerJournal.refuse(p, Component.translatable("murim.scene.retry").withStyle(ChatFormatting.GRAY));
        }
    }

    private static boolean isDone(ListTag done, UUID id) {
        for (var d : done) if (NbtUtils.loadUUID(d).equals(id)) return true;
        return false;
    }

    private static void markDone(CompoundTag t, UUID id) {
        ListTag done = t.getList("done", 11);
        if (!isDone(done, id)) {
            done.add(NbtUtils.createUUID(id));
            t.put("done", done);
        }
    }

    /** The player is in another dimension than the scene: end it, free the protected person, clean the foes that can be reached. */
    private static void cancelAway(ServerPlayer p, CompoundTag t) {
        var server = p.getServer();
        if (server != null) {
            var key = net.minecraft.resources.ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(t.getString("dim")));
            ServerLevel home = server.getLevel(key);
            if (home != null) {
                discardFoes(home, t);
                if (t.contains("protect")) {
                    SectDisciple d = member(home, p.position(), t.getString("protect"));
                    if (d != null) d.escortGoal().cancel();
                }
            }
        }
        p.getPersistentData().remove(SCENE);
    }

    /** A foe of a scene that is over (or never was this session) is removed when its chunk loads. */
    @SubscribeEvent
    static void onFoeJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide() || !event.loadedFromDisk() || !event.getEntity().getTags().contains(FOE_TAG)) return;
        var server = event.getEntity().getServer();
        if (server == null) return;
        UUID id = event.getEntity().getUUID();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CompoundTag t = p.getPersistentData().getCompound(SCENE);
            for (var tag : t.getList("foes", 11)) {
                if (NbtUtils.loadUUID(tag).equals(id) && !isDone(t.getList("done", 11), id)) return;
            }
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    static void onFoeDeath(net.neoforged.neoforge.event.entity.living.LivingDeathEvent event) {
        if (event.getEntity().level().isClientSide() || !event.getEntity().getTags().contains(FOE_TAG)) return;
        var server = event.getEntity().getServer();
        if (server == null) return;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            CompoundTag t = p.getPersistentData().getCompound(SCENE);
            for (var tag : t.getList("foes", 11)) {
                if (NbtUtils.loadUUID(tag).equals(event.getEntity().getUUID())) markDone(t, event.getEntity().getUUID());
            }
        }
    }

    private static void finish(ServerPlayer p, CompoundTag t, QuestDef.Effects e, String message) {
        p.getPersistentData().remove(SCENE);
        QuestService.apply(p, e);
        PlayerJournal.record(p, Component.translatable(message).withStyle(ChatFormatting.GOLD));
        if (t.contains("protect")) {
            SectDisciple d = member(p.serverLevel(), p.position(), t.getString("protect"));
            if (d != null) d.escortGoal().cancel();
        }
    }

    private static void flee(ServerLevel level, LivingEntity e) {
        level.sendParticles(ParticleTypes.POOF, e.getX(), e.getY() + 1, e.getZ(), 12, 0.3, 0.5, 0.3, 0.02);
        e.discard();
    }

    /** The player died in a fight scene: its foes must not linger (the scene itself does not carry over to the new body). */
    public static void abandon(ServerPlayer original) {
        CompoundTag t = original.getPersistentData().getCompound(SCENE);
        if (!t.isEmpty() && original.level() instanceof ServerLevel level) discardFoes(level, t);
    }

    /** Persistent keys that die with the body. */
    public static boolean diesWithBody(String key) {
        return SCENE.equals(key);
    }

    private static void discardFoes(ServerLevel level, CompoundTag t) {
        for (var tag : t.getList("foes", 11)) {
            Entity e = level.getEntity(NbtUtils.loadUUID(tag));
            if (e instanceof LivingEntity le && le.isAlive()) flee(level, le);
        }
    }

    // ------------------------------------------------------------------ escort

    public static boolean startEscort(ServerPlayer p, String id) {
        var located = QuestLoader.escort(id);
        if (located == null || !QuestService.passes(p, located.step().gate())) return false;
        QuestDef.Escort s = located.step();
        ServerLevel level = p.serverLevel();
        SectDisciple guide = member(level, p.position(), s.npc());
        if (guide == null) return false;
        Vec3 target;
        java.util.function.Supplier<Vec3> follow = null;
        if (s.toNpc().isPresent()) {
            SectDisciple to = member(level, p.position(), s.toNpc().get());
            if (to == null) return false;
            target = to.position();
            String key = s.toNpc().get();
            follow = () -> {
                SectDisciple d = member(level, guide.position(), key);
                return d == null ? null : d.position();
            };
        } else {
            BlockPos at = s.toMarker().map(m -> QuestMarkers.resolve(level, m)).orElse(null);
            if (at == null) return false;
            target = Vec3.atBottomCenterOf(at);
        }
        if (!guide.escortGoal().begin(p, target, false)) return false;
        if (follow != null) guide.escortGoal().follow(follow);
        guide.escortGoal().onArrival(() -> arrived(p, id));
        return true;
    }

    /** The escort reached its end (also called by the tests). */
    public static void arrived(ServerPlayer p, String id) {
        var located = QuestLoader.escort(id);
        if (located == null) return;
        QuestService.apply(p, located.step().effects());
        Quests.set(p, Quests.step(located.quest(), "arrived." + id));
    }

    // ------------------------------------------------------------------ timers

    static void tickTimers(ServerPlayer p) {
        var timers = QuestLoader.timers();
        if (timers.isEmpty()) return;
        CompoundTag t = p.getPersistentData().getCompound(TIMERS);
        boolean dirty = false;
        long now = p.serverLevel().getGameTime();
        for (var located : timers) {
            QuestDef.Timer tm = located.step();
            String done = Quests.step(located.quest(), "timer." + tm.id());
            if (Quests.has(p, done) || !Quests.has(p, tm.startFlag()) || !QuestService.passes(p, tm.gate())) continue;
            if (!t.contains(tm.id())) {
                t.putLong(tm.id(), now);
                dirty = true;
            }
            if (now - t.getLong(tm.id()) >= tm.ticks()) {
                Quests.set(p, done);
                QuestService.apply(p, tm.effects());
            }
        }
        if (dirty) p.getPersistentData().put(TIMERS, t);
    }

    /** Runs the timers now with the clock {@code ticks} later (tests). */
    public static void advanceTimersForTests(ServerPlayer p, long ticks) {
        CompoundTag t = p.getPersistentData().getCompound(TIMERS);
        for (String k : new ArrayList<>(t.getAllKeys())) t.putLong(k, t.getLong(k) - ticks);
        p.getPersistentData().put(TIMERS, t);
        tickTimers(p);
    }

    // ------------------------------------------------------------------ hand in

    public static boolean handIn(ServerPlayer p, String id) {
        var located = QuestLoader.handIn(id);
        if (located == null || !QuestService.passes(p, located.step().requires())) return false;
        QuestDef.HandIn h = located.step();
        String paid = QuestService.paidFlag(located.quest(), "hand_in." + id);
        if (Quests.has(p, paid)) return true;
        List<SectService.ItemNeed> needs = new ArrayList<>();
        for (String spec : h.takeItems()) {
            SectService.ItemNeed need = SectService.ItemNeed.parse(spec);
            if (need == null || need.count(p) < need.count()) {
                PlayerJournal.refuse(p, Component.translatable("murim.quest.missing").withStyle(ChatFormatting.GRAY));
                return false;
            }
            needs.add(need);
        }
        for (String clue : h.takeClues()) {
            // a clue item that was lost comes back (the porter brings it); a clue he never had cannot be handed in
            if (!Clues.carries(p, clue) && !(Clues.known(p, clue) && Clues.give(p, clue))) {
                PlayerJournal.refuse(p, Component.translatable("murim.quest.missing").withStyle(ChatFormatting.GRAY));
                return false;
            }
        }
        needs.forEach(n -> take(p, n));
        h.takeClues().forEach(c -> takeClue(p, c));
        Quests.set(p, Quests.step(located.quest(), "handed." + id));
        if (h.reward().isPresent()) QuestService.pay(p, located.quest(), "hand_in." + id, h.reward().get());
        else Quests.set(p, paid);
        return true;
    }

    private static void take(ServerPlayer p, SectService.ItemNeed need) {
        int left = need.count();
        for (ItemStack s : p.getInventory().items) {
            if (left <= 0) break;
            if (s.is(need.item())) {
                int n = Math.min(left, s.getCount());
                s.shrink(n);
                left -= n;
            }
        }
    }

    private static void takeClue(ServerPlayer p, String clue) {
        for (ItemStack s : p.getInventory().items) {
            if (s.getItem() instanceof ClueItem && clue.equals(ClueItem.clueOf(s))) {
                s.shrink(1);
                return;
            }
        }
    }
}
