package com.blizzard.weather;

import com.blizzard.config.ConfigHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockDoublePlant;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraftforge.event.terraingen.PopulateChunkEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.util.Random;

/**
 * Снег и лёд через Chunk.setBlockState.
 *
 * Pre  — снег на землю (вода уже есть, деревьев ещё нет).
 * Post — заморозка поверхностной воды и снег на деревья
 *        (озёра из BiomeDecorator появляются только к этому моменту).
 *
 * Пишем напрямую в чанк (Chunk.setBlockState) без флагов обновления:
 * чанк только что сгенерирован, соседние апдейты не нужны — они бы только
 * вызвали каскад обновлений жидкостей и лаги.
 *
 * Замораживается не только текущий чанк, а площадь 31x31 начиная с его
 * угла: WorldGenLakes пишет воду в объём, который заезжает в соседний
 * чанк (+X/+Z) — см. LAKE_REACH.
 */
public class SnowIcePopulator {

    /**
     * Жёсткий предел глубины скана — совпадает с max = 64 у fillCaveDepth в
     * конфиге. Нужен только как страховка от руками отредактированного .cfg:
     * сама глубина берётся из fillCaveDepth.
     */
    private static final int MAX_SCAN_DEPTH = 64;
    /**
     * Насколько глубоко искать поверхность под кроной: листва поднимает
     * heightMap, поэтому под высоким деревом реальная земля может быть на
     * десятки блоков ниже. Страховка от бесконечного скана по битому чанку.
     */
    private static final int MAX_TREE_SCAN = 48;
    /** Верхняя граница мира: выше неё блоков нет. */
    private static final int WORLD_TOP_Y = 255;

    /**
     * След одного озера в мировых координатах.
     *
     * ChunkGeneratorOverworld.populate передаёт в WorldGenLakes точку
     * blockpos.add(rand(16) + 8, y, rand(16) + 8), где blockpos — угол чанка
     * (cx * 16, cz * 16). Внутри WorldGenLakes.generate к ней добавляется
     * (-8, 0, -8), т.е. смещение по X/Z становится 0..15, а сам объём озера
     * занимает ещё 0..15 блоков от этой точки. Итого в мировых координатах
     * вода может оказаться в [cx * 16 .. cx * 16 + 30] — то есть половина
     * озера попадает в соседний чанк (+X/+Z).
     */
    private static final int LAKE_REACH = 30;

    private static final boolean DEBUG = false;

    public SnowIcePopulator() {
        if (DEBUG) log("constructed");
    }

    // ------------------------------------------------------------------
    // PRE: снег на землю
    // ------------------------------------------------------------------

    @SubscribeEvent(receiveCanceled = true)
    public void onPopulatePre(PopulateChunkEvent.Pre event) {
        // if (DEBUG) — строка не собирается, когда логи выключены.
        if (DEBUG) log("Pre fired chunk=" + event.getChunkX() + "," + event.getChunkZ());

        World world = event.getWorld();
        if (world == null) { if (DEBUG) log("skip world=null"); return; }
        if (world.isRemote) { if (DEBUG) log("skip isRemote"); return; }
        if (!isDimensionEnabled(world.provider.getDimension())) {
            if (DEBUG) log("skip dim=" + world.provider.getDimension()); return;
        }

        Chunk chunk = world.getChunk(event.getChunkX(), event.getChunkZ());
        if (chunk == null || chunk.isEmpty()) {
            if (DEBUG) log("skip chunk null/empty"); return;
        }

        // Конфиг читаем один раз на чанк.
        ConfigHandler.Snow cfg = ConfigHandler.snow;
        int maxY = Math.min(cfg.fillMaxY, WORLD_TOP_Y);
        int caveDepth = Math.max(0, Math.min(cfg.fillCaveDepth, MAX_SCAN_DEPTH));
        int minLayers = cfg.fillMinLayers;
        int maxLayers = cfg.fillMaxLayers;
        boolean doSnow = cfg.fillOnChunkLoad;

        if (!doSnow) return;

        Random random = new Random(
                (long) event.getChunkX() * 341873128712L
                        + (long) event.getChunkZ() * 132897987541L);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int snowPlaced = 0;

        for (int dz = 0; dz < 16; dz++) {
            for (int dx = 0; dx < 16; dx++) {
                int x = event.getChunkX() * 16 + dx;
                int z = event.getChunkZ() * 16 + dz;
                snowPlaced += placeSnowInColumn(chunk, x, z, dx, dz,
                        random, maxY, caveDepth, minLayers, maxLayers, p, false);
            }
        }

        if (DEBUG) log("Pre done snow=" + snowPlaced);
    }

