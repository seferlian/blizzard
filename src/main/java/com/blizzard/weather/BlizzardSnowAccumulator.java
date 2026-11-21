package com.blizzard.weather;

import com.blizzard.config.ConfigHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.BlockSnow;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

import java.util.Random;

/**
 * Накопление снега и замерзание воды ВО ВРЕМЯ метели.
 *
 * СНЕГ ТОЛЬКО НАКАПЛИВАЕТСЯ — аккумулятор НИКОГДА не удаляет и не срезает снег.
 * Для каждой колонки есть целевая высота t(x,z): НЕСКОЛЬКО синусоид друг на
 * друга (freq, 2*freq, 4*freq — крупные холмы, средние и мелкие гряды) со
 * сдвинутой шумом фазой, плюс многооктавный шум (рваные заносы), зажатая
 * в диапазон [minBlocksPerColumn*8 .. maxBlocksPerColumn*8].
 * При дефолтах (base 32, амплитуда 22, шум 12 слоёв, мин 1 / макс 7 блоков)
 * рельеф занимает всю разрешённую высоту: гребни ~7 блоков, впадины ~1 блок,
 * голая земля не остаётся, а кламп почти не срабатывает — нет плоских «полок».
 * Прирост ограничивается d-липшицевой минорантой t:
 *
 *     cap   = min( t, min_соседей(cur) + d )
 *     cur'  = cap <= cur ? cur                       (цель достигнута / соседи ниже)
 *                        : (шанс роста) cur + 1 слой  (ровно 1/8 блока)
 *
 * За одно успешное касание колонка получает РОВНО ОДИН слой, а сами колонки
 * выбираются псевдослучайно по всей площади радиуса. Поэтому накопление
 * выглядит как настоящий снегопад: снежинка за снежинкой то тут, то там,
 * и высота колонок постепенно сходится к целевой функции t(x,z).
 *
 * Темп засыпки задаётся в реальном времени: касания идут не каждый тик, а
 * каждый touchIntervalTicks-й (по умолчанию 20 — раз в секунду), за касание
 * делается attemptsPerTick попыток. При дефолтах (радиус 128, 1024 попытки,
 * N=20) зона заполняется до целевой функции примерно за 1 реальный час
 * непрерывной метели. Заморозка воды от этого параметра не зависит.
 *
 * НАД СНЕГОМ — ТОЛЬКО ОДИН СЛОЙ ЗА КАСАНИЕ. Над «коронкой» колонки (её верхним
 * слоем снега) ничего не заносится и ничто не прыгает: колонка получает ровно
 * один слой (1/8 блока) за успешное касание и только если это позволяют цель и
 * соседи. Целые блоки {@code minecraft:snow} над слоями снега не ставятся.
 *
 * ЕДИНСТВЕННОЕ ИСКЛЮЧЕНИЕ — НЕПОЛНЫЕ БЛОКИ (факел, плита, забор, лестница,
 * тропинка {@code grass_path}, стена, грядка, цветок): ванильный BlockSnow на них
 * никогда не ляжет, поэтому такие клетки мод заносит сам — ставит ЦЕЛЫЙ блок
 * {@code minecraft:snow} (не слой!) прямо НАД препятствием. Блок ставится только
 * тогда, когда СБОКУ на этом же уровне уже лежит полный 8-слойный снег (клетка
 * соседа забита снегом целиком), а клетка под блоком — само препятствие (в пустые
 * клетки над воздухом/водой снег не ставится никогда).
 *
 * Колонка, вставшая снегом на препятствие, дальше растёт обычной слоистой
 * релаксацией: целый блок здесь — только «первый», он заменяет опору, которую
 * ванильный BlockSnow не признаёт, а выше накапливаются обычные слои
 * ({@code snow_layer}). Поэтому над тропинкой, забором или лестницей получается
 * ровный сугроб вровень с соседями, а не столб. Целые блоки поверх первого
 * (стопкой) НЕ ставятся.
 *
 * Касания распределены НЕ равномерно: вероятность касания колонки растёт
 * с её целевой высотой (вес = (1-ridgeBias) + ridgeBias*(t-min)/(max-min)).
 * На гребнях снежинки «падают» чаще, во впадинах — реже, поэтому холмы
 * вырастают раньше, конечная функция рельефа проступает заранее, а впадины
 * заполняются последними.
 *
 * Радиус по умолчанию 128 блоков — как у ванильного накопления снега
 * (8 чанков вокруг игрока). Игроки, стоящие рядом, схлопываются в одну
 * «зону», поэтому второй и последующие игроки НЕ увеличивают силу снегопада:
 * на зону за тик приходится фиксированное число попыток.
 *
 * Свойства:
 *  - снег только добавляется: существующий (в т.ч. поставленный игроком или
 *    другой системой) снег не срезается, не тает и не чистится;
 *  - мод не добавляет снег выше maxBlocksPerColumn и стремится довести
 *    колонку минимум до minBlocksPerColumn, кроме кромки воды, где перепад
 *    ограничен maxNeighborDiff;
 *  - перепад между соседями при росте ≤ maxNeighborDiff, поэтому сугробы
 *    получаются с плавными склонами, а не «столбами»;
 *  - границы (вода/воздух) имеют значение 0 и плавно гасят рост к берегу,
 *    но снег над водой/воздухом не трогается вообще;
 *  - стволы деревьев «прозрачны» и не вырезают траншеи;
 *  - записываются только реально изменившиеся блоки;
 *  - состояние живёт в самих блоках мира, поэтому перезапуск безопасен
 *    (NBT не нужен).
 *
 * Стоимость тика не зависит ни от радиуса, ни от числа игроков: попыток
 * фиксированное число, все чтения идут через кэш последнего чанка, синусы
 * берутся из таблицы MathHelper, а целевая высота считается один раз на
 * попытку.
 */
public final class BlizzardSnowAccumulator {

    private static final int WORLD_TOP_Y = 255;
    private static final int MAX_LAYERS_PER_BLOCK = 8;
    /** Страховка от бесконечного скана по битой колонке (дерево + сугроб). */
    private static final int MAX_COLUMN_SCAN = 96;

