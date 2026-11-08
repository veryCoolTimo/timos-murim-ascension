package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Quest 7, the practice on the west court: the training partner (the marked post) starts a swing; the player stands on a
 * line, steps off it after the swing begins and strikes the side target that opens. No technique is needed. A mistake costs
 * nothing: the partner says what was wrong and takes his place again after three seconds.
 *
 * <p>State is in the player's persistent data (no static fields): phase WAIT (the partner is ready, the player holds the
 * line), WIND (the swing is on its way; it lands on the line when the time is up), OPEN (the side target is open), PAUSE.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class StrikeDrill {

    private static final String TAG = "murim_strike_drill";
    /** The swing takes this many ticks from the first sign to the line. */
    public static final int WIND_TICKS = 16;
    /** The side target stays open this long. */
    public static final int OPEN_TICKS = 60;
    /** After a mistake the partner needs this long to stand ready again. */
    public static final int PAUSE_TICKS = 60;
    /** Leaving the line before the swing begins is too early; after it, it must be at least this far. */
    static final double HOLD = 1.1D;
    static final double CLEAR = 1.7D;
    private static final int WAIT_MIN = 30, WAIT_RANGE = 40;
    /** The side target stands this far from the partner (world axes, any of the four sides that holds a target). */
    public static final int TARGET_DISTANCE = 4;

    private static final int PH_WAIT = 0, PH_WIND = 1, PH_OPEN = 2, PH_PAUSE = 3;

    private StrikeDrill() {
    }

    public static boolean active(ServerPlayer p) {
        return p.getPersistentData().contains(TAG);
    }

    /** The right click on the partner post. */
    public static void begin(ServerPlayer p, BlockPos post) {
        if (active(p)) return;
        CompoundTag t = new CompoundTag();
        t.putLong("post", post.asLong());
        t.putLong("line", BlockPos.containing(p.position()).asLong());
        t.putInt("phase", PH_WAIT);
        t.putInt("left", WAIT_MIN + p.getRandom().nextInt(WAIT_RANGE));
        p.getPersistentData().put(TAG, t);
        Quests.mark(p, "strike", "tried");
        say(p, "murim.strike.start");
    }

    private static void say(ServerPlayer p, String key) {
        PlayerJournal.record(p, Component.translatable("murim.strike.partner").withStyle(ChatFormatting.GOLD)
                .append(Component.translatable(key).withStyle(ChatFormatting.WHITE)));
    }

    @SubscribeEvent
    static void onTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && active(p)) tick(p, 1);
    }

    /** Advances the drill by {@code ticks} (the tests step it). */
    public static void tick(ServerPlayer p, int ticks) {
        CompoundTag t = p.getPersistentData().getCompound(TAG);
        BlockPos post = BlockPos.of(t.getLong("post"));
        BlockPos line = BlockPos.of(t.getLong("line"));
        if (!p.isAlive() || p.distanceToSqr(post.getCenter()) > 14 * 14) {
            p.getPersistentData().remove(TAG);
            return;
        }
        int phase = t.getInt("phase");
        double off = p.position().distanceTo(Vec3.atBottomCenterOf(line));
        int left = t.getInt("left") - ticks;
        t.putInt("left", left);
        switch (phase) {
            case PH_WAIT -> {
                if (off > HOLD) {
                    say(p, "murim.strike.early");
                    pause(t);
                } else if (left <= 0) {
                    t.putInt("phase", PH_WIND);
                    t.putInt("left", WIND_TICKS);
                    ServerLevel level = p.serverLevel();
                    Vec3 c = post.getCenter();
                    level.playSound(null, post, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.PLAYERS, 1.0F, 0.8F);
                    level.sendParticles(ParticleTypes.SWEEP_ATTACK, c.x, c.y + 0.6, c.z, 1, 0, 0, 0, 0);
                }
            }
            case PH_WIND -> {
                if (left <= 0) {
                    if (off < CLEAR) {
                        say(p, "murim.strike.late");
                        p.serverLevel().sendParticles(ParticleTypes.CRIT, line.getX() + 0.5, line.getY() + 1, line.getZ() + 0.5, 6, 0.2, 0.2, 0.2, 0.1);
                        pause(t);
                    } else {
                        t.putInt("phase", PH_OPEN);
                        t.putInt("left", OPEN_TICKS);
                        setTarget(p, post, true);
                        p.level().playSound(null, line, SoundEvents.PLAYER_ATTACK_WEAK, SoundSource.PLAYERS, 1.0F, 1.3F);
                    }
                }
            }
            case PH_OPEN -> {
                if (left <= 0) {
                    setTarget(p, post, false);
                    say(p, "murim.strike.missed");
                    pause(t);
                }
            }
            default -> {
                if (left <= 0) {
                    t.putInt("phase", PH_WAIT);
                    t.putLong("line", BlockPos.containing(p.position()).asLong());
                    t.putInt("left", WAIT_MIN + p.getRandom().nextInt(WAIT_RANGE));
                    say(p, "murim.strike.ready");
                }
            }
        }
        p.getPersistentData().put(TAG, t);
    }

    private static void pause(CompoundTag t) {
        t.putInt("phase", PH_PAUSE);
        t.putInt("left", PAUSE_TICKS);
    }

    /** The side target of this post: the {@code drill_target} spot within a few blocks of it. */
    static BlockPos targetOf(ServerPlayer p, BlockPos post) {
        for (BlockPos q : BlockPos.betweenClosed(post.offset(-TARGET_DISTANCE - 1, -2, -TARGET_DISTANCE - 1),
                post.offset(TARGET_DISTANCE + 1, 2, TARGET_DISTANCE + 1))) {
            if (p.level().getBlockEntity(q) instanceof ClueBlockEntity be && be.spot().equals("drill_target")) return q.immutable();
        }
        return null;
    }

    private static void setTarget(ServerPlayer p, BlockPos post, boolean open) {
        BlockPos target = targetOf(p, post);
        if (target != null) {
            var s = p.level().getBlockState(target);
            p.level().setBlock(target, s.setValue(ClueBlock.OPEN, open), 3);
        }
    }

    /** The target was struck (or used): the practice is done if it was open. */
    public static void hit(ServerPlayer p, BlockPos target) {
        if (!active(p)) return;
        CompoundTag t = p.getPersistentData().getCompound(TAG);
        if (t.getInt("phase") != PH_OPEN) return;
        solve(p);
        p.level().playSound(null, target, SoundEvents.ANVIL_HIT, SoundSource.BLOCKS, 0.5F, 1.6F);
    }

    /** The practice is done: the understanding is kept for good; how he came to it is remembered. */
    static void solve(ServerPlayer p) {
        CompoundTag t = p.getPersistentData().getCompound(TAG);
        if (t.contains("post")) setTarget(p, BlockPos.of(t.getLong("post")), false);
        p.getPersistentData().remove(TAG);
        say(p, "murim.strike.success");
        if (Quests.mark(p, "strike", "done")) {
            Quests.remember(p, "strike", Quests.done(p, "strike", "ask") ? "asked" : "alone");
        }
    }

    @SubscribeEvent
    static void onLeftClick(PlayerInteractEvent.LeftClickBlock event) {
        if (event.getEntity() instanceof ServerPlayer p && active(p)
                && p.level().getBlockEntity(event.getPos()) instanceof ClueBlockEntity be && be.spot().equals("drill_target")) {
            hit(p, event.getPos());
            event.setCanceled(true);
        }
    }

    /** Phase of the running practice (0 wait, 1 swing, 2 target open, 3 pause), for tests. */
    public static int phaseForTests(ServerPlayer p) {
        return p.getPersistentData().getCompound(TAG).getInt("phase");
    }

    /** Finishes the practice as if the target had been struck (tests). */
    public static void finishForTests(ServerPlayer p) {
        solve(p);
    }
}
