package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.entity.TrainingDummy;
import io.github.verycooltimo.murim.mastery.MasteryService;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.registry.ModDataComponents;
import io.github.verycooltimo.murim.registry.ModEntities;
import io.github.verycooltimo.murim.registry.ModItems;
import io.github.verycooltimo.murim.world.hua.MountHuaPlan;
import io.github.verycooltimo.murim.world.hua.MountHuaSite;
import io.github.verycooltimo.murim.world.hua.MountHuaSites;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Секта Хуашань на сервере (план §2.2, §5.1, этап С1): вступление, уроки наставника, книги, расстановка NPC.
 * Диалоги решают, что сказать и что сделать ({@link DialogueService}); здесь — сами действия и
 * проверки по реальному прогрессу игрока.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class SectService {

    public static final ResourceLocation SIX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies");

    /** Флаги вступительного испытания и уроков (их же читают диалоги). */
    public static final String TRIAL = "trial.started";
    public static final String TRIAL_FORM = "trial.form";
    public static final String LESSON_SPAR = "lesson.spar";
    public static final String SPAR_CLEAN = "spar.clean3";
    public static final String SPAR_WON = "spar.won";
    public static final String SPAR_LOST = "spar.lost";

    /** Чистых ударов по старшему за один спарринг (урок наставника, план §5.1). */
    public static final int CLEAN_NEEDED = 3;

    private SectService() {
    }

    static SectState state(ServerPlayer p) {
        return p.getData(ModAttachments.SECT);
    }

    static void flag(ServerPlayer p, String flag) {
        p.setData(ModAttachments.SECT, state(p).with(flag));
    }

    // ------------------------------------------------------------------ действия диалогов

    /** Книга техники из рук наставника: тот же манускрипт, что в руинах (план §5.1). */
    public static void giveBook(ServerPlayer player, ResourceLocation technique) {
        ItemStack book = new ItemStack(ModItems.TECHNIQUE_MANUAL.get());
        book.set(ModDataComponents.TECHNIQUE.get(), technique);
        Component name = book.getHoverName();
        if (!player.getInventory().add(book)) {
            player.drop(book, false);
        }
        player.level().playSound(null, player.blockPosition(), SoundEvents.BOOK_PAGE_TURN, SoundSource.PLAYERS, 1.0F, 0.8F);
        player.displayClientMessage(Component.translatable("murim.sect.book_given", name).withStyle(ChatFormatting.GOLD), false);
    }

    /** Вступление: ученик третьего поколения (Чхон) и книга Шести Равновесий, если её ещё нет. */
    public static void join(ServerPlayer player) {
        if (state(player).member()) {
            return;
        }
        player.setData(ModAttachments.SECT, state(player).joined());
        player.level().playSound(null, player.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.6F, 0.7F);
        player.displayClientMessage(Component.translatable("murim.sect.joined").withStyle(ChatFormatting.LIGHT_PURPLE), false);
        if (!MasteryService.knows(player, SIX)) {
            giveBook(player, SIX);
        }
    }

    /** Испытание у ворот: рядом с главой — манекен, на котором игрок показывает форму. */
    public static void startTrial(ServerPlayer player, SectDisciple leader) {
        flag(player, TRIAL);
        ServerLevel level = player.serverLevel();
        if (level.getEntitiesOfClass(TrainingDummy.class, leader.getBoundingBox().inflate(10.0D)).isEmpty()) {
            // Сбоку от главы и на шаг ближе к игроку: форму показывают у него на глазах.
            Vec3 side = Vec3.directionFromRotation(0.0F, leader.getYRot() + 90.0F).scale(1.8D)
                    .add(Vec3.directionFromRotation(0.0F, leader.getYRot()).scale(1.2D));
            TrainingDummy dummy = new TrainingDummy(ModEntities.DUMMY.get(), level);
            Vec3 at = ground(level, leader.position().add(side));
            dummy.moveTo(at.x, at.y, at.z, leader.getYRot(), 0.0F);
            level.addFreshEntity(dummy);
        }
    }

    /** Телепорт к месту горы ({@link MountHuaPlan#ZONES}); горы нет — сообщение. */
    public static void teleport(ServerPlayer player, String zone) {
        MountHuaSite site = MountHuaSites.get(player.server);
        int[] at = site == null ? null : zone(site, zone);
        if (at == null) {
            player.displayClientMessage(Component.translatable("murim.sect.no_mountain").withStyle(ChatFormatting.GRAY), true);
            return;
        }
        ServerLevel overworld = player.server.overworld();
        player.teleportTo(overworld, at[0] + 0.5D, at[1], at[2] + 0.5D, player.getYRot(), player.getXRot());
    }

    /** Конец спарринга со старшим: исход для его реплики и урок «три чистых удара». */
    public static void onSparEnd(ServerPlayer player, SectDisciple senior, boolean playerWon) {
        SectState s = state(player).without(SPAR_WON).without(SPAR_LOST).with(playerWon ? SPAR_WON : SPAR_LOST);
        if (s.has(LESSON_SPAR) && senior.cleanHits() >= CLEAN_NEEDED) {
            s = s.with(SPAR_CLEAN);
            player.displayClientMessage(Component.translatable("murim.sect.clean_done").withStyle(ChatFormatting.GOLD), false);
        }
        player.setData(ModAttachments.SECT, s);
    }

    /** Чистое попадание по старшему — счёт виден игроку, пока урок не сдан. */
    public static void onCleanHit(ServerPlayer player, SectDisciple senior) {
        if (state(player).has(LESSON_SPAR) && !state(player).has(SPAR_CLEAN)) {
            player.displayClientMessage(Component.translatable("murim.sect.clean_hit", Math.min(senior.cleanHits(), CLEAN_NEEDED), CLEAN_NEEDED)
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    /**
     * Испытание у ворот: форма на манекене. Знает Шесть Равновесий — засчитывается только взмах формой
     * (основа ставит отметку взмаха за тик-другой до пакета атаки, FoundationService); не знает — глава
     * принимает и простой удар («повтори, как я показал», план §2.2 вариант Б).
     */
    @SubscribeEvent
    static void onAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player) || !(event.getTarget() instanceof TrainingDummy dummy)) {
            return;
        }
        SectState s = state(player);
        if (!s.has(TRIAL) || s.has(TRIAL_FORM) || s.member()) {
            return;
        }
        if (MasteryService.knows(player, SIX)) {
            int[] swing = player.getData(ModAttachments.FOUNDATION_SWING);
            if (swing[1] < 0 || player.tickCount - swing[1] > 4) {
                MurimMod.LOGGER.info("Испытание: удар по манекену без формы основы (взмах {} тиков назад)", player.tickCount - swing[1]);
                return;
            }
        }
        flag(player, TRIAL_FORM);
        player.displayClientMessage(Component.translatable("murim.sect.trial_form").withStyle(ChatFormatting.GOLD), true);
        for (SectDisciple npc : dummy.level().getEntitiesOfClass(SectDisciple.class, dummy.getBoundingBox().inflate(16.0D))) {
            if (npc.role() == SectRole.LEADER) {
                npc.gesture("nod");
            }
        }
    }

    // ------------------------------------------------------------------ расстановка

    /** {@code /murim sect spawn}: все NPC v1 полукругом перед игроком, манекен у главы. */
    public static int spawnAround(ServerPlayer player) {
        ServerLevel level = player.serverLevel();
        for (SectDisciple old : level.getEntitiesOfClass(SectDisciple.class, player.getBoundingBox().inflate(24.0D))) {
            old.discard();
        }
        float yaw = player.getYRot();
        Vec3 fwd = Vec3.directionFromRotation(0.0F, yaw);
        Vec3 right = Vec3.directionFromRotation(0.0F, yaw + 90.0F);
        Object[][] layout = {
                {SectRole.LEADER, 6.0D, 0.0D},
                {SectRole.MENTOR, 5.0D, -4.5D},
                {SectRole.SENIOR, 5.0D, 4.5D},
                {SectRole.DISCIPLE_A, 9.0D, -2.5D},
                {SectRole.DISCIPLE_B, 9.0D, 2.5D}};
        for (Object[] row : layout) {
            Vec3 at = player.position().add(fwd.scale((double) row[1])).add(right.scale((double) row[2]));
            spawn(level, (SectRole) row[0], at, player.position());
        }
        return layout.length;
    }

    static SectDisciple spawn(ServerLevel level, SectRole role, Vec3 at, Vec3 face) {
        SectDisciple npc = new SectDisciple(ModEntities.SECT_DISCIPLE.get(), level);
        npc.setRole(role);
        Vec3 g = ground(level, at);
        float yaw = (float) Math.toDegrees(Math.atan2(face.z - g.z, face.x - g.x)) - 90.0F;
        npc.moveTo(g.x, g.y, g.z, yaw, 0.0F);
        npc.setYHeadRot(yaw);
        npc.setYBodyRot(yaw);
        level.addFreshEntity(npc);
        return npc;
    }

    private static Vec3 ground(ServerLevel level, Vec3 at) {
        BlockPos p = BlockPos.containing(at);
        // Над игроком/под ним — ближайшая опора в пределах ±4 блоков, иначе карта высот.
        for (int dy = 3; dy >= -4; dy--) {
            BlockPos b = p.offset(0, dy, 0);
            if (level.getBlockState(b.below()).isSolidRender(level, b.below()) && level.getBlockState(b).isAir()
                    && level.getBlockState(b.above()).isAir()) {
                return new Vec3(b.getX() + 0.5D, b.getY(), b.getZ() + 0.5D);
            }
        }
        int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
        return new Vec3(p.getX() + 0.5D, y, p.getZ() + 0.5D);
    }

    static int[] zone(MountHuaSite site, String id) {
        for (MountHuaPlan.Zone z : MountHuaPlan.ZONES) {
            if (z.id().equals(id)) {
                int[] w = site.toWorld(z.u(), z.v());
                return new int[] {w[0], (int) Math.round(site.worldY(z.y())) + 1, w[1]};
            }
        }
        return null;
    }

    /**
     * Гора уже в мире: NPC встают на площадки при первом приходе игрока (ворота — глава с манекеном
     * и ученик-привратник; главная терраса — наставник, старший, ученик). Генерацию горы не трогаем:
     * только читаем {@link MountHuaSites}, а «уже поставлены» помним в {@link SectSiteData}.
     */
    @SubscribeEvent
    static void onServerTick(ServerTickEvent.Post event) {
        if (event.getServer().getTickCount() % 100 != 37) {
            return;
        }
        MountHuaSite site = MountHuaSites.get(event.getServer());
        if (site == null) {
            return;
        }
        ServerLevel level = event.getServer().overworld();
        SectSiteData data = level.getDataStorage().computeIfAbsent(SectSiteData.FACTORY, SectSiteData.NAME);
        placeZone(level, site, data, "gate", List.of(SectRole.LEADER, SectRole.DISCIPLE_A));
        placeZone(level, site, data, "main", List.of(SectRole.MENTOR, SectRole.SENIOR, SectRole.DISCIPLE_B));
    }

    private static void placeZone(ServerLevel level, MountHuaSite site, SectSiteData data, String zone, List<SectRole> roles) {
        if (data.placed(zone)) {
            return;
        }
        int[] c = zone(site, zone);
        if (c == null) {
            return;
        }
        BlockPos center = new BlockPos(c[0], c[1], c[2]);
        if (!level.hasChunkAt(center)) {
            return;
        }
        Player near = level.getNearestPlayer(c[0], c[1], c[2], 64.0D, false);
        if (near == null) {
            return;
        }
        // Уже стоят (например, поставлены командой) — только запомнить.
        if (level.getEntitiesOfClass(SectDisciple.class, new AABB(center).inflate(40.0D)).isEmpty()) {
            Vec3 mid = Vec3.atBottomCenterOf(center);
            for (int i = 0; i < roles.size(); i++) {
                double off = (i - (roles.size() - 1) / 2.0D) * 4.0D;
                SectDisciple npc = spawn(level, roles.get(i), mid.add(off, 0.0D, -3.0D), mid.add(0.0D, 0.0D, 6.0D));
                if (roles.get(i) == SectRole.LEADER) {
                    Vec3 side = Vec3.directionFromRotation(0.0F, npc.getYRot() + 90.0F).scale(2.5D);
                    TrainingDummy dummy = new TrainingDummy(ModEntities.DUMMY.get(), level);
                    Vec3 at = ground(level, npc.position().add(side));
                    dummy.moveTo(at.x, at.y, at.z, npc.getYRot(), 0.0F);
                    level.addFreshEntity(dummy);
                }
            }
            MurimMod.LOGGER.info("Секта Хуашань: NPC на площадке {} ({})", zone, center);
        }
        data.place(zone);
    }
}