    /** Сколько слоёв прибавляется за одно успешное касание колонки: один (1/8 блока). */
    private static final int LAYERS_PER_TOUCH = 1;
    /**
     * Флаг записи блоков накопления: 2 — без обновления соседей. Занос ставит
     * целые блоки снега на препятствия (факел, плита, забор, лестница, тропинка),
     * которые ванильные {@code canPlaceBlockAt}/{@code canBlockStay} опорой не
     * считают, поэтому обновления соседей не рассылаются и блок не осыпается.
     */
    private static final int SET_FLAG_NO_UPDATE = 2;
    /** Сколько октав плавного шума складывается в рельеф сугробов. */
    private static final int NOISE_OCTAVES = 3;
    /** Сколько синусоидальных октав накладывается друг на друга (freq, 2*freq, 4*freq). */
    private static final int WAVE_OCTAVES = 3;
    /** Фазовый сдвиг октав по X (радианы): гребни октав не совпадают в одной точке. */
    private static final double WAVE_PHASE_X_STEP = 1.7;
    /** Фазовый сдвиг октав по Z (радианы). */
    private static final double WAVE_PHASE_Z_STEP = 2.3;
    /**
     * Доля ячейки {@code noiseScale}, на которую шум сдвигает фазу синусоиды.
     * 0.2 — гряды перестают стоять правильной решёткой (холмы разной ширины),
     * но прирост крутизны небольшой, поэтому {@code maxNeighborDiff} хватает.
     */
    private static final double WARP_CELL_FRACTION = 0.2;
    /**
     * Ожидаемый период покрытия: за сколько тиков каждая колонка в среднем
     * должна получать хотя бы одно касание. Служит нижней границей для числа
     * попыток за тик, чтобы на большом радиусе рост не «замирал».
     */
    private static final int MIN_SWEEP_TICKS = 256;

    /** Тип колонки по результату columnBase(). */
    private static final int COL_BLOCKED  = -1;                // ствол/твёрдый блок — снега нет, соседей не связывает
    private static final int COL_BOUNDARY = 0;                 // вода/воздух — фиксированный 0
    private static final int COL_DRIFT    = -2;                // над неполной опорой пусто — только занос

    private static final int NO_VALUE = Integer.MIN_VALUE;

    /**
     * Насколько глубоко под клеткой-мостом ищем твёрдую опору, если прямо под
     * ней воздух. 3 блока — компромисс: закрывает нависающие края сугробов над
     * небольшими выступами, но не строит «парящих» мостов над пропастями.
     */
    private static final int BRIDGE_SUPPORT_DEPTH = 3;

    /**
     * Максимум «зон» за тик (зона — один или несколько стоящих рядом игроков).
     * Нужен только для статических массивов ниже, поэтому с запасом.
     */
    private static final int MAX_ZONES = 64;
    private static final int[] ZONE_MIN_X = new int[MAX_ZONES];
    private static final int[] ZONE_MAX_X = new int[MAX_ZONES];
    private static final int[] ZONE_MIN_Z = new int[MAX_ZONES];
    private static final int[] ZONE_MAX_Z = new int[MAX_ZONES];
    private static int zoneCount;

    /**
     * Минимальный вес касания для самой низкой колонки: гарантирует, что при
     * любом «перекосе к гребням» впадины всё равно получат снег, просто реже.
     */
    private static final double MIN_TOUCH_WEIGHT = 0.02;

    /** Полный оборот фазы — для приведения аргументов синуса к [0, 2π). */
    private static final double TWO_PI = Math.PI * 2.0;

    /** Фазовые базы октав в «якоре» зоны: [x0, z0, x1, z1, x2, z2] (радианы). */
    private static final double[] PHASE = new double[WAVE_OCTAVES * 2];

    private static final Random RANDOM = new Random();
    private static final BlockPos.MutableBlockPos MUT = new BlockPos.MutableBlockPos();

    /**
     * Счётчик тиков между «касаниями»: снег накапливается не каждый тик, а каждый
     * {@code touchIntervalTicks}-й. Счётчик общий на все измерения (разница фаз
     * между ними не важна), сбрасывается на каждом касании.
     */
    private static int touchTickCounter;

    /**
     * Кэш последнего чанка на тик. Колонка и её 4 соседа почти всегда лежат
     * в одном чанке, поэтому кэш снимает ~4 из 5 обращений к ChunkProvider.
     * Сбрасывается в начале каждого тика, чтобы не держать выгруженный чанк.
     */
    private static World cachedWorld;
    private static int cachedChunkX = Integer.MIN_VALUE;
    private static int cachedChunkZ = Integer.MIN_VALUE;
    private static Chunk cachedChunk;

    private BlizzardSnowAccumulator() {}

    // ====================================================================
    // Тик
    // ====================================================================

    public static void tick(WorldServer world, float strength) {
        ConfigHandler.Accumulation cfg = ConfigHandler.accumulation;
        if (!cfg.enabled) return;
        if (!isDimensionEnabled(world.provider.getDimension())) return;
        if (strength < cfg.minStrength) return;

        int r = cfg.radius;
        int fr = cfg.freezeRadius;
        boolean doSnow = r > 0 && cfg.attemptsPerTick > 0 && cfg.growChance > 0.0;
        boolean doFreeze = cfg.freezeWater
                && fr > 0
                && cfg.freezeAttemptsPerTick > 0
                && cfg.freezeChance > 0.0;
        if (!doSnow && !doFreeze) return;

        // Касания (снег) идут не каждый тик, а каждый touchIntervalTicks-й: темп
        // засыпки задаётся в реальном времени (при 1024 попытках и N=20 полная
        // засыпка зоны радиусом 128 занимает ~1 час метели), а не подбором
        // attemptsPerTick. Заморозка воды от этого не зависит и, как раньше,
        // работает каждый тик.
        boolean snowTick = false;
        if (doSnow) {
            if (++touchTickCounter >= Math.max(1, cfg.touchIntervalTicks)) {
                touchTickCounter = 0;
                snowTick = true;
            }
        }

        resetChunkCache();

        int maxY = Math.min(ConfigHandler.snow.fillMaxY, WORLD_TOP_Y);

        // Пределы целевой высоты (в слоях) — нужны для перекоса касаний к гребням.
        int minLayers = Math.max(0, cfg.minBlocksPerColumn) * MAX_LAYERS_PER_BLOCK;
        int maxLayers = Math.max(0, cfg.maxBlocksPerColumn) * MAX_LAYERS_PER_BLOCK;
        if (minLayers > maxLayers) minLayers = maxLayers;

        // Попыток за ОДНО касание на ЗОНУ (игроки рядом — одна зона), но не меньше,
        // чем нужно, чтобы каждая колонка в среднем получала касание не реже
        // раза в MIN_SWEEP_TICKS касаний (иначе на большом радиусе рост замирает).
        int attempts = cfg.attemptsPerTick;
        if (snowTick) {
            long side = 2L * r + 1L;
            long need = (side * side + MIN_SWEEP_TICKS - 1) / MIN_SWEEP_TICKS;
            if (need > attempts) attempts = (int) Math.min(Integer.MAX_VALUE, need);
        }

        int zones = collectZones(world, Math.max(doSnow ? r : 0, doFreeze ? fr : 0));

        for (int zi = 0; zi < zones; zi++) {
            int minX = ZONE_MIN_X[zi];
            int maxX = ZONE_MAX_X[zi];
            int minZ = ZONE_MIN_Z[zi];
            int maxZ = ZONE_MAX_Z[zi];

            // ---- Снег: псевдослучайные колонки зоны, гребни присыпаются чаще ----
            if (snowTick) {
                int x0 = minX - r;
                int z0 = minZ - r;
                int w = maxX - minX + r * 2 + 1;
                int h = maxZ - minZ + r * 2 + 1;

                // Фазы октав считаем от якоря зоны: аргументы синусов остаются
                // маленькими, поэтому точность float-таблицы MathHelper полная.
                wavePhaseBase(x0, z0, cfg);

                int accepted = 0;
                int tries = attempts * 3;      // страховка от долгого отсева
                for (int i = 0; i < tries && accepted < attempts; i++) {
                    int x = x0 + RANDOM.nextInt(w);
                    int z = z0 + RANDOM.nextInt(h);

                    int t = targetLayers(x, z, x0, z0, cfg);
                    if (RANDOM.nextDouble() >= touchWeight(t, minLayers, maxLayers, cfg)) continue;

                    accepted++;
                    processColumn(world, x, z, t, maxY, cfg);
                }
            }

            // ---- Заморозка воды: тоже по зонам (иначе шанс умножался бы на игроков) ----
            if (doFreeze) {
                int x0 = minX - fr;
                int z0 = minZ - fr;
                int w = maxX - minX + fr * 2 + 1;
                int h = maxZ - minZ + fr * 2 + 1;
                for (int i = 0; i < cfg.freezeAttemptsPerTick; i++) {
                    if (RANDOM.nextDouble() >= cfg.freezeChance) continue;
                    tryFreeze(world, x0 + RANDOM.nextInt(w), z0 + RANDOM.nextInt(h), cfg);
                }
            }
        }
    }

