package io.github.verycooltimo.murim.client;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.vault.VaultRegistry;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleProvider;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.SpriteSet;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.core.particles.SimpleParticleType;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterParticleProvidersEvent;

/**
 * A soft, light-grey cartoon cloud of the vault door (author 24.10, refs 4 and 5): it grows, drifts and thins out, with a little
 * turbulence so it billows. API: reference/minecraft-src/net/minecraft/client/particle/TextureSheetParticle.java, ParticleProvider.java,
 * reference/neoforge-src/net/neoforged/neoforge/client/event/RegisterParticleProvidersEvent.java#registerSpriteSet.
 */
public class VaultSmokeParticle extends TextureSheetParticle {

    private final float startSize;

    VaultSmokeParticle(ClientLevel level, double x, double y, double z, double vx, double vy, double vz, SpriteSet sprites) {
        super(level, x, y, z);
        this.xd = vx;
        this.yd = vy;
        this.zd = vz;
        this.lifetime = 50 + random.nextInt(45);
        this.startSize = 0.4F + random.nextFloat() * 0.35F;
        this.quadSize = startSize;
        this.hasPhysics = false;
        this.alpha = 0.0F;
        float g = 0.76F + random.nextFloat() * 0.12F;
        this.rCol = g;
        this.gCol = g + 0.02F;
        this.bCol = Math.min(1.0F, g + 0.07F);
        this.roll = random.nextFloat() * 6.2831855F;
        this.oRoll = this.roll;
        pickSprite(sprites);
    }

    @Override
    public void tick() {
        xo = x;
        yo = y;
        zo = z;
        if (age++ >= lifetime) {
            remove();
            return;
        }
        float t = age / (float) lifetime;
        // fade in over the first fifth, out over the last half
        alpha = Math.min(t / 0.2F, 1.0F) * (1.0F - Math.max(0.0F, (t - 0.5F) / 0.5F)) * 0.6F;
        quadSize = startSize * (1.0F + 1.0F * t);
        xd += (random.nextFloat() - 0.5F) * 0.006D;
        zd += (random.nextFloat() - 0.5F) * 0.006D;
        move(xd, yd, zd);
        xd *= 0.96D;
        yd *= 0.985D;
        zd *= 0.96D;
        oRoll = roll;
        roll += 0.01F * (random.nextFloat() - 0.4F);
    }

    @Override
    public ParticleRenderType getRenderType() {
        return ParticleRenderType.PARTICLE_SHEET_TRANSLUCENT;
    }

    @Override
    public int getLightColor(float partial) {
        int base = super.getLightColor(partial);
        return Math.max(base & 0xFFFF, 0x50) | (base & 0xFFFF0000); // never fully dark: the smoke is lit by the glow
    }

    public static final class Provider implements ParticleProvider<SimpleParticleType> {
        private final SpriteSet sprites;

        public Provider(SpriteSet sprites) {
            this.sprites = sprites;
        }

        @Override
        public Particle createParticle(SimpleParticleType type, ClientLevel level, double x, double y, double z, double vx, double vy, double vz) {
            return new VaultSmokeParticle(level, x, y, z, vx, vy, vz, sprites);
        }
    }

    @EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
    static final class Registration {
        @SubscribeEvent
        static void onRegister(RegisterParticleProvidersEvent event) {
            event.registerSpriteSet(VaultRegistry.VAULT_SMOKE.get(), Provider::new);
        }
    }
}
