package io.github.verycooltimo.murim.sect;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.decor.IntSeating;
import io.github.verycooltimo.murim.decor.IntTabletopBlock;
import io.github.verycooltimo.murim.decor.IntWorkshop;
import io.github.verycooltimo.murim.decor.SectNamePlate;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.network.DialoguePayloads;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.world.hua.HuaBaked;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Newcomer arrival decisions of 18.10 (docs/design/39-player-path-v2.md): the camera tour in the mentor's dialogue, the glow
 * of the player's own bed, the absence lines and the contract board in the baked sect.
 */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class SectNewcomerGameTests {

    private static final String YARD = "sect_yard";
    /** The nodes of the tour in the mentor's dialogue, in order, with the shot each one names. */
    private static final List<String> SHOTS = List.of("main_hall", "dining", "training", "board", "bed");

    private SectNewcomerGameTests() {
    }

    // ------------------------------------------------------------------ camera tour data

    /** The mentor's reception leads through six shot nodes and back to the mentor; every line, option and shot id is real. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_newcomer")
    public static void tourDialogueChainsSixShotsWithLinesInBothLanguages(GameTestHelper h) throws Exception {
        Dialogue mentor = DialogueLoader.get(SectRole.MENTOR.dialogue());
        h.assertTrue(mentor != null, "mentor dialogue missing");
        // Reception also offers the repeatable basics explanation; find admission by its action.
        List<Dialogue.Option> admissions = mentor.nodes().get("reception").options().stream()
                .filter(o -> o.actions().stream().anyMatch(a -> a.type().equals("arrival_admit"))).toList();
        h.assertTrue(admissions.size() == 1, "reception must offer exactly one admission, found " + admissions.size());
        Dialogue.Option accept = admissions.get(0);
        String node = accept.next().orElseThrow();
        List<String> shots = new ArrayList<>();
        Set<String> keys = new HashSet<>();
        int guard = 0;
        while (mentor.nodes().get(node).shot().isPresent() && guard++ < 20) {
            Dialogue.Node n = mentor.nodes().get(node);
            shots.add(n.shot().get());
            keys.add(n.line());
            n.options().forEach(o -> keys.add(o.text()));
            // every tour line has a way to skip: the camera returns to the mentor at once
            h.assertTrue(n.options().stream().anyMatch(o -> o.next().filter("tour_end"::equals).isPresent()), node + " has no skip option");
            node = n.options().get(0).next().orElseThrow();
        }
        h.assertTrue(shots.equals(SHOTS), "tour shots are " + shots + ", expected " + SHOTS);
        h.assertTrue(node.equals("tour_end") && mentor.nodes().get(node).shot().isEmpty(),
                "the tour must end on a node without a shot (the camera is back on the mentor), was " + node);
        Dialogue.Node end = mentor.nodes().get(node);
        keys.add(end.line());
        end.options().forEach(o -> keys.add(o.text()));
        h.assertTrue(end.options().get(0).actions().stream().anyMatch(a -> "head_intro".equals(a.type())),
                "tour_end must lead the pending newcomer to the head before offering the journey");
        for (String lang : List.of("en_us", "ru_ru")) {
            JsonObject lines = lang(lang);
            for (String key : keys) {
                h.assertTrue(lines.has(key) && !lines.get(key).getAsString().isBlank(), lang + " lacks " + key);
            }
            List<String> words = new ArrayList<>(List.of("murim.sect.tour.dorm.dorm_3rd", "murim.sect.tour.dorm.dorm_2nd", "murim.sect.tour.any.dorm", "murim.sect.tour.any.floor", "murim.sect.name_plate.title", "block.murim.sect_name_plate"));
            for (int i = 1; i <= 8; i++) {
                if (i <= 6) words.add("murim.sect.tour.floor." + i);
            }
            for (String key : words) {
                h.assertTrue(lines.has(key) && !lines.get(key).getAsString().isBlank(), lang + " lacks " + key);
            }
            for (int tier : new int[] {1, 3, 7}) {
                for (int variant = 0; variant < SectAbsence.VARIANTS; variant++) {
                    String key = SectAbsence.key(tier, variant);
                    h.assertTrue(lines.has(key) && lines.get(key).getAsString().contains(lang.equals("en_us") ? "Where have you been, disciple?"
                            : "Где ты пропадал, ученик?"), lang + " absence line " + key + " is missing or off-text");
                }
            }
        }
        // the layout file carries the five fixed shots (the sixth, the bed, is computed)
        SectLayoutFile file = SectLayoutFile.shipped();
        for (String id : SHOTS) {
            h.assertTrue(id.equals("bed") || file.tour().containsKey(id), "layout.json has no tour shot " + id);
        }
        // the way to the east spare bed of the third floor, in the frame the file was made in (court to the north, viewer faces south)
        SectLayoutFile.Bed bed39 = file.bed("bed.39");
        SectDormPath.Path way = SectDormPath.of(file, bed39.head()).orElseThrow();
        h.assertTrue(way.floor() == 3 && way.entranceSide() != SectDormPath.Side.ONLY && way.ordinal() >= 1 && way.doorLeft(),
                "path of " + bed39.id() + " is " + way.floor() + " " + way.entranceSide() + " #" + way.ordinal() + " left=" + way.doorLeft());
        h.succeed();
    }

    private static JsonObject lang(String name) throws Exception {
        try (Reader r = new InputStreamReader(SectNewcomerGameTests.class.getResourceAsStream("/assets/murim/lang/" + name + ".json"),
                StandardCharsets.UTF_8)) {
            return new Gson().fromJson(r, JsonObject.class);
        }
    }

    /** The shot travels in the dialogue packet and an old packet without one still reads. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_newcomer")
    public static void dialoguePacketCarriesTheShot(GameTestHelper h) {
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), h.getLevel().registryAccess());
        DialoguePayloads.Shot shot = new DialoguePayloads.Shot(new Vec3(1.5D, 200.5D, -30.5D), new Vec3(10.5D, 190.0D, -3.5D), 55.0F);
        DialoguePayloads.Open with = new DialoguePayloads.Open(7, Component.literal("n"), Component.literal("t"), Component.literal("l"),
                List.of(Component.literal("o")), "", Optional.of(shot));
        DialoguePayloads.Open.STREAM_CODEC.encode(buf, with);
        DialoguePayloads.Open back = DialoguePayloads.Open.STREAM_CODEC.decode(buf);
        h.assertTrue(back.shot().isPresent() && back.shot().get().equals(shot) && back.npc() == 7, "shot lost in the packet: " + back);
        DialoguePayloads.Open plain = new DialoguePayloads.Open(8, Component.literal("n"), Component.literal("t"), Component.literal("l"),
                List.of(), "bow");
        DialoguePayloads.Open.STREAM_CODEC.encode(buf, plain);
        DialoguePayloads.Open back2 = DialoguePayloads.Open.STREAM_CODEC.decode(buf);
        h.assertTrue(back2.shot().isEmpty() && back2.playerAnim().equals("bow"), "plain packet changed: " + back2);
        h.succeed();
    }

    /** The own-bed shot is found in a walled corner: the eye cell is free and the line to the bed is clear. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_newcomer")
    public static void bedShotFindsAFreeSideInACorner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = SectGameTests.fakePlayer(h);
        BlockPos foot = h.absolutePos(new BlockPos(6, 2, 6));
        BlockPos head = foot.south();
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        // walls on the foot side and the east side, a ceiling 3 blocks up: the corner of a room
        for (int dy = 0; dy < 4; dy++) {
            for (int d = -4; d <= 4; d++) {
                level.setBlock(foot.north().offset(d, dy, 0), Blocks.STONE_BRICKS.defaultBlockState(), 2);
                level.setBlock(foot.offset(2, dy, d + 1), Blocks.STONE_BRICKS.defaultBlockState(), 2);
            }
        }
        p.moveTo(head.getX() + 0.5D, head.getY(), head.getZ() + 2.5D, 0, 0);
        p.setData(ModAttachments.SECT, SectService.state(p).joined().with("room.bed").record("room.pos", head.asLong()));
        Optional<DialoguePayloads.Shot> shot = SectTour.bed(p);
        h.assertTrue(shot.isPresent(), "no bed shot in a corner with a free south and west side");
        Vec3 eye = shot.get().camera();
        h.assertTrue(SectTour.free(level, BlockPos.containing(eye)), "the eye cell is inside a block: " + eye);
        h.assertTrue(eye.distanceTo(shot.get().aim()) <= 4.4D && eye.y > head.getY() + 1.4D, "eye too far or too low: " + eye);
        HitResult hit = level.clip(new ClipContext(eye, shot.get().aim(), ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        h.assertTrue(hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(shot.get().aim()) < 1.3D,
                "a wall stands between the eye and the bed at " + hit.getLocation());
        // a bedroll only (no bed): no shot, the tour falls back to the dormitory building
        p.setData(ModAttachments.SECT, SectService.state(p).without("room.bed"));
        h.assertTrue(SectTour.bed(p).isEmpty(), "bed shot without a bed");
        h.succeed();
    }

    // ------------------------------------------------------------------ your bed

    /** The glow shows within 12 blocks, only until the first sleep in the own bed; the respawn point is the vanilla one. */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_newcomer")
    public static void ownBedGlowsNearbyUntilFirstSleepAndSetsRespawn(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        ServerPlayer p = SectGameTests.fakePlayer(h);
        BlockPos foot = h.absolutePos(new BlockPos(6, 2, 6));
        BlockPos head = foot.south();
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        p.moveTo(head.getX() + 0.5D, head.getY(), head.getZ() + 1.5D, 0, 0);
        h.assertTrue(SectBedMark.glowing(p) == null, "a non-member sees a glow");
        p.setData(ModAttachments.SECT, SectService.state(p).joined().with("room.assigned").with("room.bed").record("room.pos", head.asLong()));
        h.assertTrue(head.equals(SectBedMark.glowing(p)), "no glow next to the own bed");
        p.moveTo(head.getX() + 0.5D, head.getY(), head.getZ() + 8.5D, 0, 0);
        h.assertTrue(head.equals(SectBedMark.glowing(p)), "no glow at 8 blocks");
        p.moveTo(head.getX() + 0.5D, head.getY(), head.getZ() + 15.5D, 0, 0);
        h.assertTrue(SectBedMark.glowing(p) == null, "glow at 15 blocks");
        p.moveTo(head.getX() + 0.5D, head.getY(), head.getZ() + 1.5D, 0, 0);
        h.assertTrue(!SectBedMark.markSlept(p), "marked as slept without sleeping");
        level.setDayTime(15000L);
        SectBedMark.login(new net.neoforged.neoforge.event.entity.player.PlayerEvent.PlayerLoggedInEvent(p));
        long until = p.getPersistentData().getLong(SectBedMark.NIGHT_UNTIL);
        h.assertTrue(until == level.getGameTime() + 2400, "night login did not renew 120 seconds");
        h.assertTrue(head.equals(SectBedMark.glowing(p)), "night login did not restore bed marker");
        p.getPersistentData().putLong(SectBedMark.NIGHT_UNTIL, level.getGameTime());
        h.assertTrue(SectBedMark.glowing(p) == null, "night marker did not expire");
        SectBedMark.nightLogin(p);
        h.assertTrue(head.equals(SectBedMark.glowing(p)), "returning night login did not restore marker");
        // vanilla: using the bed sets the respawn point (ServerPlayer#startSleepInBed), even when it is too early to sleep
        level.setDayTime(14000L);
        p.startSleepInBed(head);
        h.assertTrue(head.equals(p.getRespawnPosition()), "respawn point is " + p.getRespawnPosition() + ", not the bed " + head);
        if (!p.isSleeping()) {
            p.startSleeping(head);
        }
        h.assertTrue(SectBedMark.markSlept(p), "the first sleep in the own bed was not noticed");
        h.assertTrue(SectService.state(p).has(SectBedMark.SLEPT), "slept flag missing");
        p.stopSleeping();
        h.assertTrue(SectBedMark.glowing(p) == null, "the glow continues after the first sleep");
        h.succeed();
    }

    /** A layout whose plan zones sit at the test yard and whose file is the shipped one (spare beds at their real cells). */
    private record FileCampus(Vec3 origin, SectLayoutFile file) implements SectLayout {
        @Override public Vec3 at(String zone, double u, double v) { return origin.add(u, 0, v); }
        @Override public float yaw(double u, double v) { return SectLayout.yawOf(u, v); }
        @Override public double[] half(String zone) { return new double[] {3, 3}; }
        @Override public double[] local(String zone, Vec3 p) { return new double[] {p.x - origin.x, p.z - origin.z}; }
        @Override public SectLayoutFile file() { return file; }
    }

    /**
     * The player's bed is given at once from the layout file's spare beds, not after a slow block scan of the dormitory:
     * the mentor shows it a few lines later in the camera tour, and the glow needs it. The head of the bed stands against
     * walls on three sides (as many dormitory beds do): only the cell recorded in the file beside the foot is free.
     */
    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_newcomer")
    public static void newcomerGetsASpareBedFromTheLayoutFileAtOnce(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos head = h.absolutePos(new BlockPos(8, 2, 8));
        BlockPos foot = head.south();
        BlockPos approach = foot.west();
        String json = "{\"frame\": {\"center_x\": 0, \"center_z\": 0, \"base_y\": 0, \"rotation\": 0}, \"beds\": [{\"id\": \"bed.t\", "
                + "\"pos_head\": [" + head.getX() + "," + head.getY() + "," + head.getZ() + "], \"approach\": ["
                + approach.getX() + "," + approach.getY() + "," + approach.getZ() + "], \"yaw\": 180.0, \"room\": \"dorm_3rd.f1.r1\", "
                + "\"building\": \"dorm_3rd\", \"role\": \"spare\", \"owner_key\": null, \"slot\": 1, \"reach\": \"ok\"}]}";
        SectLayoutFile file = SectLayoutFile.parse(new java.io.StringReader(json), SectLayoutFile.Anchor::identity);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                level.setBlock(head.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(head.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        for (BlockPos wall : List.of(head.west(), head.east(), head.north())) {
            level.setBlock(wall, Blocks.STONE_BRICKS.defaultBlockState(), 2);
            level.setBlock(wall.above(), Blocks.STONE_BRICKS.defaultBlockState(), 2);
        }
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        FileCampus layout = new FileCampus(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file);
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D, 0, 0);
        p.setData(ModAttachments.SECT, SectService.state(p).joined());
        h.assertTrue(SectBuildings.approach(level, p.position(), head) == null, "the fixture is wrong: the head has a free side");
        h.assertTrue(SectRooms.assign(p, layout, true) != null, "no destination after the assignment");
        h.assertTrue(head.equals(SectRooms.bed(p)), "bed is " + SectRooms.bed(p) + ", expected the spare bed " + head);
        h.assertTrue(!SectRooms.available(level, head, "player:somebody-else"), "the bed is not claimed");
        h.assertTrue(head.equals(SectBedMark.glowing(p)), "the new bed does not glow");
        h.succeed();
    }

    /** int_tea_table carries its own tea set: a tray on top would float a second one (author 19.10), so it refuses to stay there. */
    @GameTest(template = YARD, timeoutTicks = 40, batch = "sect_newcomer")
    public static void teaTrayPopsOffATeaTableButSitsOnOtherTables(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos at = h.absolutePos(new BlockPos(3, 3, 3));
        BlockState tray = IntWorkshop.TEA_TRAY.get().defaultBlockState();
        for (var table : List.of(IntSeating.TEA_TABLE, IntSeating.TEA_TABLE_ROUND)) {
            level.setBlock(at.below(), table.get().defaultBlockState(), 2);
            level.setBlock(at, tray, 2);
            h.assertTrue(!tray.canSurvive(level, at), "a tea tray may stay on " + table.getId());
            h.assertTrue(tray.updateShape(Direction.DOWN, table.get().defaultBlockState(), level, at, at.below()).isAir(),
                    "a tea tray does not pop off " + table.getId());
        }
        level.setBlock(at.below(), IntSeating.LONG_TABLE.get().defaultBlockState(), 2);
        h.assertTrue(tray.canSurvive(level, at), "a tea tray cannot stand on a long table");
        h.assertTrue(IntTabletopBlock.TeaTray.ownsTeaSet(IntSeating.TEA_TABLE.get().defaultBlockState()), "tea table not recognised");
        h.succeed();
    }

    // ------------------------------------------------------------------ absence

    private static SectDisciple npc(GameTestHelper h, String key) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        npc.setMember(SectRoster.of(key).orElseThrow());
        npc.setNoAi(true);
        Vec3 at = h.absoluteVec(new Vec3(3.5D, 2, 3.5D));
        npc.moveTo(at.x, at.y, at.z, 0, 0);
        h.getLevel().addFreshEntity(npc);
        return npc;
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_newcomer")
    public static void absenceTiersFollowTheGapAndCountLogoutTime(GameTestHelper h) {
        h.assertTrue(SectAbsence.tier(23999L) == 0 && SectAbsence.tier(24000L) == 1 && SectAbsence.tier(2 * 24000L + 5) == 1
                && SectAbsence.tier(3 * 24000L) == 3 && SectAbsence.tier(6 * 24000L + 100) == 3 && SectAbsence.tier(7 * 24000L) == 7
                && SectAbsence.tier(40 * 24000L) == 7, "tier bounds are wrong");
        ServerPlayer p = SectGameTests.fakePlayer(h);
        h.assertTrue(SectAbsence.track(p, true, 100L, 0L) == 0, "a non-member is tracked");
        p.setData(ModAttachments.SECT, SectService.state(p).joined());
        h.assertTrue(SectAbsence.track(p, true, 1000L, 1_000L) == 0, "first sighting is not an absence");
        h.assertTrue(SectAbsence.track(p, false, 1000L + 5 * 24000L, 1_000L + 1000L) == 0, "outside the land nothing is judged");
        // away 3 game days: the first sighting inside again judges the whole gap
        h.assertTrue(SectAbsence.track(p, true, 1000L + 3 * 24000L + 7, 1_000L + 2000L) == 3, "three days away did not give tier 3");
        h.assertTrue(SectService.state(p).entry(SectAbsence.PENDING, 0) == 3, "tier not pending");
        // a short step later nothing new is judged
        h.assertTrue(SectAbsence.track(p, true, 1000L + 3 * 24000L + 47, 1_000L + 3000L) == 0, "the same return was judged twice");
        // logged out in real time: game time stood still, the wall clock gap of ~25 minutes is more than a Minecraft day
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectAbsence.PENDING, 0));
        long now = 5_000_000L;
        SectAbsence.track(p, true, now, 10_000L);
        h.assertTrue(SectAbsence.track(p, true, now + 20, 10_000L + 25L * 60_000L) == 1, "a 25 minute logout is more than a day away");
        h.succeed();
    }

    @GameTest(template = YARD, timeoutTicks = 60, batch = "sect_newcomer")
    public static void mentorOrSeniorSaysTheAbsenceLineOncePerReturnWithoutPenalty(GameTestHelper h) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setData(ModAttachments.SECT, SectService.state(p).joined().contribute(5).record(SectAbsence.PENDING, 7));
        SectDisciple disciple = npc(h, "yul_jin");
        h.assertTrue(!SectAbsence.senior(disciple) && !SectAbsence.meet(p, disciple), "a young disciple scolded");
        h.assertTrue(SectService.state(p).entry(SectAbsence.PENDING, 0) == 7, "a young disciple used up the line");
        SectDisciple mentor = npc(h, "gyeong_pil");
        int merit = SectService.state(p).merit();
        SectStanding standing = SectService.standing(p);
        h.assertTrue(SectAbsence.meet(p, mentor), "the mentor did not say the line");
        h.assertTrue(mentor.bubbleKey().startsWith("murim.sect.absence.7."), "wrong variant said: " + mentor.bubbleKey());
        h.assertTrue(SectService.state(p).entry(SectAbsence.PENDING, 0) == 0, "the line stays pending");
        h.assertTrue(!SectAbsence.meet(p, mentor), "said twice for one return");
        h.assertTrue(SectService.state(p).merit() == merit && SectService.standing(p).equals(standing) && SectService.state(p).member(),
                "an absence cost the member something");
        // a new, shorter return is a new line from a senior of another kind
        p.setData(ModAttachments.SECT, SectService.state(p).record(SectAbsence.PENDING, 1));
        SectDisciple elder = npc(h, "tae_gyun");
        h.assertTrue(SectAbsence.meet(p, elder) && elder.bubbleKey().startsWith("murim.sect.absence.1."), "an elder did not say the 1 day line");
        h.succeed();
    }

    // ------------------------------------------------------------------ contract board and shots in the baked sect

    /**
     * The baked sect at a turned site (rotation 1) has the contract board at the north edge of the hall court, turned to the
     * court, and every tour shot of the layout resolves on the stamped blocks: the eye cell is free, the aim point is at a
     * block and the line from the eye to the aim is clear. One cell per tick, as the baked-sect test does.
     */
    @GameTest(template = YARD, batch = "hua_baked", timeoutTicks = 3000)
    public static void bakedSectHasTheBoardAndTourShotsResolveToRealBlocks(GameTestHelper h) throws Exception {
        ServerLevel level = h.getLevel();
        HuaBaked.Index index = HuaBaked.index(level.getServer());
        h.assertTrue(index != null, "baked index missing from the mod resources");
        BlockPos corner = h.absolutePos(BlockPos.ZERO);
        MountHuaSite site = new MountHuaSite(corner.getX() + 3000 + 2 * 600, corner.getZ() + 6000, 66, 1, 1L, true);
        SectLayoutFile layout;
        try (Reader r = new InputStreamReader(SectNewcomerGameTests.class.getResourceAsStream("/data/murim/sect/layout.json"), StandardCharsets.UTF_8)) {
            layout = SectLayoutFile.parse(r, frame -> SectLayoutFile.Anchor.to(frame, site));
        }
        h.assertTrue(layout.boards().size() == 1, "the layout lists " + layout.boards().size() + " boards, expected 1");
        // cells to stamp: along every shot segment and around the board
        List<Vec3> points = new ArrayList<>();
        for (SectLayoutFile.Tour t : layout.tour().values()) {
            int n = (int) (t.camera().distanceTo(t.aim()) / 3.0D) + 1;
            for (int i = 0; i <= n; i++) {
                points.add(t.camera().lerp(t.aim(), i / (double) n));
            }
        }
        points.add(Vec3.atCenterOf(layout.boards().get(0)));
        points.add(Vec3.atCenterOf(layout.boards().get(0).relative(Direction.fromYRot(0))));
        // the whole way to that room: the route, the doors, the corridor and the tablet places
        SectDormPath.Path way = SectDormPath.of(layout, layout.beds().stream().filter(b -> "dorm_3rd".equals(b.building()) && "spare".equals(b.role()))
                .findFirst().orElseThrow().head()).orElseThrow();
        for (String node : way.route()) {
            points.add(Vec3.atCenterOf(layout.waypoint(node).pos()));
        }
        for (BlockPos c : way.corridorCells()) {
            points.add(Vec3.atCenterOf(c));
        }
        for (BlockPos c : SectNamePlates.candidates(way)) {
            points.add(Vec3.atCenterOf(c));
        }
        // the dormitory room of the first spare bed: the bed shot is computed there
        SectLayoutFile.Bed spareBed = layout.beds().stream().filter(b -> "dorm_3rd".equals(b.building()) && "spare".equals(b.role()))
                .findFirst().orElseThrow();
        for (int dx = -4; dx <= 4; dx += 4) {
            for (int dz = -4; dz <= 4; dz += 4) {
                points.add(Vec3.atCenterOf(spareBed.head().offset(dx, 0, dz)));
            }
        }
        List<Map.Entry<HuaBaked.Group, HuaBaked.Cell>> stamp = new ArrayList<>();
        for (Map.Entry<HuaBaked.Group, HuaBaked.Cell> e : HuaBaked.cells(index)) {
            if (!e.getKey().id().equals("sect")) {
                continue;
            }
            for (Vec3 pt : points) {
                double u = site.localU(pt.x, pt.z);
                double v = site.localV(pt.x, pt.z);
                HuaBaked.Cell c = e.getValue();
                if (u >= c.u() - 1 && u < c.u() + 17 && v >= c.v() - 1 && v < c.v() + 17) {
                    stamp.add(e);
                    break;
                }
            }
        }
        h.assertTrue(stamp.size() >= 4, "only " + stamp.size() + " cells cover the tour");
        // far from the yard nothing keeps these chunks loaded: without a ticket they may unload between the stamp and the checks
        for (Vec3 pt : points) {
            level.setChunkForced(BlockPos.containing(pt).getX() >> 4, BlockPos.containing(pt).getZ() >> 4, true);
        }
        Runnable preload = () -> HuaBaked.preload(level.getServer());
        net.minecraft.Util.backgroundExecutor().execute(preload);
        int start = 400;
        for (int i = 0; i < stamp.size(); i++) {
            Map.Entry<HuaBaked.Group, HuaBaked.Cell> e = stamp.get(i);
            h.runAtTickTime(start + i, () -> h.assertTrue(HuaBaked.placeCell(level.getServer(), level, site, index, e.getKey(),
                    e.getValue(), null, level.getRandom(), 2), "template missing: " + e.getValue().file()));
        }
        h.runAtTickTime(start + stamp.size() + 2, () -> {
            ServerPlayer p = SectGameTests.fakePlayer(h);
            BlockPos board = layout.boards().get(0);
            BlockState state = level.getBlockState(board);
            h.assertTrue(BuiltInRegistries.BLOCK.getKey(state.getBlock()).equals(ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "sect_mission_board")),
                    "no contract board at " + board + ": " + state);
            Direction facing = state.getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING);
            SectLayoutFile.Zone court = layout.zone("hall_court");
            h.assertTrue(court != null, "no hall_court zone");
            double cx = (court.rect().x0() + court.rect().x1() + 1) / 2.0D;
            double cz = (court.rect().z0() + court.rect().z1() + 1) / 2.0D;
            double front = (board.getX() + 0.5D + facing.getStepX() - cx) * (board.getX() + 0.5D + facing.getStepX() - cx)
                    + (board.getZ() + 0.5D + facing.getStepZ() - cz) * (board.getZ() + 0.5D + facing.getStepZ() - cz);
            double back = (board.getX() + 0.5D - facing.getStepX() - cx) * (board.getX() + 0.5D - facing.getStepX() - cx)
                    + (board.getZ() + 0.5D - facing.getStepZ() - cz) * (board.getZ() + 0.5D - facing.getStepZ() - cz);
            h.assertTrue(front < back, "the board faces away from the hall court: facing " + facing);
            for (SectLayoutFile.Tour t : layout.tour().values()) {
                BlockPos eye = BlockPos.containing(t.camera());
                h.assertTrue(SectTour.free(level, eye), "shot " + t.id() + ": the eye cell " + eye + " is inside " + level.getBlockState(eye));
                boolean solid = false;
                BlockPos aim = BlockPos.containing(t.aim());
                for (BlockPos q : BlockPos.betweenClosed(aim.offset(-2, -2, -2), aim.offset(2, 2, 2))) {
                    solid |= !level.getBlockState(q).getCollisionShape(level, q).isEmpty();
                }
                h.assertTrue(solid, "shot " + t.id() + ": nothing but air around the aim point " + aim);
                HitResult hit = level.clip(new ClipContext(t.camera(), t.aim(), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
                boolean clear = hit.getType() == HitResult.Type.MISS || hit.getLocation().distanceTo(t.aim()) < 3.0D;
                h.assertTrue(clear, "shot " + t.id() + ": the view is blocked at " + hit.getLocation() + " (" + level.getBlockState(((net.minecraft.world.phys.BlockHitResult) hit).getBlockPos()) + ")");
            }
            // the newcomer's own bed in the real dormitory: assigned from the file at once, and the camera finds a place in the room
            p.moveTo(spareBed.approach().getX() + 0.5D, spareBed.approach().getY(), spareBed.approach().getZ() + 0.5D, 0, 0);
            p.setData(ModAttachments.SECT, SectService.state(p).joined());
            FileCampus campus = new FileCampus(h.absoluteVec(new Vec3(12.5, 2, 12.5)), layout);
            List<BlockPos> spares = SectRooms.spare(level, campus, "dorm_3rd");
            BlockPos first = spares.isEmpty() ? spareBed.head() : spares.get(0);
            String why = "spare heads found " + spares.size() + " of file bed " + spareBed.head() + " state " + level.getBlockState(spareBed.head())
                    + " loaded " + level.isLoaded(spareBed.head()) + " approach " + SectRooms.approach(p, campus, first)
                    + " available " + SectRooms.available(level, first, "player:" + p.getUUID());
            h.assertTrue(SectRooms.assign(p, campus, true) != null && SectRooms.bed(p) != null, "no bed assigned in the baked dormitory: " + why);
            BlockPos own = SectRooms.bed(p);
            Optional<DialoguePayloads.Shot> bedShot = SectTour.bed(p);
            h.assertTrue(bedShot.isPresent(), "no bed shot in the baked dormitory around " + own + " (" + level.getBlockState(own) + ")");
            h.assertTrue(SectTour.free(level, BlockPos.containing(bedShot.get().camera())), "bed shot eye cell is blocked");
            // the way on foot: three more shots, all resolving on the stamped blocks, and the name tablet at the room door
            SectDormPath.Path path = SectDormPath.of(layout, own).orElseThrow();
            MurimMod.LOGGER.info("Dorm path of {}: {} floor {} entrance {} door #{} left={} room {} corridor {}", own, path.building(), path.floor(),
                    path.entranceSide(), path.ordinal(), path.doorLeft(), path.room().id(), path.corridor() == null ? null : path.corridor().id());
            h.assertTrue(path.floor() == 3 && path.ordinal() >= 1 && path.corridor() != null, "unexpected path " + path);
            BlockPos plate = SectNamePlates.choose(level, path, p.getUUID().toString()).orElseThrow(() -> new AssertionError("no tablet place"));
            SectNamePlate.Entity be = level.getBlockEntity(plate) instanceof SectNamePlate.Entity e ? e : null;
            if (be == null) {
                StringBuilder why2 = new StringBuilder();
                for (BlockPos c : SectNamePlates.candidates(path)) {
                    BlockPos wall = c.relative(path.toCorridor().getOpposite());
                    why2.append(c).append(" ").append(level.getBlockState(c)).append(" wall ").append(level.getBlockState(wall)).append(" | ");
                }
                MurimMod.LOGGER.error("TABLET DEBUG key={} bed={} room={} {}", SectService.state(p).entry(SectNamePlates.KEY, 0L), SectRooms.bed(p), path.room().id(), why2);
            }
            h.assertTrue(be != null && p.getGameProfile().getName().equals(be.ownerName()), "no name tablet with the player's name at " + plate);
            h.assertTrue(level.getBlockState(plate).getValue(net.minecraft.world.level.block.HorizontalDirectionalBlock.FACING) == path.toCorridor()
                    && level.getBlockState(plate).canSurvive(level, plate), "tablet faces the wrong way or hangs on nothing");
            h.assertTrue(plate.distManhattan(path.roomDoor().min()) <= 4, "tablet " + plate + " is not beside the room door " + path.roomDoor().min());
            // a second disciple of the same room gets a tablet of his own; the first keeps his
            ServerPlayer q = SectGameTests.fakePlayer(h);
            Optional<BlockPos> second = SectNamePlates.choose(level, path, q.getUUID().toString());
            h.assertTrue(second.isPresent() && !second.get().equals(plate), "no second tablet place");
            h.succeed();
        });
    }

    /**
     * When the spare beds run out the newcomer sleeps on the bedroll. The block scan of the dormitory sees every bed, and
     * one that belongs to somebody who has not slept in it yet (the layout names its owner) is not his to take.
     */
    @GameTest(template = YARD, timeoutTicks = 200, batch = "sect_newcomer")
    public static void anOwnedBedIsNeverGivenWhenTheSpareBedsRunOut(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        BlockPos head = h.absolutePos(new BlockPos(12, 2, 12));
        BlockPos foot = head.south();
        BlockPos approach = foot.west();
        String json = "{\"frame\": {\"center_x\": 0, \"center_z\": 0, \"base_y\": 0, \"rotation\": 0}, \"beds\": [{\"id\": \"bed.owned\", "
                + "\"pos_head\": [" + head.getX() + "," + head.getY() + "," + head.getZ() + "], \"approach\": ["
                + approach.getX() + "," + approach.getY() + "," + approach.getZ() + "], \"yaw\": 180.0, \"room\": \"dorm_3rd.f1.r1\", "
                + "\"building\": \"dorm_3rd\", \"role\": \"elder\", \"owner_key\": \"tae_hwi\", \"slot\": 1, \"reach\": \"ok\"}]}";
        SectLayoutFile file = SectLayoutFile.parse(new java.io.StringReader(json), SectLayoutFile.Anchor::identity);
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 3; dz++) {
                level.setBlock(head.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 2);
                for (int dy = 0; dy <= 2; dy++) {
                    level.setBlock(head.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(foot, bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        level.setBlock(head, bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
        FileCampus layout = new FileCampus(h.absoluteVec(new Vec3(12.5, 2, 12.5)), file);
        h.assertTrue(SectRooms.spare(level, layout, "dorm_3rd").isEmpty(), "setup: the file has a spare bed");
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.moveTo(approach.getX() + 0.5D, approach.getY(), approach.getZ() + 0.5D, 0, 0);
        p.setData(ModAttachments.SECT, SectService.state(p).joined());
        h.succeedWhen(() -> {
            // the scan walks the dormitory a few cells per tick; once it has seen the bed it is a candidate, and must be refused
            h.assertTrue(SectBuildings.beds(level, layout, "dorm_3rd").contains(head), "the dormitory scan has not seen the bed yet");
            SectRooms.assign(p, layout, true);
            h.assertTrue(SectRooms.bed(p) == null, "the owned bed went to a newcomer: " + SectRooms.bed(p));
            h.assertTrue(SectRooms.available(level, head, "player:somebody-else"), "the owned bed was claimed by the newcomer");
        });
    }
}
