package io.github.verycooltimo.murim.client.sect;

import io.github.verycooltimo.murim.network.SectTabletData;
import io.github.verycooltimo.murim.sect.SectLayoutFile;
import io.github.verycooltimo.murim.sect.SectMap;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Fixed content of the approved tablet and board mockups, for the capture stand ({@code HudGalleryCapture}, only with
 * {@code -Dmurim.capture=true}) and nothing else: no game screen reads it. It lives apart from {@link SectTabletScreen} so that
 * invented contracts (a herb order in progress, a group of two) can never be mistaken for the data of a real player
 * (playtest 19.10: «I have taken no quests, but they are already in my cards»; the real snapshot comes from the server).
 */
public final class SectTabletDemo {
    private SectTabletDemo() {
    }


    public static SectTabletData demo() {
        return demo(0);
    }

    /** {@code position}: 0 none, 1 first disciple, 2 elder. */
    public static SectTabletData demo(int position) {
        boolean elder = position == 2;
        MapDemo mapDemo = MapDemo.of(0);
        List<Component> chain = elder
                ? List.of(step("peak"), step("elder"), step("peak_stage"), step("free_practice"))
                : List.of(step("learn_petal"), step("take_spar"), step("clean_hits"), step("rank_two"));
        return new SectTabletData(4, 2, List.of(new SectTabletData.Mark("formation", true, true), new SectTabletData.Mark("meal", true, false),
                new SectTabletData.Mark("lesson", false, true)), 3, 7, false, 2, elder ? 4 : 2, position,
                Component.translatable(position == 2 ? "murim.sect.position.elder" : position == 1 ? "murim.sect.position.first" : "murim.sect.position.disciple"),
                elder ? 4 : 1, Component.translatable(elder ? "murim.rank.4" : "murim.rank.3"),
                Component.translatable(elder ? "murim.sect.journey.goal.peak_stage" : "murim.sect.journey.goal.clean_hits"),
                Component.translatable(elder ? "murim.sect.journey.blocker.breakthrough" : "murim.sect.journey.blocker.clean_hits"),
                Component.translatable(elder ? "npc.murim.tae_seong" : "npc.murim.gyeong_pil"), elder ? 0 : 2, elder ? 0 : 3,
                chain, 2, true,
                List.of(
                        new SectTabletData.Offer(0, 0, "big_mask", Component.translatable("murim.tablet.contract.camp", Component.translatable("murim.contract.place.0")),
                                Component.translatable("murim.tablet.contract.camp.where", Component.translatable("murim.contract.dir.north_east"), 300),
                                3, 12, Component.translatable("npc.murim.gyeong_tak"), false),
                        new SectTabletData.Offer(1, 1, "big_cart", Component.translatable("murim.tablet.contract.escort"),
                                Component.translatable("murim.tablet.contract.escort.where"), 0, 8,
                                Component.translatable("npc.murim.tae_gyun"), false),
                        new SectTabletData.Offer(2, 2, "big_herb", Component.translatable("murim.tablet.contract.herbs", Component.literal("6 ").append(net.minecraft.world.item.Items.FERN.getDescription())
                                        .append(", 4 ").append(net.minecraft.world.item.Items.SWEET_BERRIES.getDescription())),
                                Component.translatable("murim.tablet.contract.herbs.where", Component.translatable("murim.contract.habitat.forest"),
                                        Component.translatable("murim.contract.habitat.meadow")), 0, 6,
                                Component.translatable("npc.murim.gyeong_cho"), false)),
                new SectTabletData.Active(2, Component.translatable("murim.contract.status.herbs"), Component.translatable("murim.contract.progress.herbs", 4, 6, 1, 4),
                        List.of("Timo", "Min"), 3, List.of(new SectTabletData.Counter("minecraft:fern", 4, 6), new SectTabletData.Counter("minecraft:sweet_berries", 1, 4))),
                37, 52, 3, 5, !elder, elder ? 9 : 3, List.of(Component.translatable("npc.murim.seo_rang"), Component.translatable("npc.murim.seo_gyu"),
                        Component.translatable("npc.murim.seo_ho")),
                2, true, true, false, Component.translatable("npc.murim.gyeong_pil"), Component.empty(),
                mapDemo.you(), mapDemo.mentor(), mapDemo.bed(), mapDemo.boards(),
                mapDemo.version(), mapDemo.area().x0(), mapDemo.area().z0(), mapDemo.area().w(), mapDemo.area().h(), mapDemo.labels(),
                new SectTabletData.Board(true, true, false, Component.translatable("npc.murim.gyeong_cho"),
                        List.of(new SectTabletData.Candidate("00000000-0000-0000-0000-000000000001", "Min")), null, false),
                286, List.of(Component.translatable("murim.tablet.chronicle.repair", 3, Component.translatable("murim.sect.place.main_hall"), 24, 7)),
                demoShop(elder));
    }

    /** The today tab with the teacher's drill as a task under the three marks (capture stand only). */
    public static SectTabletData demoDrill() {
        SectTabletData base = demo();
        List<SectTabletData.Mark> marks = new ArrayList<>(base.marks());
        marks.add(new SectTabletData.Mark("drill", false, false, Component.translatable("murim.tablet.mark.drill",
                Component.translatable("murim.sect.defeat.name.strikes"), 8, 20)));
        return base.withMarks(marks);
    }

