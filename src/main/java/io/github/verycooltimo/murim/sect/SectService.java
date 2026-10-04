package io.github.verycooltimo.murim.sect;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
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
import net.neoforged.neoforge.event.tick.ServerTickEvent;

import java.util.List;

/**
 * Секта Хуашань на сервере (план §2.2, §5.1, этап С1): вступление (без экзамена, автор 04.10), уроки наставника, книги, расстановка NPC.
 * Диалоги решают, что сказать и что сделать ({@link DialogueService}); здесь — сами действия и
 * проверки по реальному прогрессу игрока.
 */
public final class SectService {

    public static final ResourceLocation SIX = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "six_harmonies");

    /** Флаги уроков и спарринга (их же читают диалоги). */
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

    // ------------------------------------------------------------------ заслуги и положение

    /** Положение игрока сейчас. */
    public static SectStanding standing(ServerPlayer p) {
        return SectStanding.of(state(p), p.getData(ModAttachments.PROFILE).rank());
    }

    /** Заслуги за утреннюю тренировку (раз в день). */
    public static final int MORNING_CONTRIBUTION = 2;

    /**
     * Заслуги перед сектой ± {@code delta} (уроки, утренняя тренировка, пожертвования, защита горы; штраф за
     * силовой вход в закрытое место). Если выросло положение — сообщение с новым положением.
     */
    public static void contribute(ServerPlayer p, int delta) {
        if (delta == 0 || !state(p).member()) {
            return;
        }
        int rank = p.getData(ModAttachments.PROFILE).rank();
        SectStanding before = SectStanding.of(state(p), rank);
        SectState next = state(p).contribute(delta);
        p.setData(ModAttachments.SECT, next);
        p.displayClientMessage(Component.translatable(delta > 0 ? "murim.sect.contribution.gain" : "murim.sect.contribution.loss",
                Math.abs(delta), next.contribution()).withStyle(delta > 0 ? ChatFormatting.GOLD : ChatFormatting.RED), true);
        announceStanding(p, before);
    }

    /** Положение выросло после изменения состояния — сказать игроку (в чат, один раз на ступень). */
    public static void announceStanding(ServerPlayer p, SectStanding before) {
        SectStanding now = SectStanding.of(state(p), p.getData(ModAttachments.PROFILE).rank());
        if (now.ordinal() > before.ordinal()) {
            p.displayClientMessage(Component.translatable("murim.sect.standing.up", Component.translatable(now.nameKey()))
                    .withStyle(ChatFormatting.LIGHT_PURPLE), false);
            p.level().playSound(null, p.blockPosition(), SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 0.5F, 1.2F);
            MurimMod.LOGGER.info("Секта: {} — положение {}", p.getName().getString(), now.id());
        }
    }

    /**
     * Пожертвование управляющему: {@code "minecraft:gold_ingot*1=3"} — забрать столько предметов, дать столько
     * заслуг. Нет предметов — ничего не берёт.
     *
     * @return отдано ли
     */
    public static boolean donate(ServerPlayer p, String spec) {
        int eq = spec.lastIndexOf('=');
        if (eq < 0) {
            return false;
        }
        ItemNeed need = ItemNeed.parse(spec.substring(0, eq));
        int points;
        try {
            points = Integer.parseInt(spec.substring(eq + 1).trim());
        } catch (NumberFormatException e) {
            return false;
        }
        if (need == null || need.count(p) < need.count()) {
            p.displayClientMessage(Component.translatable("murim.sect.donate.none").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        // Заслуги за пожертвования — не больше DONATE_CAP в день: ферма не заменяет службу (codex 04.10).
        long day = SectSchedule.day(p.level().getDayTime());
        net.minecraft.nbt.CompoundTag tag = p.getPersistentData().getCompound(DONATE_TAG);
        if (tag.getLong("day") != day) {
            tag = new net.minecraft.nbt.CompoundTag();
            tag.putLong("day", day);
        }
        int left = DONATE_CAP - tag.getInt("points");
        if (left <= 0) {
            p.displayClientMessage(Component.translatable("murim.sect.donate.enough").withStyle(ChatFormatting.GRAY), true);
            return false;
        }
        need.take(p);
        int got = Math.min(points, left);
        tag.putInt("points", tag.getInt("points") + got);
        p.getPersistentData().put(DONATE_TAG, tag);
        contribute(p, got);
        return true;
    }

    /** Заслуг за пожертвования в день — не больше. */
    public static final int DONATE_CAP = 6;
    private static final String DONATE_TAG = "murim_sect_donations";

    /** Предмет и число: {@code "minecraft:wheat*16"} (без числа — один). */
    public record ItemNeed(net.minecraft.world.item.Item item, int count) {

        public static ItemNeed parse(String spec) {
            String id = spec.trim();
            int n = 1;
            int star = id.indexOf('*');
            if (star >= 0) {
                try {
                    n = Integer.parseInt(id.substring(star + 1).trim());
                } catch (NumberFormatException e) {
                    return null;
                }
                id = id.substring(0, star).trim();
            }
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl == null || !net.minecraft.core.registries.BuiltInRegistries.ITEM.containsKey(rl)) {
                return null;
            }
            return new ItemNeed(net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl), Math.max(1, n));
        }

        /** Сколько таких у игрока. */
        public int count(ServerPlayer p) {
            int c = 0;
            for (ItemStack s : p.getInventory().items) {
                if (s.is(item)) {
                    c += s.getCount();
                }
            }
            return c;
        }

        void take(ServerPlayer p) {
            int left = count;
            for (ItemStack s : p.getInventory().items) {
                if (left > 0 && s.is(item)) {
                    int n = Math.min(left, s.getCount());
                    s.shrink(n);
                    left -= n;
                }
            }
        }
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
        // Учебный деревянный меч новому ученику (автор 04.10): раньше наставник оружия не давал.
        ItemStack sword = new ItemStack(ModItems.WOODEN_SWORD.get());
        if (!player.getInventory().add(sword)) {
            player.drop(sword, false);
        }
        player.displayClientMessage(Component.translatable("murim.sect.sword_given", sword.getHoverName()).withStyle(ChatFormatting.GOLD), false);
    }

    /** Конец спарринга со старшим: исход для его реплики и урок «три чистых удара». */
    public static void onSparEnd(ServerPlayer player, SectDisciple senior, boolean playerWon) {
        // Исход помнит только старший (его реплика после боя); урок «три чистых удара» — по второму поколению.
        SectState s = state(player);
        if (senior.role() == SectRole.SENIOR) {
            s = s.without(SPAR_WON).without(SPAR_LOST).with(playerWon ? SPAR_WON : SPAR_LOST);
        }
        if (s.has(LESSON_SPAR) && senior.cleanHits() >= CLEAN_NEEDED && lessonPartner(senior)) {
            s = s.with(SPAR_CLEAN);
            player.displayClientMessage(Component.translatable("murim.sect.clean_done").withStyle(ChatFormatting.GOLD), false);
        }
        player.setData(ModAttachments.SECT, s);
    }

    /** Урок «три чистых удара по старшему»: партнёр — старший или любой ученик второго поколения. */
    static boolean lessonPartner(SectDisciple npc) {
        return npc.role() == SectRole.SENIOR || npc.role() == SectRole.SECOND;
    }

    /** Чистое попадание по старшему — счёт виден игроку, пока урок не сдан. */
    public static void onCleanHit(ServerPlayer player, SectDisciple senior) {
        if (state(player).has(LESSON_SPAR) && !state(player).has(SPAR_CLEAN) && lessonPartner(senior)) {
            player.displayClientMessage(Component.translatable("murim.sect.clean_hit", Math.min(senior.cleanHits(), CLEAN_NEEDED), CLEAN_NEEDED)
                    .withStyle(ChatFormatting.GOLD), true);
        }
    }

    // ------------------------------------------------------------------ расстановка

    /** {@code /murim sect spawn}: все NPC v1 полукругом перед игроком. */
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

    /** Флаги подъёма: прошёл верхнюю часть тропы ногами, затем дошёл до ворот секты. */
    public static final String TRAIL_HIGH = "trail.high";
    public static final String CLIMBED = "climbed";

    /**
     * Подъём своими ногами (автор 04.10): игрок, прошедший мимо отметок верхней трети тропы
     * ({@link MountHuaPlan#TRAIL}, последние точки до Золотого Замка), получает отметку; дошедший с ней
     * до ворот секты — «поднялся сам», глава это замечает. Проверка раз в 2 с, только чтение плана горы.
     */
    static void trackClimb(ServerPlayer p, MountHuaSite site) {
        SectState s = state(p);
        if (s.has(CLIMBED) || s.member()) {
            return;
        }
        if (!s.has(TRAIL_HIGH)) {
            List<MountHuaPlan.TrailPoint> trail = MountHuaPlan.TRAIL;
            for (int i = trail.size() * 2 / 3; i < trail.size() - 2; i++) {
                MountHuaPlan.TrailPoint t = trail.get(i);
                int[] w = site.toWorld(t.u(), t.v());
                if (p.distanceToSqr(w[0] + 0.5D, site.worldY(t.y()), w[1] + 0.5D) < 14.0D * 14.0D) {
                    flag(p, TRAIL_HIGH);
                    return;
                }
            }
            return;
        }
        int[] gate = zone(site, "sect_gate");
        if (gate != null && p.distanceToSqr(gate[0] + 0.5D, gate[1], gate[2] + 0.5D) < 20.0D * 20.0D) {
            flag(p, CLIMBED);
        }
    }
}
