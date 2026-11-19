package com.blizzard.weather;

import com.blizzard.config.ConfigHandler;
import net.minecraft.block.Block;
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

public class SnowWorldGenerator implements IWorldGenerator {

    /** Максимальная глубина спуска от heightmap. Снег почти всегда в пределах 20 блоков. */
    private static final int MAX_SCAN_DEPTH = 24;

    @Override
    public void generate(Random random, int chunkX, int chunkZ, World world,
                         IChunkGenerator chunkGenerator, IChunkProvider chunkProvider) {
        if (!ConfigHandler.snow.fillOnChunkLoad) return;
        if (world.isRemote) return;
        if (!isDimensionEnabled(world.provider.getDimension())) return;

        Chunk chunk = chunkProvider.provideChunk(chunkX, chunkZ);
        if (chunk == null || chunk.isEmpty()) return;

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
                int height = chunk.getHeightValue(dx, dz);
                if (height <= 0 || height > maxY) continue;

                int x = baseX + dx;
                int z = baseZ + dz;

                placeSnowInColumn(world, chunk, x, z, height, random, minLayers, maxLayers);
            }
        }
    }

    private void placeSnowInColumn(World world, Chunk chunk, int x, int z,
                                   int height, Random random, int minLayers, int maxLayers) {
        int minY = Math.max(0, height - MAX_SCAN_DEPTH);
        int caveDepth = Math.min(ConfigHandler.snow.fillCaveDepth, MAX_SCAN_DEPTH);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();

        // 1) найти реальную поверхность, пропуская воздух/листву/растения
        int surfaceY = height;
        for (int yy = height; yy >= minY; yy--) {
            p.setPos(x, yy, z);
            IBlockState st = chunk.getBlockState(p);
            if (st.getBlock() == Blocks.AIR) continue;
            if (isLeaves(st) || isReplaceablePlant(st)) continue;
            if (isSupport(st)) { surfaceY = yy; break; }
        }

        // 2) цикл установки снега
        BlockPos.MutableBlockPos cur = new BlockPos.MutableBlockPos();
        BlockPos.MutableBlockPos below = new BlockPos.MutableBlockPos();

        for (int y = height; y >= minY; y--) {
            if (surfaceY - y > caveDepth) continue;

            cur.setPos(x, y, z);
            IBlockState curState = chunk.getBlockState(cur);

            if (curState.getBlock() == Blocks.SNOW_LAYER) continue;
            if (!isReplaceable(curState)) continue;

            if (y - 1 < 0) break;
            below.setPos(x, y - 1, z);
            IBlockState belowState = chunk.getBlockState(below);
            if (!isSupport(belowState)) continue;

            BlockPos snowPos;
            if (curState.getBlock() == Blocks.LEAVES || curState.getBlock() == Blocks.LEAVES2) {
                if (y + 1 > 255) continue;
                snowPos = new BlockPos(x, y + 1, z);
                IBlockState aboveState = chunk.getBlockState(snowPos);
                if (aboveState.getBlock() == Blocks.SNOW_LAYER) continue;
                if (aboveState.getBlock() == Blocks.LEAVES || aboveState.getBlock() == Blocks.LEAVES2) continue;
                if (!isReplaceable(aboveState)) continue;
                if (aboveState.getBlock() == Blocks.DOUBLE_PLANT) {
                    clearDoublePlant(world, snowPos, aboveState);
                } else if (isReplaceablePlant(aboveState)) {
                    world.setBlockToAir(snowPos);
                }
            } else {
                snowPos = new BlockPos(x, y, z);
                if (curState.getBlock() == Blocks.DOUBLE_PLANT) {
                    clearDoublePlant(world, snowPos, curState);
                } else if (isReplaceablePlant(curState)) {
                    world.setBlockToAir(snowPos);
                }
            }

            int layers = minLayers + random.nextInt(maxLayers - minLayers + 1);
            world.setBlockState(snowPos, Blocks.SNOW_LAYER.getDefaultState()
                    .withProperty(BlockSnow.LAYERS, layers), 2 | 16);
        }
    }

    private boolean isLeaves(IBlockState st) {
        return st.getBlock() == Blocks.LEAVES || st.getBlock() == Blocks.LEAVES2;
    }

    /** Блок, который можно заменить снегом (воздух, растения, листва). */
    private boolean isReplaceable(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.AIR) return true;
        if (b == Blocks.LEAVES || b == Blocks.LEAVES2) return true;
        return isReplaceablePlant(state);
    }

    /** Только растительность: трава, цветы, грибы, саженцы, двойные растения, кусты, лианы. */
    private boolean isReplaceablePlant(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.TALLGRASS) return true;      // трава, папоротник, DEAD_BUSH
        if (b == Blocks.DEADBUSH) return true;
        if (b == Blocks.YELLOW_FLOWER || b == Blocks.RED_FLOWER) return true;
        if (b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM) return true;
        if (b == Blocks.SAPLING) return true;
        if (b == Blocks.DOUBLE_PLANT) return true;
        if (b == Blocks.VINE) return true;
        return false;
    }

    /** Блок, на который можно опереть снег. */
    private boolean isSupport(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SNOW_LAYER) return false;
        if (b == Blocks.ICE || b == Blocks.PACKED_ICE) return true;
        if (b == Blocks.LEAVES || b == Blocks.LEAVES2) return true;
        return state.isFullBlock() && state.isOpaqueCube();
    }

    /** Удаляет двойное растение целиком — и нижнюю, и верхнюю половины. */
    private void clearDoublePlant(World world, BlockPos pos, IBlockState state) {
        BlockDoublePlant.EnumBlockHalf half = state.getValue(BlockDoublePlant.HALF);
        BlockPos other = (half == BlockDoublePlant.EnumBlockHalf.LOWER) ? pos.up() : pos.down();
        world.setBlockToAir(pos);
        world.setBlockToAir(other);
    }
}