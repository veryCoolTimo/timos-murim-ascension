package io.github.verycooltimo.murim.sect.vault;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import io.github.verycooltimo.murim.quest.Clues;
import io.github.verycooltimo.murim.quest.PlumRestoration;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.util.List;
import java.util.Map;

/**
 * {@code /murim dev vault ...} — the main vault for testing and for filming (permission 2): {@code build} stamps the plan into the
 * running world (an older world, or after a plan change), {@code tp <place>} goes there, {@code rite} does the founders' rite
 * at the real tablets for the caller, {@code door} opens the door as with one Twenty-Four Plum form, {@code dig} digs the middle shelf out,
 * {@code fragments <n...|all>} hands over fragments and restores Rainfall up to five stars.
 */
public final class VaultCommand {

    /** Places: local x, y (standing), z, and the direction to look, in the plan's frame. */
    private static final Map<String, int[]> PLACES = Map.of(
            "hall", new int[] {13, 177, 89, 3},
            "shaft", new int[] {5, 171, 89, 2},
            "landing", new int[] {-6, 162, 92, 0},
            "tunnel", new int[] {15, 147, 90, 1},
            "door", new int[] {15, 147, 72, 1},
            "study", new int[] {15, 147, 60, 1},
            "pit", new int[] {15, 140, 54, 0});

