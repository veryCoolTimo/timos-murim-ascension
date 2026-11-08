package io.github.verycooltimo.murim.quest;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.npc.GuestFighter;
import io.github.verycooltimo.murim.npc.ModNpcs;
import io.github.verycooltimo.murim.npc.NpcProfile;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import io.github.verycooltimo.murim.sect.SectLife;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Where the second batch of side quests (2 to 5) stands in the world, found from the layout file and the mountain's frame so it
 * is right at every rotation: the clue blocks (placed once, like {@link ClueSites}) and the three guest folk (supplier, impostor,
 * stall keeper), who are put there while a player is near and their quest has not sent them away. Quest data names only the
 * markers; this class is the only Java these places need.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class QuestSites {

    // Side quests stopped by the author on 24.10.
    public static final boolean ENABLED = false;

    private static final double WAKE = 64.0D;
    /** A guest who is gone (cut down, chased off) is stood up again only after this long. */
    static final long RESTAND_TICKS = 24000L;

    /** Set by the overheard hook of each quest (docs/design/45-quest-format.md): before it nothing of the quest is at the gate. */
    public static final String DEBT_HOOK = "quest.debt.hooked";
    public static final String IMPOSTOR_HOOK = "quest.impostor.hooked";

    /** A clue block of a group: where, and which spot id. The marker is where the group is looked for; dx/dz shift the block. */
    record Piece(String group, String marker, int dx, int dz, String spot, String requires) {
        Piece(String group, String marker, int dx, int dz, String spot) {
            this(group, marker, dx, dz, spot, "");
        }
    }

    static final List<Piece> PIECES = List.of(
            new Piece("east", QuestMarkers.EAST_SCENE, 0, 0, "missing_basket"),
            new Piece("east", QuestMarkers.EAST_SCENE, -9, 1, "rain_wall"),
            new Piece("gate", QuestMarkers.GATE_CART, 1, 1, "debt_bill", DEBT_HOOK),
            new Piece("yard", QuestMarkers.DEBT_YARD, 0, 0, "debt_crate", DEBT_HOOK),
            new Piece("stall", QuestMarkers.GATE_STALL, 1, 0, "impostor_receipts", IMPOSTOR_HOOK),
            new Piece("stall", QuestMarkers.GATE_STALL, 3, 2, "impostor_chest", IMPOSTOR_HOOK),
            new Piece("store", QuestMarkers.STORE_SHELF, 0, 0, "shelf_nail"),
            new Piece("store", QuestMarkers.PARCEL_SHED, 0, 0, "sok_parcel"));

    /** A guest who stands at a marker while {@code unless} (a player flag) is not set. */
    record Actor(NpcProfile profile, String marker, int dx, int dz, String unless, String requires) {
    }

    static final List<Actor> ACTORS = List.of(
            new Actor(NpcProfile.SUPPLIER, QuestMarkers.GATE_CART, -1, 0, "quest.debt.done", DEBT_HOOK),
            new Actor(NpcProfile.IMPOSTOR, QuestMarkers.GATE_STALL, 0, 2, "quest.impostor.out", IMPOSTOR_HOOK),
            new Actor(NpcProfile.TRADER, QuestMarkers.GATE_STALL, 2, -1, "", IMPOSTOR_HOOK));

    private QuestSites() {
    }

    // ------------------------------------------------------------------ markers from the layout

    /** The free cell of a layout-based marker, or null when the world has no layout (or the place is not loaded). */
    static BlockPos resolve(ServerLevel level, String marker) {
        BlockPos anchor = anchor(level, marker);
        if (anchor == null || !level.hasChunkAt(anchor)) return null;
        return ClueSites.freeFloor(level, anchor, 4);
    }

    /** The marker's anchor from the layout file, without touching the world. */
    static BlockPos anchor(ServerLevel level, String marker) {
        var layout = SectLife.layout(level);
        SectLayoutFile file = layout == null ? null : layout.file();
        if (file == null) return null;
        return switch (marker) {
            case QuestMarkers.GATE_CART -> gateOut(file, 4, 0);
            case QuestMarkers.GATE_STALL -> gateOut(file, 13, 5);
            case QuestMarkers.DEBT_YARD -> zoneAt(file, -3);
            case QuestMarkers.PARCEL_SHED -> zoneAt(file, 4);
            case QuestMarkers.STORE_SHELF -> {
                SectLayoutFile.Room r = file.room("storeroom.f0.r1");
                yield r == null ? null : new BlockPos((r.bounds().x0() + r.bounds().x1()) / 2, r.y(), (r.bounds().z0() + r.bounds().z1()) / 2);
            }
            default -> null;
        };
    }

    private static BlockPos zoneAt(SectLayoutFile file, int shift) {
        SectLayoutFile.Zone z = file.zone("treasury_yard");
        if (z == null) return null;
        SectLayoutFile.Rect r = z.rect();
        return new BlockPos((r.x0() + r.x1()) / 2 + shift, z.y(), (r.z0() + r.z1()) / 2);
    }

    /** Outside the sect gate: the gate door's outer cell moved {@code out} further out and {@code side} to the side. */
    private static BlockPos gateOut(SectLayoutFile file, int out, int side) {
        var gate = file.buildings().get("sect_gate");
        var hall = file.room("main_hall.f0.r1");
        if (gate == null) return null;
        for (String id : gate.doors()) {
            var d = file.door(id);
            if (d == null || !d.gate() || d.sideA().isEmpty() || d.sideB().isEmpty()) continue;
            BlockPos a = d.sideA().get(0), b = d.sideB().get(0);
            BlockPos centre = hall == null ? a : new BlockPos((hall.bounds().x0() + hall.bounds().x1()) / 2, a.getY(), (hall.bounds().z0() + hall.bounds().z1()) / 2);
            boolean aOut = a.distSqr(centre) > b.distSqr(centre);
            BlockPos o = aOut ? a : b, in = aOut ? b : a;
            int dx = Integer.signum(o.getX() - in.getX()), dz = Integer.signum(o.getZ() - in.getZ());
            return o.offset(dx * out + dz * side, 0, dz * out - dx * side);
        }
        return null;
    }

    // ------------------------------------------------------------------ placing

    @SubscribeEvent
    static void onTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 61) return;
        ServerLevel level = event.getServer().overworld();
        if (!ENABLED) {
            for (SectDisciple d : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(SectDisciple.class),
                    SectDisciple::questHeld)) d.questRelease();
            return;
        }
        if (level.players().isEmpty()) return;
        ClueSites data = ClueSites.get(level);
        for (ServerPlayer p : level.players()) {
            // side quests begin only once the player belongs to the sect: no strangers at the gate before that
            if (p.isSpectator() || !io.github.verycooltimo.murim.sect.SectService.state(p).member()) continue;
            for (Piece piece : PIECES) place(level, data, p, piece);
        }
        for (Actor a : ACTORS) stand(level, data, a);
        holds(level);
    }

    /** A member the quest keeps at a marker, hurt, while a player has {@code requires} and not {@code unless}. */
    record Hold(String key, String marker, String requires, String unless) {
    }

    static final List<Hold> HOLDS = List.of(new Hold("yul_jin", QuestMarkers.EAST_SCENE, "quest.missing.asked", "quest.missing.safe"));

    private static boolean wants(ServerPlayer p, String requires, String unless) {
        return !p.isSpectator() && io.github.verycooltimo.murim.sect.SectService.state(p).member()
                && (requires.isEmpty() || Quests.has(p, requires)) && (unless.isEmpty() || !Quests.has(p, unless));
    }

    /**
     * Quest-controlled placement: while any member needs it, the member lies wounded at the marker (so the rescue scene is not
     * empty), whatever his schedule says; when nobody needs it the hold is lifted. Derived from the flags every pass, so a reload
     * restores it.
     */
    static void holds(ServerLevel level) {
        for (Hold h : HOLDS) {
            ServerPlayer needy = null;
            for (ServerPlayer p : level.players()) {
                if (wants(p, h.requires(), h.unless())) { needy = p; break; }
            }
            BlockPos at = needy == null || !QuestMarkers.within(level, h.marker(), needy, WAKE) ? null
                    : QuestMarkers.resolve(level, h.marker());
            if (needy == null || at == null) {
                if (needy == null) {
                    for (SectDisciple d : level.getEntities(net.minecraft.world.level.entity.EntityTypeTest.forClass(SectDisciple.class),
                            d -> h.key().equals(d.memberKey()) && d.questHeld())) d.questRelease();
                }
                continue;
            }
            if (needy.distanceToSqr(at.getX() + 0.5, needy.getY(), at.getZ() + 0.5) > WAKE * WAKE) continue;
            SectDisciple d = QuestScenes.member(level, net.minecraft.world.phys.Vec3.atBottomCenterOf(at), h.key());
            if (d == null || d.escorting()) continue;
            net.minecraft.world.phys.Vec3 spot = net.minecraft.world.phys.Vec3.atBottomCenterOf(at);
            if (!d.questHeld() || d.distanceToSqr(spot) > 36) {
                d.teleportTo(spot.x, spot.y, spot.z);
            }
            d.questHold(spot);
        }
    }

    static void place(ServerLevel level, ClueSites data, ServerPlayer p, Piece piece) {
        String key = piece.group() + "." + piece.spot();
        if (data.where(key) != null || !piece.requires().isEmpty() && !Quests.has(p, piece.requires())) return;
        if (!QuestMarkers.within(level, piece.marker(), p, WAKE)) return;
        BlockPos at = QuestMarkers.resolve(level, piece.marker());
        if (at == null || p.distanceToSqr(at.getX() + 0.5, p.getY(), at.getZ() + 0.5) > WAKE * WAKE) return;
        BlockPos q = ClueSites.freeFloor(level, at.offset(piece.dx(), 0, piece.dz()), 2);
        if (q == null) q = at;
        ClueBlock.place(level, q, piece.spot());
        data.record(key, q);
    }

    /** One guest, judged once against every member player near his spot (not once per player). */
    static void stand(ServerLevel level, ClueSites data, Actor a) {
        List<ServerPlayer> near = new java.util.ArrayList<>();
        for (ServerPlayer p : level.players()) {
            if (!p.isSpectator() && QuestMarkers.within(level, a.marker(), p, WAKE) && io.github.verycooltimo.murim.sect.SectService.state(p).member()) near.add(p);
        }
        if (near.isEmpty()) return;
        BlockPos at = QuestMarkers.resolve(level, a.marker());
        if (at == null) return;
        BlockPos home = at.offset(a.dx(), 0, a.dz());
        AABB box = new AABB(home).inflate(24, 8, 24);
        var found = level.getEntitiesOfClass(GuestFighter.class, box, g -> g.profile() == a.profile());
        boolean needed = false, replaced = false;
        for (ServerPlayer p : near) {
            if (!Quests.has(p, a.requires()) || !a.unless().isEmpty() && Quests.has(p, a.unless())) continue;
            needed = true;
            if (QuestScenes.replaces(p, a.profile().id)) replaced = true;
        }
        if (!needed || replaced) {
            found.forEach(net.minecraft.world.entity.Entity::discard);
            return;
        }
        if (!found.isEmpty()) {
            GuestFighter g = found.get(0);
            if (g.distanceToSqr(home.getX() + 0.5, home.getY(), home.getZ() + 0.5) > 100) g.teleportTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5);
            return;
        }
        // one cut down is back only the next day, not at once (author 22.10: «их убивают и они обратно восстанавливаются»)
        long now = level.getGameTime();
        Long last = data.lastStood(a.profile().id);
        if (last != null && now >= last && now - last < RESTAND_TICKS) return;
        data.markStood(a.profile().id, now);
        BlockPos feet = ClueSites.freeFloor(level, home, 3);
        spawn(level, a.profile(), feet == null ? home : feet);
    }

    /** A quest guest on the spot (also for the tests). */
    public static GuestFighter spawn(ServerLevel level, NpcProfile profile, BlockPos feet) {
        GuestFighter g = ModNpcs.FIGHTERS.get(profile).get().create(level);
        if (g == null) return null;
        g.moveTo(feet.getX() + 0.5, feet.getY(), feet.getZ() + 0.5, level.random.nextFloat() * 360, 0);
        g.finalizeSpawn(level, level.getCurrentDifficultyAt(feet), MobSpawnType.EVENT, null);
        g.setPersistenceRequired();
        g.restrictTo(feet, 4);
        level.addFreshEntity(g);
        return g;
    }
}