    // ------------------------------------------------------------------
    // POST: заморозка воды + снег на деревья
    // ------------------------------------------------------------------

    @SubscribeEvent(receiveCanceled = true)
    public void onPopulatePost(PopulateChunkEvent.Post event) {
        World world = event.getWorld();
        if (world == null || world.isRemote) return;
        if (!isDimensionEnabled(world.provider.getDimension())) return;

        // Конфиг читаем один раз на чанк (см. Pre).
        ConfigHandler.Snow cfg = ConfigHandler.snow;
        boolean doSnow = cfg.fillOnChunkLoad;
        boolean doFreeze = cfg.freezeWater && cfg.freezeWaterLayers > 0;
        if (!doSnow && !doFreeze) return;

        Chunk chunk = world.getChunk(event.getChunkX(), event.getChunkZ());
        if (chunk == null || chunk.isEmpty()) return;

        int maxY = Math.min(cfg.fillMaxY, WORLD_TOP_Y);
        int caveDepth = Math.max(0, Math.min(cfg.fillCaveDepth, MAX_SCAN_DEPTH));
        int minLayers = cfg.fillMinLayers;
        int maxLayers = cfg.fillMaxLayers;

        Random random = new Random(
                (long) event.getChunkX() * 341873128712L
                        + (long) event.getChunkZ() * 132897987541L + 1L);

        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int snowPlaced = 0;
        int icePlaced = 0;

        // 1) Заморозка воды. В Post — потому что озёра (WorldGenLakes)
        //    генерируются в BiomeDecorator уже ПОСЛЕ PopulateChunkEvent.Pre.
        if (doFreeze) {
            icePlaced += freezeLakeFootprint(world, event.getChunkX(), event.getChunkZ(),
                    chunk, new FreezeOpts(), random, p);
        }

        // 2) Снег на деревья
        if (doSnow) {
            for (int dz = 0; dz < 16; dz++) {
                for (int dx = 0; dx < 16; dx++) {
                    int x = event.getChunkX() * 16 + dx;
                    int z = event.getChunkZ() * 16 + dz;
                    snowPlaced += placeSnowInColumn(chunk, x, z, dx, dz,
                            random, maxY, caveDepth, minLayers, maxLayers, p, true);
                }
            }
        }

        if (DEBUG) log("Post done snow=" + snowPlaced + " ice=" + icePlaced);
    }

    // ------------------------------------------------------------------
    // ЛЁД
    // ------------------------------------------------------------------

    /**
     * Настройки заморозки, прочитанные из конфига один раз на проход.
     *
     * Футпринт озера — это ~961 колонка, и раньше ConfigHandler.snow.* читался
     * на каждой из них (плюс ещё раз на каждом блоке льда). Теперь конфиг
     * читается один раз, а freezeWaterInColumn получает готовые значения.
     */
    private static final class FreezeOpts {
        final int layers;
        final boolean onlySources;
        final boolean snowOnIce;
        final int snowMinLayers;
        final int snowMaxLayers;
        /** Глубина поиска воды от поверхности без листвы (fillCaveDepth). */
        final int caveDepth;

        FreezeOpts() {
            ConfigHandler.Snow cfg = ConfigHandler.snow;
            this.layers = cfg.freezeWaterLayers;
            this.onlySources = cfg.freezeOnlySourceBlocks;
            this.snowOnIce = cfg.snowOnIce;
            this.snowMinLayers = cfg.fillMinLayers;
            this.snowMaxLayers = cfg.fillMaxLayers;
            this.caveDepth = Math.max(0, Math.min(cfg.fillCaveDepth, MAX_SCAN_DEPTH));
        }
    }

