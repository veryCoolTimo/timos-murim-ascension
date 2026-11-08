package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.sect.PlayerJournal;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Silent, server-verified continuous look at a real surface. Incomplete progress is transient;
 * completion uses the existing saved quest flags and tablet journal. API: SectTour#visible raycast.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class Observations {
    public record Look(net.minecraft.server.level.ServerLevel level, BlockPos target, long tick, int count) { }
    private Observations() { }
    @SubscribeEvent
    static void tick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p) tickPlayer(p);
    }
    public static void tickPlayer(ServerPlayer p) {
        for (var located : QuestLoader.observations()) {
            var s = located.step();
            BlockPos target;
            if (s.marker().equals("look")) {
                var eye = p.getEyePosition();
                var hit = p.level().clip(new ClipContext(eye, eye.add(p.getLookAngle().scale(s.range())),
                        ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
                target = hit.getType() == HitResult.Type.BLOCK ? ((BlockHitResult) hit).getBlockPos() : null;
            } else target = QuestMarkers.resolve(p.serverLevel(), s.marker());
            tickAt(p, located, target);
        }
    }
    public static void tickAt(ServerPlayer p, QuestLoader.Located<io.github.verycooltimo.murim.quest.def.QuestDef.Observe> located, BlockPos target) {
        var s = located.step();
        String done = Quests.step(located.quest(), "observed." + s.id());
        var looks = p.getData(io.github.verycooltimo.murim.registry.ModAttachments.OBSERVATIONS);
        if (p.isSpectator() || Quests.has(p, done) || !QuestService.passes(p, s.gate()) || target == null
                || !p.serverLevel().hasChunkAt(target) || !matches(p, target, s.block(), s.range())) {
            looks.remove(s.id());
            return;
        }
        // A tick stamp prevents accumulated progress across unloaded dimensions or skipped polling.
        long now = p.level().getGameTime();
        Look before = looks.get(s.id());
        int count = before != null && before.level() == p.serverLevel() && before.tick() == now - 1
                && before.target().equals(target) ? before.count() + 1 : 1;
        looks.put(s.id(), new Look(p.serverLevel(), target.immutable(), now, count));
        if (count < s.ticks()) return;
        Quests.set(p, done);
        QuestService.apply(p, s.effects());
        PlayerJournal.record(p, Component.translatable("murim.observation." + s.id()));
        looks.remove(s.id());
    }
    private static boolean matches(ServerPlayer p, BlockPos target, String block, int range) {
        ResourceLocation id = ResourceLocation.tryParse(block);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)
                || !p.level().getBlockState(target).is(BuiltInRegistries.BLOCK.get(id))) return false;
        var eye = p.getEyePosition();
        var end = eye.add(p.getLookAngle().scale(range));
        var hit = p.level().clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.BLOCK && ((BlockHitResult) hit).getBlockPos().equals(target);
    }
}