    /**
     * Собирает «зоны» из игроков мира.
     *
     * Игрок, который стоит в пределах mergeRadius (по Чебышёву) от уже
     * известной зоны, расширяет её, а не создаёт новую. Поэтому 2-3 игрока
     * рядом дают ОДНУ зону и не множат силу снегопада: за тик на зону
     * приходится фиксированное число попыток.
     *
     * Зона не даёт растянуть себя шире площади одного игрока (spanLimit):
     * иначе цепочка игроков слилась бы в один огромный квадрат, и снег
     * размазался бы по пустой земле между ними.
     *
     * @return количество зон (0, если игроков нет)
     */
    private static int collectZones(WorldServer world, int mergeRadius) {
        zoneCount = 0;
        int spanLimit = mergeRadius * 2 + 1;

        for (EntityPlayer p : world.playerEntities) {
            if (!(p instanceof EntityPlayerMP)) continue;
            EntityPlayerMP mp = (EntityPlayerMP) p;
            if (mp.isSpectator() || mp.isDead) continue;

            int px = (int) Math.floor(mp.posX);
            int pz = (int) Math.floor(mp.posZ);

            int zi = -1;
            for (int i = 0; i < zoneCount; i++) {
                int nx0 = Math.min(px, ZONE_MIN_X[i]);
                int nx1 = Math.max(px, ZONE_MAX_X[i]);
                int nz0 = Math.min(pz, ZONE_MIN_Z[i]);
                int nz1 = Math.max(pz, ZONE_MAX_Z[i]);

                // Зона уже растянута на несколько отдельных игроков — не в неё.
                if (nx1 - nx0 > spanLimit || nz1 - nz0 > spanLimit) continue;

                // Игрок далеко от зоны — тоже не в неё.
                if (px < ZONE_MIN_X[i] - mergeRadius || px > ZONE_MAX_X[i] + mergeRadius) continue;
                if (pz < ZONE_MIN_Z[i] - mergeRadius || pz > ZONE_MAX_Z[i] + mergeRadius) continue;

                zi = i;
                ZONE_MIN_X[i] = nx0;
                ZONE_MAX_X[i] = nx1;
                ZONE_MIN_Z[i] = nz0;
                ZONE_MAX_Z[i] = nz1;
                break;
            }

            if (zi >= 0) continue;

            if (zoneCount >= MAX_ZONES) continue;   // страховка: игроков больше, чем зон
            zi = zoneCount++;
            ZONE_MIN_X[zi] = ZONE_MAX_X[zi] = px;
            ZONE_MIN_Z[zi] = ZONE_MAX_Z[zi] = pz;
        }
        return zoneCount;
    }

    // ====================================================================
    // Снег: одна итерация релаксации
    // ====================================================================

    /**
     * Одна итерация релаксации колонки: обычный рост к целевой высоте.
     *
     * Над снегом (над «коронкой» колонки) накапливается РОВНО ОДИН слой за
     * касание — никаких заносов, целых блоков и прыжков выше. Занос существует
     * только на неполных блоках (см. {@link #tryDriftOverObstacle}).
     *
     * @param t целевая высота, уже посчитанная вызывающим (не пересчитываем)
     */
    private static void processColumn(World world, int x, int z, int t, int maxY,
                                      ConfigHandler.Accumulation cfg) {
        Chunk chunk = getLoadedChunk(world, x, z);
        if (chunk == null) return;

        int baseY = columnBase(chunk, x, z);
        if (baseY == COL_DRIFT) {
            tryDriftOverObstacle(world, chunk, x, z, t, maxY, cfg);
            return;
        }
        if (baseY == COL_BLOCKED) {
            tryDriftOverObstacle(world, chunk, x, z, t, maxY, cfg);
            return;
        }
        if (baseY == COL_BOUNDARY) {
            // Пункт 3: мост над льдом/водой, если с 3+ сторон уже лежит полный снег.
            tryBridge(world, chunk, x, z, maxY, cfg);
            return;
        }

        int cur = countLayers(chunk, x, z, baseY, maxY);
        growColumn(world, chunk, x, z, baseY, cur, t, maxY, cfg);
    }


