package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.client.ClientConfig;
import io.github.verycooltimo.murim.client.ClientMeditationState;
import io.github.verycooltimo.murim.cultivation.BreakthroughScript;
import io.github.verycooltimo.murim.cultivation.Realm;
import net.minecraft.client.Minecraft;
import net.minecraft.client.ParticleStatus;
import net.minecraft.client.particle.Particle;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * The matter of the breakthrough: dust, leaves and chips of the floor that the scene moves (docs/design/research/
 * hua-main-1810.md section 4: «пыль и листья закручивает», «пыль и снег с земли»; author 19.10: simulate, not pictures).
 *
 * <p>Real client particles, so they are lit, fall and bounce like the game's own matter and read in every shader pack: in the
 * 2.5 s of omen dust and petals are steered into a spiral that closes on the player (the pull of {@code AuraPressure},
 * turned to the player); in the scene chips of the very block the player sits on are thrown up from a ring and fall back;
 * at the impact the floor bursts outward in a wave; Snow
 * floors give snow. The player's own settings are respected (minimal particles: none; decreased: fewer; the quality slider).
 *
 * <p>Client only; nothing here reaches the server. The steered particles are kept in a short list so their velocity can be
 * rewritten every tick; ballistic ones (chips) are left to the game's gravity.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class BreakthroughParticles {

    private static final double TAU = Math.PI * 2.0D;
    /** The most particles steered at once. */
    private static final int CAP = 260;

    /** A particle that is steered into the spiral each tick: {@code sign} is the sense of the swirl. */
    private record Steered(Particle particle, double sign, double reach) {
    }

    private static final List<Steered> STEERED = new ArrayList<>();
    /** Everything this class spawned and may still be alive: the ones that drift into the lens are removed (a chip that is
     *  half a block from the camera fills the screen with one dark square). */
    private static final List<Particle> SPAWNED = new ArrayList<>();
    private static final double LENS_CLEAR = 2.0D;
    private static final int SPAWNED_CAP = 700;
    private static int lastRankUpAge = -1;

    private BreakthroughParticles() {
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if(io.github.verycooltimo.murim.dev.DevHooks.INSTANCE.benchActive()) {
            for(Particle p:SPAWNED)p.remove();
            SPAWNED.clear();STEERED.clear();lastRankUpAge=-1;
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.isPaused()) {
            return;
        }
        int warn = ClientMeditationState.warningAge();
        int scene = ClientMeditationState.breakthroughAge();
        int rankUp = ClientMeditationState.rankUpAge();
        int glow = ClientMeditationState.afterglowAge();
        if (warn < 0 && !STEERED.isEmpty()) {
            // Omen particles must end with the omen, including petals with a remaining vanilla lifetime.
            for (Steered p : STEERED) p.particle().remove();
            SPAWNED.removeIf(p -> !p.isAlive());
            STEERED.clear();
        }
        steer(mc);
        clearLens(mc);
        VfxBudget.setBreakthrough(SPAWNED.size());
        if (warn < 0 && scene < 0 && rankUp < 0 && glow < 0) {
            STEERED.clear();
            lastRankUpAge = -1;
            return;
        }
        ParticleStatus status = mc.options.particles().get();
        if (status == ParticleStatus.MINIMAL) {
            return;
        }
        float density = (status == ParticleStatus.DECREASED ? 0.4F : 1.0F)
                * switch (ClientConfig.VFX_QUALITY.get()) {
                    case LOW -> 0.5F;
                    case MEDIUM -> 0.75F;
                    case HIGH -> 1.0F;
                };
        Vec3 axis = axis(mc);
        BlockState floor = floor(mc);
        if (warn >= 0) {
            omen(mc, axis, floor, ClientMeditationState.breakthroughTarget(), warn, density);
        }
        lastRankUpAge = rankUp;
    }

    // ------------------------------------------------------------------ places

    /** The body's axis on the floor: the player's own position (the particles do not need the bones). */
    private static Vec3 axis(Minecraft mc) {
        return mc.player.position();
    }

    /** The block the player sits on: its chips are the matter that flies. */
    private static BlockState floor(Minecraft mc) {
        BlockPos on = mc.player.getOnPos();
        BlockState state = mc.level.getBlockState(on);
        return state.isAir() ? mc.level.getBlockState(on.below()) : state;
    }

    private static boolean snowy(BlockState floor) {
        return floor.is(BlockTags.SNOW);
    }

    private static Particle spawn(Minecraft mc, ParticleOptions options, Vec3 at, Vec3 v) {
        // the cap is checked before the particle exists: one made over the cap would still be simulated and drawn
        if (SPAWNED.size() >= SPAWNED_CAP || !VfxBudget.hasRoom()) {
            return null;
        }
        Particle p = mc.particleEngine.createParticle(options, at.x, at.y, at.z, v.x, v.y, v.z);
        if (p != null) {
            SPAWNED.add(p);
            VfxBudget.setBreakthrough(SPAWNED.size());
        }
        return p;
    }

    /** Removes what has come within {@link #LENS_CLEAR} blocks of the camera and forgets what is gone. */
    private static void clearLens(Minecraft mc) {
        if (SPAWNED.isEmpty()) {
            return;
        }
        Vec3 cam = mc.gameRenderer.getMainCamera().getPosition();
        double limit = LENS_CLEAR * LENS_CLEAR;
        for (Iterator<Particle> it = SPAWNED.iterator(); it.hasNext(); ) {
            Particle p = it.next();
            if (!p.isAlive()) {
                it.remove();
            } else if (p.getBoundingBox().getCenter().distanceToSqr(cam) < limit) {
                p.remove();
                it.remove();
            }
        }
    }

    private static ParticleOptions chip(BlockState floor) {
        return new BlockParticleOption(ParticleTypes.BLOCK, floor.isAir() ? net.minecraft.world.level.block.Blocks.STONE.defaultBlockState() : floor);
    }

    // ------------------------------------------------------------------ omen

    /**
     * 2.5 s: dust, petals and chips rise from the ground round the player and wind in toward him, faster as they close; the
     * more rank, the farther they come from.
     */
    private static void omen(Minecraft mc, Vec3 axis, BlockState floor, int rank, int t, float density) {
        double reach = 2.5D + 0.45D * BreakthroughScript.radius(rank);
        RandomSource r = mc.level.random;
        int count = Math.round(7.0F * density * (0.5F + 0.5F * Math.min(1.0F, t / 20.0F)));
        for (int i = 0; i < count && STEERED.size() < CAP; i++) {
            double ang = r.nextDouble() * TAU;
            double rad = reach * (0.55D + 0.45D * r.nextDouble());
            Vec3 at = axis.add(Math.cos(ang) * rad, 0.1D + r.nextDouble() * 1.6D, Math.sin(ang) * rad);
            ParticleOptions options;
            float roll = r.nextFloat();
            if (roll < 0.45F) {
                options = chip(floor);
            } else if (roll < 0.8F) {
                options = ParticleTypes.CHERRY_LEAVES;
            } else {
                options = snowy(floor) ? ParticleTypes.SNOWFLAKE : ParticleTypes.CLOUD;
            }
            Particle p = spawn(mc, options, at, Vec3.ZERO);
            if (p != null) {
                if (options == ParticleTypes.CLOUD) {
                    p.scale(0.6F);
                }
                p.setLifetime(30 + r.nextInt(20));
                STEERED.add(new Steered(p, r.nextBoolean() ? 1.0D : 0.8D, reach));
            }
        }
    }

    /** Rewrites the velocity of every steered particle: inward, round, a little up; speed rises as it closes. */
    private static void steer(Minecraft mc) {
        if (STEERED.isEmpty() || mc.player == null) {
            return;
        }
        Vec3 axis = axis(mc);
        for (Iterator<Steered> it = STEERED.iterator(); it.hasNext(); ) {
            Steered s = it.next();
            Particle p = s.particle();
            if (!p.isAlive()) {
                it.remove();
                continue;
            }
            Vec3 at = p.getBoundingBox().getCenter();
            double dx = at.x - axis.x;
            double dz = at.z - axis.z;
            double dist = Math.sqrt(dx * dx + dz * dz);
            if (dist < 0.45D) {
                p.remove();
                it.remove();
                continue;
            }
            double closeness = 1.0D - Math.min(1.0D, dist / s.reach());
            double in = 0.05D + 0.14D * closeness;
            double turn = (0.12D + 0.16D * closeness) * s.sign();
            double vx = -dx / dist * in + -dz / dist * turn;
            double vz = -dz / dist * in + dx / dist * turn;
            double vy = 0.012D + 0.03D * closeness - (at.y - axis.y > 1.9D ? 0.03D : 0.0D);
            p.setParticleSpeed(vx, vy, vz);
        }
    }

    // ------------------------------------------------------------------ scene

    // ------------------------------------------------------------------ impact

}