    /** The shop lines of the capture stand: three for a disciple, four for the holder of an office. */
    private static List<SectTabletData.Shop> demoShop(boolean four) {
        List<SectTabletData.Shop> lines = new ArrayList<>(List.of(
                new SectTabletData.Shop(Component.translatable("murim.tablet.shop.sword"), 0, 1, 14),
                new SectTabletData.Shop(Component.translatable("murim.tablet.shop.snow_plum"), 1, 2, 22),
                new SectTabletData.Shop(Component.translatable("murim.tablet.shop.origin_energy"), 1, 1, 0)));
        if (four) {
            lines.add(new SectTabletData.Shop(Component.translatable("murim.tablet.shop.quick_recovery"), 3, 3, 0));
        }
        return lines;
    }

    /**
     * Board scenes of the capture stand. {@code variant}: 0 nothing taken yet; 1 a herb contract taken, group of two;
     * 2 a newcomer (the board is closed to him); 3 an invitation waiting; 4 the same as 1 and the herbs gathered;
     * 5 the tablet's contracts tab far from the board with nothing taken (the server sends no offers there: an empty tab);
     * 6 the same far from the board with the herb contract taken (only the player's own card).
     */
    public static SectTabletData demoBoard(int variant) {
        SectTabletData base = demo();
        Component giver = Component.translatable("npc.murim.gyeong_cho");
        SectTabletData.Active none = null;
        SectTabletData.Active herbs = new SectTabletData.Active(2, Component.translatable("murim.contract.status.herbs"),
                Component.translatable("murim.contract.progress.herbs", 4, 6, 1, 4), List.of("Timo", "Min"), 3, List.of(new SectTabletData.Counter("minecraft:fern", 4, 6), new SectTabletData.Counter("minecraft:sweet_berries", 1, 4)));
        SectTabletData.Active ready = new SectTabletData.Active(2, Component.translatable("murim.contract.status.ready"), Component.empty(),
                List.of("Timo", "Min"), 3, List.of(new SectTabletData.Counter("minecraft:fern", 6, 6), new SectTabletData.Counter("minecraft:sweet_berries", 4, 4)));
        List<SectTabletData.Candidate> near = List.of(new SectTabletData.Candidate("00000000-0000-0000-0000-000000000001", "Min"),
                new SectTabletData.Candidate("00000000-0000-0000-0000-000000000002", "Ayu"));
        return switch (variant) {
            case 1 -> base.withActive(herbs).withContracts(new SectTabletData.Board(true, true, false, giver, near, null, false));
            case 2 -> base.withActive(none).withStanding(1).withContracts(new SectTabletData.Board(true, false, false, Component.empty(), List.of(), null, false));
            case 3 -> base.withActive(none).withContracts(new SectTabletData.Board(true, true, false, Component.empty(), List.of(),
                    new SectTabletData.Invite("Min", Component.translatable("murim.tablet.contract.herbs", Component.literal("6 ")
                            .append(net.minecraft.world.item.Items.FERN.getDescription()))), false));
            case 4 -> base.withActive(ready).withContracts(new SectTabletData.Board(true, true, true, giver, near, null, false));
            case 5 -> base.withActive(none).withOffers(List.of()).withContracts(new SectTabletData.Board(false, true, false, Component.empty(), List.of(), null, false));
            case 6 -> base.withActive(herbs).withOffers(List.of(base.offers().get(2)))
                    .withContracts(new SectTabletData.Board(false, true, false, giver, List.of(), null, false));
            default -> base.withActive(none).withContracts(new SectTabletData.Board(true, true, false, Component.empty(), List.of(), null, false));
        };
    }

    private static Component step(String id) {
        return Component.translatable("murim.tablet.step." + id);
    }

    /**
     * The map part of the capture data, laid over the shipped layout (the author's build frame, rotation 0): the markers
     * are where the mockups put them. {@code version} 0 shows only the footprints, anything else expects the live map.
     */
    private record MapDemo(SectMap.Area area, List<SectTabletData.MapLabel> labels, float[] you, float[] mentor, float[] bed,
                           List<float[]> boards, int version) {
        static MapDemo of(int version) {
            SectLayoutFile file = SectLayoutFile.shipped();
            SectMap.Area area = SectMap.areaFor(file);
            List<SectTabletData.MapLabel> labels = new ArrayList<>();
            for (SectMap.Label l : SectMap.labels(file)) {
                labels.add(new SectTabletData.MapLabel(l.key(), l.x0(), l.z0(), l.x1(), l.z1(),
                        l.parts().stream().map(r -> new SectTabletData.Box(r.x0(), r.z0(), r.x1(), r.z1())).toList()));
            }
            List<float[]> boards = new ArrayList<>();
            for (net.minecraft.core.BlockPos b : file.boards()) {
                boards.add(new float[] {b.getX() + 0.5F, b.getZ() + 0.5F});
            }
            // the frame of the shipped file: world = centre + plan
            int cx = file.frame().centerX();
            int cz = file.frame().centerZ();
            return new MapDemo(area, labels, new float[] {cx - 4.0F, cz + 24.0F}, new float[] {cx + 2.0F, cz + 36.0F},
                    new float[] {cx - 71.5F, cz + 50.5F}, boards, version);
        }
    }
}
