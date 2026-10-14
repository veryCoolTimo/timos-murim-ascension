package io.github.verycooltimo.murim.world.build;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * GameTests of the red timber kit (docs/design/33-building-kit.md): the pillar goes where you click,
 * stacks keep their offset and join up/down, beams join along their axis and see pillars, sneak-click
 * with an empty hand moves a pillar. Placement goes through BlockItem#place with a real hit result,
 * the same path as a player's click.
 *
 * <p>API: reference/minecraft-src/net/minecraft/world/item/BlockItem.java#place,
 * BlockPlaceContext(Level, Player, InteractionHand, ItemStack, BlockHitResult).
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class BuildingKitGameTests {

    private BuildingKitGameTests() {
    }

    /** small_floor is solid up to y=2 and has a barrier lid at y=5: two free layers, test y=1 and 2. */
    private static final int DY = 2;

    private static BlockPos at(int x, int y, int z) {
        return new BlockPos(x, y + DY, z);
    }

    private static Vec3 v(double x, double y, double z) {
        return new Vec3(x, y + DY, z);
    }

    /** Clicks {@code face} of the block at relative {@code clicked}, at relative point {@code hit}, holding {@code item}. */
    private static BlockState click(GameTestHelper helper, BlockItem item, BlockPos clicked, Direction face, Vec3 hit) {
        return clickAs(helper, null, item, clicked, face, hit);
    }

    private static BlockState clickAs(GameTestHelper helper, ServerPlayer player, BlockItem item, BlockPos clicked, Direction face, Vec3 hit) {
        BlockHitResult result = new BlockHitResult(helper.absoluteVec(hit), face, helper.absolutePos(clicked), false);
        BlockPlaceContext ctx = new BlockPlaceContext(helper.getLevel(), player, InteractionHand.MAIN_HAND, new ItemStack(item), result);
        BlockPos target = ctx.getClickedPos();
        var res = item.place(ctx);
        BlockState s = helper.getLevel().getBlockState(target);
        helper.assertTrue(s.is(item.getBlock()), "placing " + item + " at " + target + " clicked " + helper.getLevel().getBlockState(helper.absolutePos(clicked))
                + " gave " + res + " / " + s);
        return s;
    }

    private static BlockItem item(net.minecraft.world.level.block.Block b) {
        return (BlockItem) b.asItem();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void pillarOffsetFollowsHit(GameTestHelper helper) {
        BlockItem pillar = item(ModBlocks.RED_PILLAR.get());
        helper.setBlock(at(1, 0, 1), Blocks.STONE);
        // top face of stone, near its north-east corner -> pillar in the north-east corner
        BlockState a = click(helper, pillar, at(1, 0, 1), Direction.UP, v(1.9, 1.0, 1.1));
        helper.assertTrue(a.getValue(RedPillarBlock.OFFSET) == TimberOffset.NORTH_EAST, "corner click gave " + a);
        // clicking the pillar top far from it still stacks straight
        BlockState b = click(helper, pillar, at(1, 1, 1), Direction.UP, v(1.2, 2.0, 1.8));
        helper.assertTrue(b.getValue(RedPillarBlock.OFFSET) == TimberOffset.NORTH_EAST, "stack lost offset: " + b);
        BlockState lower = helper.getBlockState(at(1, 1, 1));
        helper.assertTrue(lower.getValue(RedPillarBlock.UP) == RedPillarBlock.Joint.RUN && (lower.getValue(RedPillarBlock.DOWN) != RedPillarBlock.Joint.RUN), "lower pillar joins: " + lower);
        helper.assertTrue(b.getValue(RedPillarBlock.DOWN) == RedPillarBlock.Joint.RUN && (b.getValue(RedPillarBlock.UP) != RedPillarBlock.Joint.RUN), "upper pillar joins: " + b);
        // east face of a wall, middle height -> pillar hugs the wall (west edge of the new block)
        helper.setBlock(at(0, 1, 0), Blocks.STONE);
        BlockState c = click(helper, pillar, at(0, 1, 0), Direction.EAST, v(1.0, 1.5, 0.5));
        helper.assertTrue(c.getValue(RedPillarBlock.OFFSET) == TimberOffset.WEST, "wall click gave " + c);
        helper.succeed();
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void beamConnectsAlongAxis(GameTestHelper helper) {
        BlockItem beam = item(ModBlocks.RED_BEAM.get());
        helper.setBlock(at(0, 1, 1), Blocks.STONE);
        // east face of the stone, high up -> x beam at the top of the block
        BlockState a = click(helper, beam, at(0, 1, 1), Direction.EAST, v(1.0, 1.8, 1.5));
        helper.assertTrue(a.getValue(RedBeamBlock.AXIS) == Direction.Axis.X && a.getValue(RedBeamBlock.LEVEL) == RedBeamBlock.Level3.TOP
                && a.getValue(RedBeamBlock.SIDE) == RedBeamBlock.Side.CENTER, "first beam " + a);
        // click its end: the lintel goes on with the same slot
        BlockState b = click(helper, beam, at(1, 1, 1), Direction.EAST, v(2.0, 1.8, 1.5));
        helper.assertTrue(b.getValue(RedBeamBlock.LEVEL) == RedBeamBlock.Level3.TOP && b.getValue(RedBeamBlock.NEG_END) == RedBeamBlock.End.BEAM,
                "second beam " + b);
        helper.assertTrue(helper.getBlockState(at(1, 1, 1)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.BEAM, "first beam did not join");
        // a pillar flush against the end gets the brace, a centred one the tenon
        helper.setBlock(at(3, 1, 1), ModBlocks.RED_PILLAR.get().defaultBlockState().setValue(RedPillarBlock.OFFSET, TimberOffset.WEST));
        helper.assertTrue(helper.getBlockState(at(2, 1, 1)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR, "flush pillar not seen");
        helper.setBlock(at(3, 1, 1), ModBlocks.RED_PILLAR.get().defaultBlockState());
        helper.assertTrue(helper.getBlockState(at(2, 1, 1)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_MID, "centred pillar not seen");
        // a beam on top of the lintel, crossing it, does not join it
        BlockState cross = click(helper, beam, at(1, 1, 1), Direction.UP, v(1.5, 2.0, 1.5));
        helper.assertTrue(cross.getValue(RedBeamBlock.LEVEL) == RedBeamBlock.Level3.BOTTOM, "beam on top should sit at the bottom: " + cross);
        // bracket placed on the lintel follows its axis and side
        BlockState br = click(helper, item(ModBlocks.BEAM_BRACKET.get()), at(2, 1, 1), Direction.UP, v(2.5, 2.0, 1.5));
        helper.assertTrue(br.getValue(BeamBracketBlock.AXIS) == Direction.Axis.X && br.getValue(BeamBracketBlock.OFFSET) == TimberOffset.CENTER,
                "bracket " + br);
        helper.succeed();
    }

    /** Player without a login (as in JunkArtTests): vanilla packets go to an embedded channel. */
    private static ServerPlayer fakePlayer(GameTestHelper helper) {
        var cookie = net.minecraft.server.network.CommonListenerCookie.createInitial(
                new com.mojang.authlib.GameProfile(java.util.UUID.randomUUID(), "kit-test"), false);
        ServerPlayer p = new ServerPlayer(helper.getLevel().getServer(), helper.getLevel(), cookie.gameProfile(), cookie.clientInformation());
        net.minecraft.network.Connection connection = new net.minecraft.network.Connection(net.minecraft.network.protocol.PacketFlow.SERVERBOUND);
        new io.netty.channel.embedded.EmbeddedChannel(connection);
        new net.minecraft.server.network.ServerGamePacketListenerImpl(helper.getLevel().getServer(), connection, p, cookie);
        return p;
    }

    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void sneakClickCyclesPillar(GameTestHelper helper) {
        BlockPos low = at(1, 1, 1);
        helper.setBlock(low, ModBlocks.RED_PILLAR.get().defaultBlockState());
        helper.setBlock(low.above(), ModBlocks.RED_PILLAR.get().defaultBlockState());
        helper.assertTrue(helper.getBlockState(low).getValue(RedPillarBlock.UP) == RedPillarBlock.Joint.RUN, "stack did not join");
        ServerPlayer p = fakePlayer(helper);
        BlockHitResult hit = new BlockHitResult(helper.absoluteVec(v(1.5, 1.5, 1.5)), Direction.NORTH, helper.absolutePos(low), false);
        // without sneaking nothing happens
        helper.getBlockState(low).useWithoutItem(helper.getLevel(), p, hit);
        helper.assertTrue(helper.getBlockState(low).getValue(RedPillarBlock.OFFSET) == TimberOffset.CENTER, "moved without sneak");
        p.setShiftKeyDown(true);
        helper.getBlockState(low).useWithoutItem(helper.getLevel(), p, hit);
        BlockState moved = helper.getBlockState(low);
        helper.assertTrue(moved.getValue(RedPillarBlock.OFFSET) == TimberOffset.NORTH, "sneak-click gave " + moved);
        helper.assertTrue((moved.getValue(RedPillarBlock.UP) != RedPillarBlock.Joint.RUN), "moved pillar still joins the centred one");
        helper.assertTrue((helper.getBlockState(low.above()).getValue(RedPillarBlock.DOWN) != RedPillarBlock.Joint.RUN), "upper pillar still joins");
        helper.succeed();
    }

    private static BlockState beamX(RedBeamBlock.Level3 level) {
        return ModBlocks.RED_BEAM.get().defaultBlockState().setValue(RedBeamBlock.AXIS, Direction.Axis.X).setValue(RedBeamBlock.LEVEL, level);
    }

    /** Fence-like joint: a beam runs into a pillar wherever it stands; a stub on the far side reaches back into it. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void beamRunsThroughPillarAndStubCrosses(GameTestHelper helper) {
        helper.setBlock(at(1, 1, 1), beamX(RedBeamBlock.Level3.TOP));
        helper.setBlock(at(2, 1, 1), ModBlocks.RED_PILLAR.get().defaultBlockState().setValue(RedPillarBlock.OFFSET, TimberOffset.EAST));
        helper.assertTrue(helper.getBlockState(at(1, 1, 1)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_FAR,
                "beam does not run through a far pillar: " + helper.getBlockState(at(1, 1, 1)));
        // stub on the pillar's east face, high: points east at the beam's height, reaches back to the pillar
        BlockState stub = click(helper, item(ModBlocks.BEAM_STUB.get()), at(2, 1, 1), Direction.EAST, v(3.0, 1.8, 1.5));
        helper.assertTrue(stub.getValue(BeamStubBlock.FACING) == Direction.EAST && stub.getValue(BeamStubBlock.LEVEL) == RedBeamBlock.Level3.TOP
                && stub.getValue(BeamStubBlock.REACH) == BeamStubBlock.Reach.NEAR, "stub " + stub);
        helper.succeed();
    }

    /** Strut between two lintels, like a fence post between rails: stands on the lower beam, carries the upper one. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void strutStandsBetweenLintels(GameTestHelper helper) {
        for (int x = 0; x <= 2; x++) {
            helper.setBlock(at(x, 1, 1), beamX(RedBeamBlock.Level3.TOP));
        }
        helper.setBlock(at(0, 2, 1), beamX(RedBeamBlock.Level3.TOP));
        helper.setBlock(at(2, 2, 1), beamX(RedBeamBlock.Level3.TOP));
        BlockState strut = click(helper, item(ModBlocks.HANGING_POST.get()), at(1, 1, 1), Direction.UP, v(1.5, 2.0, 1.5));
        helper.assertTrue(strut.getValue(HangingPostBlock.DOWN) && strut.getValue(HangingPostBlock.CARRY)
                && strut.getValue(HangingPostBlock.AXIS) == Direction.Axis.X && strut.getValue(HangingPostBlock.OFFSET) == TimberOffset.CENTER,
                "strut " + strut);
        helper.assertTrue(helper.getBlockState(at(0, 2, 1)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.BEAM, "upper lintel broken at the strut");
        // without a beam below it is a short piece hanging free
        helper.setBlock(at(1, 1, 1), Blocks.AIR);
        helper.assertTrue(!helper.getBlockState(at(1, 2, 1)).getValue(HangingPostBlock.DOWN), "strut still thinks it stands on a beam");
        helper.succeed();
    }

    /** A bottom bracket between two top-level beams carries the lintel; the beams see it as more lintel. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void bracketCarriesLintel(GameTestHelper helper) {
        helper.setBlock(at(0, 1, 1), beamX(RedBeamBlock.Level3.TOP));
        helper.setBlock(at(2, 1, 1), beamX(RedBeamBlock.Level3.TOP));
        // null player looks north, so the bracket's arms run east-west, along the lintel
        BlockState br = click(helper, item(ModBlocks.BEAM_BRACKET.get()), at(1, 0, 1), Direction.UP, v(1.5, 1.0, 1.5));
        helper.assertTrue(br.getValue(BeamBracketBlock.CARRY) && br.getValue(BeamBracketBlock.REST) == BeamBracketBlock.Rest.LINTEL, "bracket " + br);
        helper.assertTrue(helper.getBlockState(at(0, 1, 1)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.BEAM, "lintel broken at the bracket");
        helper.assertTrue(helper.getBlockState(at(2, 1, 1)).getValue(RedBeamBlock.NEG_END) == RedBeamBlock.End.BEAM, "lintel broken at the bracket");
        helper.succeed();
    }

    /**
     * The column laid down as a beam: placed while sneaking on a side face it lies along that face's axis at
     * the clicked height; clicking its end continues the run; the run joins end to end and a beam crossing it
     * runs into it like into a standing pillar.
     */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void pillarLiesDownAsBeam(GameTestHelper helper) {
        BlockItem pillar = item(ModBlocks.RED_PILLAR.get());
        helper.setBlock(at(0, 1, 1), Blocks.STONE);
        ServerPlayer p = fakePlayer(helper);
        p.setShiftKeyDown(true);
        // east face of the stone, high up, middle across -> lies along x, high, centred
        BlockState a = clickAs(helper, p, pillar, at(0, 1, 1), Direction.EAST, v(1.0, 1.85, 1.5));
        helper.assertTrue(a.getValue(RedPillarBlock.AXIS) == Direction.Axis.X && a.getValue(RedPillarBlock.OFFSET) == TimberOffset.EAST,
                "lying pillar " + a);
        // its east end, clicked without sneaking: the run goes on with the same axis and offset
        BlockState b = click(helper, pillar, at(1, 1, 1), Direction.EAST, v(2.0, 1.85, 1.5));
        helper.assertTrue(b.getValue(RedPillarBlock.AXIS) == Direction.Axis.X && b.getValue(RedPillarBlock.OFFSET) == TimberOffset.EAST
                && b.getValue(RedPillarBlock.DOWN) == RedPillarBlock.Joint.RUN && (b.getValue(RedPillarBlock.UP) != RedPillarBlock.Joint.RUN), "second piece " + b);
        helper.assertTrue(helper.getBlockState(at(1, 1, 1)).getValue(RedPillarBlock.UP) == RedPillarBlock.Joint.RUN, "run did not join");
        // a z beam meeting the lying pillar from the north runs into it (pillar centred across x)
        // setBlock skips placement logic, so the new beam's own ends are computed with connect()
        helper.setBlock(at(1, 1, 0), RedBeamBlock.connect(ModBlocks.RED_BEAM.get().defaultBlockState().setValue(RedBeamBlock.AXIS, Direction.Axis.Z),
                helper.getLevel(), helper.absolutePos(at(1, 1, 0))));
        helper.assertTrue(helper.getBlockState(at(1, 1, 0)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_FAR
                || helper.getBlockState(at(1, 1, 0)).getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_MID,
                "beam does not meet the lying pillar: " + helper.getBlockState(at(1, 1, 0)));
        helper.succeed();
    }

    private static BlockState pillar(Direction.Axis axis) {
        return ModBlocks.RED_PILLAR.get().defaultBlockState().setValue(RedPillarBlock.AXIS, axis);
    }

    /** setBlock skips placement logic: compute the new pillar's own ends with connect() first. */
    private static void putPillar(GameTestHelper helper, BlockPos rel, Direction.Axis axis) {
        helper.setBlock(rel, RedPillarBlock.connect(pillar(axis), helper.getLevel(), helper.absolutePos(rel)));
    }

    /**
     * Standing and lying pillars join (author 07.10): a lying run runs into a standing column, a column runs up
     * into a run lying on it, a run at right angles runs into another run; no rings at those ends.
     */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void pillarsJoinAcrossAxes(GameTestHelper helper) {
        putPillar(helper, at(0, 1, 0), Direction.Axis.Y);
        putPillar(helper, at(0, 2, 0), Direction.Axis.Y);
        putPillar(helper, at(2, 1, 0), Direction.Axis.Y);
        putPillar(helper, at(1, 2, 0), Direction.Axis.X);
        putPillar(helper, at(2, 2, 0), Direction.Axis.X);
        putPillar(helper, at(1, 2, 1), Direction.Axis.Z);
        putPillar(helper, at(1, 2, 2), Direction.Axis.Z);
        RedPillarBlock.Joint runIntoColumn = helper.getBlockState(at(1, 2, 0)).getValue(RedPillarBlock.DOWN);
        RedPillarBlock.Joint columnIntoRun = helper.getBlockState(at(2, 1, 0)).getValue(RedPillarBlock.UP);
        RedPillarBlock.Joint runIntoRun = helper.getBlockState(at(1, 2, 1)).getValue(RedPillarBlock.DOWN);
        helper.assertTrue(runIntoColumn == RedPillarBlock.Joint.MID, "lying run vs standing column: " + runIntoColumn);
        helper.assertTrue(columnIntoRun == RedPillarBlock.Joint.MID, "column under a run: " + columnIntoRun);
        helper.assertTrue(runIntoRun == RedPillarBlock.Joint.MID, "runs at right angles: " + runIntoRun);
        helper.assertTrue(helper.getBlockState(at(2, 2, 0)).getValue(RedPillarBlock.DOWN) == RedPillarBlock.Joint.RUN, "run broken");
        helper.assertTrue(helper.getBlockState(at(0, 2, 0)).getValue(RedPillarBlock.UP) == RedPillarBlock.Joint.NONE,
                "column top should keep its capital (nothing above)");
        helper.succeed();
    }

    /** The plaque lies flush on a centred 5 px pillar: moved back 5.5 px (11 half pixels) to its face. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void plaqueSitsFlushOnPillar(GameTestHelper helper) {
        putPillar(helper, at(1, 1, 2), Direction.Axis.Y);
        BlockState plaque = click(helper, (BlockItem) io.github.verycooltimo.murim.decor.SectPlaques.SECT_PLAQUE_ITEM.get(),
                at(1, 1, 2), Direction.NORTH, v(1.5, 1.5, 2 + 5.5 / 16));
        helper.assertTrue(plaque.getValue(io.github.verycooltimo.murim.decor.SectPlaqueBlock.INSET) == 11, "plaque inset " + plaque);
        helper.succeed();
    }

    /** Bracket snaps to what it is placed against: under a mid-level beam on its side slot, against a pillar's face. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void bracketSnapsToSupport(GameTestHelper helper) {
        helper.setBlock(at(1, 2, 0), ModBlocks.RED_BEAM.get().defaultBlockState().setValue(RedBeamBlock.AXIS, Direction.Axis.X)
                .setValue(RedBeamBlock.LEVEL, RedBeamBlock.Level3.MIDDLE).setValue(RedBeamBlock.SIDE, RedBeamBlock.Side.MINUS));
        BlockItem bracket = item(ModBlocks.BEAM_BRACKET.get());
        BlockState under = click(helper, bracket, at(1, 2, 0), Direction.DOWN, v(1.5, 2 + 6 / 16.0, 0.2));
        helper.assertTrue(under.getValue(BeamBracketBlock.REST) == BeamBracketBlock.Rest.UNDER_MID
                && under.getValue(BeamBracketBlock.OFFSET).dz() == -1 && under.getValue(BeamBracketBlock.AXIS) == Direction.Axis.X,
                "bracket under a mid beam " + under);
        putPillar(helper, at(0, 1, 2), Direction.Axis.Y);
        BlockState side = click(helper, bracket, at(0, 1, 2), Direction.EAST, v(10.5 / 16, 1.8, 2.5));
        helper.assertTrue(side.getValue(BeamBracketBlock.REACH) == BeamBracketBlock.Reach.NEG_MID
                && side.getValue(BeamBracketBlock.AXIS) == Direction.Axis.X && side.getValue(BeamBracketBlock.OFFSET).dx() == -1,
                "bracket against a pillar " + side);
        helper.succeed();
    }

    /**
     * Two columns a block apart: a column item clicked on the side of one, between them, lies down there at the
     * clicked height and runs into both (author 07.10). A beam item does the same with a beam.
     */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void pillarSpansGapBetweenColumns(GameTestHelper helper) {
        putPillar(helper, at(0, 1, 1), Direction.Axis.Y);
        putPillar(helper, at(2, 1, 1), Direction.Axis.Y);
        BlockState span = click(helper, item(ModBlocks.RED_PILLAR.get()), at(0, 1, 1), Direction.EAST, v(8.5 / 16, 1.85, 1.5));
        helper.assertTrue(span.getValue(RedPillarBlock.AXIS) == Direction.Axis.X
                && span.getValue(RedPillarBlock.DOWN) == RedPillarBlock.Joint.MID && span.getValue(RedPillarBlock.UP) == RedPillarBlock.Joint.MID,
                "span " + span);
        helper.assertTrue(span.getValue(RedPillarBlock.OFFSET).dx() == 1, "span not high: " + span);
        putPillar(helper, at(0, 1, 3), Direction.Axis.Y);
        putPillar(helper, at(2, 1, 3), Direction.Axis.Y);
        BlockState beam = click(helper, item(ModBlocks.RED_BEAM.get()), at(0, 1, 3), Direction.EAST, v(10.5 / 16, 1.5, 3.5));
        helper.assertTrue(beam.getValue(RedBeamBlock.NEG_END) == RedBeamBlock.End.PILLAR_MID
                && beam.getValue(RedBeamBlock.POS_END) == RedBeamBlock.End.PILLAR_MID, "beam between columns " + beam);
        helper.succeed();
    }

    /** Two columns side by side: a beam item used between them makes a bridge beam on the lower one. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void bridgeBetweenAdjacentColumns(GameTestHelper helper) {
        putPillar(helper, at(1, 1, 1), Direction.Axis.Y);
        putPillar(helper, at(2, 1, 1), Direction.Axis.Y);
        ServerPlayer p = fakePlayer(helper);
        ItemStack stack = new ItemStack(ModBlocks.RED_BEAM.get());
        p.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos abs = helper.absolutePos(at(2, 1, 1));
        BlockHitResult hit = new BlockHitResult(helper.absoluteVec(v(2 + 5.5 / 16, 1.9, 1.5)), Direction.WEST, abs, false);
        helper.getBlockState(at(2, 1, 1)).useItemOn(stack, helper.getLevel(), p, InteractionHand.MAIN_HAND, hit);
        BlockState low = helper.getBlockState(at(1, 1, 1));
        helper.assertTrue(low.getValue(RedPillarBlock.BRIDGED) && helper.getLevel().getBlockEntity(helper.absolutePos(at(1, 1, 1)))
                instanceof PillarBridgeBlockEntity be && be.get(Direction.Axis.X) == RedPillarBlock.Bridge.HIGH_MID, "bridge " + low);
        helper.setBlock(at(2, 1, 1), Blocks.AIR);
        helper.assertTrue(!helper.getBlockState(at(1, 1, 1)).getValue(RedPillarBlock.BRIDGED), "bridge left hanging");
        helper.succeed();
    }

    /**
     * No kit block may have a state count the client cannot bake: 1.21.1 bakes a multipart model per state, and the
     * bridge properties once made 43,200 states per column — the client ran out of memory (07.10). Covers every
     * block of the mod.
     */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void blockStateCountsStaySmall(GameTestHelper helper) {
        StringBuilder bad = new StringBuilder();
        for (net.minecraft.world.level.block.Block b : java.util.List.of(ModBlocks.RED_PILLAR.get(), ModBlocks.WHITE_PILLAR.get(),
                ModBlocks.RED_BEAM.get(), ModBlocks.BEAM_BRACKET.get(), ModBlocks.HANGING_POST.get(), ModBlocks.BEAM_STUB.get(),
                ModBlocks.TIMBER_DIVIDER.get())) {
            int n = b.getStateDefinition().getPossibleStates().size();
            if (n > MAX_STATES) {
                bad.append(b).append('=').append(n).append(' ');
            }
        }
        helper.assertTrue(bad.isEmpty(), "kit block with too many states (max " + MAX_STATES + "): " + bad);
        // Mod-wide, the real cost: the client bakes a multipart model per state, each testing every part.
        StringBuilder heavy = new StringBuilder();
        for (net.minecraft.world.level.block.Block b : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            net.minecraft.resources.ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b);
            if (!MurimMod.MODID.equals(id.getNamespace())) {
                continue;
            }
            long cost = (long) b.getStateDefinition().getPossibleStates().size() * multipartParts(id.getPath());
            if (cost > MAX_BAKE_COST) {
                heavy.append(id).append('=').append(cost).append(' ');
            }
        }
        helper.assertTrue(heavy.isEmpty(), "states x multipart parts over " + MAX_BAKE_COST + ": " + heavy);
        helper.succeed();
    }

    /** Parts in the block's multipart blockstate (1 for variants or none), read from the mod's resources. */
    private static int multipartParts(String block) {
        try (var in = BuildingKitGameTests.class.getResourceAsStream("/assets/" + MurimMod.MODID + "/blockstates/" + block + ".json")) {
            if (in == null) {
                return 1;
            }
            var json = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)).getAsJsonObject();
            return json.has("multipart") ? Math.max(1, json.getAsJsonArray("multipart").size()) : 1;
        } catch (java.io.IOException e) {
            return 1;
        }
    }

    static final int MAX_STATES = 4096;
    /** The column at 43,200 states x 405 parts (17.5M) ran the client out of memory; ours are now well under 1M. */
    static final long MAX_BAKE_COST = 2_000_000L;

    /** A white column stacks into a white run; on a red column it meets it with a joint, not as one run. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void whitePillarRuns(GameTestHelper helper) {
        BlockItem white = item(ModBlocks.WHITE_PILLAR.get());
        helper.setBlock(at(1, 0, 1), Blocks.STONE);
        BlockState a = click(helper, white, at(1, 0, 1), Direction.UP, v(1.5, 1.0, 1.5));
        BlockState b = click(helper, white, at(1, 1, 1), Direction.UP, v(1.5, 2.0, 1.5));
        helper.assertTrue(b.getValue(RedPillarBlock.DOWN) == RedPillarBlock.Joint.RUN, "white run " + b);
        putPillar(helper, at(3, 1, 1), Direction.Axis.Y);
        helper.setBlock(at(3, 2, 1), RedPillarBlock.connect(ModBlocks.WHITE_PILLAR.get().defaultBlockState(), helper.getLevel(),
                helper.absolutePos(at(3, 2, 1))));
        helper.assertTrue(helper.getBlockState(at(3, 2, 1)).getValue(RedPillarBlock.DOWN) != RedPillarBlock.Joint.RUN,
                "white on red joined as one run");
        helper.succeed();
    }

    /** Divider posts stack into one post; the cap stays only at the bottom end. */
    @GameTest(template = "small_floor", timeoutTicks = 20)
    public static void dividerStacks(GameTestHelper helper) {
        BlockItem divider = item(ModBlocks.TIMBER_DIVIDER.get());
        BlockState low = click(helper, divider, at(2, 0, 2), Direction.UP, v(2.5, 1.0, 2.5));
        BlockState high = click(helper, divider, at(2, 1, 2), Direction.UP, v(2.9, 2.0, 2.9));
        helper.assertTrue(high.getValue(TimberDividerBlock.OFFSET) == low.getValue(TimberDividerBlock.OFFSET)
                && high.getValue(TimberDividerBlock.DOWN), "upper divider " + high);
        helper.assertTrue(helper.getBlockState(at(2, 1, 2)).getValue(TimberDividerBlock.UP)
                && !helper.getBlockState(at(2, 1, 2)).getValue(TimberDividerBlock.DOWN), "lower divider keeps the cap");
        helper.succeed();
    }
}
