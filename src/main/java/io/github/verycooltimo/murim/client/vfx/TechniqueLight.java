package io.github.verycooltimo.murim.client.vfx;

import io.github.verycooltimo.murim.MurimMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import java.util.ArrayList;
import java.util.List;

/** Client-only visual state, discarded on level identity change; never persisted or sent to the server. */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class TechniqueLight {
    private static final TechniqueLight CLIENT = new TechniqueLight();
    private ClientLevel level;
    private final List<Light> lights = new ArrayList<>();
    private TechniqueLight() {}
    public static final class Light {
        private Vec3 pos, sampledAt;
        public final int rgb;
        public final float radius, intensity;
        private final int attack, hold, release;
        private final long born;
        private List<TechniqueLightMath.Patch> cells = List.of();
        private Light(Vec3 pos, int rgb, float radius, float intensity, int attack, int hold, int release, long born) {
            this.pos = pos; this.rgb = rgb; this.radius = radius; this.intensity = intensity;
            this.attack = attack; this.hold = hold; this.release = release; this.born = born;
        }
        public Vec3 position() { return pos; }
        public void moveTo(Vec3 position) {
            if (Double.isFinite(position.x) && Double.isFinite(position.y) && Double.isFinite(position.z)) pos = position;
        }
        public float power(float partial) {
            return CLIENT.level == null ? 0 : intensity * TechniqueLightMath.envelope(
                    CLIENT.level.getGameTime() - born + partial, attack, hold, release);
        }
        List<TechniqueLightMath.Patch> cells() { return cells; }
    }
    private static void sync() {
        var level = Minecraft.getInstance().level;
        if (CLIENT.level != level) { CLIENT.lights.clear(); CLIENT.level = level; }
    }
    /** Ticks: attack ramps to peak, hold stays at peak, release ramps to zero. Returns an optional movable handle. */
    public static Light add(Vec3 pos, int rgb, float radius, float intensity, int attack, int hold, int release) {
        sync();
        if (CLIENT.level == null || !(radius > 0) || !Float.isFinite(radius) || !Float.isFinite(intensity)
                || intensity <= 0 || attack < 0 || hold < 0 || release < 0 || (long) attack + hold + release <= 0 || (long) attack + hold + release > Integer.MAX_VALUE
                || !Double.isFinite(pos.x) || !Double.isFinite(pos.y) || !Double.isFinite(pos.z)) return null;
        // Bound dev input and sampling work as well as the number of active sources.
        Light light = new Light(pos, rgb & 0xffffff, Math.min(radius, 16), Math.min(intensity, 1),
                attack, hold, release, CLIENT.level.getGameTime());
        CLIENT.lights.removeIf(l -> CLIENT.level.getGameTime() - l.born >= l.attack + l.hold + l.release);
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        TechniqueLightMath.admit(CLIENT.lights, light, l -> l.pos.distanceToSqr(camera));
        if (CLIENT.lights.contains(light)) sample(light);
        return CLIENT.lights.contains(light) ? light : null;
    }
    public static List<Light> active() {
        sync();
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        return TechniqueLightMath.nearest(CLIENT.lights, l -> l.pos.distanceToSqr(camera), TechniqueLightMath.CAP);
    }
    @SubscribeEvent
    static void tick(ClientTickEvent.Post event) {
        sync();
        if (CLIENT.level == null) return;
        CLIENT.lights.removeIf(l -> CLIENT.level.getGameTime() - l.born >= l.attack + l.hold + l.release);
        for (Light l : CLIENT.lights) if (l.sampledAt == null || l.pos.distanceToSqr(l.sampledAt) > .25) sample(l);
    }
    // API: supplied minecraft-src: LevelReader#hasChunkAt, Level#getBlockState/getGameTime,
    // BlockBehaviour.BlockStateBase#getCollisionShape(BlockGetter,BlockPos), VoxelShape#toAabbs.
    // Runs at spawn/tick, never fetches an unloaded chunk and never queries terrain during drawing.
    private static void sample(Light light) {
        light.sampledAt = light.pos;
        light.cells = TechniqueLightMath.cells(light.pos.x, light.pos.z, light.radius, (x, z) -> {
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            double top = Double.NaN;
            for (int y = (int) Math.floor(light.pos.y + light.radius); y >= Math.floor(light.pos.y - light.radius); y--) {
                BlockPos p = new BlockPos(bx, y, bz);
                if (!CLIENT.level.hasChunkAt(p)) continue;
                for (var box : CLIENT.level.getBlockState(p).getCollisionShape(CLIENT.level, p).toAabbs()) {
                    // A half-cell must fit on the face: do not bridge gaps or narrow fence tops.
                    double x0 = Math.floor(x * 2) / 2 - bx, z0 = Math.floor(z * 2) / 2 - bz;
                    if (x0 < box.minX || x0 + .5 > box.maxX || z0 < box.minZ || z0 + .5 > box.maxZ) continue;
                    double h = y + box.maxY;
                    if (h <= light.pos.y + light.radius && (!Double.isFinite(top) || h > top)) top = h;
                }
            }
            return top;
        });
    }
}
