package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import java.util.Set;

/** What the game tests of the 19.10 playtest share: a fake player with a sect state, a named person, a bare layout (docs/adr/0013). */
final class SectPlaytestRig {

    private SectPlaytestRig() {
    }

    static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, path);
    }

    static Vec3 spot(GameTestHelper h, double x, double z) {
        return SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(x + 0.5D, 2.0D, z + 0.5D)));
    }

    static ServerPlayer player(GameTestHelper h, double x, double z, SectState state) {
        ServerPlayer p = SectGameTests.fakePlayer(h);
        p.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        Vec3 at = spot(h, x, z);
        p.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        p.setOnGround(true);
        p.setData(ModAttachments.SECT, state);
        return p;
    }

    static SectDisciple npc(GameTestHelper h, SectLayout layout, String key, double x, double z, boolean ai) {
        SectDisciple d = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), h.getLevel());
        d.setMember(SectRoster.of(key).orElseThrow());
        d.setLayout(layout);
        d.setKeepAwake(true);
        d.setNoAi(!ai);
        Vec3 at = spot(h, x, z);
        d.moveTo(at.x, at.y, at.z, 0.0F, 0.0F);
        h.getLevel().addFreshEntity(d);
        return d;
    }

    /** No zones: the daily routine does not lead anybody off the spot he was put on. */
    static SectHierarchyGameTests.Only empty(GameTestHelper h) {
        return new SectHierarchyGameTests.Only(h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D)), Set.of());
    }

    static SectHierarchyGameTests.Only only(GameTestHelper h, String... zones) {
        return new SectHierarchyGameTests.Only(SectLife.stand(h.getLevel(), h.absoluteVec(new Vec3(12.5D, 2.0D, 12.5D))), Set.of(zones));
    }

    /** The yard template has props of its own; a test about walking across it wants an open floor (air above the paving). */
    static void clearYard(GameTestHelper h) {
        for (int x = 0; x < 24; x++) {
            for (int z = 0; z < 24; z++) {
                for (int y = 2; y <= 4; y++) {
                    h.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    static void bed(GameTestHelper h, int x, int z) {
        var bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.SOUTH);
        h.getLevel().setBlock(h.absolutePos(new BlockPos(x, 2, z)), bed.setValue(BedBlock.PART, BedPart.FOOT), 2);
        h.getLevel().setBlock(h.absolutePos(new BlockPos(x, 2, z + 1)), bed.setValue(BedBlock.PART, BedPart.HEAD), 2);
    }
}
