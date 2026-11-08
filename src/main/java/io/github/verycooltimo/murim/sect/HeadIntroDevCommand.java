package io.github.verycooltimo.murim.sect;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.SeatStyle;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Capture setup only; the authored scene remains owned by SectHeadIntro.arrived. */
public final class HeadIntroDevCommand {
    private HeadIntroDevCommand() { }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("head").then(Commands.literal("intro").executes(c -> {
            ServerPlayer p = c.getSource().getPlayerOrException();
            // Prefer the nearby mentor's yard even when the suite has already generated Mount Hua.
            SectLayout layout = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(32),
                    d -> d.role() == SectRole.MENTOR && d.layout() != null)
                    .stream().min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p)))
                    .map(SectDisciple::layout).orElseGet(() -> SectLife.layout(p.serverLevel()));
            if (layout == null || layout.at("main_hall", 0, 0.5) == null) {
                c.getSource().sendFailure(Component.translatable("command.murim.head.no_hall"));
                return 0;
            }
            if (SectHeadIntro.active(p)) SectHeadIntro.finish(p, layout);
            SectArrival.cancelGuides(p);
            DialogueService.dropSession(p);
            Vec3 at = SectLife.stand(p.level(), layout.at("main_hall", 0, 0.5));
            p.teleportTo(p.serverLevel(), at.x, at.y, at.z, layout.yaw(0, 1), 0);
            SectDisciple mentor = person(p, layout, "gyeong_pil", -1.5, 0.5);
            SectDisciple head = person(p, layout, "tae_hwi", 0, 4);
            if (!mentor.free() || !head.free()) {
                c.getSource().sendFailure(Component.translatable("command.murim.head.busy"));
                return 0;
            }
            SectArrival.forget(p);
            p.getPersistentData().remove(SectHeadIntro.WAITING);
            p.getPersistentData().remove(SectHeadIntro.DONE_AT);
            p.setData(ModAttachments.SECT, SectState.NONE.joined().with("met_mentor").with(SectArrival.PENDING)
                    .with(SectAccess.HEAD_ESCORT).record("arrival.day", SectSchedule.day(p.level().getDayTime()))
                    .record(SectHeadIntro.STARTED, p.level().getGameTime()));
            SectArrival.accepted(p);
            SectJourney.syncHud(p);
            // Explicit dev staging bypasses the ordinary walk, as before.
            SectHeadIntro.stageForDevelopment(p, layout, head);
            SectHeadIntro.arrived(p, mentor);
            return SectHeadIntro.involves(p, head) ? 1 : 0;
        }));
    }

    // API: SectPlaytestRig.npc and SparDevCommand.face; reuse the world's named actors.
    private static SectDisciple person(ServerPlayer p, SectLayout layout, String key, double u, double v) {
        SectDisciple actor = null;
        for (var entity : p.serverLevel().getAllEntities()) {
            // SectLayout.hua creates fresh layout objects: compare hall coordinates, not identity.
            // Other playtest yards can have the same named roster in this server level.
            if (entity instanceof SectDisciple d && d.isAlive() && key.equals(d.memberKey())
                    && d.layout() != null && layout.at("main_hall", 0, 0).equals(d.layout().at("main_hall", 0, 0))) {
                actor = d;
                break;
            }
        }
        boolean spawned = actor == null;
        if (spawned) {
            actor = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), p.serverLevel());
            actor.setMember(SectRoster.of(key).orElseThrow());
            actor.setLayout(layout);
        }
        if (!actor.free()) return actor;
        actor.escortGoal().cancel();
        actor.walking().stop();
        actor.wake();
        actor.sit(SeatStyle.NONE);
        actor.setLayout(layout);
        actor.setKeepAwake(true);
        Vec3 at = SectLife.stand(p.level(), layout.at("main_hall", u, v));
        actor.moveTo(at.x, at.y, at.z, layout.yaw(0, -1), 0);
        actor.getNavigation().stop();
        if (spawned) p.serverLevel().addFreshEntity(actor);
        return actor;
    }
}
