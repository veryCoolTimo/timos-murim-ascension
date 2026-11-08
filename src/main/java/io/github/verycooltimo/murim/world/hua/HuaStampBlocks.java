package io.github.verycooltimo.murim.world.hua;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.ServerLevelAccessor;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessor;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureProcessorType;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

/** Block-entity lifecycle for the baked stamp and its clearing passes (ADR 0021). */
public final class HuaStampBlocks {
    private HuaStampBlocks() {
    }

    /** Optional per-call probe; absent during normal generation (no shared counters). */
    static final class Work {
        long entries;
        long clippedEntries;
        final java.util.Set<BlockPos> sections = new java.util.HashSet<>();
    }

    // API: Minecraft 1.21.1 ChunkAccess#pendingBlockEntities (official Mojang mappings),
    // ProtoChunk#removeBlockEntity, LevelChunk#removeBlockEntity and #getBlockEntity.
    // LevelChunk removal does not clear pending NBT; resolving it would log on an already incompatible state.
    // Keep this single-field access here rather than loading/discarding corrupt NBT or changing the build with an AT.
    private static final Field PENDING = pendingField();

    private static Field pendingField() {
        try {
            Field field = ChunkAccess.class.getDeclaredField("pendingBlockEntities");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    static ChunkAccess chunk(LevelAccessor level, BlockPos pos) {
        ChunkAccess chunk = level.getChunk(pos);
        // ImposterProtoChunk#removeBlockEntity is a no-op, including when writes are forwarded.
        return chunk instanceof ImposterProtoChunk imposter ? imposter.getWrapped() : chunk;
    }

    static void discard(ChunkAccess chunk, BlockPos pos) {
        chunk.removeBlockEntity(pos);
        // LevelChunk also retains instantiated entities when it is not loaded/ticking yet.
        if (chunk instanceof LevelChunk live) {
            BlockEntity old = live.getBlockEntities().remove(pos);
            if (old != null) old.setRemoved();
        }
        discardPending(chunk, pos);
    }

    @SuppressWarnings("unchecked")
    private static void discardPending(ChunkAccess chunk, BlockPos pos) {
        try {
            ((Map<BlockPos, CompoundTag>) PENDING.get(chunk)).remove(pos);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot discard stamped block entity NBT", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean hasPending(ChunkAccess chunk, BlockPos pos) {
        try {
            return ((Map<BlockPos, CompoundTag>) PENDING.get(chunk)).containsKey(pos);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot inspect stamped block entity NBT", e);
        }
    }

    public static boolean set(LevelAccessor level, BlockPos pos, BlockState state, int flags) {
        if (level.isOutsideBuildHeight(pos) || (level instanceof WorldGenLevel worldgen && !worldgen.ensureCanWrite(pos))) return false;
        if (HuaHerbs.protects(level, pos)) return false;
        ChunkAccess chunk = chunk(level, pos);
        discard(chunk, pos);
        boolean changed = level.setBlock(pos, state, flags);
        finish(chunk, pos);
        return changed;
    }

    private static BlockEntity fresh(BlockPos pos, BlockState state) {
        return state.hasBlockEntity() && state.getBlock() instanceof EntityBlock factory ? factory.newBlockEntity(pos, state) : null;
    }

    private static void finish(ChunkAccess chunk, BlockPos pos) {
        // WorldGenRegion writes DUMMY even for an EntityBlock whose factory returns null (unbridged pillars/gates).
        // Never resolve that placeholder. Install a real entity only when this particular state supports one.
        discardPending(chunk, pos);
        BlockState state = chunk.getBlockState(pos);
        BlockEntity entity = fresh(pos, state);
        if (entity == null) {
            discard(chunk, pos);
        } else if (chunk.getBlockEntity(pos) == null) {
            if (chunk instanceof LevelChunk live) {
                live.addAndRegisterBlockEntity(entity);
            } else {
                chunk.setBlockEntity(entity);
            }
        }
    }

    /** Keep vanilla rotation, NBT loading, clipping and fluid handling; clean only the actual sparse entries. */
    static void place(StructureTemplate template, ServerLevelAccessor level, BlockPos origin,
            StructurePlaceSettings settings, RandomSource random, int flags) {
        place(template, level, origin, settings, random, flags, null);
    }

    static void place(StructureTemplate template, ServerLevelAccessor level, BlockPos origin,
            StructurePlaceSettings settings, RandomSource random, int flags, Work work) {
        List<BlockPos> written = new ArrayList<>();
        StructurePlaceSettings safe = settings.copy().addProcessor(new StructureProcessor() {
            // API: NeoForge 1.21.1 patches/.../templatesystem/StructureProcessor.java#process;
            // StructureTemplate#placeInWorld processes transformed positions before writing, then rotates states.
            @Override
            public StructureTemplate.StructureBlockInfo process(LevelReader reader, BlockPos offset, BlockPos pivot,
                    StructureTemplate.StructureBlockInfo original, StructureTemplate.StructureBlockInfo current,
                    StructurePlaceSettings placement, StructureTemplate source) {
                if (work != null) work.entries++;
                BlockPos pos = current.pos();
                if (placement.getBoundingBox() != null && !placement.getBoundingBox().isInside(pos)) return null;
                if (level.isOutsideBuildHeight(pos) || (level instanceof WorldGenLevel worldgen && !worldgen.ensureCanWrite(pos))) return null;
                if (HuaHerbs.protects(level, pos)) return null;
                if (work != null) {
                    work.clippedEntries++;
                    work.sections.add(new BlockPos(pos.getX() >> 4, pos.getY() >> 4, pos.getZ() >> 4));
                }
                ChunkAccess target = chunk(level, pos);
                BlockState before = target.getBlockState(pos);
                BlockState state = current.state().mirror(placement.getMirror()).rotate(placement.getRotation());
                boolean pending = hasPending(target, pos);
                // Include orphan instances whose block was already changed (the proto regression covers this).
                boolean existing = before.hasBlockEntity() || pending || (target instanceof LevelChunk live
                        ? live.getBlockEntities().containsKey(pos) : target.getBlockEntity(pos) != null);
                // ADR 0021 lifecycle work belongs to entity-bearing entries, not every terrain/air block.
                // A repeated cleanup stamp can also leave identical ordinary blocks alone.
                if (!existing && !state.hasBlockEntity() && current.nbt() == null
                        && before == state) return null;
                if (existing) discard(target, pos);
                if (state.hasBlockEntity()) written.add(pos.immutable());
                BlockEntity entity = fresh(pos, state);
                if (entity != null && level.getBlockState(pos) == state) {
                    // A same-state live write returns false, bypassing vanilla's template-NBT loading.
                    level.setBlock(pos, Blocks.BARRIER.defaultBlockState(), 20);
                }
                // A stale tag on an intentionally BE-less state must not trigger vanilla's getBlockEntity either.
                return entity == null && current.nbt() != null
                        ? new StructureTemplate.StructureBlockInfo(pos, current.state(), null) : current;
            }

            @Override
            protected StructureProcessorType<?> getType() {
                throw new UnsupportedOperationException("Stamp lifecycle processor is never serialized");
            }
        });
        template.placeInWorld(level, origin, origin, safe, random, flags);
        for (BlockPos pos : written) finish(chunk(level, pos), pos);
    }
}
