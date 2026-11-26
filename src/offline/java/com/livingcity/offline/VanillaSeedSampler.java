package com.livingcity.offline;

import net.minecraft.Bootstrap;
import net.minecraft.SharedConstants;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.fluid.Fluids;
import net.minecraft.registry.BuiltinRegistries;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.world.biome.source.BiomeCoords;
import net.minecraft.world.gen.WorldPresets;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.List;

/** Vanilla 1.20.1 Overworld worldgen APIs, without creating a game, world, or chunk. */
final class VanillaSeedSampler {
    static final int MIN_Y = -64, MAX_Y = 319;
    private static RegistryWrapper.WrapperLookup registries;
    private static NoiseChunkGenerator generator;
    private static ChunkGeneratorSettings settings;
    private static AquiferSampler.FluidLevelSampler fluidLevels;
    private final NoiseConfig noise;

    static void prepare() {
        SharedConstants.createGameVersion();
        Bootstrap.initialize();
        registries = BuiltinRegistries.createWrapperLookup();
        generator = (NoiseChunkGenerator) registries.getWrapperOrThrow(RegistryKeys.WORLD_PRESET)
                .getOrThrow(WorldPresets.DEFAULT).value().getOverworld().orElseThrow().chunkGenerator();
        settings = generator.getSettings().value();
        var lava = new AquiferSampler.FluidLevel(-54, Blocks.LAVA.getDefaultState());
        var sea = new AquiferSampler.FluidLevel(settings.seaLevel(), settings.defaultFluid());
        fluidLevels = (x, y, z) -> y < Math.min(-54, settings.seaLevel()) ? lava : sea;
    }

    VanillaSeedSampler(long seed) {
        noise = NoiseConfig.create(registries.createRegistryLookup(), ChunkGeneratorSettings.OVERWORLD, seed);
    }

    List<OfflineWorld.Tile> surfaceChunk(int cx, int cz) {
        var shape = settings.generationShapeConfig();
        int cellHeight = shape.verticalCellBlockCount();
        int verticalCells = shape.height() / cellHeight;
        var columns = new SurfaceColumns(noise, cx * 16, cz * 16);
        var surface = new OfflineWorld.Tile[16];
        // The default Overworld's four-block horizontal noise cells match this map's 4×4 tiles.
        columns.sampleStartDensity();
        for (int dx = 0; dx < 4; dx++) {
            int x = BiomeCoords.toBlock(cx * 4 + dx) + 2;
            columns.sampleEndDensity(dx);
            int remaining = 4;
            for (int cellY = verticalCells - 1; cellY >= 0 && remaining > 0; cellY--) {
                int baseY = shape.minimumY() + cellY * cellHeight;
                for (int dz = 0; dz < 4; dz++) {
                    int index = dz * 4 + dx;
                    if (surface[index] != null) continue;
                    int z = BiomeCoords.toBlock(cz * 4 + dz) + 2;
                    columns.onSampledCellCorners(cellY, dz);
                    for (int dy = cellHeight - 1; dy >= 0; dy--) {
                        int y = baseY + dy;
                        BlockState top = columns.atSurfacePoint(x, y, z, (double) dy / cellHeight);
                        if (top.isAir()) continue;
                        var fluid = top.getFluidState();
                        boolean water = fluid.isOf(Fluids.WATER) || fluid.isOf(Fluids.FLOWING_WATER);
                        surface[index] = new OfflineWorld.Tile(y, biome(cx * 4 + dx, y, cz * 4 + dz), water);
                        remaining--;
                        break;
                    }
                }
            }
            columns.swapBuffers();
        }
        columns.stopInterpolation();
        for (int i = 0; i < surface.length; i++) {
            if (surface[i] == null) surface[i] = new OfflineWorld.Tile(MIN_Y, biome(cx * 4 + i % 4, MIN_Y, cz * 4 + i / 4), false);
        }
        return List.of(surface);
    }

    String biome(int quartX, int y, int quartZ) {
        return generator.getBiomeSource().getBiome(quartX, BiomeCoords.fromBlock(y), quartZ,
                noise.getMultiNoiseSampler()).getKey().orElseThrow().getValue().getPath();
    }

    private static final class SurfaceColumns extends ChunkNoiseSampler {
        private static final DensityFunctionTypes.Beardifying NO_STRUCTURES = new DensityFunctionTypes.Beardifying() {
            @Override public double sample(DensityFunction.NoisePos position) { return 0; }
            @Override public double minValue() { return 0; }
            @Override public double maxValue() { return 0; }
        };

        SurfaceColumns(NoiseConfig noise, int x, int z) {
            super(4, noise, x, z, settings.generationShapeConfig(), NO_STRUCTURES,
                    settings, fluidLevels, Blender.getNoBlending());
        }

        BlockState atSurfacePoint(int x, int y, int z, double yFraction) {
            interpolateY(y, yFraction);
            interpolateX(x, .5);
            interpolateZ(z, .5);
            var state = sampleBlockState();
            return state == null ? settings.defaultBlock() : state;
        }
    }
}
