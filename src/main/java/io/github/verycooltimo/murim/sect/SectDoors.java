package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.decor.GateDoorBlock;
import io.github.verycooltimo.murim.decor.GateDoorBlockEntity;
import io.github.verycooltimo.murim.decor.HallGrandDoorBlock;
import io.github.verycooltimo.murim.decor.HallGrandDoorBlockEntity;
import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Doors that sect people opened (author 17.10: «двери не забывали закрывать»). Every door an NPC opens is registered
 * here; ordinary doors close only after their opener crosses. Player-opened house doors are never adopted.
 * Grand doors wait for a duty keeper or passer; night cleanup never overrides player safety. Tracking is server-only and transient;
 * existing block state / block entity updates synchronize the actual door (decor/HallGrandDoorBlock.java#setOpen).
 */
@net.neoforged.fml.common.EventBusSubscriber(modid = io.github.verycooltimo.murim.MurimMod.MODID)
public final class SectDoors {
    /** Ticks without a living thing near the door before it is closed (1 s). */
    public static final long EMPTY_TICKS = 20L;

    /** Ticks a door found open (not opened by a person we saw) must stand unattended before it is shut (10 s). */
    public static final long UNATTENDED_TICKS = 200L;
    /** "Nobody near" for such a door, blocks. */
    public static final double UNATTENDED_RADIUS = 4.0D;
    /** Somebody standing this close in front of a door (a body-width from its cells) is about to go through it: the door waits for him. */
    public static final double IN_FRONT = 1.2D;
    /**
     * ... but only this long (10 s): the guards of the sect gate stand one cell inside it all day on their posts and kept it open for ever
     * (sect check 24.10, real layout: the gate was found open at noon, at dusk and after the player had gone through). Somebody IN the
     * cells of the door, a player near it, or a walker coming up holds it as long as he is there.
     */
    public static final long IN_FRONT_HOLD = 200L;

    private static final class Entry {
        java.lang.ref.WeakReference<SectDisciple> opener;
        double openedSide;
        boolean passed;
        long lastBusy;
        long lastNear;
        /** Found open by the safety net, not opened by a person we watched. */
        boolean adopted;
        /** Since when somebody has stood in front of the door without going through it (0: nobody does). */
        long frontSince;
    }

    private static final Map<Level, Map<BlockPos, Entry>> PER_LEVEL = new WeakHashMap<>();

    private SectDoors() {
    }

    private static Map<BlockPos, Entry> of(Level level) {
        return PER_LEVEL.computeIfAbsent(level, l -> new HashMap<>());
    }

    /** A person opened the door whose base block is {@code base}. */
    public static void mark(Level level, BlockPos base, long now) {
        Entry e = of(level).computeIfAbsent(base.immutable(), p -> new Entry());
        e.lastBusy = now;
        e.lastNear = now;
        e.adopted = false;
        e.opener = null;
        e.passed = false;
    }

    /** House doors belong to their opener, until he has actually crossed their plane. */
    public static void mark(SectDisciple npc, BlockPos base, long now) {
        mark(npc.level(), base, now);
        Entry e = of(npc.level()).get(base);
        e.opener = new java.lang.ref.WeakReference<>(npc);
        e.passed = false;
        e.openedSide = side(npc, base, npc.level().getBlockState(base));
    }

    private static double side(SectDisciple npc, BlockPos base, BlockState state) {
        var f = state.getValue(DoorBlock.FACING);
        return f.getAxis() == net.minecraft.core.Direction.Axis.X ? npc.getX() - base.getX() - 0.5D
                : npc.getZ() - base.getZ() - 0.5D;
    }

    /** Player interaction cancels an ordinary door's NPC ownership, including close/reopen between sweeps. */
    public static void playerUsed(Level level, BlockPos pos, BlockState state) {
        if (!level.isClientSide && state.getBlock() instanceof DoorBlock) {
            BlockPos base = state.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER ? pos.below() : pos;
            of(level).remove(base);
        }
    }

    /** Register a door found open with no person known to have opened it (the safety net, tests). */
    public static void markUnattended(Level level, BlockPos base, long now) {
        mark(level, base, now);
        Entry e = of(level).get(base.immutable());
        e.adopted = true;
        e.opener = null;
    }

    @net.neoforged.bus.api.SubscribeEvent
    static void playerClick(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.RightClickBlock event) {
        playerUsed(event.getLevel(), event.getPos(), event.getLevel().getBlockState(event.getPos()));
    }

    /** Number of doors waiting to be closed (tests). */
    public static int tracked(Level level) {
        return of(level).size();
    }

    /** How often the doors beside the people are looked at for ones nobody registered (after a restart), ticks. */
    static final long ADOPT_EVERY = 200L;
    private static final Map<Level, Long> ADOPTED = new WeakHashMap<>();

    /**
     * The registry is lost on a restart, so a door that was open when the world was saved is loaded open and nobody would shut
     * it (holes review npc-8). Every {@link #ADOPT_EVERY} ticks the open doors within 8 blocks of a sect person are registered
     * as unattended; the sweep waits ten empty seconds and a passing NPC. Untracked gates belong to the gatekeeper.
     * Returns how many were adopted.
     */
    static int adopt(Level level) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel sl)) {
            return 0;
        }
        long now = level.getGameTime();
        Long last = ADOPTED.get(level);
        if (last != null && now - last < ADOPT_EVERY) {
            return 0;
        }
        ADOPTED.put(level, now);
        Map<BlockPos, Entry> reg = of(level);
        int n = 0;
        // people standing in the same 4x4 cell see the same doors: scan each cell once per sweep (the baked layout's doors are
        // already covered by adoptLayout, this is only the net for doors beside people)
        java.util.Set<Long> cells = new java.util.HashSet<>();
        for (var npc : sl.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(io.github.verycooltimo.murim.entity.SectDisciple.class),
                d -> d.isAlive())) {
            BlockPos at = npc.blockPosition();
            if (!cells.add(((long) (at.getX() >> 2) << 32) ^ ((at.getZ() >> 2) & 0xFFFFFFFFL) ^ ((long) (at.getY() >> 2) << 54))) {
                continue;
            }
            for (BlockPos p : BlockPos.betweenClosed(at.offset(-8, -1, -8), at.offset(8, 2, 8))) {
                if (!level.isLoaded(p)) {
                    continue;
                }
                BlockState s = level.getBlockState(p);
                BlockPos base;
                if (s.getBlock() instanceof HallGrandDoorBlock && s.getValue(HallGrandDoorBlock.OPEN)) {
                    base = HallGrandDoorBlock.origin(p, s);
                } else {
                    continue;
                }
                if (!reg.containsKey(base)) {
                    mark(level, base, now);
                    reg.get(base).adopted = true;
                    n++;
                }
            }
        }
        return n;
    }

    /**
     * Safety net for doors no person is near (night, after a restart, a player who left one open): the open leaves of the layout's
     * grand hall doors are registered as unattended. Ordinary house doors are never adopted; the main gate is kept by the gatekeeper.
     */
    public static int adoptLayout(Level level, io.github.verycooltimo.murim.sect.SectLayoutFile file) {
        if (level.isClientSide || file == null) {
            return 0;
        }
        long now = level.getGameTime();
        Map<BlockPos, Entry> reg = of(level);
        int n = 0;
        for (var d : file.doors()) {
            if (!level.isLoaded(d.min())) {
                continue;
            }
            BlockPos lo = new BlockPos(Math.min(d.min().getX(), d.max().getX()), Math.min(d.min().getY(), d.max().getY()), Math.min(d.min().getZ(), d.max().getZ()));
            BlockPos hi = new BlockPos(Math.max(d.min().getX(), d.max().getX()), Math.max(d.min().getY(), d.max().getY()) + 1, Math.max(d.min().getZ(), d.max().getZ()));
            for (BlockPos p : BlockPos.betweenClosed(lo, hi)) {
                BlockState s = level.getBlockState(p);
                BlockPos base;
                if (s.getBlock() instanceof HallGrandDoorBlock && s.getValue(HallGrandDoorBlock.OPEN)) {
                    base = HallGrandDoorBlock.origin(p, s);
                } else if (s.getBlock() instanceof GateDoorBlock && s.getValue(GateDoorBlock.OPEN) && night(level)) {
                    base = GateDoorBlock.origin(p, s);
                } else {
                    continue;
                }
                if (!reg.containsKey(base)) {
                    mark(level, base, now);
                    reg.get(base).adopted = true;
                    n++;
                }
            }
        }
        return n;
    }

    /** True in the dark half of the day (hall doors are shut then). */
    static boolean night(Level level) {
        long t = Math.floorMod(level.getDayTime(), 24000L);
        return t >= 13000L && t < 23000L;
    }

    /** People who keep a door open: players and every mob (sect people), not armour stands or statues. */
    private static boolean person(LivingEntity e) {
        return e.isAlive() && !e.isSpectator() && (e instanceof net.minecraft.world.entity.player.Player || e instanceof net.minecraft.world.entity.Mob);
    }

    /** The passage of the door: the cells a person stands in to use it, plus a margin. */
    private static AABB doorway(Level level, BlockPos base, BlockState s, double margin) {
        if (s.getBlock() instanceof HallGrandDoorBlock) {
            var f = s.getValue(HallGrandDoorBlock.FACING);
            BlockPos far = HallGrandDoorBlock.partPos(base, f, HallGrandDoorBlock.WIDTH - 1, HallGrandDoorBlock.HEIGHT - 1);
            return new AABB(base).minmax(new AABB(far)).inflate(margin, 0.0D, margin);
        }
        if (s.getBlock() instanceof GateDoorBlock) {
            var f = s.getValue(GateDoorBlock.FACING);
            BlockPos far = GateDoorBlock.partPos(base, f, 3, 3);
            return new AABB(base).minmax(new AABB(far)).inflate(margin, 0.0D, margin);
        }
        return new AABB(base).expandTowards(0.0D, 1.0D, 0.0D).inflate(margin, 0.0D, margin);
    }

    /** Nearest awake passer within eight blocks of the passage. Safety is checked before selecting him. */
    private static SectDisciple passer(Level level, BlockPos base, BlockState state) {
        // API already used in technique/BehaviorExecutor.java: AABB#getCenter, Vec3#distanceToSqr.
        var centre = doorway(level, base, state, 0.0D).getCenter();
        return level.getEntitiesOfClass(SectDisciple.class, doorway(level, base, state, 8.0D),
                        d -> person(d) && !d.isSleeping()
                                && (d.role() == SectRole.GATEKEEPER || d.doingKind() == SectSchedule.Kind.GUARD
                                    || !d.getNavigation().isDone() || d.walking().goal() != null))
                .stream().min(java.util.Comparator.comparingDouble((SectDisciple d) -> d.position().distanceToSqr(centre))).orElse(null);
    }

    /** Close every registered door that has been empty long enough. Call a few times a second. */
    public static void sweep(Level level) {
        if (level.isClientSide) {
            return;
        }
        long now = level.getGameTime();
        adopt(level);
        Iterator<Map.Entry<BlockPos, Entry>> it = of(level).entrySet().iterator();
        while (it.hasNext()) {
            var me = it.next();
            BlockPos base = me.getKey();
            if (!level.isLoaded(base)) {
                continue;
            }
            BlockState s = level.getBlockState(base);
            boolean open;
            boolean swinging = false;
            if (s.getBlock() instanceof DoorBlock && s.is(BlockTags.WOODEN_DOORS)) {
                open = s.getValue(DoorBlock.OPEN);
            } else if (s.getBlock() instanceof GateDoorBlock) {
                open = s.getValue(GateDoorBlock.OPEN);
                swinging = level.getBlockEntity(base) instanceof GateDoorBlockEntity be && be.busy();
            } else if (s.getBlock() instanceof HallGrandDoorBlock) {
                open = s.getValue(HallGrandDoorBlock.OPEN);
                swinging = level.getBlockEntity(base) instanceof HallGrandDoorBlockEntity be && be.busy();
            } else {
                it.remove();
                continue;
            }
            // Root cause of the doors left open (21.10): the big doors set OPEN only at the END of the opening swing, so a
            // sweep that ran during the swing saw "not open" and dropped the entry; nobody ever closed that door.
            if (swinging) {
                me.getValue().lastBusy = now;
                continue;
            }
            if (!open) {
                it.remove();
                continue;
            }
            Entry en = me.getValue();
            if (s.getBlock() instanceof DoorBlock) {
                SectDisciple owner = en.opener == null ? null : en.opener.get();
                if (owner == null || !owner.isAlive()) { it.remove(); continue; }
                if (side(owner, base, s) * en.openedSide < -0.16D) en.passed = true;
                if (!en.passed) continue;
            }
            boolean follower = false;
            AABB wide = doorway(level, base, s, 3.0D);
            for (var d : level.getEntitiesOfClass(io.github.verycooltimo.murim.entity.SectDisciple.class, wide, LivingEntity::isAlive)) {
                if (!d.getNavigation().isDone()) {
                    follower = true;
                    break;
                }
            }
            // IN the doorway = his middle is inside the cells of the door: the guards of the sect gate stand at the edge of the cell behind it,
            // their bodies overlap it by a hand (found in the real run: the gate stood open for 30 s beside two guards on their posts)
            AABB cells = doorway(level, base, s, 0.0D);
            boolean inDoorway = level.getEntitiesOfClass(LivingEntity.class, cells, SectDoors::person).stream()
                    .anyMatch(e -> cells.contains(e.getX(), e.getY() + 0.1D, e.getZ()));
            boolean inFront = !inDoorway && !level.getEntitiesOfClass(LivingEntity.class, doorway(level, base, s, IN_FRONT), SectDoors::person).isEmpty();
            if (inFront) {
                if (en.frontSince == 0L) {
                    en.frontSince = now;
                }
            } else {
                en.frontSince = 0L;
            }
            if (inDoorway || inFront && now - en.frontSince <= IN_FRONT_HOLD || follower) {
                en.lastBusy = now;
                en.lastNear = now;
                continue;
            }
            var nearby = level.getEntitiesOfClass(LivingEntity.class, doorway(level, base, s, UNATTENDED_RADIUS), SectDoors::person);
            // Player protection is unconditional: night is no permission to close a player-opened door beside him.
            // Also covers a player reopening a door still tracked as NPC-opened.
            if (nearby.stream().anyMatch(e -> e instanceof net.minecraft.world.entity.player.Player)) {
                en.lastBusy = now;
                en.lastNear = now;
                continue;
            }
            // A sect person who stands still beside the door (the guard on his post one cell from the gate) does not keep it open; one
            // who walks, and every other living thing, does.
            if (nearby.stream().anyMatch(e -> !(e instanceof SectDisciple d) || !d.getNavigation().isDone())) {
                en.lastNear = now;
            }
            // the gate too: at night the sect keeps it shut (author 22.10); a person in the passage is still protected above
            boolean dark = night(level);
            long idle = en.adopted && !dark ? Math.min(now - en.lastBusy, now - en.lastNear) : now - en.lastBusy;
            long need = en.adopted && !dark ? UNATTENDED_TICKS : EMPTY_TICKS;
            if (idle < need || level.hasNeighborSignal(base)) {
                continue;
            }
            SectDisciple closer = en.opener != null ? en.opener.get() : passer(level, base, s);
            if (closer == null || !closer.isAlive()) closer = passer(level, base, s);
            if (closer == null) continue;
            if (s.getBlock() instanceof DoorBlock door) {
                if (!s.getValue(DoorBlock.POWERED)) {
                    door.setOpen(closer, level, s, base, false);
                    it.remove();
                }
            } else if (s.getBlock() instanceof GateDoorBlock gate) {
                if (!(level.getBlockEntity(base) instanceof GateDoorBlockEntity be) || (!be.busy() && !be.powered())) {
                    if (gate.toggle(level, base, closer)) {
                        it.remove();
                    }
                }
            } else if (s.getBlock() instanceof HallGrandDoorBlock grand) {
                if (!(level.getBlockEntity(base) instanceof HallGrandDoorBlockEntity be) || (!be.busy() && !be.powered())) {
                    if (grand.toggle(level, base, closer)) {
                        it.remove();
                    }
                }
            }
        }
    }
}