    /**
     * Замораживает воду на площади, куда мог написать WorldGenLakes.
     *
     * Озеро «своего» чанка плюс его половина в соседнем (+X/+Z) — поэтому
     * обходим квадрат LAKE_REACH x LAKE_REACH от угла чанка. Сосед берётся
     * через getLoadedChunk: он уже загружен (иначе WorldGenLakes не смог бы
     * туда писать), но provideChunk/getChunk вызвали бы загрузку и генерацию
     * чанков — тот самый каскад, из-за которого раньше падало со StackOverflow.
     */
    private int freezeLakeFootprint(World world, int cx, int cz, Chunk centerChunk,
                                    FreezeOpts opts, Random random, BlockPos.MutableBlockPos p) {
        int placed = 0;
        int x0 = cx * 16;
        int z0 = cz * 16;

        Chunk chunk = null;
        int cachedCx = Integer.MIN_VALUE;
        int cachedCz = Integer.MIN_VALUE;

        // Обход в порядке x-снаружи / z-внутри — как в прежнем построчном скане,
        // поэтому последовательность вызовов random (snowOnIce) не меняется.
        for (int x = x0; x <= x0 + LAKE_REACH; x++) {
            for (int z = z0; z <= z0 + LAKE_REACH; z++) {
                int ccx = x >> 4;
                int ccz = z >> 4;

                if (ccx != cachedCx || ccz != cachedCz) {
                    Chunk c = (ccx == cx && ccz == cz)
                            ? centerChunk
                            : world.getChunkProvider().getLoadedChunk(ccx, ccz);
                    chunk = (c == null || c.isEmpty()) ? null : c;
                    cachedCx = ccx;
                    cachedCz = ccz;
                }

                if (chunk == null) continue;
                placed += freezeWaterInColumn(chunk, x, z, opts, random, p);
            }
        }

        return placed;
    }

    /**
     * Замораживает поверхность воды в одной колонке.
     *
     * Поверхность — первый сверху блок воды в пределах fillCaveDepth от
     * поверхностного блока (ground - 1): глубже вода не замерзает и не
     * читается. Лёд кладётся на freezeWaterLayers блоков вниз от этой
     * поверхности (сам блок + те, что под ним, пока там вода) — fillCaveDepth
     * толщину не ограничивает, иначе от трёх слоёв оставался бы один.
     *
     * Листва при поиске поверхности игнорируется (см. {@link #groundHeight}):
     * вода под кроной замерзает независимо от высоты дерева и толщины кроны.
     *
     * @return количество блоков, превращённых в лёд.
     */
    private int freezeWaterInColumn(Chunk chunk, int x, int z, FreezeOpts opts,
                                    Random random, BlockPos.MutableBlockPos p) {
        int layers = opts.layers;
        if (layers <= 0) return 0;

        int dx = x & 15;
        int dz = z & 15;

        // heightMap учитывает воду (lightOpacity воды = 3),
        // поэтому для океана getHeightValue() = Y верхнего блока воды + 1.
        int height = chunk.getHeightValue(dx, dz);
        if (height <= 0) return 0;

        // Листва — не поверхность: крона поднимает heightMap (lightOpacity
        // листвы = 1), и вода под высоким деревом уезжала ниже fillCaveDepth,
        // то есть не замерзала. Считаем от земли без листвы, а саму крону
        // вообще не читаем: воды над листвой не бывает.
        int ground = groundHeight(chunk, x, z, height, p);

        // Верхняя граница — потолок мира (fillMaxY относится к снегу, не ко льду)
        int topY = Math.min(ground + 1, WORLD_TOP_Y);
        // Глубина — как у снега, от поверхностного блока (ground - 1):
        // вода в пещерах замерзает в тех же пределах fillCaveDepth.
        int bottomY = Math.max(0, ground - 1 - opts.caveDepth);

        int surfaceY = -1;
        // Состояние найденной воды запоминаем: оно уже прочитано, второй раз
        // читать его для проверки «это источник» не нужно.
        IBlockState surfaceState = null;
        // Ищем ПОВЕРХНОСТЬ воды — первый сверху блок воды в пределах
        // fillCaveDepth. Твёрдые блоки пропускаем (сквозь землю и камень
        // смотрим дальше), поэтому замерзают и водоёмы в пещерах/гротах.
        // Лёд — граница: под ним уже не поверхность (иначе при перекрытии
        // футпринтов соседних чанков лёд намерзал бы снова, слой за слоем).
        for (int y = topY; y >= bottomY; y--) {
            p.setPos(x, y, z);
            IBlockState state = chunk.getBlockState(p);
            if (isWater(state)) {
                surfaceY = y;
                surfaceState = state;
                break;
            }
            if (isIce(state)) break;
        }
        if (surfaceY < 0) return 0;

        // Только поверхностная вода: над ней не должно быть воды.
        // Блок над найденной водой уже прочитан на предыдущем шаге скана, и вода
        // там быть не может (иначе скан остановился бы на нём), поэтому читаем
        // его только когда вода нашлась на самой верхней границе скана.
        if (surfaceY == topY) {
            p.setPos(x, surfaceY + 1, z);
            if (isWater(chunk.getBlockState(p))) return 0;
        }

        if (opts.onlySources && !isSourceWater(surfaceState)) return 0;

        int frozen = 0;
        for (int i = 0; i < layers; i++) {
            int y = surfaceY - i;
            if (y < 0) break;

            p.setPos(x, y, z);
            // i == 0 — это поверхностная вода, уже прочитанная сканом.
            if (i > 0 && !isWater(chunk.getBlockState(p))) break;

            // Chunk.setBlockState — без каскада обновлений жидкостей:
            // чанк только что сгенерирован, апдейты не нужны.
            chunk.setBlockState(p.toImmutable(), Blocks.ICE.getDefaultState());
            frozen++;
        }

        // Опционально присыпаем лёд снегом (по умолчанию лёд остаётся видимым)
        if (frozen > 0 && opts.snowOnIce) {
            int snowY = surfaceY + 1;
            if (snowY <= WORLD_TOP_Y) {
                p.setPos(x, snowY, z);
                if (chunk.getBlockState(p).getBlock() == Blocks.AIR) {
                    int snowLayers = opts.snowMinLayers + random.nextInt(
                            Math.max(1, opts.snowMaxLayers - opts.snowMinLayers + 1));
                    chunk.setBlockState(p.toImmutable(), Blocks.SNOW_LAYER.getDefaultState()
                            .withProperty(BlockSnow.LAYERS, snowLayers));
                }
            }
        }

        return frozen;
    }

