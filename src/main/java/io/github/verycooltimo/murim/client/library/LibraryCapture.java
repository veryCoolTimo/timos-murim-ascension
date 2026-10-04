package io.github.verycooltimo.murim.client.library;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.library.JunkKind;
import io.github.verycooltimo.murim.library.LibraryMapFunction;
import io.github.verycooltimo.murim.library.LibraryPiece;
import io.github.verycooltimo.murim.library.LibraryPlan;
import io.github.verycooltimo.murim.library.ModLibrary;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.entity.ChiseledBookShelfBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructureStart;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Capture stand for the abandoned archive ({@code ./capture.sh <timeout> fp library}; only with
 * {@code -Dmurim.capture=true}): locates the nearest generated archive, flies the player in through the rock mouth,
 * opens the record barrel and reads the keeper's note, pulls three junk volumes from the top shelves and reads them,
 * walks down all four tiers and pulls the genuine manual from under the propped shelf, then opens it.
 *
 * <p>Dev only. Server actions run on the integrated server's thread ({@code server.execute}); the walk is client-side
 * creative flight along waypoints in the piece's local frame. A frame every {@code MURIM_CAPTURE_EVERY} ticks
 * (default 3), {@code MURIM_CAPTURE_FRAMES} in all; {@code TIME=night} is honoured.
 */
@EventBusSubscriber(modid = MurimMod.MODID, value = Dist.CLIENT)
public final class LibraryCapture {

    /** One scripted step: move/look over {@code ticks}, then run {@code action} once. */
    private record Step(Vec3 to, Vec3 look, int ticks, Runnable action, String note) {
    }

    private static int phase = -1;
    private static int wait;
    private static volatile LibraryPiece piece;
    private static final List<Step> steps = new ArrayList<>();
    private static int stepIndex;
    private static int stepTick;
    private static Vec3 from;
    private static float fromYaw;
    private static float fromPitch;
    private static int frame;
    private static int frames;
    private static int every;
    private static int tick;

    private LibraryCapture() {
    }

    private static boolean enabled() {
        return Boolean.getBoolean("murim.capture") && "library".equals(System.getProperty("murim.capture.technique"));
    }

    @SubscribeEvent
    static void onClientTick(ClientTickEvent.Post event) {
        if (!enabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.getSingleplayerServer() == null) {
            return;
        }
        if (phase == -1) {
            phase = 0;
            wait = 60;
            frames = Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_FRAMES", "400").trim());
            every = Math.max(1, Integer.parseInt(System.getenv().getOrDefault("MURIM_CAPTURE_EVERY", "3").trim()));
            mc.options.hideGui = true;
            mc.options.cloudStatus().set(net.minecraft.client.CloudStatus.OFF);
        }
        if (phase == 0 && --wait <= 0) {
            phase = 1;
            MinecraftServer server = mc.getSingleplayerServer();
            server.execute(() -> locate(server));
            wait = 600;
        }
        if (phase == 1) {
            // Waiting for the archive to be found and the chunks round the mouth to arrive and render.
            if (piece != null && mc.level.isLoaded(piece.world(11, 0, piece.mouthZ())) && wait-- < 480) {
                phase = 2;
                script(mc);
                MurimMod.LOGGER.info("Стенд архива: сценарий из {} шагов, подпёртый стеллаж № {}", steps.size(), piece.plan().proppedNumber());
            }
            return;
        }
        if (phase == 2) {
            run(mc);
        }
    }

    // ---- server side (integrated server thread) ------------------------------------------------------------