    /**
     * Если наша клетка — воздух, а с 3+ боковых сторон уже лежит полный
     * 8-слойный снег, ставим целый блок minecraft:snow в эту клетку.
     *
     * Поддерживаемые основания:
     *  - лёд (замёрзшая вода) — обычный случай;
     *  - твёрдый полный блок, если он есть прямо под клеткой (нависающий край
     *    сугроба над выступом скалы/земли);
     *  - воздух — ТОЛЬКО если в пределах BRIDGE_SUPPORT_DEPTH под клеткой есть
     *    твёрдая опора (полный непрозрачный блок). Иначе блок снега повис бы
     *    в воздухе, а это некрасиво и потенциально опасно при внешних
     *    обновлениях блоков.
     *
     * Над открытой водой (не льдом) мост НЕ ставим: ванильный BlockSnow на воде
     * не держится, а лёд под ним ещё не образовался — блок повис бы.
     *
     * Блок не поднимает общую высоту: columnBase() в дальнейшем вернёт эту
     * клетку как baseY, и growColumn() дорастит колонку до той же целевой t,
     * что и соседи (мост съедает ровно один блок из целевой высоты).
     */
    private static void tryBridge(World world, Chunk chunk, int x, int z, int maxY,
                                  ConfigHandler.Accumulation cfg) {
        int height = chunk.getHeightValue(x & 15, z & 15);
        if (height <= 0) return;

        int baseY = snowBaseY(chunk, x, z, height);
        if (baseY <= 0) return;

        // Клетка, куда ставим мост, должна быть пустой.
        MUT.setPos(x, baseY, z);
        IBlockState at = chunk.getBlockState(MUT);
        if (at.getBlock() != Blocks.AIR && !isReplaceablePlant(at)) return;

        MUT.setPos(x, baseY - 1, z);
        IBlockState below = chunk.getBlockState(MUT);

        boolean okBase;
        if (isIce(below)) {
            // Лёд — идеальная опора: блок снега ляжет и не осыпется.
            okBase = true;
        } else if (isSnowSupport(below)) {
            // Полный непрозрачный блок прямо под клеткой (выступ скалы/земли).
            okBase = true;
        } else if (below.getBlock() == Blocks.AIR) {
            // Воздух под клеткой: разрешаем, только если в пределах
            // BRIDGE_SUPPORT_DEPTH есть твёрдая опора — иначе блок повиснет.
            okBase = hasSolidWithin(chunk, x, baseY - 1, z, BRIDGE_SUPPORT_DEPTH);
        } else {
            // Открытая вода, листва, стволы и прочее — не наш случай.
            okBase = false;
        }
        if (!okBase) return;

        int snowSides = 0;
        if (hasFullSnowAt(world, x - 1, z, baseY, maxY)) snowSides++;
        if (hasFullSnowAt(world, x + 1, z, baseY, maxY)) snowSides++;
        if (hasFullSnowAt(world, x, z - 1, baseY, maxY)) snowSides++;
        if (hasFullSnowAt(world, x, z + 1, baseY, maxY)) snowSides++;
        if (snowSides < 3) return;

        if (cfg.growChance < 1.0 && RANDOM.nextDouble() >= cfg.growChance) return;

        setSnowBlock(world, x, baseY, z);
    }

    private static boolean hasFullSnowAt(World world, int x, int z, int y, int maxY) {
        Chunk chunk = getLoadedChunk(world, x, z);
        if (chunk == null) return false;

        MUT.setPos(x, y, z);
        IBlockState state = chunk.getBlockState(MUT);
        if (state.getBlock() == Blocks.SNOW) return true;
        if (state.getBlock() == Blocks.SNOW_LAYER) {
            return state.getValue(BlockSnow.LAYERS) >= MAX_LAYERS_PER_BLOCK;
        }
        return false;
    }

    /**
     * Есть ли в колонке (x, z) начиная с yFrom вниз в пределах depth твёрдая
     * опора — полный непрозрачный блок (isSnowSupport). Используется только
     * tryBridge, чтобы не ставить парящий снег над пустотой.
     */
    private static boolean hasSolidWithin(Chunk chunk, int x, int yFrom, int z, int depth) {
        int yTo = Math.max(0, yFrom - depth + 1);
        for (int y = yFrom; y >= yTo; y--) {
            MUT.setPos(x, y, z);
            IBlockState s = chunk.getBlockState(MUT);
            if (isSnowSupport(s)) return true;
            // Встретили воду/листву/ствол — это не «твёрдая опора», прекращаем.
            Block b = s.getBlock();
            if (b == Blocks.AIR) continue;
            if (isLeaf(s) || b == Blocks.LOG || b == Blocks.LOG2) return false;
            if (isWater(s)) return false;
        }
        return false;
    }

    /**
     * Одна итерация релаксации колонки по правилу {@code cap = min(t, min_соседей + d)}.
     * Добавляет не больше одного слоя, ничего не срезая.
     */
    private static void growColumn(World world, Chunk chunk, int x, int z, int baseY, int cur,
                                   int t, int maxY, ConfigHandler.Accumulation cfg) {
        // Пункт 2: занос на препятствие (забор, плита, факел, тропинка) поднимает
        // основание колонки на 1 блок выше, чем у обычной земли. Чтобы итоговая
        // ВЕРХНЯЯ КРОМКА сугроба совпадала с соседями, уменьшаем цель на 8 слоёв.
        // Для мостов над льдом/водой этого не делаем — там основание не смещено.
        MUT.setPos(x, baseY - 1, z);
        IBlockState below = chunk.getBlockState(MUT);
        if (isDriftTarget(below) && !isSnowSupport(below)) {
            t -= MAX_LAYERS_PER_BLOCK;
        }

        int reachable = (maxY - baseY + 1) * MAX_LAYERS_PER_BLOCK;
        if (reachable < 0) reachable = 0;
        if (t > reachable) t = reachable;
        if (t < 0) t = 0;

        if (cur >= t) return;

        int d = Math.max(1, cfg.maxNeighborDiff);
        int minNb = Integer.MAX_VALUE;

        minNb = lower(minNb, neighborValue(world, x - 1, z, maxY));
        if (minNb <= cur - d) return;
        minNb = lower(minNb, neighborValue(world, x + 1, z, maxY));
        if (minNb <= cur - d) return;
        minNb = lower(minNb, neighborValue(world, x, z - 1, maxY));
        if (minNb <= cur - d) return;
        minNb = lower(minNb, neighborValue(world, x, z + 1, maxY));

        int cap = (minNb == Integer.MAX_VALUE) ? t : Math.min(t, minNb + d);
        if (cap < 0) cap = 0;

        if (cap <= cur) return;
        if (cfg.growChance < 1.0 && RANDOM.nextDouble() >= cfg.growChance) return;

        int next = Math.min(cap, cur + LAYERS_PER_TOUCH);
        applyStack(world, chunk, x, z, baseY, next, maxY);
    }

    // ====================================================================
    // Занос на неполные блоки (тропинка, плита, забор, лестница, стена, факел)
    // ====================================================================