    /** @return true, если блок — вода (источник или текущая). */
    private boolean isWater(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }

    /** @return true, если блок — лёд (природный или уже поставленный нами). */
    private boolean isIce(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE;
    }

    /** @return true, если блок — листва (дуб/акация или хвоя). */
    private boolean isLeaf(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.LEAVES || b == Blocks.LEAVES2;
    }

    /** @return true, если блок воды — источник (LEVEL == 0). */
    private boolean isSourceWater(IBlockState state) {
        // Быстрый путь: уровень 0 у воды — это её базовое состояние, а состояния
        // в чанке канонические (сравнение ссылками, как в Chunk.setBlockState).
        // Для FLOWING_WATER так нельзя: у BlockDynamicLiquid есть ещё FALLING.
        if (state == Blocks.WATER.getDefaultState()) return true;
        if (!isWater(state)) return false;
        return state.getValue(BlockLiquid.LEVEL).intValue() == 0;
    }

    // ------------------------------------------------------------------
    // СНЕГ
    // ------------------------------------------------------------------

    private int placeSnowInColumn(Chunk chunk, int x, int z,
                                  int dx, int dz, Random random, int maxY,
                                  int caveDepth, int minLayers, int maxLayers,
                                  BlockPos.MutableBlockPos p, boolean treesOnly) {
        int height = chunk.getHeightValue(dx, dz);
        if (height <= 0) return 0;

        // Верх окна — над кроной: в Post именно там ставится снег на листву.
        int scanMaxY = Math.min(height + 1, maxY);
        // Листва — не поверхность: иначе высота дерева и толщина кроны
        // выталкивали землю под ними из окна fillCaveDepth, и снега под
        // деревом не было. Глубину считаем от земли без листвы.
        int ground = groundHeight(chunk, x, z, height, p);
        // caveDepth блоков вниз от поверхностного блока (ground - 1):
        // 0 — только сама поверхность, без пещер; 8 — пещеры у поверхности.
        int scanMinY = Math.max(0, ground - 1 - caveDepth);

        int placed = 0;

        for (int y = scanMaxY; y >= scanMinY; y--) {
            p.setPos(x, y, z);
            IBlockState curState = chunk.getBlockState(p);

            if (curState.getBlock() == Blocks.SNOW_LAYER) continue;
            if (!isReplaceable(curState)) continue;

            // Фильтр по листве — до чтения блока ниже и isSupport: в Post
            // (treesOnly) так отсекается почти всё, и лишних чтений нет.
            boolean leaf = isLeaf(curState);

            // Pre — только не-листва; Post — только листва
            if (treesOnly != leaf) continue;

            if (y - 1 < 0) break;

            p.setPos(x, y - 1, z);
            IBlockState belowState = chunk.getBlockState(p);
            if (!isSupport(belowState)) continue;

            BlockPos snowPos;
            if (leaf) {
                if (y + 1 > 255) continue;
                p.setPos(x, y + 1, z);
                snowPos = p.toImmutable();
                IBlockState aboveState = chunk.getBlockState(snowPos);
                if (aboveState.getBlock() == Blocks.SNOW_LAYER) continue;
                if (isLeaf(aboveState)) continue;
                if (!isReplaceable(aboveState)) continue;

                if (aboveState.getBlock() == Blocks.DOUBLE_PLANT) {
                    clearDoublePlant(chunk, snowPos, aboveState);
                } else if (isReplaceablePlant(aboveState)) {
                    chunk.setBlockState(snowPos, Blocks.AIR.getDefaultState());
                }
            } else {
                p.setPos(x, y, z);
                snowPos = p.toImmutable();
                if (curState.getBlock() == Blocks.DOUBLE_PLANT) {
                    clearDoublePlant(chunk, snowPos, curState);
                } else if (isReplaceablePlant(curState)) {
                    chunk.setBlockState(snowPos, Blocks.AIR.getDefaultState());
                }
            }

            int layers = minLayers + random.nextInt(Math.max(1, maxLayers - minLayers + 1));
            chunk.setBlockState(snowPos, Blocks.SNOW_LAYER.getDefaultState()
                    .withProperty(BlockSnow.LAYERS, layers));
            placed++;
        }
        return placed;
    }

