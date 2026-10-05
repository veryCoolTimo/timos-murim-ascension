package io.github.verycooltimo.murim.world.camp;

import io.github.verycooltimo.murim.world.location.CapturedLocation;
import io.github.verycooltimo.murim.world.location.LocationMarker;
import io.github.verycooltimo.murim.world.location.LocationTemplatePiece;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

/**
 * Bandits of a camp built from the author's template (docs/design/28-location-capture.md §4): where they stand and
 * who they are. Signs {@code spawn:chief}, {@code spawn:bandit}, {@code spawn:archer}, {@code spawn:tower} (an
 * archer who keeps to the tower), with {@code _qi} for a bandit with qi ({@code spawn:bandit_qi}). One bandit per
 * sign, the chief first. Without spawn signs the posts of the captured procedural camp are used, turned with the
 * template — the tents stand where they stood when the author captured them.
 */
public final class CampTemplate {

    private CampTemplate() {
    }

    /** The template piece of the same structure start, or null. */
    public static LocationTemplatePiece templatePiece(ServerLevel level, Structure structure, long start) {
        ChunkAccess chunk = level.getChunk(ChunkPos.getX(start), ChunkPos.getZ(start), ChunkStatus.STRUCTURE_STARTS, true);
        StructureStart s = chunk == null ? null : chunk.getStartForStructure(structure);
        if (s == null || !s.isValid()) {
            return null;
        }
        for (StructurePiece p : s.getPieces()) {
            if (p instanceof LocationTemplatePiece t) {
                return t;
            }
        }
        return null;
    }

    /** Fills the camp's template posts once per load (no-op if already done). */
    public static void attach(BanditCampData.Camp camp, BanditCampPiece shell, LocationTemplatePiece tpl) {
        if (camp.templatePosts != null) {
            return;
        }
        CapturedLocation loc = tpl == null ? null : tpl.captured();
        List<LocationMarker> spawns = loc == null ? List.of() : new ArrayList<>(loc.markers("spawn"));
        List<CampLayout.Post> posts = new ArrayList<>();
        List<CampRoster.Member> roster = new ArrayList<>();
        if (!spawns.isEmpty()) {
            spawns.sort(Comparator.comparingInt(CampTemplate::order));
            List<BlockPos> feet = new ArrayList<>();
            for (int i = 0; i < spawns.size(); i++) {
                LocationMarker m = spawns.get(i);
                BlockPos w = m.world(tpl.origin(), tpl.placementRotation());
                boolean chief = m.name().startsWith("chief");
                boolean tower = m.name().startsWith("tower");
                boolean archer = tower || m.name().startsWith("archer");
                boolean qi = chief || m.name().endsWith("_qi");
                posts.add(new CampLayout.Post(w.getX() - camp.centre.getX(), w.getZ() - camp.centre.getZ(), tower, chief));
                feet.add(w);
                roster.add(new CampRoster.Member(i, archer, qi, chief, i));
            }
            camp.templateFeet = List.copyOf(feet);
        } else {
            CampLayout plan = CampLayout.plan(camp.seed);
            Rotation rot = shell.rotation();
            for (CampLayout.Post p : plan.posts()) {
                BlockPos r = new BlockPos(p.dx(), 0, p.dz()).rotate(rot);
                posts.add(new CampLayout.Post(r.getX(), r.getZ(), p.onTower(), p.chief()));
            }
            roster.addAll(CampRoster.plan(plan));
        }
        camp.templateRoster = List.copyOf(roster);
        camp.templatePosts = List.copyOf(posts);
    }

    /** Chief, then the tower, then the rest in the manifest's order. */
    private static int order(LocationMarker m) {
        return m.name().startsWith("chief") ? 0 : m.name().startsWith("tower") ? 1 : 2;
    }

    public static List<CampLayout.Post> posts(BanditCampData.Camp camp) {
        return camp.templatePosts != null ? camp.templatePosts : CampLayout.plan(camp.seed).posts();
    }

    public static List<CampRoster.Member> roster(BanditCampData.Camp camp) {
        return camp.templateRoster != null ? camp.templateRoster : CampRoster.plan(CampLayout.plan(camp.seed));
    }
}
