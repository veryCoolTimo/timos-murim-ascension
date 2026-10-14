package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import java.util.List;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.LevelTickEvent;

/** Operator-only, reversible sketch of the author's buildings using vanilla concrete. */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectLayoutBoxes {
    private SectLayoutBoxes() {
    }

    public static int place(CommandSourceStack source) {
        MountHuaSite site = MountHuaSites.get(source.getServer());
        if (site == null) {
            source.sendFailure(Component.translatable("command.murim.sect.boxes.no_site"));
            return 0;
        }
        // Mount Hua is always in the overworld, even if the command was issued in another dimension.
        ServerLevel level = source.getServer().overworld();
        SectLayoutData data = SectLayoutData.get(level);
        if (data.active()) {
            source.sendFailure(Component.translatable("command.murim.sect.boxes.already"));
            return 0;
        }
        int n = place(level, site, SectLayoutPlan.BOXES, data);
        source.sendSuccess(() -> Component.translatable("command.murim.sect.boxes.placed", n, data.blocks.size()), true);
        return n;
    }

    public static int clear(CommandSourceStack source) {
        SectLayoutData data = SectLayoutData.get(source.getServer().overworld());
        int n = clear(source.getServer().overworld(), data);
        source.sendSuccess(() -> Component.translatable("command.murim.sect.boxes.cleared", n), true);
        return n;
    }

    /** Shared with GameTests; a fake placement-only site needs no mountain generation. */
    static int place(ServerLevel level, MountHuaSite site, List<SectLayoutPlan.Box> boxes, SectLayoutData data) {
        return place(level, site, boxes, data, Integer.MIN_VALUE);
    }

    /** scanFrom: top Y to start the ground search from (GameTests have barrier ceilings); MIN_VALUE = heightmap. */
    static int place(ServerLevel level, MountHuaSite site, List<SectLayoutPlan.Box> boxes, SectLayoutData data, int scanFrom) {
        if (data.active()) {
            return 0;
        }
        int n = 0;
        for (SectLayoutPlan.Box box : boxes) {
            int[] centre = site.toWorld(box.minU() + (box.width() - 1) / 2.0,
                    box.minV() + (box.depth() - 1) / 2.0);
            // getHeight returns the first air block. Descend past water/plants to the solid support.
            int ground = scanFrom != Integer.MIN_VALUE ? scanFrom
                    : level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, centre[0], centre[1]) - 1;
            BlockPos support = new BlockPos(centre[0], ground, centre[1]);
            while (ground >= level.getMinBuildHeight()
                    && (!level.getBlockState(support).isSolid() || level.getBlockState(support).is(BlockTags.LEAVES))) {
                support = support.below();
                ground--;
            }
            int base = ground + 1;
            if (ground < level.getMinBuildHeight() || base + box.kind().height() > level.getMaxBuildHeight()) {
                continue;
            }
            // Already built (the foot gate, the author's own buildings): no sketch box over a real building.
            if (alreadyBuilt(level, site, box, base)) {
                continue;
            }
            for (int x = 0; x < box.width(); x++) {
                for (int z = 0; z < box.depth(); z++) {
                    if (x != 0 && x != box.width() - 1 && z != 0 && z != box.depth() - 1) {
                        continue;
                    }
                    int[] world = site.toWorld(box.minU() + x, box.minV() + z);
                    for (int y = 0; y < box.kind().height(); y++) {
                        if (box.door(x, z, y)) {
                            continue;
                        }
                        BlockPos pos = new BlockPos(world[0], base + y, world[1]);
                        var before = level.getBlockState(pos);
                        // Preserve the author's mod blocks, inventories/signs, and pre-existing same-colour concrete.
                        if (BuiltInRegistries.BLOCK.getKey(before.getBlock()).getNamespace().equals(MurimMod.MODID)
                                || before.hasBlockEntity() || before.equals(box.kind().state())) {
                            continue;
                        }
                        if (level.setBlock(pos, box.kind().state(), 2 | 16)) {
                            data.remember(pos, before, box.kind().state());
                        }
                    }
                }
            }
            label(level, site, box, base, data);
            n++;
        }
        return n;
    }

    /** True when the footprint already holds a real building: enough of our mod's blocks inside its volume. */
    static boolean alreadyBuilt(ServerLevel level, MountHuaSite site, SectLayoutPlan.Box box, int base) {
        int found = 0;
        for (int x = 0; x < box.width(); x++) {
            for (int z = 0; z < box.depth(); z++) {
                int[] world = site.toWorld(box.minU() + x, box.minV() + z);
                for (int y = 0; y < box.kind().height() + 4; y++) {
                    var key = BuiltInRegistries.BLOCK.getKey(level.getBlockState(new BlockPos(world[0], base + y, world[1])).getBlock());
                    if (key.getNamespace().equals(MurimMod.MODID) && !terrain(key.getPath()) && ++found >= BUILT_THRESHOLD) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Mountain terrain and plants from our mod are not buildings. */
    private static boolean terrain(String path) {
        return path.startsWith("hua_") || path.contains("granite") || path.contains("plum_blossom") || path.contains("sapling")
                || path.contains("moss") || path.contains("litter") || path.contains("handhold") || path.contains("rope")
                || path.startsWith("plum_log") || path.startsWith("plum_wood") || path.contains("leaves");
    }

    /** Mod blocks in a footprint that mean "a building stands here" (paving alone stays under it). */
    static final int BUILT_THRESHOLD = 24;

    private static void label(ServerLevel level, MountHuaSite site, SectLayoutPlan.Box box, int base, SectLayoutData data) {
        Display.TextDisplay display = EntityType.TEXT_DISPLAY.create(level);
        if (display == null) {
            return;
        }
        // Separate language keys intentionally show BOTH languages on clients of either locale.
        String key = "layout.murim.sect.zone." + box.zoneId();
        var text = Component.translatable(key + ".ru").append("\n").append(Component.translatable(key + ".en"));
        if (!box.id().equals(box.zoneId())) {
            String part = "layout.murim.sect.building." + box.id();
            text.append("\n").append(Component.translatable(part + ".ru")).append(" / ")
                    .append(Component.translatable(part + ".en"));
        }
        text.append("\n" + box.width() + " × " + box.depth() + " × " + box.kind().height());
        // Display's text/style setters are private in vanilla 1.21.1; use its public NBT load API.
        CompoundTag tag = new CompoundTag();
        tag.putString("text", Component.Serializer.toJson(text, level.registryAccess()));
        tag.putString("billboard", "center");
        tag.putInt("line_width", 600);
        tag.putBoolean("shadow", true);
        tag.putInt("background", 0xB0202020);
        tag.putFloat("view_range", 4.0F);
        display.load(tag);
        int[] a = site.toWorld(box.minU(), box.minV());
        int[] b = site.toWorld(box.minU() + box.width() - 1, box.minV() + box.depth() - 1);
        display.setPos((a[0] + b[0]) / 2.0 + 0.5, base + box.kind().height() + 1.0,
                (a[1] + b[1]) / 2.0 + 0.5);
        if (level.addFreshEntity(display)) {
            data.remember(display.getUUID(), display.blockPosition());
        }
    }

    static int clear(ServerLevel level, SectLayoutData data) {
        int n = 0;
        for (var e : data.blocks.entrySet()) {
            BlockPos pos = e.getKey();
            // Load saved chunks, not only chunks currently around a player.
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            // Preserve later edits, including any murim block built on top of the sketch.
            if (level.getBlockState(pos).equals(e.getValue().marker())) {
                level.setBlock(pos, e.getValue().before(), 2 | 16);
                n++;
            }
        }
        // Entity storage loads asynchronously. Queue exact UUIDs for removal on level ticks.
        data.blocks.clear();
        data.clearingLabels = !data.labels.isEmpty();
        removeLoadedLabels(level, data);
        data.setDirty();
        return n;
    }

    static void removeLoadedLabels(ServerLevel level, SectLayoutData data) {
        var it = data.labels.entrySet().iterator();
        while (it.hasNext()) {
            var e = it.next();
            BlockPos pos = e.getValue();
            level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            Entity entity = level.getEntity(e.getKey());
            if (entity != null) {
                if (entity instanceof Display.TextDisplay) {
                    entity.discard();
                }
                it.remove();
                data.setDirty();
            } else if (level.areEntitiesLoaded(new ChunkPos(pos).toLong())) {
                // A label already killed manually must not keep the sketch marked as active forever.
                it.remove();
                data.setDirty();
            }
        }
        if (data.labels.isEmpty()) {
            data.clearingLabels = false;
            data.setDirty();
        }
    }

    @SubscribeEvent
    static void onLevelTick(LevelTickEvent.Post event) {
        if (event.getLevel() instanceof ServerLevel level) {
            SectLayoutData data = level.getDataStorage().get(SectLayoutData.FACTORY, SectLayoutData.NAME);
            if (data != null && data.clearingLabels) {
                removeLoadedLabels(level, data);
            }
        }
    }
}