    /**
     * ЗАНОС НА ПРЕПЯТСТВИЕ.
     *
     * Ванильный {@code BlockSnow.canPlaceBlockAt} не считает опорой ни факелы,
     * ни плиты, ни заборы, ни лестницы, ни тропинки ({@code grass_path}), ни
     * стены, ни грядки — всё, что не является полным непрозрачным кубом. Сам он
     * туда никогда не ляжет, поэтому такие клетки оставались бы голыми
     * «проплешинами» посреди сугроба.
     *
     * Метель заносит их ЦЕЛЫМ блоком {@code minecraft:snow} (не слоем!): если
     * СБОКУ на уровне нового блока уже лежит полный 8-слойный снег (клетка соседа
     * забита снегом целиком), то в клетку НАД препятствием ставится блок
     * {@code minecraft:snow}. Блок пишется с флагом 2 (без обновления соседей),
     * поэтому ванильный {@code checkAndDropBlock} его сразу не сносит.
     *
     * Дальше колонка живёт как обычная: {@link #columnBase} возвращает этот блок
     * как основание, и выше него снег набирается ОБЫЧНЫМИ СЛОЯМИ (snow_layer),
     * как у всех соседей. Целый блок — только первый, он заменяет опору, которую
     * ванильный BlockSnow не признаёт. Второй и последующие блоки сверху не
     * ставятся — иначе над каждой тропинкой и забором вырос бы столб.
     *
     * @param t целевая высота колонки в слоях (занос не выходит за неё)
     */
    private static void tryDriftOverObstacle(World world, Chunk chunk, int x, int z, int t,
                                             int maxY, ConfigHandler.Accumulation cfg) {
        // Занос — это ЦЕЛЫЙ блок, т.е. сразу 8 слоёв: цель должна быть не ниже.
        int maxLayers = Math.max(0, cfg.maxBlocksPerColumn) * MAX_LAYERS_PER_BLOCK;
        if (maxLayers < MAX_LAYERS_PER_BLOCK) return;
        if (t < MAX_LAYERS_PER_BLOCK) return;

        int height = chunk.getHeightValue(x & 15, z & 15);
        if (height <= 0) return;

        // Быстрый отсев: листва и стволы опорой заноса не бывают, а полный скан
        // колонки дерева (snowBaseY идёт вниз до земли) на каждом касании дорог.
        MUT.setPos(x, height - 1, z);
        IBlockState top = chunk.getBlockState(MUT);
        if (isLeaf(top) || top.getBlock() == Blocks.LOG || top.getBlock() == Blocks.LOG2) return;

        int baseY = snowBaseY(chunk, x, z, height);   // тот же расчёт, что и в columnBase
        if (baseY <= 0) return;

        // Где препятствие и куда ставим блок. Если высотная карта препятствие
        // учитывает (у факелов/плит/заборов ненулевая светонепроницаемость), то
        // baseY — уже свободная клетка над ним. Если не учитывает, то в baseY
        // стоит само препятствие, и клетка под снег — на одну выше.
        int yTarget;
        MUT.setPos(x, baseY, z);
        if (isDriftTarget(chunk.getBlockState(MUT))) {
            yTarget = baseY + 1;               // препятствие стоит в baseY — кладём выше
        } else {
            MUT.setPos(x, baseY - 1, z);
            if (!isDriftTarget(chunk.getBlockState(MUT))) return;   // под нами не препятствие
            yTarget = baseY;                   // препятствие в baseY - 1 — кладём в baseY
        }
        if (yTarget > maxY) return;

        // Клетка, КУДА ставим блок, должна быть пустой: воздух или сменяемое
        // растение. Под ней — обязательно препятствие (проверено строкой выше),
        // поэтому снег не повисает над пустотой.
        MUT.setPos(x, yTarget, z);
        IBlockState target = chunk.getBlockState(MUT);
        if (target.getBlock() != Blocks.AIR && !isReplaceablePlant(target)) return;

        // Сбоку на уровне нового блока уже должен лежать полный 8-слойный снег:
        // занос не ставится в пустоту, он «перетекает» с готового сугроба.
        if (!neighborDriftsBeside(world, x - 1, z, yTarget, maxY)
                && !neighborDriftsBeside(world, x + 1, z, yTarget, maxY)
                && !neighborDriftsBeside(world, x, z - 1, yTarget, maxY)
                && !neighborDriftsBeside(world, x, z + 1, yTarget, maxY)) {
            return;
        }

        // Тот же шанс, что и у обычной снежинки: занос идёт постепенно.
        if (cfg.growChance < 1.0 && RANDOM.nextDouble() >= cfg.growChance) return;

        // Кладём ЦЕЛЫЙ блок minecraft:snow (не слой) прямо на препятствие.
        setSnowBlock(world, x, yTarget, z);
    }

    /**
     * Полностью ли забита снегом клетка {@code y} в колонке соседа, т.е. лежит ли
     * там полный 8-слойный снег (8 слоёв = вид и высота целого блока).
     *
     * Сравниваются АБСОЛЮТНЫЕ высоты, а не номера оснований колонок: у соседа
     * основание обычно лежит не там, где у нас (тропинка и забор стоят в клетке
     * земли, а у соседа снег начинается на клетку выше/ниже). Старый тест «{@code
     * b <= yTop}» для тропинок и заборов не проходил никогда, из-за чего занос на
     * них не работал вовсе.
     */
    private static boolean neighborDriftsBeside(World world, int x, int z, int y, int maxY) {
        Chunk chunk = getLoadedChunk(world, x, z);
        if (chunk == null) return false;

        // Пункт 1: если у соседа ровно на уровне y уже лежит полный 8-слойный снег,
        // считаем условие выполненным. Это помогает заборам/проводам, у которых
        // верхняя кромка сугроба может совпадать с целевой клеткой, даже если
        // «снизу доверху» колонка забита не полностью.
        MUT.setPos(x, y, z);
        IBlockState atY = chunk.getBlockState(MUT);
        if (atY.getBlock() == Blocks.SNOW) return true;
        if (atY.getBlock() == Blocks.SNOW_LAYER
                && atY.getValue(BlockSnow.LAYERS) >= MAX_LAYERS_PER_BLOCK) return true;

        int b = columnBase(chunk, x, z);
        if (b <= 0 || b > y) return false;

        int need = (y + 1 - b) * MAX_LAYERS_PER_BLOCK;
        return countLayers(chunk, x, z, b, maxY) >= need;
    }

    /** Минимум без учёта «нет значения» (NO_VALUE = Integer.MIN_VALUE). */
    private static int lower(int min, int v) {
        return (v == NO_VALUE || v >= min) ? min : v;
    }

    /** Значение колонки для ограничения соседей. */
    private static int neighborValue(World world, int x, int z, int maxY) {
        Chunk chunk = getLoadedChunk(world, x, z);      // сосед почти всегда в кэше
        if (chunk == null) return NO_VALUE;
        int b = columnBase(chunk, x, z);
        // Ствол/чужой твёрдый блок (COL_BLOCKED) и препятствие под занос
        // (COL_DRIFT) соседей НЕ связывают. Иначе у каждой тропинки, забора и
        // факела соседи упирались бы в жёсткий нуль (cap = 0 + d) и вокруг них
        // оставалась бы голая «канава» шириной в блок, из-за чего занос стоял бы
        // вечно: рядом никогда не появлялся бы полноценный сугроб.
        if (b == COL_BLOCKED || b == COL_DRIFT) return NO_VALUE;
        if (b <= 0) return 0;                           // жёсткий нуль: вода/воздух
        return countLayers(chunk, x, z, b, maxY);
    }

    // ====================================================================
    // Чтение состояния колонки
    // ====================================================================