    // ------------------------------------------------------------------
    // Проверки
    // ------------------------------------------------------------------

    private boolean isReplaceable(IBlockState state) {
        if (state.isOpaqueCube()) return false;
        Block b = state.getBlock();
        if (b == Blocks.AIR) return true;
        if (isLeaf(state)) return true;
        return isReplaceablePlant(state);
    }

    private boolean isReplaceablePlant(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.TALLGRASS) return true;
        if (b == Blocks.DEADBUSH) return true;
        if (b == Blocks.YELLOW_FLOWER || b == Blocks.RED_FLOWER) return true;
        if (b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM) return true;
        if (b == Blocks.SAPLING) return true;
        if (b == Blocks.DOUBLE_PLANT) return true;
        if (b == Blocks.VINE) return true;
        return false;
    }

    /**
     * heightMap, но без листвы.
     *
     * lightOpacity листвы = 1, поэтому крона попадает в getHeightValue():
     * под высоким деревом height указывает на верхушку, а реальная земля или
     * вода — на десятки блоков ниже, то есть за пределами fillCaveDepth.
     * Из-за этого под кроной не ставились ни снег, ни лёд. Здесь поверхность
     * ищется заново: листва, ствол и просвет под кроной пропускаются.
     *
     * @return Y первого свободного блока над поверхностью — то же соглашение,
     *         что у heightMap (у воды — Y верхнего блока воды + 1);
     *         для колонки без кроны возвращается height без лишних чтений.
     */
    private int groundHeight(Chunk chunk, int x, int z, int height,
                             BlockPos.MutableBlockPos p) {
        if (height <= 0) return height;
        p.setPos(x, height - 1, z);
        // Быстрая проверка: верхний блок колонки — не листва, кроны нет.
        if (!isLeaf(chunk.getBlockState(p))) return height;

        int limit = Math.max(0, height - MAX_TREE_SCAN);
        for (int y = height - 1; y >= limit; y--) {
            p.setPos(x, y, z);
            IBlockState state = chunk.getBlockState(p);
            Block b = state.getBlock();
            // Крона, ствол, воздух под кроной, трава и лежащий на земле
            // снежный слой — не поверхность, смотрим ниже. Всё остальное
            // (земля, камень, вода, лёд) — поверхность.
            if (b == Blocks.AIR || isLeaf(state) || b == Blocks.LOG || b == Blocks.LOG2
                    || b == Blocks.SNOW_LAYER || isReplaceablePlant(state)) {
                continue;
            }
            return y + 1;
        }
        // Поверхности не нашли (битая колонка) — ведём себя как раньше.
        return height;
    }

    private boolean isSupport(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SNOW_LAYER) return false;
        // Лёд: снег кладём только если это включено в конфиге (snowOnIce),
        // иначе замороженная вода остаётся видимым льдом.
        if (isIce(state)) return ConfigHandler.snow.snowOnIce;
        if (isLeaf(state)) return true;
        return state.isFullBlock() && state.isOpaqueCube();
    }

    private void clearDoublePlant(Chunk chunk, BlockPos pos, IBlockState state) {
        BlockDoublePlant.EnumBlockHalf half = state.getValue(BlockDoublePlant.HALF);
        BlockPos other = (half == BlockDoublePlant.EnumBlockHalf.LOWER) ? pos.up() : pos.down();
        chunk.setBlockState(pos, Blocks.AIR.getDefaultState());
        chunk.setBlockState(other, Blocks.AIR.getDefaultState());
    }

    private boolean isDimensionEnabled(int dim) {
        int[] dims = ConfigHandler.snow.fillDimensions;
        if (dims == null || dims.length == 0) return false;
        for (int id : dims) if (id == dim) return true;
        return false;
    }

    private static void log(String msg) {
        if (DEBUG) System.out.println("[Blizzard][GEN] " + msg);
    }
}