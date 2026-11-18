package com.blizzard.weather;

import com.blizzard.config.ConfigHandler;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.IChunkProvider;
import net.minecraft.world.gen.IChunkGenerator;
import net.minecraftforge.fml.common.IWorldGenerator;

import java.util.Random;

/**
 * Генерация первичного снега в новых чанках через IWorldGenerator.
 *
 * weight = 100 при регистрации — снег ложится после ванильной генерации.
 *
 * Логика:
 *  - от heightmap спускаемся вниз до первой опоры (пропуская листву и воздух);
 *  - снег кладём в блок СРАЗУ над опорой, только если там воздух или трава;
 *  - если над опорой листва — колонку пропускаем (на крону не лезем);
 *  - трава (обычная и двойная) заменяется снегом; у двойной
 *    верхняя половина убирается явно, чтобы не висела в воздухе.
 */
public class SnowWorldGenerator implements IWorldGenerator {

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world,
                         IChunkGenerator chunkGenerator, IChunkProvider chunkProvider) {
        if (!ConfigHandler.snow.fillOnChunkLoad) return;
        if (world.isRemote) return;
        if (!isDimensionEnabled(world.provider.getDimension())) return;

        Chunk chunk = world.getChunk(chunkX, chunkZ);
        if (chunk == null) return;

        fillChunk(world, chunk, random);
    }

    private boolean isDimensionEnabled(int dimensionId) {
        int[] dims = ConfigHandler.snow.fillDimensions;
        if (dims == null || dims.length == 0) return false;
        for (int id : dims) {
            if (id == dimensionId) return true;
        }
        return false;
    }

    private void fillChunk(World world, Chunk chunk, Random random) {
        int baseX = chunk.x * 16;
        int baseZ = chunk.z * 16;
        int minLayers = ConfigHandler.snow.fillMinLayers;
        int maxLayers = ConfigHandler.snow.fillMaxLayers;
        int maxY = ConfigHandler.snow.fillMaxY;

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int y = chunk.getHeightValue(dx, dz);
                if (y <= 0 || y > maxY) continue;

                int x = baseX + dx;
                int z = baseZ + dz;

                BlockPos supportPos = findSupport(world, x, y - 1, z);
                if (supportPos == null) continue;

                BlockPos snowPos = supportPos.up();
                IBlockState above = world.getBlockState(snowPos);

                // Снег кладём только в воздух или на место травы.
                // Листва и прочее — колонку пропускаем.
                if (above.getBlock() == Blocks.AIR) {
                    // всё ок
                } else if (isGrass(above)) {
                    removeDoublePlantUpper(world, snowPos, above);
                } else {
                    continue;
                }

                int layers = minLayers + random.nextInt(maxLayers - minLayers + 1);
                world.setBlockState(snowPos, Blocks.SNOW_LAYER.getDefaultState()
                        .withProperty(BlockSnow.LAYERS, layers), 2);
            }
        }
    }

    /**
     * Если блок — нижняя половина двойной травы, убираем верхнюю половину.
     * Вызывать ДО замены нижней половины снегом.
     */
    private void removeDoublePlantUpper(World world, BlockPos lowerPos, IBlockState lowerState) {
        if (lowerState.getBlock() != Blocks.DOUBLE_PLANT) return;
        if (lowerState.getValue(BlockDoublePlant.HALF) != BlockDoublePlant.EnumBlockHalf.LOWER) return;

        BlockPos upperPos = lowerPos.up();
        IBlockState upper = world.getBlockState(upperPos);
        if (upper.getBlock() == Blocks.DOUBLE_PLANT
                && upper.getValue(BlockDoublePlant.HALF) == BlockDoublePlant.EnumBlockHalf.UPPER) {
            world.setBlockToAir(upperPos);
        }
    }

    /**
     * Спуск от startY вниз до первой опоры для снега.
     * Листва и воздух пропускаются. Любой другой неподходящий блок
     * (вода, лава и т.п.) — стоп, возвращаем null.
     */
    private BlockPos findSupport(World world, int x, int startY, int z) {
        for (int scanY = startY; scanY >= 0; scanY--) {
            BlockPos p = new BlockPos(x, scanY, z);
            IBlockState st = world.getBlockState(p);

            if (isLeaves(st) || st.getBlock() == Blocks.AIR) continue; // идём дальше
            if (canSupportSnow(st)) return p;                          // опора
            return null;                                               // стоп
        }
        return null;
    }

    private boolean isLeaves(IBlockState state) {
        return state.getBlock() == Blocks.LEAVES || state.getBlock() == Blocks.LEAVES2;
    }

    /** Трава: обычная (TALLGRASS) и двойная (DOUBLE_PLANT с типом GRASS/FERN). */
    private boolean isGrass(IBlockState state) {
        if (state.getBlock() == Blocks.TALLGRASS) return true;
        if (state.getBlock() == Blocks.DOUBLE_PLANT) {
            BlockDoublePlant.EnumPlantType type =
                    state.getValue(BlockDoublePlant.VARIANT);
            return type == BlockDoublePlant.EnumPlantType.GRASS
                    || type == BlockDoublePlant.EnumPlantType.FERN;
        }
        return false;
    }

    /**
     * Можно ли класть снег на этот блок.
     * Твёрдый непрозрачный куб, лёд, плотный лёд — да.
     * Снежный слой, воздух, жидкости, неполные блоки — нет.
     */
    private boolean canSupportSnow(IBlockState state) {
        if (state == null) return false;
        if (state.getBlock() == Blocks.SNOW_LAYER) return false;
        if (state.getBlock() == Blocks.ICE) return true;
        if (state.getBlock() == Blocks.PACKED_ICE) return true;
        return state.isFullBlock() && state.isOpaqueCube();
    }
}