    /**
     * Возвращает baseY (>0) для колонки-держателя снега,
     * COL_BOUNDARY (0) для воды/воздуха, COL_BLOCKED (-1) для ствола/твёрдого блока,
     * COL_DRIFT (-2) для пустой клетки над неполной опорой (факел/плита/забор).
     * Читает через уже полученный чанк — без повторного поиска чанка по координате.
     */
    private static int columnBase(Chunk chunk, int x, int z) {
        int height = chunk.getHeightValue(x & 15, z & 15);
        if (height <= 0) return COL_BOUNDARY;

        int baseY = snowBaseY(chunk, x, z, height);
        if (baseY <= 0) return COL_BOUNDARY;

        MUT.setPos(x, baseY - 1, z);
        IBlockState below = chunk.getBlockState(MUT);

        MUT.setPos(x, baseY, z);
        IBlockState at = chunk.getBlockState(MUT);

        boolean snowHere = isSnow(at);
        boolean free = at.getBlock() == Blocks.AIR || snowHere || isReplaceablePlant(at);
        if (!free) {
            // Пункт 1: модовые провода и т.п. могут быть полными непрозрачными
            // блоками (isSnowSupport == true), но не попадать в heightmap из-за
            // getLightOpacity()==0. Тогда baseY указывает на сам блок, и снег
            // должен начинаться на клетку выше.
            if (isSnowSupport(at)) return baseY + 1;

            // Занос уже стоит поверх препятствия — считаем этот снег основанием.
            if (baseY + 1 <= WORLD_TOP_Y) {
                MUT.setPos(x, baseY + 1, z);
                if (isSnow(chunk.getBlockState(MUT))) return baseY + 1;
            }
            return COL_BLOCKED;
        }

        if (isSnowSupport(below)) return baseY;

        if (isDriftTarget(below)) return snowHere ? baseY : COL_DRIFT;

        // Пункт 3: снег, поставленный tryBridge над льдом/водой, тоже является
        // основанием обычной колонки и должен расти дальше как у соседей.
        if ((isIce(below) || isWater(below)) && snowHere) return baseY;

        return COL_BOUNDARY;
    }

    /** Слоёв снега над baseY: слой даёт 1..8, целый блок minecraft:snow — ровно 8. */
    private static int countLayers(Chunk chunk, int x, int z, int baseY, int maxY) {
        int total = 0;
        for (int y = baseY; y <= maxY; y++) {
            MUT.setPos(x, y, z);
            IBlockState s = chunk.getBlockState(MUT);
            if (s.getBlock() == Blocks.SNOW) {          // целый блок заноса = 8 слоёв
                total += MAX_LAYERS_PER_BLOCK;
                continue;
            }
            if (s.getBlock() != Blocks.SNOW_LAYER) break;
            total += s.getValue(BlockSnow.LAYERS);
        }
        return total;
    }

    // ====================================================================
    // Запись состояния колонки (ТОЛЬКО добавление снега)
    // ====================================================================

    /**
     * Приводит колонку к totalLayers, но только «вверх»: блоки снега
     * добавляются/утолщаются, воздух никогда не ставится.
     * Вызывается исключительно при росте (next > cur). Целые блоки
     * {@code minecraft:snow} (занос) считаются за 8 слоёв и не утоньшаются.
     */
    private static void applyStack(World world, Chunk chunk, int x, int z, int baseY,
                                   int totalLayers, int maxY) {
        int remaining = Math.max(0, totalLayers);
        int y = baseY;

        while (y <= maxY && remaining > 0) {
            MUT.setPos(x, y, z);
            IBlockState state = chunk.getBlockState(MUT);
            Block b = state.getBlock();
            boolean full = b == Blocks.SNOW;                 // целый блок = 8 слоёв
            boolean layer = b == Blocks.SNOW_LAYER;

            if (!full && !layer && b != Blocks.AIR && !isReplaceablePlant(state)) {
                break;   // упёрлись в ствол/листву — выше не растём
            }

            if (full) {
                remaining -= MAX_LAYERS_PER_BLOCK;           // уже полный — не трогаем
                y++;
                continue;
            }

            int want = Math.min(MAX_LAYERS_PER_BLOCK, remaining);
            if (layer) {
                int have = state.getValue(BlockSnow.LAYERS);
                if (have > want) want = have;   // никогда не утоньшаем существующий слой
            }
            if (!layer || state.getValue(BlockSnow.LAYERS) != want) {
                setSnow(world, x, y, z, want);
            }
            remaining -= want;
            y++;
        }
    }

    private static void setSnow(World world, int x, int y, int z, int layers) {
        int l = Math.max(1, Math.min(MAX_LAYERS_PER_BLOCK, layers));
        if (l >= MAX_LAYERS_PER_BLOCK) {
            // Набралось 8 слоёв — подменяем снежный слой целым блоком snow.
            setSnowBlock(world, x, y, z);
            return;
        }
        world.setBlockState(new BlockPos(x, y, z),
                Blocks.SNOW_LAYER.getDefaultState()
                        .withProperty(BlockSnow.LAYERS, l),
                SET_FLAG_NO_UPDATE);
    }

    /**
     * Ставит ЦЕЛЫЙ блок снега ({@code minecraft:snow}), а не слой
     * ({@code minecraft:snow_layer}) — именно так работает занос на тропинки,
     * заборы, лестницы, плиты, стены и факелы, где ванильный BlockSnow держаться
     * не может. Это единственный целый блок колонки: выше него снег набирается
     * обычными слоями ({@link #setSnow}).
     */
    private static void setSnowBlock(World world, int x, int y, int z) {
        world.setBlockState(new BlockPos(x, y, z), Blocks.SNOW.getDefaultState(),
                SET_FLAG_NO_UPDATE);
    }

    // ====================================================================
    // Целевая высота: сумма синусоид (фаза искажена шумом) + многооктавный шум, [min..max]
    // ====================================================================

    /**
     * Фазовые базы октав в точке-«якоре» зоны, приведённые к [0, 2π).
     *
     * Синус считается как sin(base + freq * (x - anchorX)), что математически
     * равно sin(freq * x + offset), но аргумент остаётся маленьким: точность
     * float-таблицы MathHelper сохраняется на любых координатах мира, а
     * приведение по модулю 2π делается один раз на зону, а не на колонку.
     */
    private static void wavePhaseBase(int anchorX, int anchorZ, ConfigHandler.Accumulation cfg) {
        double fx = cfg.freqX;
        double fz = cfg.freqZ;
        for (int o = 0; o < WAVE_OCTAVES; o++) {
            PHASE[2 * o]     = reducePhase(fx * anchorX + cfg.offsetX + WAVE_PHASE_X_STEP * o);
            PHASE[2 * o + 1] = reducePhase(fz * anchorZ + cfg.offsetZ + WAVE_PHASE_Z_STEP * o);
            fx *= 2.0;
            fz *= 2.0;
        }
    }

    /** Точное приведение фазы к [0, 2π) (fmod по double не теряет точность). */
    private static double reducePhase(double phase) {
        double r = phase % TWO_PI;
        return r < 0.0 ? r + TWO_PI : r;
    }

