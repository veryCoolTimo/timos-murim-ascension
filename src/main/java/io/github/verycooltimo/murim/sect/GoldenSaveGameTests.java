package io.github.verycooltimo.murim.sect;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.compat.RegistrySnapshotGameTests;
import io.github.verycooltimo.murim.cultivation.PillState;
import io.github.verycooltimo.murim.mastery.Loadout;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.seal.PenanceService;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/** Real old-jar player and level SavedData, with independently recorded expectations. */
@GameTestHolder(MurimMod.MODID)
@PrefixGameTestTemplate(false)
public final class GoldenSaveGameTests {
    // API: minecraft-src/net/minecraft/nbt/{NbtIo,NbtAccounter}.java; Entity.load;
    // ServerPlayer constructor; CommonListenerCookie.createInitial; NeoForge IAttachmentHolder.getData.
    @GameTest(template = "small_floor", timeoutTicks = 40)
    public static void oldJarPlayerAndLevelKeepProgress(GameTestHelper helper) throws IOException {
        Path root = RegistrySnapshotGameTests.projectRoot().resolve("src/test/resources/golden");
        List<Path> samples;
        try (var children = Files.list(root)) {
            samples = children.filter(Files::isDirectory).sorted().toList();
        }
        if (samples.isEmpty()) {
            MurimMod.LOGGER.info("GOLDEN-SAVES-SKIPPED: no old-jar samples in {}", root);
            helper.succeed();
            return;
        }
        for (Path sample : samples) check(helper, sample);
        helper.succeed();
    }

    private static void check(GameTestHelper helper, Path sample) throws IOException {
        JsonObject expected;
        try (var reader = Files.newBufferedReader(sample.resolve("expected.json"))) {
            expected = JsonParser.parseReader(reader).getAsJsonObject();
        }
        UUID id = UUID.fromString(expected.get("player_uuid").getAsString());
        var cookie = CommonListenerCookie.createInitial(new com.mojang.authlib.GameProfile(id, "golden-test"), false);
        var level = helper.getLevel();
        ServerPlayer player = new ServerPlayer(level.getServer(), level, cookie.gameProfile(), cookie.clientInformation());
        player.load(NbtIo.readCompressed(sample.resolve("player.dat"), NbtAccounter.unlimitedHeap()));
        var sect = player.getData(ModAttachments.SECT);
        helper.assertTrue(sect.member() == expected.get("member").getAsBoolean(), sample + ": membership");
        helper.assertTrue(player.getData(ModAttachments.PROFILE).rank() == expected.get("rank").getAsInt(), sample + ": rank");
        var stars = player.getData(ModAttachments.MASTERY).stars();
        var expectedStars = expected.getAsJsonObject("stars");
        helper.assertTrue(stars.size() == expectedStars.size(), sample + ": learned technique count");
        expectedStars.entrySet().forEach(entry -> helper.assertTrue(
                java.util.Objects.equals(stars.get(ResourceLocation.parse(entry.getKey())), entry.getValue().getAsInt()),
                sample + ": stars for " + entry.getKey()));
        helper.assertTrue(player.getData(ModAttachments.LOADOUT).equals(
                Loadout.CODEC.parse(JsonOps.INSTANCE, expected.get("loadout")).getOrThrow()), sample + ": loadout");
        helper.assertTrue(player.getData(ModAttachments.PILLS).equals(
                PillState.CODEC.parse(JsonOps.INSTANCE, expected.get("pills")).getOrThrow()), sample + ": pills");
        helper.assertTrue(sect.has(PenanceService.SENTENCED) == expected.get("penance").getAsBoolean(), sample + ": penance flag");
        var saved = NbtIo.readCompressed(sample.resolve("data/murim_wanderers.dat"), NbtAccounter.unlimitedHeap());
        var data = Wanderer.Data.load(saved.getCompound("data"), level.registryAccess());
        var entry = data.byPlayer.get(id);
        helper.assertTrue(entry != null, sample + ": missing wanderer entry");
        var wanderer = expected.getAsJsonObject("wanderer");
        helper.assertTrue(entry.visits == wanderer.get("visits").getAsInt()
                && entry.nextAt == wanderer.get("next_at").getAsLong()
                && entry.expiresAt == wanderer.get("expires_at").getAsLong()
                && entry.mapGiven == wanderer.get("map_given").getAsBoolean()
                && entry.extra == wanderer.get("extra").getAsInt()
                && Double.compare(entry.lastDist, wanderer.get("last_dist").getAsDouble()) == 0
                && java.util.Objects.equals(entry.entity, wanderer.get("entity").isJsonNull()
                        ? null : UUID.fromString(wanderer.get("entity").getAsString())), sample + ": wanderer entry changed");
        MurimMod.LOGGER.info("GOLDEN-SAVE-PASSED {}", sample.getFileName());
    }

    private GoldenSaveGameTests() { }
}
