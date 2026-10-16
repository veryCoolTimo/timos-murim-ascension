package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.entity.SectDisciple;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/** Dialogue destinations use the current build and the current position of the person being visited. */
public final class SectEscorts {
    private SectEscorts() { }
    public static boolean available(ServerPlayer p, SectDisciple guide, String destination) {
        if (!SectService.state(p).member() || SectPositions.disciplined(SectService.state(p)) || guide.layout() == null
                || guide.guardTarget() != null || guide.defending() || guide.escorting() || guide.spar() != SectDisciple.Spar.NONE) return false;
        if ("elder".equals(destination)) {
            var rule = SectAccess.RULES.stream().filter(r -> r.id().equals("elders")).findFirst().orElseThrow();
            if (!SectAccess.allowed(rule, SectService.standing(p), SectService.state(p).flags(), SectSchedule.at(p.level().getDayTime()))) return false;
        }
        // Rendering a dialogue choice must never assign a bed or send a hint.
        if ("room".equals(destination)) return guide.layout().at("dorm_3rd", 0, 0) != null
                || guide.layout().at("dorm_2nd", 0, 0) != null;
        return ("room".equals(destination) || "mentor".equals(destination) || "elder".equals(destination)) && target(p, guide, destination) != null;
    }
    public static Vec3 target(ServerPlayer p, SectDisciple guide, String destination) {
        SectLayout layout = guide.layout();
        if (layout == null) return null;
        if ("room".equals(destination)) return SectRooms.assign(p, layout, false);
        String zone = switch (destination) {
            case "mentor" -> "mentor";
            case "elder" -> "elders";
            default -> "";
        };
        if (zone.isEmpty()) return null;
        {
            SectDisciple person = p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(160),
                    d -> d != guide && d.isAlive() && ("mentor".equals(destination) ? d.role() == SectRole.MENTOR : d.role() == SectRole.ELDER))
                    .stream().min(java.util.Comparator.comparingDouble(d -> d.distanceToSqr(p))).orElse(null);
            if (person != null) return SectLife.stand(p.level(), person.position().add(1.5, 0, 0));
        }
        var furniture = SectBuildings.furniture(guide, zone, SectBuildings.Kind.LECTERN);
        Vec3 interior = furniture == null ? null : SectBuildings.approach(guide, furniture);
        Vec3 center = layout.at(zone, 0, 0);
        return interior != null ? interior : center == null ? null : SectLife.stand(p.level(), center);
    }
}