    /**
     * Вероятность касания колонки по её целевой высоте.
     *
     * Гребни (t около максимума) присыпаются почти каждую попытку, впадины
     * (t около минимума) — в (1 - ridgeBias) раз реже. Поэтому холмы вырастают
     * раньше: конечная функция рельефа проступает заранее, а впадины
     * догоняют последними. ridgeBias = 0 — все колонки равноправны.
     */
    private static double touchWeight(int t, int minLayers, int maxLayers,
                                      ConfigHandler.Accumulation cfg) {
        double bias = cfg.ridgeBias;
        if (bias <= 0.0 || maxLayers <= minLayers) return 1.0;

        double f = (t - minLayers) / (double) (maxLayers - minLayers);
        if (f < 0.0) f = 0.0;
        else if (f > 1.0) f = 1.0;

        // Вес впадины. Даже при bias = 1.0 оставляем крошечный шанс, иначе
        // впадины не получили бы НИ ОДНОГО касания и навсегда остались пустыми.
        double valley = 1.0 - bias;
        if (valley < MIN_TOUCH_WEIGHT) valley = MIN_TOUCH_WEIGHT;

        return valley + (1.0 - valley) * f;
    }

    /**
     * Целевая высота колонки, в слоях.
     *
     * Рельеф = несколько синусоид друг на друга (3 октавы: freq, 2*freq, 4*freq —
     * крупные холмы, средние гряды, мелкие бугры) плюс многооктавный шум.
     * Октавы — это «яйцеклетки» sin*sin со своими сдвигами фазы, поэтому гребни
     * не выстраиваются в правильную решётку; вдобавок фаза крупной октавы
     * искажается шумом (warp).
     *
     *     warp    = WARP_CELL_FRACTION * noiseScale
     *     target  = baseLayers
     *             + amplitudeLayers * Σ 0.5^o * sin(2^o*fx*(x+warp*na) + ox + 1.7*o)
     *                                           * sin(2^o*fz*(z+warp*nb) + oz + 2.3*o)
     *             + noiseAmplitude * fractalNoise(x, z)
     *     (сумма синусов нормирована на 1 + 0.5 + 0.25 = 1.75, т.е. в [-1..1])
     *
     * Фазы октав берутся из PHASE (см. wavePhaseBase), поэтому координаты
     * подставляются относительно якоря зоны.
     *
     * Сумма октав редко доходит до ±1, поэтому amplitudeLayers + noiseAmplitude
     * берутся с запасом ~25-30% относительно (max - min)/2. Дефолты
     * (base 32, амплитуда 22, шум 12, min 1, max 7 блоков) дают реальный
     * рельеф от ~1 до 7 блоков БЕЗ срабатывания клампа — ни голой земли,
     * ни плоских «полок».
     */
    private static int targetLayers(int x, int z, int anchorX, int anchorZ,
                                    ConfigHandler.Accumulation cfg) {
        // Фазовое искажение синусоиды: сдвиг фазы до ~0.2 ячейки шума.
        // Без него холмы стоят строгой решёткой и сливаются в одну «волну».
        double warp = WARP_CELL_FRACTION * Math.max(1, cfg.noiseScale);
        double wx = (x - anchorX) + warp * valueNoise(x, z, cfg.noiseScale, cfg.noiseSeed + 1301);
        double wz = (z - anchorZ) + warp * valueNoise(x, z, cfg.noiseScale, cfg.noiseSeed + 2609);

        // Наложение нескольких синусоид: каждая следующая октава вдвое короче
        // и вдвое слабее — крупный рельеф остаётся читаемым, но появляется
        // средняя и мелкая гряда, поэтому холмы разной ширины и высоты.
        // MathHelper.sin — табличный (float), ~10x дешевле Math.sin.
        double waveSum = 0.0;
        double weightSum = 0.0;
        double weight = 1.0;
        double fx = cfg.freqX;
        double fz = cfg.freqZ;
        for (int o = 0; o < WAVE_OCTAVES; o++) {
            float sx = MathHelper.sin((float) (PHASE[2 * o] + fx * wx));
            float sz = MathHelper.sin((float) (PHASE[2 * o + 1] + fz * wz));
            waveSum += weight * sx * sz;
            weightSum += weight;
            weight *= 0.5;
            fx *= 2.0;
            fz *= 2.0;
        }
        double wave = weightSum > 0.0 ? waveSum / weightSum : 0.0;

        // Плавный многооктавный шум — рваные, «естественные» очертания сугробов.
        double noise = fractalNoise(x, z, cfg.noiseScale, cfg.noiseSeed);

        double value = cfg.baseLayers
                + cfg.amplitudeLayers * wave
                + cfg.noiseAmplitude * noise;

        int maxLayers = Math.max(0, cfg.maxBlocksPerColumn) * MAX_LAYERS_PER_BLOCK;
        int minLayers = Math.max(0, cfg.minBlocksPerColumn) * MAX_LAYERS_PER_BLOCK;
        if (minLayers > maxLayers) minLayers = maxLayers;

        int layers = (int) Math.round(value);
        if (layers < minLayers) layers = minLayers;
        if (layers > maxLayers) layers = maxLayers;
        return layers;
    }

    /** Сумма NOISE_OCTAVES октав valueNoise с лакунарностью 2 и затуханием 0.5, результат в [-1..1]. */
    private static double fractalNoise(int x, int z, int cell, int seed) {
        int c = Math.max(1, cell);
        double sum = 0.0;
        double norm = 0.0;
        double amp = 1.0;
        for (int o = 0; o < NOISE_OCTAVES; o++) {
            sum += amp * valueNoise(x, z, c, seed + o * 7919);
            norm += amp;
            amp *= 0.5;
            c = Math.max(1, c / 2);
        }
        return norm > 0.0 ? sum / norm : 0.0;
    }

    private static double valueNoise(int x, int z, int cell, int seed) {
        if (cell <= 0) cell = 1;

        // Целочисленные эквиваленты floor(x / cell): без double-делений и Math.floor.
        int x0 = Math.floorDiv(x, cell);
        int z0 = Math.floorDiv(z, cell);
        double tx = (x - x0 * cell) / (double) cell;
        double tz = (z - z0 * cell) / (double) cell;

        double v00 = hash01(x0, z0, seed);
        double v10 = hash01(x0 + 1, z0, seed);
        double v01 = hash01(x0, z0 + 1, seed);
        double v11 = hash01(x0 + 1, z0 + 1, seed);

        double sx = smooth(tx);
        double sz = smooth(tz);
        double a = v00 + (v10 - v00) * sx;
        double b = v01 + (v11 - v01) * sx;
        return (a + (b - a) * sz) * 2.0 - 1.0;
    }

    private static double smooth(double t) {
        return t * t * (3.0 - 2.0 * t);
    }

    private static double hash01(int x, int z, int seed) {
        long h = x * 0x9E3779B97F4A7C15L
                + z * 0xC2B2AE3D27D4EB4FL
                + seed * 0x165667B19E3779F9L;
        h ^= (h >>> 33);
        h *= 0xff51afd7ed558ccdL;
        h ^= (h >>> 33);
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= (h >>> 33);
        return (h & 0xFFFFFFL) / (double) 0x1000000L;
    }

