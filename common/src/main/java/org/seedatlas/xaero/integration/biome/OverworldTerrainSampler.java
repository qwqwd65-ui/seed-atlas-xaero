package org.seedatlas.xaero.integration.biome;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Seed-only vanilla base terrain; never reads a world, constructs a chunk, or adds vegetation. */
public final class OverworldTerrainSampler {
    private static final int TILE_SIZE = 16;
    private static final int MAX_DIMENSION = 262;
    // 16x16 samples each, ~3 KB: hardware-scaled to balance zoom/pan reuse against client heap limits.
    private static final int MAX_CACHED_TILES = HardwareProfile.overworldCachedTiles();
    // The Overworld top slide fixes density to -0.078125 from 64 blocks below the world top up, and
    // fluid only fills air below sea level, so every block from there up is air.
    private static final int TOP_SLIDE_END_DEPTH = 64;
    private static final int UPPER_PASS_MIN_Y = 16;
    private static final int MAX_CONTEXTS = 2;
    private static final LinkedHashMap<TileKey, SurfaceGrid> CACHE = new LinkedHashMap<>(16, 0.75f, true);
    private static final LinkedHashMap<WorldKey, Context> CONTEXTS = new LinkedHashMap<>(4, 0.75f, true);
    private static final ConcurrentHashMap<String, Holder<Biome>> PREVIEW_BIOMES = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<TileKey, CompletableFuture<SurfaceGrid>> GENERATING = new ConcurrentHashMap<>();

    private OverworldTerrainSampler() { }

    /**
     * Row-major arrays, sampled at origin + index * step + (step - 1) / 2, including any caller halo.
     * Heights are the highest non-air base block Y (including fluid); floors are the highest solid Y.
     * These match base columns, not surface rules, carvers, structures, features, ice or snow layers.
     * Colours infer surface material from the base block, surface biome and temperature, not exact blocks.
     * Grass and leaves use the biome's vanilla tint; forest crowns are decoration, not predicted trees.
     * Minecraft must already be bootstrapped. Steps 1/2/4/8/16 and dimensions 1..262 are supported;
     * coarse steps still evaluate real vanilla density and are substantially more expensive per sample.
     */
    public static SurfaceGrid sample(long seed, boolean largeBiomes, int originX, int originZ,
                                     int step, int width, int height) {
        return sample(seed, largeBiomes, originX, originZ, step, width, height, () -> true, () -> true);
    }

    /** Cancels stale work between output rows and before each tile it would generate. Safe to call from several threads. */
    public static SurfaceGrid sample(long seed, boolean largeBiomes, int originX, int originZ,
                                     int step, int width, int height, BooleanSupplier stillCurrent) {
        return sample(seed, largeBiomes, originX, originZ, step, width, height, stillCurrent, () -> true);
    }

    static SurfaceGrid sample(long seed, boolean largeBiomes, int originX, int originZ,
                              int step, int width, int height, BooleanSupplier stillCurrent, BooleanSupplier fastAbort) {
        if (step < 1 || step > 16 || (step & (step - 1)) != 0) {
            throw new IllegalArgumentException("step must be one of 1, 2, 4, 8, or 16");
        }
        if (width < 1 || height < 1 || width > MAX_DIMENSION || height > MAX_DIMENSION) {
            throw new IllegalArgumentException("Surface dimensions must be between 1 and " + MAX_DIMENSION);
        }
        if ((long) originX + (long) width * step - 1 > Integer.MAX_VALUE
                || (long) originZ + (long) height * step - 1 > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Surface area exceeds block coordinate limits");
        }
        Objects.requireNonNull(stillCurrent, "stillCurrent");
        Objects.requireNonNull(fastAbort, "fastAbort");
        var world = new WorldKey(seed, largeBiomes);
        var result = new SurfaceGrid(new float[width * height], new float[width * height], new int[width * height]);
        int span = TILE_SIZE * step;
        for (int z = 0; z < height; ++z) {
            requireCurrent(stillCurrent);
            int blockZ = originZ + z * step + (step - 1) / 2;
            for (int x = 0; x < width; ++x) {
                int blockX = originX + x * step + (step - 1) / 2;
                var key = new TileKey(world, step, Math.floorDiv(blockX, span), Math.floorDiv(blockZ, span),
                        Math.floorMod(blockX, step), Math.floorMod(blockZ, step));
                var tile = tile(key, stillCurrent, fastAbort);
                int source = (Math.floorMod(blockZ, span) / step) * TILE_SIZE + Math.floorMod(blockX, span) / step;
                int target = z * width + x;
                result.heights[target] = tile.heights[source];
                result.floors[target] = tile.floors[source];
                result.colors[target] = tile.colors[source];
            }
        }
        return result;
    }

