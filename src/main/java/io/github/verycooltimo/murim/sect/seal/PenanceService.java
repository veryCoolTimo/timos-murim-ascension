package io.github.verycooltimo.murim.sect.seal;

import io.github.verycooltimo.murim.MurimMod;
import io.github.verycooltimo.murim.entity.SectDisciple;
import io.github.verycooltimo.murim.registry.ModAttachments;
import io.github.verycooltimo.murim.sect.DialogueService;
import io.github.verycooltimo.murim.sect.SectLayout;
import io.github.verycooltimo.murim.sect.SectLife;
import io.github.verycooltimo.murim.sect.SectSchedule;
import io.github.verycooltimo.murim.sect.SectService;
import io.github.verycooltimo.murim.sect.SectState;
import io.github.verycooltimo.murim.sect.SectRole;
import io.github.verycooltimo.murim.sect.SectWatch;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * The penance cave (author 03.10 p.7; docs/design/23-mount-hua-sect.md §6.3; numbers — {@link PenanceRules}).
 *
 * <ol>
 *   <li>{@link #offence}: a hand raised on a brother outside a spar ({@code SectDisciple#hurt}), a closed place forced
 *       ({@code SectWatch#escalate}). First — a warning; the second within the window — the sentence is read.</li>
 *   <li>Light offences use a dialogue ({@code murim:penance}: accept / refuse); rampage and repeat refusal use
 *       {@link PenanceEnforcement}, with an elder's suppression and mandatory transport. With
 *       nobody near, flag {@link #SUMMONED} keeps calling the nearest elder or guard to approach.</li>
 *   <li>{@link #sentence}: flag {@code penance.sentenced} (the guards let him into the cave), the player stands on the
 *       penance seal the author placed in his cave (none — the cave of the generated mountain), the cold iron door
 *       shuts. Leaving the cell brings him back.</li>
 *   <li>{@link #release}: after {@link PenanceRules#DAYS} days or the meditation quota; the elders open the door.</li>
 * </ol>
 * The sentence lives in the player's persistent data ({@code murim_penance}: cell, end, meditation) and in the sect
 * flag; both are saved and copied on death by PenanceEnforcement.
 */
@EventBusSubscriber(modid = MurimMod.MODID)
public final class PenanceService {

    public static final String SENTENCED = "penance.sentenced";
    public static final String SUMMONED = "penance.summoned";
    public static final ResourceLocation DIALOGUE = ResourceLocation.fromNamespaceAndPath(MurimMod.MODID, "penance");

    private static final String TAG = "murim_penance";
    private static final String HISTORY = "murim_penance_history";
    private static final String OFFENCE_TAG = "murim_sect_offences";
    /** The penance seal is looked for this far around the cave zone (or the player). */
    private static final int FIND = 20;

    private PenanceService() {
    }

    public static boolean sentenced(ServerPlayer p) {
        return p.getData(ModAttachments.SECT).has(SENTENCED) && p.getPersistentData().contains(TAG);
    }

    // ------------------------------------------------------------------ offences

    /**
     * A breach of the sect rules by {@code p}; {@code witness} — who saw it (the struck brother, the guard), may be null.
     *
     * @param kind {@code assault} or {@code trespass}
     */
    public static void offence(ServerPlayer p, String kind, SectDisciple witness) {
        SectState s = p.getData(ModAttachments.SECT);
        if (!s.member() || s.has(SENTENCED) || s.has(SUMMONED) || PenanceEnforcement.active(p) || p.isCreative() || p.isSpectator()) {
            return;
        }
        long now = p.level().getGameTime();
        CompoundTag tag = p.getPersistentData().getCompound(OFFENCE_TAG);
        if ("assault".equals(kind) && now - tag.getLong("lastAssault") < PenanceRules.ASSAULT_GAP && tag.contains("lastAssault")) {
            return;
        }
        if ("assault".equals(kind)) {
            tag.putLong("lastAssault", now);
        }
        io.github.verycooltimo.murim.sect.SectProgression.offence(p, kind, witness);
        long today = SectSchedule.day(p.level().getDayTime());
        int count = PenanceRules.count(tag.getInt("count"), tag.getLong("day"), today);
        tag.putLong("day", today);
        MurimMod.LOGGER.info("Секта: проступок {} ({}), {} за окно", p.getName().getString(), kind, count);
        if (!PenanceRules.sentence(count)) {
            tag.putInt("count", count);
            p.getPersistentData().put(OFFENCE_TAG, tag);
            Component who = witness != null ? witness.getName() : Component.translatable("murim.penance.elder");
            p.displayClientMessage(Component.translatable("murim.penance.warn." + kind, who).withStyle(ChatFormatting.GOLD), false);
            return;
        }
        tag.putInt("count", 0);
        p.getPersistentData().put(OFFENCE_TAG, tag);
        summon(p, witness);
    }

    /** Серьёзный проступок сразу требует приговора, обычные — после повторения. */
    public static void summon(ServerPlayer p, SectDisciple witness) {
        if (!p.getData(ModAttachments.SECT).member() || sentenced(p) || PenanceEnforcement.active(p) || p.isCreative() || p.isSpectator()) {
            return;
        }
        boolean first = !p.getData(ModAttachments.SECT).has(SUMMONED);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SUMMONED));
        io.github.verycooltimo.murim.sect.SectSocial.remember(p, "punished");
        if (!offer(p, witness) && first) {
            p.displayClientMessage(Component.translatable("murim.penance.summoned").withStyle(ChatFormatting.RED), false);
        }
    }

    private static boolean canDeliver(SectDisciple d) {
        return d.isAlive() && d.free() && !d.wounded() && !d.escorting()
                && (d.role() == SectRole.ELDER || d.role() == SectRole.LEADER || SectWatch.guards(d) || d.role() == SectRole.GUARD);
    }

    /** Приговор читает подошедший старейшина или страж; закрытый экран не снимает вызов. */
    public static boolean offer(ServerPlayer p, SectDisciple witness) {
        if (PenanceEnforcement.active(p)) return false;
        if (p.getData(ModAttachments.DIALOGUE).active()) {
            return true;
        }
        // Keep an existing approach. Otherwise a capable witness delivers their own
        // summons before an unrelated nearby NPC; periodic retries still find a speaker.
        SectDisciple speaker = nearest(p);
        if ((speaker == null || !speaker.approachingDialogue(p, DIALOGUE))
                && witness != null && witness.level() == p.level() && canDeliver(witness)
                && witness.distanceTo(p) < 64.0D && (!witness.blocking() || witness.blockTarget() == p.getId())) {
            speaker = witness;
        }
        if (speaker == null) {
            return false;
        }
        if (speaker.distanceToSqr(p) <= 9.0D && speaker.getSensing().hasLineOfSight(p)) {
            return DialogueService.openDialogue(p, speaker, DIALOGUE, "sentence");
        }
        return speaker.approachDialogue(p, DIALOGUE, "sentence", p.position());
    }

    private static SectDisciple nearest(ServerPlayer p) {
        SectDisciple best = null;
        double bestD = 64.0D;
        for (SectDisciple d : p.level().getEntitiesOfClass(SectDisciple.class, p.getBoundingBox().inflate(64.0D), PenanceService::canDeliver)) {
            if (d.approachingDialogue(p, DIALOGUE)) {
                return d;
            }
            if (d.blocking() && d.blockTarget() != p.getId()) {
                continue;
            }
            double dist = d.distanceTo(p);
            if (dist < bestD) {
                best = d;
                bestD = dist;
            }
        }
        return best;
    }

    /** The player accepted (dialogue action {@code penance_accept}). */
    public static void accept(ServerPlayer p) {
        if (PenanceEnforcement.active(p) || sentenced(p) || p.isCreative() || p.isSpectator()) return;
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(SUMMONED));
        if (!sentence(p)) {
            p.displayClientMessage(Component.translatable("murim.penance.no_cave").withStyle(ChatFormatting.GRAY), false);
        }
    }

    /** The player refused (dialogue action {@code penance_refuse}): merit lost, freedom kept. */
    public static void refuse(ServerPlayer p) {
        if (PenanceEnforcement.active(p) || sentenced(p) || !p.getData(ModAttachments.SECT).has(SUMMONED)
                || p.isCreative() || p.isSpectator()) return;
        if (recentSentences(p) > 0) {
            p.displayClientMessage(Component.translatable("murim.penance.repeat_refusal").withStyle(ChatFormatting.RED), false);
            PenanceEnforcement.rampage(p);
            return;
        }
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(SUMMONED));
        SectService.contribute(p, -PenanceRules.REFUSE_COST);
        p.displayClientMessage(Component.translatable("murim.penance.refused", PenanceRules.REFUSE_COST).withStyle(ChatFormatting.RED), false);
        MurimMod.LOGGER.info("Секта: {} отказался от пещеры покаяния (−{} заслуг)", p.getName().getString(), PenanceRules.REFUSE_COST);
    }

    // ------------------------------------------------------------------ sentence and release

    /** Puts the player into the cave. @return false — no cave found */
    public static boolean sentence(ServerPlayer p) {
        BlockPos cell = findCell(p);
        return cell != null && sentence(p, cell);
    }

    /** Puts the player into the cave whose cell (the block he stands on) is {@code cell}. */
    public static boolean sentence(ServerPlayer p, BlockPos cell) {
        if (sentenced(p) || p.isCreative() || p.isSpectator()) return false;
        recordSentence(p);
        ServerLevel level = p.server.overworld();
        long until = PenanceRules.until(level.getDayTime());
        CompoundTag tag = new CompoundTag();
        tag.putLong("cell", cell.asLong());
        tag.putString("dimension", level.dimension().location().toString());
        tag.putLong("until", until);
        tag.putInt("meditated", 0);
        p.getPersistentData().put(TAG, tag);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).with(SENTENCED).without(SUMMONED));
        PenanceEnforcement.clear(p);
        bringBack(p, cell);
        for (BlockPos d : SealAccess.doors(level, cell, SealAccess.RANGE)) {
            var st = level.getBlockState(d);
            if (st.getBlock() instanceof ColdIronDoorBlock door && st.getValue(ColdIronDoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                door.open(level, d, st, false, 0);
            }
        }
        level.playSound(null, cell, SoundEvents.IRON_DOOR_CLOSE, SoundSource.BLOCKS, 1.0F, 0.6F);
        p.sendSystemMessage(Component.translatable("murim.penance.sentenced", PenanceRules.DAYS, PenanceRules.MEDITATION_QUOTA / 1200)
                .withStyle(ChatFormatting.RED));
        MurimMod.LOGGER.info("Секта: {} в пещере покаяния у {} до {}", p.getName().getString(), cell.toShortString(), until);
        return true;
    }

    /** Actual sentences only; fixed journal slots survive death through the sect attachment. */
    public static int recentSentences(ServerPlayer p) {
        long now = p.level().getGameTime();
        long window = io.github.verycooltimo.murim.Config.SECT_JAIL_DAYS.get() * 24000L;
        SectState state = p.getData(ModAttachments.SECT);
        long total = state.entry("penance.history.total", -1);
        int count = 0;
        if (total < 0) {
            // Read the first version's persisted history until the next actual sentence migrates it.
            for (long time : p.getPersistentData().getLongArray(HISTORY))
                if (time <= now && now - time <= window) count++;
        } else {
            for (int i = 0; i < Math.min(64, total); i++) {
                long time = state.entry("penance.history." + i, Long.MIN_VALUE / 2);
                if (time <= now && now - time <= window) count++;
            }
        }
        return count;
    }

    private static void recordSentence(ServerPlayer p) {
        SectState state = p.getData(ModAttachments.SECT);
        long total = state.entry("penance.history.total", -1);
        if (total < 0) {
            long[] old = p.getPersistentData().getLongArray(HISTORY);
            total = Math.min(64, old.length);
            for (int i = 0; i < total; i++) state = state.record("penance.history." + i, old[old.length - (int) total + i]);
            p.getPersistentData().remove(HISTORY);
        }
        state = state.record("penance.history." + Math.floorMod(total, 64L), p.level().getGameTime())
                .record("penance.history.total", total + 1);
        p.setData(ModAttachments.SECT, state);
    }

    /** Sentence served: flag off, the elders open the door. */
    public static void release(ServerPlayer p) {
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        BlockPos cell = BlockPos.of(tag.getLong("cell"));
        p.getPersistentData().remove(TAG);
        p.setData(ModAttachments.SECT, p.getData(ModAttachments.SECT).without(SENTENCED));
        int doors = SealAccess.openDoors(cave(p, tag), cell, SealAccess.RANGE, PenanceRules.RELEASE_DOOR_TICKS);
        PenanceEnforcement.escortOnRelease(p);
        p.level().playSound(null, cell, SoundEvents.BELL_BLOCK, SoundSource.BLOCKS, 0.8F, 1.1F);
        p.sendSystemMessage(Component.translatable("murim.penance.released").withStyle(ChatFormatting.GOLD));
        MurimMod.LOGGER.info("Секта: {} отпущен из пещеры покаяния (дверей открыто {})", p.getName().getString(), doors);
    }

    /** Meditation in the cell gathers qi faster (MeditationService#tickSeeded). */
    public static double meditationFactor(ServerPlayer p) {
        if (!sentenced(p)) {
            return 1.0D;
        }
        BlockPos cell = BlockPos.of(p.getPersistentData().getCompound(TAG).getLong("cell"));
        return p.position().distanceTo(cell.above().getBottomCenter()) <= PenanceRules.CELL_RADIUS ? PenanceRules.MEDITATION_FACTOR : 1.0D;
    }

    /** What is left: {days left ×10 rounded, meditated ticks} for the seal and the command. */
    public static Component status(ServerPlayer p) {
        if (!sentenced(p)) {
            return Component.translatable("murim.penance.free");
        }
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        long left = Math.max(0L, tag.getLong("until") - p.level().getDayTime());
        return Component.translatable("murim.penance.status", String.format(java.util.Locale.ROOT, "%.1f", left / 24000.0D),
                tag.getInt("meditated") / 20, PenanceRules.MEDITATION_QUOTA / 20);
    }

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.tickCount % 20 != 0 || !p.isAlive()) {
            return;
        }
        if (sentenced(p) && !p.isCreative() && !p.isSpectator()) {
            tick(p);
        } else if (p.getData(ModAttachments.SECT).has(SUMMONED) && !p.isCreative() && !p.isSpectator()) {
            offer(p, null);
        }
    }

    /** One check (once a second): count meditation, keep him in, release when served. */
    public static void tick(ServerPlayer p) {
        if (!sentenced(p) || p.isCreative() || p.isSpectator()) return;
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        BlockPos cell = BlockPos.of(tag.getLong("cell"));
        ServerLevel cave = cave(p, tag);
        boolean inCell = p.level() == cave && p.position().distanceTo(cell.above().getBottomCenter()) <= PenanceRules.CELL_RADIUS;
        if (inCell && p.getData(ModAttachments.MEDITATION).active()) {
            tag.putInt("meditated", tag.getInt("meditated") + 20);
            p.getPersistentData().put(TAG, tag);
        }
        if (PenanceRules.served(cave.getDayTime(), tag.getLong("until"), tag.getInt("meditated"))) {
            release(p);
            return;
        }
        if (!inCell && !p.isCreative() && !p.isSpectator()) {
            bringBack(p, cell);
            p.displayClientMessage(Component.translatable("murim.penance.back").withStyle(ChatFormatting.GRAY), true);
        }
    }

    private static ServerLevel cave(ServerPlayer p, CompoundTag tag) {
        ResourceLocation dim = ResourceLocation.tryParse(tag.getString("dimension"));
        ServerLevel level = dim == null ? null : p.server.getLevel(net.minecraft.resources.ResourceKey.create(
                net.minecraft.core.registries.Registries.DIMENSION, dim));
        return level == null ? p.server.overworld() : level;
    }

    /** A sentenced player respawns on the seal, including after dying inside the cell. */
    static void respawnInCell(ServerPlayer p) {
        if (PenanceEnforcement.confined(p)) bringBack(p, BlockPos.of(p.getPersistentData().getCompound(TAG).getLong("cell")));
    }

    /** Every tick and immediately on login: contain direct teleports from other mods as well. */
    public static void keepInCell(ServerPlayer p) {
        if (!PenanceEnforcement.confined(p) || !p.isAlive()) return;
        CompoundTag tag = p.getPersistentData().getCompound(TAG);
        BlockPos cell = BlockPos.of(tag.getLong("cell"));
        if (p.level() != cave(p, tag) || p.position().distanceToSqr(cell.above().getBottomCenter())
                > PenanceRules.CELL_RADIUS * PenanceRules.CELL_RADIUS) bringBack(p, cell);
    }

    private static void bringBack(ServerPlayer p, BlockPos cell) {
        Vec3 at = cell.above().getBottomCenter();
        ServerLevel cave = cave(p, p.getPersistentData().getCompound(TAG));
        PenanceEnforcement.transport(p, () -> p.teleportTo(cave, at.x, at.y, at.z, p.getYRot(), p.getXRot()));
        p.setDeltaMovement(Vec3.ZERO);
        p.fallDistance = 0;
    }

    /**
     * The cell: the penance seal nearest to the cave zone of the mountain (the author's cave) or to the player
     * (his showcase builds); no seal — the floor of the generated cave.
     */
    static BlockPos findCell(ServerPlayer p) {
        ServerLevel level = p.server.overworld();
        SectLayout layout = SectLife.layout(level);
        Vec3 zone = layout == null ? null : layout.at("penance", 0.0D, 0.0D);
        if (zone != null) {
            BlockPos seal = SealAccess.nearest(level, BlockPos.containing(zone), FIND, SealRegistry.PENANCE_SEAL.get());
            if (seal != null) {
                return seal;
            }
        }
        if (p.level() == level) {
            BlockPos seal = SealAccess.nearest(level, p.blockPosition(), FIND, SealRegistry.PENANCE_SEAL.get());
            if (seal != null) {
                return seal;
            }
        }
        if (zone != null) {
            Vec3 floor = SectLife.stand(level, zone);
            return BlockPos.containing(floor).below();
        }
        return null;
    }
}
