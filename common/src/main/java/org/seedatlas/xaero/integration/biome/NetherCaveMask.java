package org.seedatlas.xaero.integration.biome;

import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.biome.FixedBiomeSource;
import net.minecraft.world.level.chunk.CarvingMask;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.LegacyRandomSource;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.WorldgenRandom;
import net.minecraft.world.level.levelgen.carver.CarvingContext;
import net.minecraft.world.level.levelgen.carver.CaveCarverConfiguration;
import net.minecraft.world.level.levelgen.carver.ConfiguredWorldCarver;
import net.minecraft.world.level.levelgen.carver.NetherWorldCarver;
import org.apache.commons.lang3.mutable.MutableBoolean;

/** Vanilla Nether carver geometry from the seed alone; never loads or generates world chunks. */
public final class NetherCaveMask {
    private static final int MAX_DIMENSION = 262;
    private static final int MAX_CACHED_CHUNKS = 512;
    private static final LinkedHashMap<Key, long[]> CACHE = new LinkedHashMap<>(16, 0.75f, true);

    private NetherCaveMask() { }

    /**
     * Two words per row-major sample: bits 0..63, then 64..127. Set bits are carver
     * geometry, not a complete air/material prediction. Steps 1/2/4 use the lower
     * block at the native fractional centre; steps 8/16 use its nearest 4-block cell.
     * Dimensions must be 1..262 (including a tile's halo). Minecraft must already be bootstrapped.
     */
    public static long[] area(long seed, int originX, int originZ, int step, int width, int height) {
        return area(seed, originX, originZ, step, width, height, () -> true);
    }

    static long[] area(long seed, int originX, int originZ, int step, int width, int height, BooleanSupplier fastAbort) {
        if (step < 1 || step > 16 || (step & (step - 1)) != 0) {
            throw new IllegalArgumentException("step must be one of 1, 2, 4, 8, or 16");
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Mask dimensions must be between 1 and " + MAX_DIMENSION);
        }
        if ((long) originX + (long) width * step - 1 > Integer.MAX_VALUE
                || (long) originZ + (long) height * step - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Mask area exceeds block coordinate limits");
        }
        Objects.requireNonNull(fastAbort, "fastAbort");
        long[] result = new long[width * height * 2];
        for (int z = 0; z < height; ++z) {
            requireCurrent(fastAbort);
            int blockZ = sampleCoordinate(originZ, z, step);
            for (int x = 0; x < width; ++x) {
                int blockX = sampleCoordinate(originX, x, step);
                long[] chunk = chunkMask(new Key(seed, blockX >> 4, blockZ >> 4), fastAbort);
                int source = ((blockZ & 15) * 16 + (blockX & 15)) * 2;
                int target = (z * width + x) * 2;
                result[target] = chunk[source];
                result[target + 1] = chunk[source + 1];
            }
        }
        return result;
    }

    public static synchronized void releaseIdleMemory() {
        CACHE.clear();
    }

    private static int sampleCoordinate(int origin, int index, int step) {
        long block = (long) origin + (long) index * step;
        return (int) (step >= 8 ? Math.floorDiv(block + step / 2 + 2, 4L) * 4
                : block + (step - 1) / 2);
    }

    private static long[] chunkMask(Key key, BooleanSupplier fastAbort) {
        synchronized (NetherCaveMask.class) {
            long[] mask = CACHE.get(key);
            if (mask != null) return mask;
        }
        requireCurrent(fastAbort);
        long[] generated = generate(key, fastAbort);
        synchronized (NetherCaveMask.class) {
            long[] existing = CACHE.get(key);
            if (existing != null) return existing;
            if (CACHE.size() == MAX_CACHED_CHUNKS) CACHE.remove(CACHE.keySet().iterator().next());
            CACHE.put(key, generated);
            return generated;
        }
    }

    private static long[] generate(Key key, BooleanSupplier fastAbort) {
        Vanilla state = Vanilla.get();
        long[] mask = new long[16 * 16 * 2];
        var target = new ChunkPos(key.chunkX, key.chunkZ);
        var random = new WorldgenRandom(new LegacyRandomSource(0));
        // Older carvers need chunk coordinates. This empty shell has zero sections;
        // the recording carver never reads blocks, allocates terrain or loads a world.
        var shell = new ProtoChunk(target, UpgradeData.EMPTY, LevelHeightAccessor.create(0, 0), null, null);
        var output = new CarvingMask(128, 0);
        for (int dx = -8; dx <= 8; ++dx) {
            requireCurrent(fastAbort);
            for (int dz = -8; dz <= 8; ++dz) {
                var source = new ChunkPos(key.chunkX + dx, key.chunkZ + dz);
                int index = 0;
                for (var holder : state.carvers) {
                    var carver = holder.value();
                    random.setLargeFeatureSeed(key.seed + index++, source.x, source.z);
                    if (carver.isStartChunk(random)) {
                        if (!(carver.worldCarver() instanceof NetherWorldCarver)
                                || !(carver.config() instanceof CaveCarverConfiguration configuration)) {
                            throw new IllegalStateException("Unsupported built-in Nether carver");
                        }
                        Vanilla.RECORDER.carve(state.context, configuration, shell, null, random, null, source, output);
                    }
                }
            }
        }
        for (int z = 0; z < 16; ++z) for (int x = 0; x < 16; ++x) {
            for (int y = 1; y <= 120; ++y) {
                if (output.get(x, y, z)) mask[(z * 16 + x) * 2 + (y >>> 6)] |= 1L << (y & 63);
            }
        }
        return mask;
    }

    private static void requireCurrent(BooleanSupplier fastAbort) {
        if (!fastAbort.getAsBoolean()) throw new CancellationException("Nether cave mask sample is no longer current");
    }

    private record Key(long seed, int chunkX, int chunkZ) { }

    private static final class Vanilla {
        private static final NetherWorldCarver RECORDER = new NetherWorldCarver(CaveCarverConfiguration.CODEC) {
            @Override protected boolean carveBlock(CarvingContext context, CaveCarverConfiguration configuration,
                    ChunkAccess chunk, Function<BlockPos, Holder<Biome>> biomeGetter, CarvingMask mask,
                    BlockPos.MutableBlockPos position, BlockPos.MutableBlockPos helper, Aquifer aquifer,
                    MutableBoolean hasGrass) {
                // WorldCarver has already recorded this voxel in the CarvingMask.
                return true;
            }
        };
        private static volatile Vanilla instance;

        private final Iterable<Holder<ConfiguredWorldCarver<?>>> carvers;
        private final CarvingContext context;

        private Vanilla() {
            var lookup = VanillaLookup.get();
            var netherWastes = lookup.lookupOrThrow(Registries.BIOME).getOrThrow(Biomes.NETHER_WASTES);
            // All five built-in Nether biomes share these carvers; no live biome source is needed.
            this.carvers = netherWastes.value().getGenerationSettings().getCarvers();
            this.context = new CarvingContext(
                    new NoiseBasedChunkGenerator(
                            new FixedBiomeSource(netherWastes),
                            lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(NoiseGeneratorSettings.NETHER)),
                    RegistryAccess.EMPTY, LevelHeightAccessor.create(0, 256), null, null, null);
        }

        private static Vanilla get() {
            Vanilla current = instance;
            if (current == null) {
                synchronized (Vanilla.class) {
                    current = instance;
                    if (current == null) {
                        current = new Vanilla();
                        instance = current;
                    }
                }
            }
            return current;
        }
    }
}
