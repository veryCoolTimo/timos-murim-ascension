package io.github.verycooltimo.murim.world.location;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.LibraryPiece;
import io.github.verycooltimo.murim.library.ModLibrary;
import io.github.verycooltimo.murim.world.camp.BanditCamp;
import io.github.verycooltimo.murim.world.camp.BanditCampPiece;
import io.github.verycooltimo.murim.world.fortress.Fortress;
import io.github.verycooltimo.murim.world.fortress.FortressData;
import io.github.verycooltimo.murim.world.fortress.FortressPiece;
import io.github.verycooltimo.murim.world.fortress.Fortresses;
import io.github.verycooltimo.murim.world.hua.MountHuaOverlay;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Dev commands of the location round trip (docs/design/28-location-capture.md), operator only:
 * <ul>
 *   <li>{@code /murim capture <location> [from to]} — take the author's edited location back: without corners the
 *       box is found from the structure the player stands in (camp, archive, fortress) or the sect shelf of
 *       Mount Hua; with corners — exactly that box;</li>
 *   <li>{@code /murim location info <location>} — what is captured and where it comes from;</li>
 *   <li>{@code /murim location place <location> [rotation]} — stamp it at the player (preview in a live world);</li>
 *   <li>{@code /murim location forget <location>} — drop this world's copy (resources and procedural come back).</li>
 * </ul>
 *
 * <p>API: reference/minecraft-src/net/minecraft/commands/arguments/coordinates/BlockPosArgument.java#getLoadedBlockPos,
 * reference/minecraft-src/net/minecraft/world/level/chunk/ChunkAccess.java#getReferencesForStructure/#getStartForStructure,
 * reference/neoforge-src/net/neoforged/neoforge/event/RegisterCommandsEvent.java.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class LocationCommand {

    /** How far (chunks) around the player a structure is looked for. */
    static final int SEARCH_CHUNKS = 8;

    private LocationCommand() {
    }

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("murim")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("capture")
                        .then(Commands.argument("location", StringArgumentType.word())
                                .suggests((c, b) -> SharedSuggestionProvider.suggest(ModLocations.KNOWN, b))
                                .executes(c -> capture(c, null, null))
                                .then(Commands.argument("from", BlockPosArgument.blockPos())
                                        .then(Commands.argument("to", BlockPosArgument.blockPos())
                                                .executes(c -> capture(c, BlockPosArgument.getLoadedBlockPos(c, "from"),
                                                        BlockPosArgument.getLoadedBlockPos(c, "to")))))))
                .then(Commands.literal("location")
                        .then(Commands.literal("info").then(location().executes(LocationCommand::info)))
                        .then(Commands.literal("forget").then(location().executes(LocationCommand::forget)))
                        .then(Commands.literal("place").then(location().executes(c -> place(c, Rotation.NONE))
                                .then(Commands.argument("rotation", StringArgumentType.word())
                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("0", "90", "180", "270"), b))
                                        .executes(c -> place(c, rotation(StringArgumentType.getString(c, "rotation")))))))));
    }

    private static com.mojang.brigadier.builder.RequiredArgumentBuilder<CommandSourceStack, String> location() {
        return Commands.argument("location", StringArgumentType.word())
                .suggests((c, b) -> SharedSuggestionProvider.suggest(ModLocations.KNOWN, b));
    }

    private static Rotation rotation(String deg) {
        return switch (deg) {
            case "90" -> Rotation.CLOCKWISE_90;
            case "180" -> Rotation.CLOCKWISE_180;
            case "270", "-90" -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    /** What a capture takes: the box and the procedural anchor inside it. */
    public record Region(BoundingBox box, LocationCapture.Anchor anchor, CapturedLocation.HuaFrame hua) {
    }

    private static int capture(CommandContext<CommandSourceStack> c, BlockPos from, BlockPos to) {
        CommandSourceStack source = c.getSource();
        String id = StringArgumentType.getString(c, "location").toLowerCase(Locale.ROOT);
        if (!id.matches("[a-z0-9_]+")) {
            source.sendFailure(Component.translatable("command.murim.capture.bad_id", id));
            return 0;
        }
        ServerLevel level = source.getLevel();
        BlockPos at = BlockPos.containing(source.getPosition());
        Region region = from == null ? auto(level, id, at) : explicit(level, id, BoundingBox.fromCorners(from, to));
        if (region == null) {
            source.sendFailure(Component.translatable("command.murim.capture.not_found", id));
            return 0;
        }
        BoundingBox box = region.box();
        if (box.getXSpan() > LocationCapture.MAX_XZ || box.getZSpan() > LocationCapture.MAX_XZ || box.getYSpan() > LocationCapture.MAX_Y) {
            source.sendFailure(Component.translatable("command.murim.capture.too_big", box.getXSpan(), box.getYSpan(), box.getZSpan(),
                    LocationCapture.MAX_XZ, LocationCapture.MAX_Y));
            return 0;
        }
        try {
            LocationCapture.Result r = LocationCapture.capture(level, id, box, region.anchor(), region.hua(), true);
            CapturedLocation loc = r.location();
            source.sendSuccess(() -> Component.translatable("command.murim.capture.done", id, box.getXSpan(), box.getYSpan(),
                    box.getZSpan(), loc.parts().size(), r.blocks(), r.entities()).withStyle(ChatFormatting.GREEN), true);
            source.sendSuccess(() -> Component.translatable("command.murim.capture.markers", markerSummary(loc)), false);
            source.sendSuccess(() -> Component.translatable(r.sourceDir() != null ? "command.murim.capture.saved_source"
                    : "command.murim.capture.saved_world", String.valueOf(r.sourceDir() != null ? r.sourceDir() : r.worldDir())), false);
            return 1;
        } catch (Exception e) {
            MurimMod.LOGGER.error("Capture {} failed", id, e);
            source.sendFailure(Component.translatable("command.murim.capture.failed", id, String.valueOf(e.getMessage())));
            return 0;
        }
    }

    static String markerSummary(CapturedLocation loc) {
        if (loc.markers().isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (LocationMarker m : loc.markers()) {
            if (!sb.isEmpty()) {
                sb.append(", ");
            }
            sb.append(m.type()).append(':').append(m.name());
        }
        return sb.toString();
    }

    /** The region of a known location around the player. */
    public static Region auto(ServerLevel level, String id, BlockPos at) {
        switch (id) {
            case ModLocations.HUA_SECT -> {
                MountHuaSite site = MountHuaSites.get(level.getServer());
                if (site == null) {
                    return null;
                }
                BoundingBox box = MountHuaOverlay.defaultBox(site);
                return new Region(box, null, MountHuaOverlay.frame(site, new BlockPos(box.minX(), box.minY(), box.minZ())));
            }
            case ModLocations.CAMP -> {
                StructureStart s = nearestStart(level, BanditCamp.KEY, at);
                return s == null ? null : fromStart(s, null);
            }
            case ModLocations.FORTRESS -> {
                StructureStart s = nearestStart(level, Fortress.KEY, at);
                if (s != null) {
                    return fromStart(s, null);
                }
                // A fortress built by /murim fortress place has no structure start.
                FortressData.Entry e = Fortresses.nearest(level, at);
                if (e == null || e.yard.distSqr(at) > 96 * 96) {
                    return null;
                }
                BoundingBox box = new BoundingBox(e.yard.getX() - 36, e.yard.getY() - 12, e.yard.getZ() - 36,
                        e.yard.getX() + 36, e.yard.getY() + 30, e.yard.getZ() + 36);
                return new Region(box, new LocationCapture.Anchor(e.yard, e.rotation.ordinal(), e.seed), null);
            }
            case ModLocations.ARCHIVE -> {
                StructureStart s = nearestStart(level, ModLibrary.STRUCTURE, at);
                return s == null ? null : fromStart(s, null);
            }
            default -> {
                return null;
            }
        }
    }

    /** An explicit box; the procedural anchor is taken from a known structure inside it, if any. */
    static Region explicit(ServerLevel level, String id, BoundingBox box) {
        LocationCapture.Anchor anchor = null;
        ResourceKey<Structure> key = switch (id) {
            case ModLocations.CAMP -> BanditCamp.KEY;
            case ModLocations.FORTRESS -> Fortress.KEY;
            case ModLocations.ARCHIVE -> ModLibrary.STRUCTURE;
            default -> null;
        };
        if (key != null) {
            StructureStart s = nearestStart(level, key, box.getCenter());
            Region r = s == null ? null : fromStart(s, box);
            if (r != null && r.anchor() != null && box.isInside(r.anchor().world())) {
                anchor = r.anchor();
            }
        }
        CapturedLocation.HuaFrame hua = null;
        if (id.equals(ModLocations.HUA_SECT)) {
            MountHuaSite site = MountHuaSites.get(level.getServer());
            if (site != null) {
                hua = MountHuaOverlay.frame(site, new BlockPos(box.minX(), box.minY(), box.minZ()));
            }
        }
        return new Region(box, anchor, hua);
    }

    /** Box of a start's pieces (+2 around) and the anchor of its camp/fortress piece. */
    static Region fromStart(StructureStart s, BoundingBox fixed) {
        BoundingBox box = null;
        LocationCapture.Anchor anchor = null;
        for (StructurePiece p : s.getPieces()) {
            box = box == null ? p.getBoundingBox() : encapsulate(box, p.getBoundingBox());
            if (p instanceof BanditCampPiece camp) {
                anchor = new LocationCapture.Anchor(new BlockPos(camp.centreX(), camp.height(0), camp.centreZ()),
                        camp.rotation().ordinal(), camp.seed());
            } else if (p instanceof FortressPiece f) {
                anchor = new LocationCapture.Anchor(new BlockPos(f.centreX(), f.floorY(), f.centreZ()), f.rotation().ordinal(), f.seed());
            } else if (p instanceof LibraryPiece lib) {
                anchor = new LocationCapture.Anchor(lib.archive(13, io.github.verycooltimo.murim.library.LibraryPlan.TOP, 13),
                        0, lib.seed());
            }
        }
        if (box == null) {
            return null;
        }
        return new Region(fixed != null ? fixed : box.inflatedBy(2, 0, 2), anchor, null);
    }

    private static BoundingBox encapsulate(BoundingBox a, BoundingBox b) {
        return new BoundingBox(Math.min(a.minX(), b.minX()), Math.min(a.minY(), b.minY()), Math.min(a.minZ(), b.minZ()),
                Math.max(a.maxX(), b.maxX()), Math.max(a.maxY(), b.maxY()), Math.max(a.maxZ(), b.maxZ()));
    }

    /** Nearest start of a structure referenced by the loaded chunks around {@code at}. */
    public static StructureStart nearestStart(ServerLevel level, ResourceKey<Structure> key, BlockPos at) {
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(key);
        if (structure == null) {
            return null;
        }
        ChunkPos pc = new ChunkPos(at);
        StructureStart best = null;
        double bestD = Double.MAX_VALUE;
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (int dx = -SEARCH_CHUNKS; dx <= SEARCH_CHUNKS; dx++) {
            for (int dz = -SEARCH_CHUNKS; dz <= SEARCH_CHUNKS; dz++) {
                if (!level.hasChunk(pc.x + dx, pc.z + dz)) {
                    continue;
                }
                for (long ref : level.getChunk(pc.x + dx, pc.z + dz).getReferencesForStructure(structure)) {
                    if (!seen.add(ref)) {
                        continue;
                    }
                    ChunkAccess chunk = level.getChunk(ChunkPos.getX(ref), ChunkPos.getZ(ref), ChunkStatus.STRUCTURE_STARTS, true);
                    StructureStart s = chunk == null ? null : chunk.getStartForStructure(structure);
                    if (s == null || !s.isValid()) {
                        continue;
                    }
                    double d = s.getBoundingBox().getCenter().distSqr(at);
                    if (d < bestD) {
                        bestD = d;
                        best = s;
                    }
                }
            }
        }
        return best;
    }

    private static String arg(CommandContext<CommandSourceStack> c) {
        return StringArgumentType.getString(c, "location").toLowerCase(Locale.ROOT);
    }

    private static int info(CommandContext<CommandSourceStack> c) {
        String id = arg(c);
        CapturedLocation loc = LocationTemplates.get(c.getSource().getServer(), id);
        if (loc == null) {
            c.getSource().sendFailure(Component.translatable("command.murim.location.none", id));
            return 0;
        }
        boolean world = java.nio.file.Files.isRegularFile(LocationTemplates.generatedDir(c.getSource().getServer(), id)
                .resolve(LocationTemplates.MANIFEST));
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.location.info", id, loc.size().getX(), loc.size().getY(),
                loc.size().getZ(), loc.parts().size(), loc.markers().size(),
                Component.translatable(world ? "command.murim.location.source.world" : "command.murim.location.source.mod")), false);
        c.getSource().sendSuccess(() -> Component.translatable("command.murim.capture.markers", markerSummary(loc)), false);
        return 1;
    }

    private static int forget(CommandContext<CommandSourceStack> c) {
        String id = arg(c);
        try {
            boolean done = LocationCapture.deleteWorldCopy(c.getSource().getServer(), id);
            c.getSource().sendSuccess(() -> Component.translatable(done ? "command.murim.location.forgot" : "command.murim.location.none", id), true);
            return done ? 1 : 0;
        } catch (Exception e) {
            c.getSource().sendFailure(Component.literal(String.valueOf(e.getMessage())));
            return 0;
        }
    }

    private static int place(CommandContext<CommandSourceStack> c, Rotation rot) {
        String id = arg(c);
        ServerLevel level = c.getSource().getLevel();
        CapturedLocation loc = LocationTemplates.get(level.getServer(), id);
        if (loc == null) {
            c.getSource().sendFailure(Component.translatable("command.murim.location.none", id));
            return 0;
        }
        Ground.Placement p;
        MountHuaSite site = MountHuaSites.get(level.getServer());
        if (loc.hua() != null && site != null) {
            p = MountHuaOverlay.placement(site, loc);
        } else {
            BlockPos at = BlockPos.containing(c.getSource().getPosition());
            p = Ground.place(loc, at.getX(), at.getZ(), rot, Ground.live(level));
        }
        BoundingBox box = loc.box(p.origin(), p.rotation());
        for (ChunkPos cp : LocationCapture.chunks(box)) {
            level.getChunk(cp.x, cp.z);
        }
        boolean ok = LocationPlacer.place(level.getServer(), level, loc, p.origin(), p.rotation(), null, level.getRandom(), 3);
        c.getSource().sendSuccess(() -> Component.translatable(ok ? "command.murim.location.placed" : "command.murim.location.partial",
                id, p.origin().toShortString(), p.rotation().name().toLowerCase(Locale.ROOT)), true);
        return ok ? 1 : 0;
    }
}