    // ====================================================================
    // Вода
    // ====================================================================

    private static void tryFreeze(World world, int x, int z, ConfigHandler.Accumulation cfg) {
        Chunk chunk = getLoadedChunk(world, x, z);
        if (chunk == null) return;

        int height = chunk.getHeightValue(x & 15, z & 15);
        if (height <= 0) return;

        int baseY = snowBaseY(chunk, x, z, height);
        int surfaceY = baseY - 1;
        if (surfaceY < 0) return;

        MUT.setPos(x, surfaceY, z);
        IBlockState state = chunk.getBlockState(MUT);
        if (!isWater(state)) return;
        if (cfg.freezeOnlySources && !isSourceWater(state)) return;

        // Над водой уже лежит снег (слой или целый блок заноса) — НЕ замораживаем:
        // ванильный BlockSnow не держится на льду (checkAndDropBlock), и такой слой
        // осыпается, увлекая соседей каскадом вплоть до StackOverflowError.
        if (surfaceY + 1 <= WORLD_TOP_Y) {
            MUT.setPos(x, surfaceY + 1, z);
            if (isSnow(chunk.getBlockState(MUT))) return;
        }

        int layers = cfg.freezeLayers;
        if (layers <= 0) return;

        for (int i = 0; i < layers; i++) {
            int y = surfaceY - i;
            if (y < 0) break;
            MUT.setPos(x, y, z);
            IBlockState cur = chunk.getBlockState(MUT);
            if (i > 0 && !isWater(cur)) break;
            world.setBlockState(new BlockPos(x, y, z),
                    Blocks.ICE.getDefaultState(), 2);
        }
    }

    private static boolean isWater(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.WATER || b == Blocks.FLOWING_WATER;
    }

    private static boolean isSourceWater(IBlockState state) {
        if (state == Blocks.WATER.getDefaultState()) return true;
        if (!isWater(state)) return false;
        return state.getValue(BlockLiquid.LEVEL) == 0;
    }

    private static boolean isIce(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE;
    }

    // ====================================================================
    // Поверхность (листва игнорируется)
    // ====================================================================

    private static int snowBaseY(Chunk chunk, int x, int z, int height) {
        if (height <= 0) return height;

        MUT.setPos(x, height - 1, z);
        IBlockState top = chunk.getBlockState(MUT);
        if (!isLeaf(top)
                && !isSnow(top)
                && !isReplaceablePlant(top)) {
            return height;
        }

        int limit = Math.max(0, height - MAX_COLUMN_SCAN);
        for (int y = height - 1; y >= limit; y--) {
            MUT.setPos(x, y, z);
            IBlockState s = chunk.getBlockState(MUT);
            Block b = s.getBlock();
            if (b == Blocks.AIR
                    || isLeaf(s)
                    || b == Blocks.LOG || b == Blocks.LOG2
                    || isSnow(s)
                    || isReplaceablePlant(s)) {
                continue;
            }
            return y + 1;
        }
        return height;
    }

    /**
     * Любой снег накопления: слой {@code minecraft:snow_layer} (1..8 слоёв) или
     * целый блок {@code minecraft:snow}, который ставит занос и который считается
     * за полные {@link #MAX_LAYERS_PER_BLOCK} слоёв.
     */
    private static boolean isSnow(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.SNOW_LAYER || b == Blocks.SNOW;
    }

    private static boolean isLeaf(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.LEAVES || b == Blocks.LEAVES2;
    }

    private static boolean isReplaceablePlant(IBlockState state) {
        Block b = state.getBlock();
        return b == Blocks.TALLGRASS || b == Blocks.DEADBUSH
                || b == Blocks.YELLOW_FLOWER || b == Blocks.RED_FLOWER
                || b == Blocks.BROWN_MUSHROOM || b == Blocks.RED_MUSHROOM
                || b == Blocks.SAPLING || b == Blocks.DOUBLE_PLANT
                || b == Blocks.VINE;
    }

    /**
     * Опора, на которой ванильный BlockSnow НЕ осыпется (canBlockStay).
     * Лёд намеренно не подходит: checkAndDropBlock сносит слой снега со льда,
     * а каскад таких сносов по большой плоскости способен уронить сервер
     * (см. run/crash-reports/crash-2026-11-19_15.22.19-server.txt).
     * Поэтому накопление на лёд не кладём никогда — «снег не исчезает».
     */
    private static boolean isSnowSupport(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.SNOW_LAYER) return false;
        if (isIce(state)) return false;
        return state.isFullBlock() && state.isOpaqueCube();
    }

    /**
     * Неполная опора, на которую метель может намести снег: тропинка
     * ({@code grass_path}), плита, забор, лестница, стена, грядка, факел и т.п.
     * Ванильный BlockSnow сам на такие блоки никогда не ляжет, но занос (см.
     * {@link #tryDriftOverObstacle}) ставит на них ЦЕЛЫЙ блок {@code minecraft:snow},
     * и дальше колонка набирается такими же блоками сверху.
     *
     * Полные опоры ({@link #isSnowSupport}) сюда не входят — на них снег растёт
     * обычным порядком. Воздух, вода, лёд, листва и стволы — тоже нет: на них
     * снег не накапливаем никогда (на воде/льду ванильный checkAndDropBlock
     * сносит слой, кроны и стволы не должны обрастать сугробами).
     */
    private static boolean isDriftTarget(IBlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.AIR || isSnow(state)) return false;
        if (b == Blocks.LOG || b == Blocks.LOG2) return false;
        if (isLeaf(state)) return false;
        if (isWater(state) || isIce(state)) return false;
        if (isReplaceablePlant(state)) return false;   // цветы/трава просто замещаются снегом
        if (isSnowSupport(state)) return false;
        return true;
    }

    // ====================================================================

    /**
     * Чанк по координатам блока с кэшем на один чанк (сбрасывается раз в тик).
     * Возвращает null, если чанк не загружен или пуст.
     */
    private static Chunk getLoadedChunk(World world, int x, int z) {
        int cx = x >> 4;
        int cz = z >> 4;
        if (world == cachedWorld && cx == cachedChunkX && cz == cachedChunkZ) return cachedChunk;

        Chunk c = world.getChunkProvider().getLoadedChunk(cx, cz);
        if (c != null && c.isEmpty()) c = null;

        cachedWorld = world;
        cachedChunkX = cx;
        cachedChunkZ = cz;
        cachedChunk = c;
        return c;
    }

    /** Сброс кэша чанка: между тиками чанк мог выгрузиться. */
    private static void resetChunkCache() {
        cachedWorld = null;
        cachedChunkX = Integer.MIN_VALUE;
        cachedChunkZ = Integer.MIN_VALUE;
        cachedChunk = null;
    }

    private static boolean isDimensionEnabled(int dim) {
        int[] dims = ConfigHandler.accumulation.dimensions;
        if (dims == null || dims.length == 0) return false;
        for (int id : dims) if (id == dim) return true;
        return false;
    }
}