    public static void releaseIdleMemory() {
        PREVIEW_BIOMES.clear();
        synchronized (CACHE) {
            if (HardwareProfile.isConstrainedMemory()) {
                CACHE.clear();
            } else {
                int keep = HardwareProfile.isMediumMemory() ? 128 : 256;
                var iterator = CACHE.entrySet().iterator();
                while (CACHE.size() > keep && iterator.hasNext()) {
                    iterator.next();
                    iterator.remove();
                }
            }
        }
        if (HardwareProfile.isConstrainedMemory()) {
            synchronized (CONTEXTS) {
                CONTEXTS.clear();
            }
        }
    }

    public record SurfaceGrid(float[] heights, float[] floors, int[] colors) { }

    /** Cheap LOD preview using the same material palette, without evaluating terrain density. */
    static int previewColor(String name, int x, int y, int z, boolean water, int step) {
        if (name == null) return -1;
        var biome = PREVIEW_BIOMES.computeIfAbsent(name, value -> VanillaLookup.get().lookupOrThrow(Registries.BIOME)
                .get(ResourceKey.create(Registries.BIOME, Identifier.parse(value))).orElse(null));
        return biome == null ? -1 : surfaceColor(biome, x, y, z, water, 63, step);
    }

    private static SurfaceGrid tile(TileKey key, BooleanSupplier stillCurrent, BooleanSupplier fastAbort) {
        synchronized (CACHE) {
            var cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        requireCurrent(stillCurrent);
        while (true) {
            requireCurrent(fastAbort);
            // RandomState is shared by vanilla's own parallel chunk generation, so workers generate
            // different tiles at once; one that another worker is already generating is awaited instead.
            var mine = new CompletableFuture<SurfaceGrid>();
            var running = GENERATING.putIfAbsent(key, mine);
            if (running != null) {
                try {
                    return await(running, fastAbort);
                } catch (CancellationException otherCancelled) {
                    if (!fastAbort.getAsBoolean()) throw otherCancelled;
                    synchronized (CACHE) {
                        var cached = CACHE.get(key);
                        if (cached != null) return cached;
                    }
                    continue;
                }
            }
            try {
                SurfaceGrid generated;
                synchronized (CACHE) {
                    generated = CACHE.get(key);
                }
                if (generated == null) {
                    generated = context(key.world).generate(key, fastAbort);
                    synchronized (CACHE) {
                        if (CACHE.size() == MAX_CACHED_TILES) CACHE.remove(CACHE.keySet().iterator().next());
                        CACHE.put(key, generated);
                    }
                }
                mine.complete(generated);
                return generated;
            } catch (RuntimeException | Error error) {
                mine.completeExceptionally(error);
                throw error;
            } finally {
                GENERATING.remove(key, mine);
            }
        }
    }

    private static SurfaceGrid await(CompletableFuture<SurfaceGrid> running, BooleanSupplier fastAbort) {
        while (true) {
            requireCurrent(fastAbort);
            try {
                return running.get(25L, TimeUnit.MILLISECONDS);
            } catch (TimeoutException ignored) {
                // Re-check fastAbort while waiting so closing the map or panning wakes workers immediately.
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new CancellationException("Interrupted while awaiting Overworld sample");
            } catch (ExecutionException wrapped) {
                if (wrapped.getCause() instanceof RuntimeException cause) throw cause;
                if (wrapped.getCause() instanceof Error cause) throw cause;
                throw new CompletionException(wrapped.getCause());
            }
        }
    }

    private static Context context(WorldKey world) {
        synchronized (CONTEXTS) {
            var context = CONTEXTS.get(world);
            if (context == null) {
                context = new Context(world);
                if (CONTEXTS.size() == MAX_CONTEXTS) CONTEXTS.remove(CONTEXTS.keySet().iterator().next());
                CONTEXTS.put(world, context);
            }
            return context;
        }
    }

    private static void requireCurrent(BooleanSupplier stillCurrent) {
        if (!stillCurrent.getAsBoolean()) throw new CancellationException("Overworld sample is no longer current");
    }

    private record WorldKey(long seed, boolean largeBiomes) { }
    private record TileKey(WorldKey world, int step, int x, int z, int phaseX, int phaseZ) { }

    private static final class Context {
        private final NoiseGeneratorSettings baseSettings;
        private final NoiseGeneratorSettings terrainSettings;
        private final NoiseSettings upperNoiseSettings;
        private final NoiseSettings lowerNoiseSettings;
        private final RandomState terrainState;
        private final Climate.Sampler biomeSampler;
        private final Aquifer.FluidPicker fluids;

        private Context(WorldKey key) {
            var lookup = VanillaLookup.get();
            var noises = lookup.lookupOrThrow(Registries.NOISE);
            baseSettings = lookup.lookupOrThrow(Registries.NOISE_SETTINGS).getOrThrow(
                    key.largeBiomes ? NoiseGeneratorSettings.LARGE_BIOMES : NoiseGeneratorSettings.OVERWORLD).value();
            biomeSampler = RandomState.create(baseSettings, noises, key.seed).sampler();

            NoiseRouter r = baseSettings.noiseRouter();
            DensityFunction zero = DensityFunctions.zero();
            // Store finalDensity in veinToggle with oreVeinsEnabled=false: RandomState wires finalDensity,
            // preliminarySurfaceLevel, erosion, and depth in one shared pass (preserving FlatCache/Cache2D
            // node identity inside NoiseChunk.wrapped), while skipping OreVeinifier's three 3D interpolators,
            // unused climate FlatCaches, and NoiseChunk's 128-voxel CacheAllInCell evaluation.
            NoiseRouter terrainRouter = new NoiseRouter(
                    r.barrierNoise(),
                    r.fluidLevelFloodednessNoise(),
                    r.fluidLevelSpreadNoise(),
                    r.lavaNoise(),
                    zero,
                    zero,
                    zero,
                    r.erosion(),
                    r.depth(),
                    zero,
                    r.preliminarySurfaceLevel(),
                    zero,
                    r.finalDensity(),
                    zero,
                    zero);
            terrainSettings = new NoiseGeneratorSettings(
                    baseSettings.noiseSettings(),
                    baseSettings.defaultBlock(),
                    baseSettings.defaultFluid(),
                    terrainRouter,
                    baseSettings.surfaceRule(),
                    baseSettings.spawnTarget(),
                    baseSettings.seaLevel(),
                    baseSettings.disableMobGeneration(),
                    baseSettings.isAquifersEnabled(),
                    false,
                    baseSettings.useLegacyRandomSource());
            terrainState = RandomState.create(terrainSettings, noises, key.seed);

            var baseNoise = baseSettings.noiseSettings();
            int baseMinY = baseNoise.minY();
            int activeHeight = baseNoise.height() - TOP_SLIDE_END_DEPTH;
            if (baseMinY < UPPER_PASS_MIN_Y && baseMinY + activeHeight > UPPER_PASS_MIN_Y) {
                upperNoiseSettings = baseNoise.clampToHeightAccessor(
                        LevelHeightAccessor.create(UPPER_PASS_MIN_Y, baseMinY + activeHeight - UPPER_PASS_MIN_Y));
                lowerNoiseSettings = baseNoise.clampToHeightAccessor(
                        LevelHeightAccessor.create(baseMinY, UPPER_PASS_MIN_Y - baseMinY));
            } else {
                upperNoiseSettings = baseNoise.clampToHeightAccessor(
                        LevelHeightAccessor.create(baseMinY, activeHeight));
                lowerNoiseSettings = null;
            }

            var water = new Aquifer.FluidStatus(baseSettings.seaLevel(), baseSettings.defaultFluid());
            var lava = new Aquifer.FluidStatus(-54, Blocks.LAVA.defaultBlockState());
            // Same built-in Overworld global picker as NoiseBasedChunkGenerator (without debug fluid disabling).
            fluids = (x, y, z) -> y < Math.min(-54, baseSettings.seaLevel()) ? lava : water;
        }

        private SurfaceGrid generate(TileKey key, BooleanSupplier fastAbort) {
            var baseNoise = baseSettings.noiseSettings();
            int baseMinY = baseNoise.minY();
            int samplesPerChunk = Math.max(1, 16 / key.step);
            int originX = key.x * TILE_SIZE * key.step + key.phaseX;
            int originZ = key.z * TILE_SIZE * key.step + key.phaseZ;
            int columns = TILE_SIZE * TILE_SIZE;
            int[] tops = new int[columns];
            int[] floors = new int[columns];
            boolean[] fluid = new boolean[columns];
            boolean[] done = new boolean[columns];
            Arrays.fill(tops, baseMinY - 1);
            Arrays.fill(floors, baseMinY - 1);

            for (int gz = 0; gz < TILE_SIZE; gz += samplesPerChunk) {
                int gzEnd = Math.min(TILE_SIZE, gz + samplesPerChunk);
                for (int gx = 0; gx < TILE_SIZE; gx += samplesPerChunk) {
                    requireCurrent(fastAbort);
                    int gxEnd = Math.min(TILE_SIZE, gx + samplesPerChunk);
                    int remaining = (gxEnd - gx) * (gzEnd - gz);
                    remaining = scanRange(key, originX, originZ, gx, gxEnd, gz, gzEnd,
                            upperNoiseSettings, baseMinY, tops, floors, fluid, done, remaining, fastAbort);
                    if (remaining > 0 && lowerNoiseSettings != null) {
                        scanRange(key, originX, originZ, gx, gxEnd, gz, gzEnd,
                                lowerNoiseSettings, baseMinY, tops, floors, fluid, done, remaining, fastAbort);
                    }
                }
            }

            var biomes = Vanilla.biomes();
            int[] cachedQx = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            int[] cachedQy = new int[4];
            int[] cachedQz = new int[4];
            @SuppressWarnings("unchecked")
            Holder<Biome>[] cachedBiome = (Holder<Biome>[]) new Holder<?>[4];
            int seaLevel = baseSettings.seaLevel();
            var result = new SurfaceGrid(new float[columns], new float[columns], new int[columns]);
            for (int z = 0; z < TILE_SIZE; ++z) {
                int blockZ = originZ + z * key.step;
                int qz = blockZ >> 2;
                for (int x = 0; x < TILE_SIZE; ++x) {
                    int blockX = originX + x * key.step;
                    int index = z * TILE_SIZE + x;
                    int top = tops[index];
                    result.heights[index] = top;
                    result.floors[index] = floors[index];
                    int qx = blockX >> 2;
                    int qy = top >> 2;
                    int slot = qx & 3;
                    Holder<Biome> biome = cachedBiome[slot];
                    if (biome == null || cachedQx[slot] != qx || cachedQy[slot] != qy || cachedQz[slot] != qz) {
                        biome = biomes.getNoiseBiome(qx, qy, qz, biomeSampler);
                        cachedQx[slot] = qx;
                        cachedQy[slot] = qy;
                        cachedQz[slot] = qz;
                        cachedBiome[slot] = biome;
                    }
                    result.colors[index] = surfaceColor(biome, blockX, top, blockZ, fluid[index], seaLevel, key.step);
                }
            }
            return result;
        }

        private int scanRange(TileKey key, int originX, int originZ, int gx, int gxEnd, int gz, int gzEnd,
                              NoiseSettings noiseSettings, int baseMinY, int[] tops, int[] floors,
                              boolean[] fluid, boolean[] done, int remaining, BooleanSupplier fastAbort) {
            int minBlockX = Integer.MAX_VALUE;
            int maxBlockX = Integer.MIN_VALUE;
            int minBlockZ = Integer.MAX_VALUE;
            int maxBlockZ = Integer.MIN_VALUE;
            for (int z = gz; z < gzEnd; ++z) {
                int blockZ = originZ + z * key.step;
                for (int x = gx; x < gxEnd; ++x) {
                    if (done[z * TILE_SIZE + x]) continue;
                    int blockX = originX + x * key.step;
                    if (blockX < minBlockX) minBlockX = blockX;
                    if (blockX > maxBlockX) maxBlockX = blockX;
                    if (blockZ < minBlockZ) minBlockZ = blockZ;
                    if (blockZ > maxBlockZ) maxBlockZ = blockZ;
                }
            }
            if (minBlockX > maxBlockX) return 0;

            int cellWidth = noiseSettings.getCellWidth();
            int cellHeight = noiseSettings.getCellHeight();
            int minY = noiseSettings.minY();
            int topCell = noiseSettings.height() / cellHeight - 1;
            int firstCellX = Math.floorDiv(minBlockX, cellWidth);
            int lastCellX = Math.floorDiv(maxBlockX, cellWidth);
            int firstCellZ = Math.floorDiv(minBlockZ, cellWidth);
            int lastCellZ = Math.floorDiv(maxBlockZ, cellWidth);
            int countX = lastCellX - firstCellX + 1;
            int countZ = lastCellZ - firstCellZ + 1;
            int cellCountXZ = Math.max(countX, countZ);

            var noise = new SurfaceNoise(cellCountXZ, terrainState, firstCellX * cellWidth, firstCellZ * cellWidth,
                    noiseSettings, terrainSettings, fluids);
            noise.initializeForFirstCellX();
            try {
                for (int cx = 0; cx < countX && remaining > 0; ++cx) {
                    requireCurrent(fastAbort);
                    noise.advanceCellX(cx);
                    int targetCellX = firstCellX + cx;
                    int cellBlockX = targetCellX * cellWidth;
                    int xStart = gxEnd;
                    int xEnd = gxEnd;
                    for (int x = gx; x < gxEnd; ++x) {
                        int cellX = Math.floorDiv(originX + x * key.step, cellWidth);
                        if (cellX == targetCellX) {
                            if (xStart == gxEnd) xStart = x;
                            xEnd = x + 1;
                        } else if (cellX > targetCellX) {
                            break;
                        }
                    }
                    if (xStart < xEnd) {
                        for (int cz = 0; cz < countZ && remaining > 0; ++cz) {
                            int targetCellZ = firstCellZ + cz;
                            int cellBlockZ = targetCellZ * cellWidth;
                            int zStart = gzEnd;
                            int zEnd = gzEnd;
                            int cellRemaining = 0;
                            for (int z = gz; z < gzEnd; ++z) {
                                int cellZ = Math.floorDiv(originZ + z * key.step, cellWidth);
                                if (cellZ == targetCellZ) {
                                    if (zStart == gzEnd) zStart = z;
                                    zEnd = z + 1;
                                    for (int x = xStart; x < xEnd; ++x) {
                                        if (!done[z * TILE_SIZE + x]) ++cellRemaining;
                                    }
                                } else if (cellZ > targetCellZ) {
                                    break;
                                }
                            }
                            if (cellRemaining == 0) continue;

                            for (int cy = topCell; cy >= 0 && cellRemaining > 0; --cy) {
                                noise.selectCellYZ(cy, cz);
                                for (int dy = cellHeight - 1; dy >= 0 && cellRemaining > 0; --dy) {
                                    int blockY = minY + cy * cellHeight + dy;
                                    noise.updateForY(blockY, (double) dy / cellHeight);
                                    for (int x = xStart; x < xEnd; ++x) {
                                        boolean xActive = false;
                                        for (int z = zStart; z < zEnd; ++z) {
                                            if (!done[z * TILE_SIZE + x]) {
                                                xActive = true;
                                                break;
                                            }
                                        }
                                        if (!xActive) continue;
                                        int blockX = originX + x * key.step;
                                        noise.updateForX(blockX, (double) (blockX - cellBlockX) / cellWidth);
                                        for (int z = zStart; z < zEnd; ++z) {
                                            int index = z * TILE_SIZE + x;
                                            if (done[index]) continue;
                                            int blockZ = originZ + z * key.step;
                                            noise.updateForZ(blockZ, (double) (blockZ - cellBlockZ) / cellWidth);
                                            var block = noise.sample();
                                            if (block == null) block = baseSettings.defaultBlock();
                                            if (block.isAir()) continue;
                                            if (tops[index] < baseMinY) {
                                                tops[index] = blockY;
                                                fluid[index] = !block.getFluidState().isEmpty();
                                            }
                                            if (block.getFluidState().isEmpty()) {
                                                floors[index] = blockY;
                                                done[index] = true;
                                                --cellRemaining;
                                                --remaining;
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    if (cx + 1 < countX && remaining > 0) {
                        noise.swapSlices();
                    }
                }
            } finally {
                noise.stopInterpolation();
            }
            return remaining;
        }
    }

    /** Exposes vanilla's cell interpolation and aquifer sampler while evaluating only requested voxels. */
    private static final class SurfaceNoise extends NoiseChunk {
        private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES = new DensityFunctions.BeardifierOrMarker() {
            @Override public double compute(DensityFunction.FunctionContext context) { return 0; }
            @Override public void fillArray(double[] output, DensityFunction.ContextProvider provider) { Arrays.fill(output, 0); }
            @Override public double minValue() { return 0; }
            @Override public double maxValue() { return 0; }
        };
        private final DensityFunction density;

        private SurfaceNoise(int cellCountXZ, RandomState state, int x, int z, NoiseSettings noiseSettings,
                             NoiseGeneratorSettings settings, Aquifer.FluidPicker fluids) {
            super(cellCountXZ, state, x, z, noiseSettings, NO_STRUCTURES,
                    settings, fluids, Blender.empty());
            this.density = state.router().veinToggle().mapAll(this::wrap);
        }

        private BlockState sample() {
            return this.aquifer().computeSubstance(this, this.density.compute(this));
        }
    }

    /** Surface colour of one sample; see {@link BiomeSurfacePalette}. */
    static int surfaceColor(Holder<Biome> biome, int x, int y, int z, boolean fluid, int seaLevel, int step) {
        return BiomeSurfacePalette.surfaceColor(biome, x, y, z, fluid, seaLevel, step);
    }

    private static final class Vanilla {
        private static volatile MultiNoiseBiomeSource biomes;

        private static MultiNoiseBiomeSource biomes() {
            MultiNoiseBiomeSource source = biomes;
            if (source == null) {
                synchronized (Vanilla.class) {
                    source = biomes;
                    if (source == null) {
                        source = MultiNoiseBiomeSource.createFromPreset(
                                VanillaLookup.get().lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                                        .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
                        biomes = source;
                    }
                }
            }
            return source;
        }
    }
}
