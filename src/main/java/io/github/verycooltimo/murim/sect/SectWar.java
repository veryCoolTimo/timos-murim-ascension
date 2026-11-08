package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.GateDoorBlockEntity;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.sect.seal.PenanceEnforcement;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The sect against one player (docs/design/50-sect-war.md; author 22.10: «если ты ударишь — вся секта против тебя», a sandbox: the
 * mountain may be bled, and it answers).
 *
 * <ol>
 *   <li>A person of the sect struck outside a bout is hurt for real. The first light blow is a warning: he recoils and says a
 *       sharp word, nothing else happens. Newcomers receive two warnings in their first day. A repeated blow within {@link #WARN_WINDOW}, a blow of a technique
 *       or a killing is WAR. Everybody, the elders and the head too, forgives one light blow.</li>
 *   <li>War: the player is put out of the sect (no perks, no goals, no tasks), the gate is shut, the bell rings, fighters come
 *       at him, elders and the head with their full aura and strength, servants run and hide.</li>
 *   <li>The dead stay dead. Their slots of the roster are empty until a successor comes (a couple of days for a disciple or a
 *       servant, a week for an elder or the head); the slot keeps its key, bed and post, the person has a new name.</li>
 *   <li>Two days without a new killing a messenger waits at the gate: scaled silver or a voluntary repair delivery ends the war. Not if the head fell.</li>
 * </ol>
 * Bouts, lessons and the splash of somebody else's technique never start a war.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectWar {

    /** A second blow within this many ticks (5 minutes) after the warning is war. */
    public static final long WARN_WINDOW = 6000L;
    /** Days (sect day time) without a new killing before the messenger comes. */
    public static final long ATONE_AFTER = 2L * 24000L;
    /** A disciple or a servant is replaced this long after he died, and the new ones come this far apart. */
    public static final long DISCIPLE_REPLACE = 2L * 24000L;
    /** An elder, the mentor or the head is replaced this long after he died. */
    public static final long ELDER_REPLACE = 7L * 24000L;
    /** The price of peace, silver taels. */
    public static final int ATONE_COST = 8;
    public static final String ENEMY = "sect.enemy";
    public static final String WARNED = "sect.warned";
    public static final String SCAR = "war.scar";
    public static final String MESSENGER_TAG = "murim_messenger";

    /** Names a new person can carry, per pool (generation of the slot); {@code npc.murim.heir.<pool>.<n>}. */
    public static final int POOL_SIZE = 8;
    public static final List<String> POOLS = List.of("tae", "gyeong", "seo", "yul", "lay");

    public enum Verdict {
        /** The blow does nothing (the sect is not touched). */
        CANCEL,
        /** The blow lands in full, nothing follows (creative mode, a test dummy). */
        FREE,
        /** A light blow, capped; the player is warned. */
        WARN,
        /** War: every blow lands. */
        WAR
    }

    private SectWar() { }

    public static SectWarData data(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(SectWarData.FACTORY, SectWarData.NAME);
    }

    public static boolean atWar(ServerPlayer p) {
        return p.level() instanceof ServerLevel l && data(l).wars.containsKey(p.getUUID());
    }

    public static boolean permanent(ServerPlayer p) {
        return p.level() instanceof ServerLevel l && data(l).wars.getOrDefault(p.getUUID(), new SectWarData.War()).permanent;
    }

    public static boolean isDead(ServerLevel level, String key) {
        return data(level).dead.containsKey(key);
    }

    public static boolean elderOrHead(SectDisciple d) {
        return d.role() == SectRole.ELDER || d.role() == SectRole.LEADER || d.role() == SectRole.MENTOR;
    }

    // ------------------------------------------------------------------ the blow

    /** A blow of the player on a person of the sect outside any bout: what happens. */
    public static Verdict struck(ServerPlayer p, SectDisciple victim, DamageSource source, float amount) {
        if (!(victim.level() instanceof ServerLevel level) || p.isSpectator() || p.isCreative()) {
            return Verdict.FREE;
        }
        SectWarData d = data(level);
        UUID id = p.getUUID();
        if (d.wars.containsKey(id)) {
            return Verdict.WAR;
        }
        long now = level.getGameTime();
        Long warned = d.warnings.get(id);
        boolean recent = warned != null && now >= warned && now - warned <= WARN_WINDOW;
        // everyone, the mentor, the elders and the head too, forgives one light blow (release review 22.10)
        int hits = recent ? p.getPersistentData().getInt("murim_grace_hits") : 0;
        boolean technique = PenanceEnforcement.techniqueHit(p, source);
        // A light warning blow cannot finish an injured person; a lethal heavy blow is an assault.
        // See docs/design/guidance-fixes-1031.md: accidental blows get grace, killings retain consequences.
        boolean lethal = amount >= victim.getHealth() && amount > Math.max(1.0F, victim.getMaxHealth() * 0.1F);
        String why = technique ? "technique" : lethal ? "killing" : recent && (!newcomer(p, now) || hits >= 2) ? "second" : null;
        if (why != null && io.github.verycooltimo.murim.Config.SECT_WAR_ENABLED.get()) {
            declare(p, victim, why);
            return Verdict.WAR;
        }
        warn(p, victim, now);
        return Verdict.WARN;
    }

    static boolean newcomer(ServerPlayer p, long now) {
        if (!SectService.state(p).member()) return SectService.state(p).entry(SCAR, 0) == 0;
        var tag = p.getPersistentData();
        return tag.contains("murim_joined_at") && now >= tag.getLong("murim_joined_at")
                && now - tag.getLong("murim_joined_at") < 24000L;
    }

    /** The first light blow: the victim recoils and speaks sharply. */
    static void warn(ServerPlayer p, SectDisciple victim, long now) {
        ServerLevel level = (ServerLevel) victim.level();
        SectWarData d = data(level);
        Long previous = d.warnings.get(p.getUUID());
        int hits = previous != null && now >= previous && now - previous <= WARN_WINDOW
                ? p.getPersistentData().getInt("murim_grace_hits") : 0;
        p.getPersistentData().putInt("murim_grace_hits", hits + 1);
        d.warnings.put(p.getUUID(), now);
        d.setDirty();
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(WARNED));
        victim.knockback(0.5D, p.getX() - victim.getX(), p.getZ() - victim.getZ());
        victim.faceEntity(p, 90.0F);
        victim.gesture("shake");
        say(victim, "murim.sect.war.warn", 3);
        PlayerJournal.record(p, Component.translatable("murim.guidance.war.warning", victim.getName()));
    }

    /** The warning is old: forget it (its flag too). */
    static void expireWarnings(ServerLevel level, long now) {
        SectWarData d = data(level);
        if (d.warnings.isEmpty()) return;
        var it = d.warnings.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            if (now < e.getValue() || now - e.getValue() > WARN_WINDOW) {
                ServerPlayer p = level.getServer().getPlayerList().getPlayer(e.getKey());
                if (p != null) p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(WARNED));
                it.remove();
                d.setDirty();
            }
        }
    }

    // ------------------------------------------------------------------ war

    /** Starts the war (nothing happens when the player is at war already). {@code why}: elder, technique, second, kill. */
    public static void declare(ServerPlayer p, SectDisciple cause, String why) {
        if (!io.github.verycooltimo.murim.Config.SECT_WAR_ENABLED.get()) return;
        if (!(p.level() instanceof ServerLevel level) || p.isCreative() || p.isSpectator()) return;
        SectWarData d = data(level);
        if (d.wars.containsKey(p.getUUID())) return;
        SectWarData.War w = new SectWarData.War();
        w.since = level.getDayTime();
        w.lastKill = w.since;
        w.wasMember = SectService.state(p).member();
        d.wars.put(p.getUUID(), w);
        d.warnings.remove(p.getUUID());
        d.setDirty();
        MurimMod.LOGGER.info("Sect war: {} against the mountain ({})", p.getGameProfile().getName(), why);
        expel(p);
        PlayerJournal.record(p, Component.translatable("murim.sect.war.declared." + ("elder".equals(why) ? "elder" : "other"))
                .withStyle(ChatFormatting.DARK_RED));
        SectLife.alarmBell(level);
        closeGate(level);
        Vec3 at = cause != null ? cause.position() : p.position();
        mobilize(level, p, at, true);
        // the guard on the watch rings, a voice carries it
        for (SectDisciple g : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(96.0D),
                x -> x.isAlive() && x.member().isPresent() && SectWatch.guards(x))) {
            say(g, "murim.sect.war.bell", 2);
            break;
        }
    }

    /** Puts the player out of the sect: no member, no tasks in progress, no standing; the flag {@link #ENEMY}. */
    static void expel(ServerPlayer p) {
        SectState s = SectService.state(p);
        Set<String> flags = new HashSet<>(s.flags());
        flags.removeIf(f -> f.startsWith("request.") && f.endsWith(".active"));
        flags.remove(WARNED);
        flags.add(ENEMY);
        p.setData(ModAttachments.SECT, new SectState(SectState.VERSION, false, 0, flags, 0, 0, s.journal()));
        if (p.getServer() != null) {
            var contracts = SectContracts.data(p.getServer());
            if (SectContracts.active(contracts, p.getUUID()) != null) SectContracts.leave(p);
        }
        SectTablet.touch(p);
    }

    /** Everybody who hears of it: fighters turn on the player, servants run. {@code all}: even far away (the first alarm). */
    static void mobilize(ServerLevel level, ServerPlayer p, Vec3 at, boolean all) {
        mobilize(level, p, at, all, false);
    }

    /** {@code global}: every loaded person of the sect, wherever he stands (a player who logs in at war). */
    static void mobilize(ServerLevel level, ServerPlayer p, Vec3 at, boolean all, boolean global) {
        double range = all ? 96.0D : 48.0D;
        long now = level.getGameTime();
        java.util.function.Predicate<SectDisciple> fit = x -> x.isAlive() && x.member().isPresent() && !x.isMessenger();
        List<? extends SectDisciple> people = global
                ? level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(SectDisciple.class), fit)
                : level.getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(range, 40.0D, range), fit);
        for (SectDisciple d : people) {
            SectRoster m = d.member().get();
            if (m.lay()) {
                if (d.alarmGoal().mode(now) == null) {
                    d.alarmGoal().assign(SectAlarmGoal_FLEE(), p.position(), SectAlarm.fleePoint(level, d, p.position()), now + 600);
                    if (all) say(d, "murim.sect.war.flee", 3);
                }
                continue;
            }
            boolean sees = d.getSensing().hasLineOfSight(p) || d.distanceTo(p) < 24.0D;
            if (!all && !sees) continue;
            if (d.getTarget() != p || !d.warring()) {
                boolean first = !d.warring();
                d.warOn(p);
                d.warStrength(true);
                if (first && all) {
                    say(d, d.role() == SectRole.LEADER ? "murim.sect.war.head" : d.role() == SectRole.ELDER ? "murim.sect.war.elder"
                            : "murim.sect.war.cry", d.role() == SectRole.LEADER || d.role() == SectRole.ELDER ? 2 : 4);
                }
            }
        }
    }

    private static io.github.verycooltimo.murim.entity.SectAlarmGoal.Mode SectAlarmGoal_FLEE() {
        return io.github.verycooltimo.murim.entity.SectAlarmGoal.Mode.FLEE;
    }

    /** The big gate stays shut while the mountain is at war. */
    static void closeGate(ServerLevel level) {
        SectLayout layout = SectLife.layout(level);
        Vec3 c = layout == null ? null : layout.at("sect_gate", 0.0D, 0.0D);
        if (c == null || !level.isLoaded(BlockPos.containing(c))) return;
        for (BlockPos pos : BlockPos.betweenClosed(BlockPos.containing(c.x - 8, c.y - 3, c.z - 8), BlockPos.containing(c.x + 8, c.y + 8, c.z + 8))) {
            var state = level.getBlockState(pos);
            if (state.getBlock() instanceof GateDoorBlock gate && state.getValue(GateDoorBlock.OPEN)
                    && level.getBlockEntity(pos) instanceof GateDoorBlockEntity be && !be.busy()) {
                gate.toggle(level, pos.immutable(), null);
            }
        }
    }

    // ------------------------------------------------------------------ the dead

    /** A person of the sect died ({@code SectDisciple#die}). */
    public static void died(SectDisciple npc, DamageSource source) {
        if (!(npc.level() instanceof ServerLevel level) || npc.isMessenger()) return;
        Optional<SectRoster> m = npc.member();
        if (m.isEmpty()) return;
        ServerPlayer killer = source.getEntity() instanceof ServerPlayer sp ? sp : npc.getKillCredit() instanceof ServerPlayer sp2 ? sp2 : null;
        if (killer == null || killer.isCreative() || killer.isSpectator()) return;
        SectWarData d = data(level);
        long now = level.getDayTime();
        boolean leadership = m.get().generation() == 0 || m.get().role() == SectRole.MENTOR;
        d.dead.put(m.get().key(), new SectWarData.Dead(now, now + (leadership ? ELDER_REPLACE : DISCIPLE_REPLACE)));
        d.setDirty();
        declare(killer, npc, "kill");
        SectWarData.War w = d.wars.get(killer.getUUID());
        if (w != null) {
            w.lastKill = now;
            w.kills++;
            if (npc.role() == SectRole.LEADER && !w.permanent) {
                w.permanent = true;
                discardMessengers(level, killer.getUUID());
                PlayerJournal.record(killer, Component.translatable("murim.sect.war.head_fell").withStyle(ChatFormatting.DARK_RED));
            }
            d.setDirty();
        }
        MurimMod.LOGGER.info("Sect war: {} fell by {}, successor due day {}", m.get().key(), killer.getGameProfile().getName(),
                (now + (leadership ? ELDER_REPLACE : DISCIPLE_REPLACE)) / 24000L);
    }

    /** The successors arrive. {@code now} is the sect day time. */
    static void arrivals(ServerLevel level, long now) {
        SectWarData d = data(level);
        if (d.dead.isEmpty()) return;
        List<String> due = new ArrayList<>();
        for (var e : d.dead.entrySet()) {
            if (e.getValue().due() <= now) due.add(e.getKey());
        }
        for (String key : due) {
            Optional<SectRoster> m = SectRoster.of(key);
            if (m.isEmpty()) {
                d.dead.remove(key);
                d.setDirty();
                continue;
            }
            boolean leadership = m.get().generation() == 0 || m.get().role() == SectRole.MENTOR;
            if (!leadership && now - d.lastArrival < DISCIPLE_REPLACE) continue;
            d.dead.remove(key);
            d.names.put(key, pickName(d, m.get()));
            if (!leadership) d.lastArrival = now;
            d.setDirty();
            SectLife.data(level).unplace("npc:" + key);
            MurimMod.LOGGER.info("Sect war: a successor arrives for {} as {}", key, d.names.get(key));
        }
    }

    /** The pool entry for a new person of this slot: the next one nobody carries now. */
    static int pickName(SectWarData d, SectRoster m) {
        String pool = pool(m);
        Set<Integer> used = new HashSet<>();
        d.names.forEach((k, n) -> SectRoster.of(k).filter(o -> pool(o).equals(pool)).ifPresent(o -> used.add(n)));
        for (int i = 0; i < POOL_SIZE; i++) {
            int n = Math.floorMod(d.cursor++, POOL_SIZE);
            if (!used.contains(n)) return n;
        }
        return Math.floorMod(d.cursor++, POOL_SIZE);
    }

    public static String pool(SectRoster m) {
        return m.lay() ? "lay" : switch (m.generation()) {
            case 0 -> "tae";
            case 1 -> "gyeong";
            case 2 -> "seo";
            default -> "yul";
        };
    }

    /** The name of the person who now stands in the slot (null — the original one). */
    public static Component nameOf(ServerLevel level, SectRoster m) {
        Integer n = data(level).names.get(m.key());
        return n == null ? null : Component.translatable("npc.murim.heir." + pool(m) + "." + n);
    }

    /** A newly stood-up NPC carries the name of the slot's current person. */
    public static void dress(ServerLevel level, SectDisciple npc) {
        npc.member().ifPresent(m -> {
            Component name = nameOf(level, m);
            if (name != null) npc.setCustomName(name);
        });
    }

    // ------------------------------------------------------------------ the way back

    /** The messenger offers voluntary silver or repair work in the dialogue UI. */
    public static void interact(ServerPlayer p, SectDisciple messenger) {
        if (!(p.level() instanceof ServerLevel level)) return;
        UUID owner = messenger.messengerFor();
        if (owner == null || !owner.equals(p.getUUID()) || !atWar(p)) {
            say(messenger, "murim.sect.war.messenger.other", 2);
            return;
        }
        DialogueService.openDialogue(p, messenger, net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "war_terms"), "terms");
    }

    public static int atonementCost(ServerPlayer p) {
        int silver = count(p);
        return silver == 0 ? 0 : Math.min(ATONE_COST, Math.max(1, (silver + 1) / 2));
    }

    public static Component terms(ServerPlayer p) {
        int cost = atonementCost(p);
        return Component.translatable(cost == 0 ? "murim.guidance.war.work_terms" : "murim.guidance.war.terms", cost);
    }

    public static boolean pay(ServerPlayer p, SectDisciple messenger, boolean labour) {
        if (!p.getUUID().equals(messenger.messengerFor()) || !atWar(p) || permanent(p)) return false;
        if (labour) {
            SectService.ItemNeed stone = SectService.ItemNeed.parse("minecraft:cobblestone*16");
            if (stone == null || stone.count(p) < stone.count()) return false;
            stone.take(p);
        } else {
            int cost = atonementCost(p);
            if (cost <= 0) return false;
            take(p, cost);
        }
        atone(p);
        return true;
    }

    private static int count(ServerPlayer p) {
        int n = 0;
        for (var stacks : List.of(p.getInventory().items, p.getInventory().offhand))
            for (ItemStack s : stacks) if (s.is(ModItems.SILVER_TAEL.get())) n += s.getCount();
        return n;
    }

    private static void take(ServerPlayer p, int cost) {
        int left = cost;
        for (var stacks : List.of(p.getInventory().items, p.getInventory().offhand)) for (ItemStack s : stacks) {
            if (left > 0 && s.is(ModItems.SILVER_TAEL.get())) {
                int k = Math.min(left, s.getCount());
                s.shrink(k);
                left -= k;
            }
        }
    }

    /** Peace: the war ends; a former member is taken back at the very bottom with a scar. */
    public static void atone(ServerPlayer p) {
        if (!(p.level() instanceof ServerLevel level)) return;
        SectWarData d = data(level);
        SectWarData.War w = d.wars.remove(p.getUUID());
        if (w == null) return;
        d.setDirty();
        end(level, p.getUUID());
        SectState s = SectService.state(p);
        Set<String> flags = new HashSet<>(s.flags());
        flags.remove(ENEMY);
        long scar = s.entry(SCAR, 0) + 1;
        if (w.wasMember) {
            flags.add("sect.scar");
            // readmitted at the very bottom: the lessons, the head's approval and the granted step are earned again
            // (other flags, the quest history, stay)
            flags.remove(SectStanding.LESSON_ONE);
            flags.remove(SectStanding.LESSON_TWO);
            flags.remove(SectStanding.APPROVAL);
            p.setData(ModAttachments.SECT, new SectState(SectState.VERSION, true, SectState.THIRD, flags, 0, 0, s.journal())
                    .record(SectBouts.GRANTED, 0L).record(SCAR, scar));
            PlayerJournal.record(p, Component.translatable("murim.sect.war.peace").withStyle(ChatFormatting.GREEN));
            // Peace restores access. The voluntary delivery is the chore; no forced confinement.
        } else {
            p.setData(ModAttachments.SECT, new SectState(SectState.VERSION, false, 0, flags, 0, 0, s.journal()).record(SCAR, scar));
            PlayerJournal.record(p, Component.translatable("murim.sect.war.pardon").withStyle(ChatFormatting.GREEN));
        }
        SectTablet.touch(p);
        MurimMod.LOGGER.info("Sect war: {} atoned (member again: {})", p.getGameProfile().getName(), w.wasMember);
    }

    /** The mountain stands down. (Once per war: a scan of the loaded sect people is fine here.) */
    static void end(ServerLevel level, UUID player) {
        for (SectDisciple d : allLoaded(level, x -> true)) {
            if (d.isMessenger()) {
                if (player.equals(d.messengerFor())) d.discard();
            } else if (d.warring() && (d.getTarget() == null || d.getTarget().getUUID().equals(player))) {
                d.peace();
            }
        }
    }

    static void discardMessengers(ServerLevel level, UUID player) {
        for (SectDisciple d : allLoaded(level, SectDisciple::isMessenger)) {
            if (player.equals(d.messengerFor())) d.discard();
        }
    }

    private static List<? extends SectDisciple> allLoaded(ServerLevel level, java.util.function.Predicate<SectDisciple> test) {
        return level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(SectDisciple.class), test);
    }

    /** The messenger stands outside the gate. */
    static SectDisciple spawnMessenger(ServerLevel level, ServerPlayer p) {
        SectLayout layout = SectLife.layout(level);
        Vec3 at = layout == null ? null : SectWatch.outsideGate(layout, p);
        // the gate is not in a loaded chunk (the player is elsewhere): the messenger finds him instead
        if (at == null || !level.isPositionEntityTicking(BlockPos.containing(at)) || at.distanceToSqr(p.position()) > 96.0D * 96.0D) {
            at = p.position().add(3.0D, 0.0D, 0.0D);
        }
        SectDisciple m = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
        m.setRole(SectRole.DISCIPLE);
        m.setMessengerFor(p.getUUID());
        m.setCustomName(Component.translatable("npc.murim.messenger"));
        m.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        // the war's single messenger identity is written before he joins: the join check discards any other
        SectWarData.War war = data(level).wars.get(p.getUUID());
        if (war != null) {
            war.messengerId = m.getUUID();
            war.messenger = true;
            data(level).setDirty();
        }
        level.addFreshEntity(m);
        PlayerJournal.record(p, Component.translatable("murim.sect.war.messenger").withStyle(ChatFormatting.GOLD));
        return m;
    }

    // ------------------------------------------------------------------ the clock

    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        int tick = event.getServer().getTickCount();
        if (tick % 20 != 9) return;
        ServerLevel level = event.getServer().overworld();
        process(level, level.getDayTime(), level.getGameTime(), tick % 100 == 9);
    }

    /** One second of the sect war (tests call it with their own clock). */
    static void process(ServerLevel level, long dayTime, long gameTime, boolean slow) {
        process(level, dayTime, gameTime, slow, id -> level.getServer().getPlayerList().getPlayer(id));
    }

    static void process(ServerLevel level, long dayTime, long gameTime, boolean slow,
                        java.util.function.Function<UUID, ServerPlayer> players) {
        SectWarData d = data(level);
        expireWarnings(level, gameTime);
        arrivals(level, dayTime);
        if (d.wars.isEmpty()) return;
        for (var e : new ArrayList<>(d.wars.entrySet())) {
            ServerPlayer p = players.apply(e.getKey());
            if (p == null || p.level() != level || !p.isAlive()) continue;
            SectWarData.War w = e.getValue();
            if (SectService.state(p).member()) expel(p);
            mobilize(level, p, p.position(), false);
            if (slow) closeGate(level);
            if (!w.permanent && dayTime - w.lastKill >= ATONE_AFTER && !hasMessenger(level, p)) {
                spawnMessenger(level, p);
            }
        }
    }

    /** For tests and rare calls: every loaded messenger of this player. */
    static List<SectDisciple> messengers(ServerLevel level, UUID player) {
        return new ArrayList<>(allLoaded(level, x -> x.isMessenger() && player.equals(x.messengerFor())));
    }

    /** The messenger of this war, by his stored id (no world scan); falls back to a small scan round the gate. */
    private static boolean hasMessenger(ServerLevel level, ServerPlayer p) {
        SectWarData.War w = data(level).wars.get(p.getUUID());
        if (w == null) return false;
        if (w.messengerId != null) {
            if (level.getEntity(w.messengerId) instanceof SectDisciple m && m.isAlive() && m.isMessenger()) return true;
            SectLayout layout = SectLife.layout(level);
            Vec3 c = layout == null ? null : layout.at("sect_gate", 0.0D, 0.0D);
            Vec3 centre = c != null ? c : p.position();
            boolean found = !level.getEntitiesOfClass(SectDisciple.class, new net.minecraft.world.phys.AABB(centre, centre).inflate(256.0D),
                    x -> x.isAlive() && x.isMessenger() && p.getUUID().equals(x.messengerFor())).isEmpty();
            if (found) return true;
            // not in the loaded world: he is gone (or sleeps in an unloaded chunk; a new one will come)
            w.messengerId = null;
            w.messenger = false;
            data(level).setDirty();
        }
        return false;
    }

    /** True when this messenger is still the one persisted for a running war. */
    static boolean current(ServerLevel level, SectDisciple m) {
        UUID owner = m.messengerFor();
        SectWarData.War w = owner == null ? null : data(level).wars.get(owner);
        return w != null && !w.permanent && m.getUUID().equals(w.messengerId);
    }

    /** A messenger that loads after the war ended, or after a newer one was sent, is not needed: he goes (no duplicates). */
    @SubscribeEvent
    static void onMessengerJoin(net.neoforged.neoforge.event.entity.EntityJoinLevelEvent event) {
        if (event.getLevel() instanceof ServerLevel level && event.getEntity() instanceof SectDisciple m && m.isMessenger()
                && level.getServer() != null && !current(level, m)) {
            event.setCanceled(true);
        }
    }

    /** A player who logs in at war: the whole loaded mountain turns on him, not only those who see him. */
    @SubscribeEvent
    static void onLogin(net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer p && p.level() instanceof ServerLevel level && atWar(p)) {
            mobilize(level, p, p.position(), true, true);
        }
    }

    // ------------------------------------------------------------------ voices

    /** A line over his head, one of {@code n} under {@code base.<i>}. */
    static void say(SectDisciple d, String base, int n) {
        String key = base + "." + d.getRandom().nextInt(n);
        SectChatter.send(d, key, Component.translatable(key));
    }
}