    private VaultCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("vault")
                .then(Commands.literal("build").executes(c -> build(c.getSource())))
                .then(Commands.literal("tp").then(Commands.argument("place", StringArgumentType.word())
                        .suggests((c, b) -> SharedSuggestionProvider.suggest(PLACES.keySet(), b))
                        .executes(c -> tp(c.getSource(), StringArgumentType.getString(c, "place")))))
                .then(Commands.literal("at")
                        .then(Commands.argument("u", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-200, 200))
                                .then(Commands.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer(0, 400))
                                        .then(Commands.argument("v", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-200, 300))
                                                .then(Commands.argument("dir", StringArgumentType.word())
                                                        .suggests((c, b) -> SharedSuggestionProvider.suggest(List.of("n", "e", "s", "w"), b))
                                                        .then(Commands.argument("pitch", com.mojang.brigadier.arguments.IntegerArgumentType.integer(-90, 90))
                                                                .executes(c -> at(c.getSource(),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "u"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "y"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "v"),
                                                                        StringArgumentType.getString(c, "dir"),
                                                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "pitch")))))))))
                .then(Commands.literal("rite").executes(c -> rite(c.getSource())))
                .then(Commands.literal("dig").executes(c -> dig(c.getSource())))
                .then(Commands.literal("door").executes(c -> door(c.getSource())))
                .then(Commands.literal("fragments").then(Commands.argument("ids", StringArgumentType.greedyString())
                        .executes(c -> fragments(c.getSource(), StringArgumentType.getString(c, "ids")))));
    }

    private static MountHuaSite site(CommandSourceStack s) {
        MountHuaSite site = MountHuaSites.get(s.getServer());
        if (site == null || !HuaVault.applies(site)) {
            s.sendFailure(Component.literal("no canonical Mount Hua site"));
            return null;
        }
        return site;
    }

    private static int build(CommandSourceStack s) {
        MountHuaSite site = site(s);
        if (site == null) {
            return 0;
        }
        int n = HuaVault.place(s.getLevel(), site, null, 2);
        s.sendSuccess(() -> Component.literal("vault: " + n + " blocks placed"), true);
        return 1;
    }

    private static int tp(CommandSourceStack s, String place) throws RuntimeException {
        MountHuaSite site = site(s);
        int[] p = PLACES.get(place);
        if (site == null || p == null) {
            return 0;
        }
        try {
            ServerPlayer player = s.getPlayerOrException();
            BlockPos at = HuaVault.world(site, new BlockPos(p[0], p[1], p[2]));
            Direction look = HuaVault.turn(site, switch (p[3]) {
                case 0 -> Direction.NORTH;
                case 1 -> Direction.NORTH;
                case 2 -> Direction.WEST;
                default -> Direction.SOUTH;
            });
            player.teleportTo(s.getLevel(), at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, look.toYRot(), p[3] == 2 || p[3] == 3 ? 20.0F : 5.0F);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            s.sendFailure(Component.literal("players only"));
            return 0;
        }
    }

    /** Stands the caller at a local block of the plan (feet cell), looking along a local direction n, e, s or w at a pitch. */
    private static int at(CommandSourceStack s, int u, int y, int v, String dir, int pitch) {
        MountHuaSite site = site(s);
        if (site == null) {
            return 0;
        }
        try {
            ServerPlayer player = s.getPlayerOrException();
            BlockPos at = HuaVault.world(site, new BlockPos(u, y, v));
            Direction local = switch (dir) {
                case "e" -> Direction.EAST;
                case "s" -> Direction.SOUTH;
                case "w" -> Direction.WEST;
                default -> Direction.NORTH;
            };
            player.teleportTo(s.getLevel(), at.getX() + 0.5D, at.getY(), at.getZ() + 0.5D, HuaVault.turn(site, local).toYRot(), pitch);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
    }

    private static int rite(CommandSourceStack s) {
        MountHuaSite site = site(s);
        if (site == null) {
            return 0;
        }
        try {
            ServerPlayer p = s.getPlayerOrException();
            ServerLevel level = s.getLevel();
            for (int o = 1; o <= 4; o++) {
                VaultMechanism.tablet(level, p, HuaVault.tablet(site, o), o);
            }
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
    }

    private static int door(CommandSourceStack s) {
        MountHuaSite site = site(s);
        if (site == null) {
            return 0;
        }
        try {
            ServerPlayer p = s.getPlayerOrException();
            // Operator shortcut; normal play still requires one performed Twenty-Four Plum form.
            VaultDoorBlock.wake(s.getLevel(), HuaVault.world(site, VaultPlan.doorCorner()));
            p.setData(io.github.verycooltimo.murim.registry.ModAttachments.SECT,
                    p.getData(io.github.verycooltimo.murim.registry.ModAttachments.SECT)
                            .with(io.github.verycooltimo.murim.sect.seal.SealAccess.VAULT_FLAG));
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException e) {
            return 0;
        }
    }

    /**
     * Digs ONE block of the middle shelf out per call, like a pick would (top row first, then the next; the vanilla break effects only):
     * breaking a block never triggers the rest. Returns 0 when nothing is left.
     */
    private static int dig(CommandSourceStack s) {
        MountHuaSite site = site(s);
        if (site == null) {
            return 0;
        }
        for (int y = VaultPlan.TUNNEL_FLOOR + 4; y >= VaultPlan.TUNNEL_FLOOR + 1; y--) {
            for (int v = VaultPlan.SHELF_V + VaultPlan.SHELF_DEPTH - 1; v >= VaultPlan.SHELF_V; v--) {
                for (int u = VaultPlan.MIDDLE_U; u < VaultPlan.MIDDLE_U + VaultPlan.SHELF_W; u++) {
                    BlockPos at = HuaVault.world(site, new BlockPos(u, y, v));
                    if (!s.getLevel().getBlockState(at).isAir()) {
                        s.getLevel().destroyBlock(at, false);
                        return 1;
                    }
                }
            }
        }
        return 0;
    }

    private static int fragments(CommandSourceStack s, String ids) {
        try {
            ServerPlayer p = s.getPlayerOrException();
            List<String> list = ids.trim().equals("all") ? List.of("0", "1", "2", "3", "4", "5") : List.of(ids.trim().split("[ ,]+"));
            for (String id : list) {
                Clues.give(p, PlumRestoration.clue(Integer.parseInt(id)));
            }
            PlumRestoration.sync(p);
            s.sendSuccess(() -> Component.translatable("murim.vault.fragments.result"), false);
            return 1;
        } catch (com.mojang.brigadier.exceptions.CommandSyntaxException | NumberFormatException e) {
            s.sendFailure(Component.literal("usage: fragments 0 1 3 5 | all"));
            return 0;
        }
    }
}