    private static void locate(MinecraftServer server) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = p.serverLevel();
        p.setGameMode(GameType.CREATIVE);
        p.getAbilities().flying = true;
        p.onUpdateAbilities();
        p.getInventory().clearContent();
        boolean night = "night".equalsIgnoreCase(System.getProperty("murim.capture.time", "day"));
        level.setDayTime(night ? 18000L : 6000L);
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DAYLIGHT).set(false, server);
        server.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
        BlockPos found = level.findNearestMapStructure(LibraryMapFunction.LIBRARIES, p.blockPosition(), 100, false);
        if (found == null) {
            MurimMod.LOGGER.error("Стенд архива: архив не найден");
            return;
        }
        Structure structure = level.registryAccess().registryOrThrow(Registries.STRUCTURE).get(ModLibrary.STRUCTURE);
        ChunkAccess chunk = level.getChunk(found.getX() >> 4, found.getZ() >> 4, ChunkStatus.STRUCTURE_STARTS, true);
        StructureStart start = chunk == null ? null : chunk.getStartForStructure(structure);
        if (start == null || start.getPieces().isEmpty() || !(start.getPieces().get(0) instanceof LibraryPiece lp)) {
            MurimMod.LOGGER.error("Стенд архива: нет куска в начале структуры у {}", found);
            return;
        }
        Vec3 at = Vec3.atBottomCenterOf(lp.world(11, lp.steps() + LibraryPlan.TOP + 2, lp.mouthZ() - 7));
        p.teleportTo(level, at.x, at.y, at.z, 0.0F, 10.0F);
        MurimMod.LOGGER.info("Стенд архива: архив у {}, вход {}", found, lp.world(11, 0, lp.mouthZ()));
        piece = lp;
    }

    /** Take up to three junk volumes of different kinds from top-tier shelves near the doorway into the hotbar. */
    private static void takeBooks(MinecraftServer server, LibraryPiece lp) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        ServerLevel level = p.serverLevel();
        List<JunkKind> wanted = new ArrayList<>(List.of(JunkKind.FAKE_GRAND, JunkKind.LOVE, JunkKind.HERETICAL, JunkKind.COOK, JunkKind.LEDGER));
        int slot = 0;
        for (LibraryPlan.Shelf s : lp.plan().shelves()) {
            if (s.tier() != 3 || slot >= 3) {
                continue;
            }
            BlockPos pos = lp.archive(s.ax(), s.y(), s.az());
            if (!(level.getBlockEntity(pos) instanceof ChiseledBookShelfBlockEntity be)) {
                continue;
            }
            for (int i = 0; i < 6 && slot < 3; i++) {
                ItemStack st = be.getItem(i);
                if (st.has(ModLibrary.JUNK.get()) && wanted.remove(st.get(ModLibrary.JUNK.get()).kind())) {
                    p.getInventory().setItem(slot++, be.removeItem(i, 1));
                }
            }
        }
        p.inventoryMenu.broadcastChanges();
    }

    private static void giveNote(MinecraftServer server, LibraryPiece lp) {
        ServerPlayer p = server.getPlayerList().getPlayers().get(0);
        BlockPos barrel = lp.archive(LibraryPlan.BARRELS[3][1], LibraryPlan.TOP + 1, LibraryPlan.WALL_LO);
        if (p.serverLevel().getBlockEntity(barrel) instanceof net.minecraft.world.level.block.entity.BarrelBlockEntity be) {
            p.getInventory().setItem(4, be.removeItem(13, 1));
        }
    }

    // ---- client script --------------------------------------------------------------------------------------

    private static Vec3 at(double x, double y, double z) {
        BlockPos b = piece.world((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        BlockPos o = piece.world(0, 0, 0);
        BlockPos ex = piece.world(1, 0, 0);
        BlockPos ez = piece.world(0, 0, 1);
        // Sub-block offsets follow the piece's axes.
        double fx = x - Math.floor(x);
        double fz = z - Math.floor(z);
        double wx = b.getX() + 0.5D + (ex.getX() - o.getX()) * (fx - 0.5D) + (ez.getX() - o.getX()) * (fz - 0.5D);
        double wz = b.getZ() + 0.5D + (ex.getZ() - o.getZ()) * (fx - 0.5D) + (ez.getZ() - o.getZ()) * (fz - 0.5D);
        return new Vec3(wx, b.getY() + (y - Math.floor(y)), wz);
    }

    /** Archive coordinates (az → z + tunnel). */
    private static Vec3 ar(double ax, double y, double az) {
        return at(ax, y, az + LibraryPlan.TUNNEL);
    }

    private static void move(Vec3 to, Vec3 look, int ticks, String note) {
        steps.add(new Step(to, look, ticks, null, note));
    }

    private static void act(int ticks, String note, Runnable action) {
        steps.add(new Step(null, null, ticks, action, note));
    }

    private static void script(Minecraft mc) {
        steps.clear();
        LibraryPiece lp = piece;
        MinecraftServer server = mc.getSingleplayerServer();
        int t = LibraryPlan.TUNNEL;
        int top = LibraryPlan.TOP + 1;
        int mz = lp.mouthZ();
        int my = lp.steps() + LibraryPlan.TOP + 1;
        // 1. The rock mouth from outside (a little above the path, so the outcrop reads), then in and down.
        move(at(11.5, my + 2.5, mz - 10), at(11.5, my + 2.5, mz + 2), 50, "rock mouth");
        move(at(11.5, my + 0.1, mz - 3), at(11.5, my + 1.8, mz + 1), 50, "approach");
        move(at(11.5, my + 0.1, mz + 0.5), at(11.5, my - 1.5, mz + 9), 40, "into the mouth");
        move(at(11.5, top + 0.1, t - 2), at(11.5, top + 1.2, t + 6), 22 * (lp.steps() + 3) / 4, "down the tunnel");
        // 2. The doorway, then the rail edge: the shaft opens below; look round the tiers.
        move(at(11.5, top + 0.1, t + 3.5), ar(11.5, top + 1.2, 16), 40, "doorway");
        move(ar(11.5, top + 0.1, 5.2), ar(12.5, 1, 13), 50, "rail edge, look down");
        act(30, "the shaft", null);
        move(ar(11.6, top + 0.1, 5.2), ar(22, 9, 20), 50, "look round");
        move(ar(11.7, top + 0.1, 5.2), ar(4, 12, 20), 60, "look round 2");
        // 3. The record barrel by the doorway: open it, take the keeper's note, read it.
        move(ar(7.5, top + 0.1, 3.2), ar(7.5, top + 0.4, 1.5), 30, "barrel");
        act(45, "open barrel", () -> use(mc, lp.archive(LibraryPlan.BARRELS[3][1], top, LibraryPlan.WALL_LO), Direction.NORTH));
        act(10, "close barrel", () -> close(mc));
        act(5, "take note", () -> server.execute(() -> giveNote(server, lp)));
        act(60, "read note", () -> read(mc, 4));
        act(30, "note page 2", () -> page(mc));
        act(10, "close note", () -> close(mc));
        // 4. Three junk volumes from the top shelves.
        move(ar(3.6, top + 0.1, 4.5), ar(1.5, top + 0.6, 4.5), 30, "shelves");
        act(20, "take books", () -> server.execute(() -> takeBooks(server, lp)));
        for (int i = 0; i < 3; i++) {
            final int slot = i;
            act(55, "read junk " + i, () -> read(mc, slot));
            act(35, "junk page", () -> page(mc));
            act(10, "close junk", () -> close(mc));
        }
        // 5. Down the tiers: west stairs, across the middle tier to the east stairs, the west stairs again.
        move(ar(4.9, top + 0.1, 6.8), ar(4.9, 12.5, 13), 35, "stair top");
        move(ar(4.9, 11.1, 12.5), ar(4.2, 12.6, 22), 45, "down to tier 2");
        move(ar(3.6, 11.1, 22.6), ar(16, 12.4, 22.6), 55, "tier 2 west");
        move(ar(21.5, 11.1, 22.6), ar(21.5, 11.5, 14), 80, "tier 2 north");
        move(ar(21.9, 11.1, 19.4), ar(21.9, 7.5, 13), 20, "east stair top");
        move(ar(21.9, 6.1, 13.4), ar(21.6, 7.6, 4), 45, "down to tier 1");
        move(ar(21.5, 6.1, 5.4), ar(10, 7.4, 5.4), 45, "tier 1 east");
        move(ar(4.9, 6.1, 5.4), ar(4.9, 7.2, 10), 80, "tier 1 south");
        move(ar(4.9, 6.1, 6.8), ar(4.9, 2.5, 13), 15, "west stair top");
        move(ar(4.9, 1.1, 12.5), ar(13, 2.4, 13), 45, "down to the bottom");
        // 6. The propped shelf: count to it, look at the wedge, pull the book, open it.
        int[] c = lp.plan().proppedCell();
        Vec3 shelf = ar(c[0] + 0.5, 1.1, c[1] + 0.5);
        Vec3 unit = ar(c[0] + 0.5, 2.6, c[1] + 0.5);
        Vec3 front = ar(c[0] + 0.5 + inward(c)[0] * 2.4, 1.1, c[1] + 0.5 + inward(c)[1] * 2.4);
        move(ar(13, 1.1, 13), unit, 60, "void bottom");
        move(ar(c[0] + 0.5 + inward(c)[0] * 4.5, 1.1, c[1] + 0.5 + inward(c)[1] * 4.5), unit, 60, "the shelf");
        move(front, shelf, 50, "propped shelf");
        act(50, "look at the wedge", null);
        act(10, "pull the book", () -> use(mc, lp.archive(c[0], 1, c[1]), facing(c)));
        move(ar(c[0] + 0.5 + inward(c)[0] * 1.3, 1.1, c[1] + 0.5 + inward(c)[1] * 1.3), shelf, 25, "pick it up");
        act(30, "select the manual", () -> select(mc, firstManual(mc)));
        act(80, "open the manual", () -> mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND));
        act(40, "manual page", () -> page(mc));
        act(40, "manual page 2", () -> page(mc));
        stepIndex = 0;
        stepTick = 0;
        from = mc.player.position();
        fromYaw = mc.player.getYRot();
        fromPitch = mc.player.getXRot();
    }

    /** Unit vector (ax, az) from the propped shelf's wall into the archive. */
    private static int[] inward(int[] c) {
        if (c[0] == LibraryPlan.WALL_LO) {
            return new int[]{1, 0};
        }
        if (c[0] == LibraryPlan.WALL_HI) {
            return new int[]{-1, 0};
        }
        return c[1] == LibraryPlan.WALL_LO ? new int[]{0, 1} : new int[]{0, -1};
    }

    private static Direction facing(int[] c) {
        int[] in = inward(c);
        return in[0] > 0 ? Direction.EAST : in[0] < 0 ? Direction.WEST : in[1] > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private static int firstManual(Minecraft mc) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getItem(i).is(io.github.verycooltimo.murim.registry.ModItems.TECHNIQUE_MANUAL.get())) {
                return i;
            }
        }
        return 0;
    }

    private static void run(Minecraft mc) {
        if (stepIndex < steps.size()) {
            Step s = steps.get(stepIndex);
            if (s.to() != null) {
                double k = Math.min(1.0D, (stepTick + 1) / (double) s.ticks());
                double e = k * k * (3 - 2 * k);
                Vec3 p = from.lerp(s.to(), e);
                Vec3 eye = p.add(0, mc.player.getEyeHeight(), 0);
                Vec3 d = s.look().subtract(eye);
                float yaw = (float) Math.toDegrees(Math.atan2(-d.x, d.z));
                float pitch = (float) -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z)));
                float yy = fromYaw + net.minecraft.util.Mth.wrapDegrees(yaw - fromYaw) * (float) e;
                float pp = fromPitch + (pitch - fromPitch) * (float) e;
                mc.player.absMoveTo(p.x, p.y, p.z, yy, pp);
                mc.player.setYHeadRot(yy);
                mc.player.setDeltaMovement(Vec3.ZERO);
                mc.player.getAbilities().flying = true;
            } else if (stepTick == 0 && s.action() != null) {
                MurimMod.LOGGER.info("Стенд архива: {}", s.note());
                s.action().run();
            }
            if (++stepTick >= s.ticks()) {
                if (s.to() != null) {
                    from = s.to();
                    fromYaw = mc.player.getYRot();
                    fromPitch = mc.player.getXRot();
                }
                stepIndex++;
                stepTick = 0;
            }
        }
        if (tick++ % every == 0 && frame < frames) {
            Screenshot.grab(mc.gameDirectory, String.format("murim_library_%03d.png", frame), mc.getMainRenderTarget(), m -> {
            });
            frame++;
        }
        if (frame >= frames) {
            phase = 3;
        }
    }

    private static void use(Minecraft mc, BlockPos pos, Direction face) {
        if (mc.gameMode != null) {
            select(mc, 8);
            mc.gameMode.useItemOn(mc.player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        }
    }

    private static void select(Minecraft mc, int slot) {
        mc.player.getInventory().selected = slot;
        if (mc.getConnection() != null) {
            mc.getConnection().send(new ServerboundSetCarriedItemPacket(slot));
        }
    }

    private static void read(Minecraft mc, int slot) {
        select(mc, slot);
        if (mc.gameMode != null && !mc.player.getInventory().getItem(slot).isEmpty()) {
            mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
        }
    }

    private static void page(Minecraft mc) {
        if (mc.screen != null) {
            mc.screen.mouseScrolled(0, 0, 0, -1);
        }
    }

    private static void close(Minecraft mc) {
        if (mc.screen != null) {
            mc.screen.onClose();
        }
        if (mc.player.containerMenu != mc.player.inventoryMenu) {
            mc.player.closeContainer();
        }
    }
}
