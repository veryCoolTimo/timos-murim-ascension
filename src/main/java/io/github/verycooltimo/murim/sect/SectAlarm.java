package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectAlarmGoal;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The sect hears a blast and sees a fire (playtest 19.10: «когда я начинаю взрывать динамитом, никто не начинает застраивать.
 * Дом взрывается, там взрывы, и все продолжают спать»). Until now the damage only went into the repair records
 * ({@code RepairEvents}), the crew came after the grace period in daylight, and nobody in the sect noticed anything.
 *
 * <p>Now, at once, for every person of the sect within {@link #HEAR} blocks of the blast (a fire: {@link #HEAR_FIRE}, inside the
 * grounds):
 *
 * Everybody shouts and turns immediately. Servants flee, disciples gather at a safe distance, guards and the nearest
 * elder investigate. Only that elder confronts a survival culprit; repeated incidents use the existing penance flow.
 * Repairs remain the responsibility of RepairService after its grace period.
 * Alarm errands are server-only; bubbles and aura use their existing synchronization.
 * See docs/adr/0020-sect-blast-reaction.md.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectAlarm {

    public enum Kind { BLAST, FIRE, MONSTER, GATE }

    /** A blast is heard this far (horizontally and 24 blocks up and down). */
    public static final double HEAR = 64.0D;
    /** A fire is seen this far, and only inside the grounds. */
    public static final double HEAR_FIRE = 40.0D;
    /** A hostile creature in the grounds is cried out this far (the watch sees it from {@link SectLife#WATCH_RANGE}). */
    public static final double HEAR_MONSTER = 48.0D;
    /** A string of blasts is one alarm: no new one for this many ticks. */
    public static final long COOLDOWN = 120L;
    /** Everybody turns to the spot and looks this long. */
    public static final int LOOK_TICKS = 100;
    /** The runner and the inspector have this long to get there. */
    public static final int ERRAND_TICKS = 700;
    /** Where they stop, short of the spot. */
    public static final double RUN_STANDOFF = 6.0D;
    public static final double INSPECT_STANDOFF = 8.0D;
    /** Disciples who run to look, and where they stop (a safe distance). */
    public static final double GAWK_STANDOFF = 14.0D;
    public static final double FLEE_DISTANCE = 16.0D;
    public static final double ELDER_STANDOFF = 6.0D;
    /** Alarms apart by less than this are one strike of the culprit. */
    public static final long STRIKE_GAP = 400L;
    /** The strike of a sect day at which the penance flow takes the culprit. */
    public static final int WARRANT_AT = 3;

    private SectAlarm() {
    }

    @SubscribeEvent
    static void onDetonate(ExplosionEvent.Detonate event) {
        if (event.getLevel() instanceof ServerLevel level && level.dimension() == Level.OVERWORLD) {
            Vec3 at = event.getExplosion().center();
            raise(level, at, Kind.BLAST, culprit(level, at, event.getExplosion().getIndirectSourceEntity(), 14.0D, false));
        }
    }

    /** A fire block appearing (a spreading fire, a flint and steel, a lightning strike) in the sect grounds. */
    @SubscribeEvent
    static void onNeighbor(BlockEvent.NeighborNotifyEvent event) {
        BlockState state = event.getState();
        if (!state.is(BlockTags.FIRE) || !(event.getLevel() instanceof ServerLevel level) || level.dimension() != Level.OVERWORLD) {
            return;
        }
        // a burning block updates its neighbours all the time: look once a second
        SectSiteData data = SectLife.data(level);
        if (level.getGameTime() - data.lastFireLook < 20L) {
            return;
        }
        BlockPos pos = event.getPos();
        MountHuaSite site = MountHuaSites.get(level.getServer());
        // A fire on the mountainside is none of the sect's business; one in the grounds is. (No mountain: a test world, everywhere.)
        if (site == null || SectTerritory.contains(site, pos.getX() + 0.5D, pos.getY(), pos.getZ() + 0.5D)) {
            Vec3 at = Vec3.atCenterOf(pos);
            if (raise(level, at, Kind.FIRE, culprit(level, at, null, 8.0D, true)) > 0) {
                // Unobserved fires (including ones outside the grounds) must not swallow a visible ignition.
                data.lastFireLook = level.getGameTime();
            }
        }
    }

    /**
     * Who did it: the one who lit the TNT (or fired the cannon), else the nearest player close to the spot. A fire is pinned
     * on a player only when he holds something that lights one.
     */
    static ServerPlayer culprit(ServerLevel level, Vec3 at, net.minecraft.world.entity.LivingEntity igniter, double near, boolean needsFlame) {
        if (igniter instanceof ServerPlayer p && p.isAlive()) {
            return p;
        }
        return level.players().stream()
                .filter(p -> p.isAlive() && !p.isSpectator() && p.distanceToSqr(at) <= near * near && (!needsFlame || holdsFlame(p)))
                .min(Comparator.comparingDouble(p -> p.distanceToSqr(at))).orElse(null);
    }

    private static boolean holdsFlame(ServerPlayer p) {
        for (net.minecraft.world.item.ItemStack stack : List.of(p.getMainHandItem(), p.getOffhandItem())) {
            if (stack.is(net.minecraft.world.item.Items.FLINT_AND_STEEL) || stack.is(net.minecraft.world.item.Items.FIRE_CHARGE)
                    || stack.is(net.minecraft.world.item.Items.LAVA_BUCKET)) {
                return true;
            }
        }
        return false;
    }

    public static int raise(ServerLevel level, Vec3 at, Kind kind) {
        return raise(level, at, kind, null);
    }

    /**
     * The alarm. Returns how many people took it up, 0 when nobody of the sect is within hearing or it is the tail of an alarm
     * just raised.
     *
     * <p>Everybody who hears it reacts at once, each in his own way: the guards and the nearest elder run to the spot, the
     * servants and the cook run away from it, disciples run to look and stop at a safe distance, the rest stop and turn. All of
     * them cry out over their heads. If {@code culprit} is a survival player the elder walks up
     * to him, pins him with his aura and speaks sharply; the third strike of one sect day is a warrant (the penance flow).
     */
    public static int raise(ServerLevel level, Vec3 at, Kind kind, ServerPlayer culprit) {
        double range = kind == Kind.FIRE ? HEAR_FIRE : kind == Kind.MONSTER ? HEAR_MONSTER : HEAR;
        List<SectDisciple> heard = new ArrayList<>(level.getEntitiesOfClass(SectDisciple.class,
                new AABB(at, at).inflate(range, 24.0D, range),
                d -> d.isAlive() && d.member().isPresent() && !d.wounded() && d.free() && !d.escorting() && d.guardTarget() == null
                        && d.distanceToSqr(at) <= range * range));
        if (heard.isEmpty()) {
            return 0;
        }
        SectSiteData site = SectLife.data(level);
        long now = level.getGameTime();
        if (now - site.lastAlarm < COOLDOWN) {
            return 0;
        }
        site.lastAlarm = now;
        heard.sort(Comparator.comparingDouble(d -> d.distanceToSqr(at)));
        boolean fire = kind == Kind.FIRE;
        List<SectDisciple> runners = heard.stream().filter(SectAlarm::guard).toList();
        SectDisciple elder = heard.stream().filter(SectAlarm::elder).findFirst().orElse(null);
        // whoever is within earshot of the sect's people when he sets it off is the sect's business (no territory test: the heard
        // people are the proof that this is the sect)
        boolean confront = culprit != null && culprit.isAlive() && !culprit.isCreative() && !culprit.isSpectator();
        SectDisciple confronter = confront ? elder : null;
        int strike = confronter == null ? 0 : strike(culprit, now, fire);
        for (SectDisciple d : heard) {
            SectAlarmGoal.Mode mode;
            if (d == confronter) {
                mode = SectAlarmGoal.Mode.CONFRONT;
            } else if (runners.contains(d)) {
                mode = SectAlarmGoal.Mode.RUN;
            } else if (d == elder) {
                mode = SectAlarmGoal.Mode.ELDER;
            } else if (lay(d)) {
                mode = SectAlarmGoal.Mode.FLEE;
            } else if (d.member().get().disciple() || d.role() == SectRole.FIRST) {
                mode = SectAlarmGoal.Mode.GAWK;
            } else {
                mode = SectAlarmGoal.Mode.LOOK;
            }
            Vec3 stand = switch (mode) {
                case INSPECT -> standPoint(level, d, at, INSPECT_STANDOFF);
                case GAWK -> standPoint(level, d, at, GAWK_STANDOFF);
                case FLEE -> fleePoint(level, d, at);
                case CONFRONT -> standPoint(level, d, at, ELDER_STANDOFF);
                case ELDER -> standPoint(level, d, at, ELDER_STANDOFF);
                default -> standPoint(level, d, at, RUN_STANDOFF);
            };
            long until = now + (mode == SectAlarmGoal.Mode.LOOK ? LOOK_TICKS : ERRAND_TICKS);
            d.alarmGoal().assign(mode, at, stand, until);
            if (mode == SectAlarmGoal.Mode.CONFRONT) {
                d.alarmGoal().target(culprit, strike);
            }
            SectBubbles.Group group = switch (mode) {
                case RUN -> SectBubbles.Group.ALARM_GUARD;
                case FLEE -> SectBubbles.Group.ALARM_FLEE;
                case GAWK -> SectBubbles.Group.ALARM_CURIOUS;
                case ELDER, CONFRONT -> SectBubbles.Group.ALARM_ELDER;
                case INSPECT -> null; // he speaks when he has come
                default -> fire ? SectBubbles.Group.ALARM_FIRE : kind == Kind.MONSTER ? SectBubbles.Group.ALARM_MONSTER
                        : SectBubbles.Group.ALARM_BLAST;
            };
            // An emergency interrupts ambient chatter, including a recently spoken line. Do not depend on a nearby
            // player's chatter-delivery tick: every listener must shout within two seconds.
            if (group != null) {
                String key = group.pick(d.getRandom().nextInt(1 << 16));
                SectChatter.send(d, key, Component.translatable(key));
            }
        }

        return heard.size();
    }

    private static final String STRIKE_DAY = "murim_alarm_day";
    private static final String STRIKE_N = "murim_alarm_n";
    private static final String STRIKE_AT = "murim_alarm_at";

    /**
     * Which strike of the sect day this is for the culprit: alarms less than {@link #STRIKE_GAP} ticks apart are one strike (a
     * blast chain, a TNT cannon), fire and TNT share the same daily count.
     */
    static int strike(ServerPlayer p, long now, boolean fire) {
        var tag = p.getPersistentData();
        long day = SectSchedule.day(p.level().getDayTime()) + 1;
        int n = tag.getLong(STRIKE_DAY) == day ? tag.getInt(STRIKE_N) : 0;
        if (n == 0 || now - tag.getLong(STRIKE_AT) >= STRIKE_GAP) {
            n++;
        }
        tag.putLong(STRIKE_DAY, day);
        tag.putInt(STRIKE_N, n);
        tag.putLong(STRIKE_AT, now);
        return n;
    }

    /** Strikes of this player counted today. */
    public static int strikes(ServerPlayer p) {
        var tag = p.getPersistentData();
        return tag.getLong(STRIKE_DAY) == SectSchedule.day(p.level().getDayTime()) + 1 ? tag.getInt(STRIKE_N) : 0;
    }

    /**
     * The elder has come up to the culprit: full aura on him (the existing aura pressure does the pinning), a sharp word;
     * from {@link #WARRANT_AT} strikes in one day the penance flow takes over (an elder pins him and leads him to the cell).
     */
    public static void confronted(SectDisciple elder, ServerPlayer p, int strike) {
        elder.suppressionGoal().fullAura();
        SectBubbles.Group group = strike >= 2 ? SectBubbles.Group.ALARM_CONFRONT_AGAIN : SectBubbles.Group.ALARM_CONFRONT;
        String key = group.pick(p, elder.getRandom());
        SectChatter.send(elder, key, Component.translatable(key));
        elder.gesture("point");
        elder.faceEntity(p, 60.0F);
        if (strike >= WARRANT_AT && !p.isCreative() && !p.isSpectator()) {
            MurimMod.LOGGER.info("Sect alarm: {} struck {} times today, the elders take him", p.getGameProfile().getName(), strike);
            io.github.verycooltimo.murim.sect.seal.PenanceEnforcement.rampage(p);
        }
    }

    /** Servants promise repairs from safety; guards and elders point out the incident. */
    public static void arrived(SectDisciple npc, SectAlarmGoal.Mode mode) {
        if (mode == SectAlarmGoal.Mode.CONFRONT || mode == SectAlarmGoal.Mode.ELDER) {
            npc.gesture("point");
        } else if (mode == SectAlarmGoal.Mode.INSPECT || mode == SectAlarmGoal.Mode.FLEE && servant(npc)) {
            String key = SectBubbles.Group.ALARM_SERVANT.pick(npc.getRandom().nextInt(1 << 16));
            SectChatter.send(npc, key, Component.translatable(key));
            npc.gesture("point");
        } else if (mode == SectAlarmGoal.Mode.RUN) {
            npc.gesture("point");
        }
    }

    /** Servants who promise repairs after fleeing to safety. */
    static boolean servant(SectDisciple d) {
        SectRole r = d.role();
        return r == SectRole.PORTER || r == SectRole.SWEEPER || r == SectRole.GARDENER || r == SectRole.WATER_CARRIER || r == SectRole.STEWARD;
    }

    /** The elder who comes to the spot: an elder of the sect, else its head. */
    static boolean elder(SectDisciple d) {
        return d.role() == SectRole.ELDER || d.role() == SectRole.LEADER;
    }

    /** Servants, cook, steward: people who do not fight and run from a blast. */
    static boolean lay(SectDisciple d) {
        return d.member().isPresent() && d.member().get().lay();
    }

    /** A place to run to: away from the spot, further than he stands now. */
    public static Vec3 fleePoint(ServerLevel level, SectDisciple d, Vec3 at) {
        Vec3 away = new Vec3(d.getX() - at.x, 0.0D, d.getZ() - at.z);
        if (away.lengthSqr() < 1.0E-4D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }
        double start = away.length();
        // Prefer the full retreat; near a ledge try shorter safe retreats before giving up.
        for (double radius = start + FLEE_DISTANCE; radius > start + 2.0D; radius -= 1.0D) {
            Vec3 point = supportedRingPoint(level, d, at, away, radius);
            if (point != null) return point;
        }
        return d.position();
    }

    /** The watch on duty, the gate keeper, the hall guards. */
    static boolean guard(SectDisciple d) {
        return SectWatch.guards(d) || d.role() == SectRole.GUARD;
    }

    /** A standing place {@code standoff} blocks from the spot on the side he comes from. */
    public static Vec3 standPoint(ServerLevel level, SectDisciple d, Vec3 at, double standoff) {
        Vec3 away = new Vec3(d.getX() - at.x, 0.0D, d.getZ() - at.z);
        if (away.lengthSqr() < 1.0E-4D) {
            away = new Vec3(1.0D, 0.0D, 0.0D);
        }
        Vec3 point = supportedRingPoint(level, d, at, away, standoff);
        return point == null ? d.position() : point;
    }

    /** Keep the intended distance from danger, but rotate around it when the direct point has no floor. */
    private static Vec3 supportedRingPoint(ServerLevel level, SectDisciple d, Vec3 at, Vec3 away, double radius) {
        double heading = Math.atan2(away.z, away.x);
        // Source: SectLife.stand/standable and SectWalking collision checks; no unsupported endpoint over a ledge.
        for (int i = 0; i < 32; i++) {
            int offset = i % 2 == 0 ? i / 2 : -(i + 1) / 2;
            double angle = heading + offset * Math.PI / 16.0D;
            Vec3 guess = new Vec3(at.x + Math.cos(angle) * radius, d.getY(), at.z + Math.sin(angle) * radius);
            if (!level.isLoaded(BlockPos.containing(guess))) continue;
            Vec3 point = SectLife.stand(level, guess);
            if (Math.abs(point.y - d.getY()) <= 2.0D && SectLife.standable(level, BlockPos.containing(point))
                    && level.noCollision(d, d.getBoundingBox().move(point.subtract(d.position())))) {
                // Supported ground may be across a drop or on the other side of a wall. A partial path is not enough.
                // API: existing SectStuckGameTests.pathsGoRoundCabinetsNotThroughThem (createPath/canReach).
                if (d.onGround()) {
                    var path = d.getNavigation().createPath(BlockPos.containing(point), 0);
                    if (path == null || !path.canReach()) continue;
                }
                return point;
            }
        }
        return null;
    }
}
