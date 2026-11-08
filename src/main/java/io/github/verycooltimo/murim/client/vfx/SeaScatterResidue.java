package io.github.verycooltimo.murim.client.vfx;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import java.util.ArrayList;
import java.util.List;

/** Bounded presentation residue; holds survive their source storm's dissolve. */
final class SeaScatterResidue {
    private record Petal(Vec3 at, int born, int life, int slot, double size) {}
    private final List<Petal> petals = new ArrayList<>();
    void tick(int now) { petals.removeIf(p -> now - p.born >= p.life); }
    boolean empty() { return petals.isEmpty(); }
    // API: read-only /tmp/mr-ref-1121/minecraft-src/net/minecraft/world/level/
    // BlockGetter.java#clip,LevelReader.java#hasChunkAt,ClipContext.java;
    // core/BlockPos.java#containing,world/phys/HitResult.java#getType,getLocation,
    // world/phys/shapes/CollisionContext.java#empty; client/Minecraft.java#getInstance,level.
    void land(Vec3 from, int now, int slot, double size) {
        var world = Minecraft.getInstance().level;
        Vec3 to = from.add(0, -12, 0);
        if (world == null || petals.size() >= 64 || !world.hasChunkAt(BlockPos.containing(from))
                || !world.hasChunkAt(BlockPos.containing(to))) return;
        var hit = world.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()));
        if (hit.getType() != HitResult.Type.MISS) petals.add(new Petal(hit.getLocation().add(0, .025 + slot % 3 * .004, 0),
                now, SeaScatterFinish.landedLife(slot), slot, size));
    }
    void draw(VertexConsumer v, PoseStack.Pose pose, int now, float partial) {
        for (Petal p : petals) {
            double spin = p.slot * 2.399963;
            SeaScatterPetals.drawPlane(v, pose, p.at, new Vec3(Math.cos(spin), 0, Math.sin(spin)),
                    new Vec3(-Math.sin(spin), 0, Math.cos(spin)), new Vec3(0, 1, 0), p.size,
                    p.slot, .8F * SeaScatterFinish.envelope(now - p.born + partial, p.life), .85F, .85F, .85F);
        }
    }
}
