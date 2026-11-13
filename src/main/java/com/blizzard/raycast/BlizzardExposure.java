package com.blizzard.raycast;

import com.blizzard.config.ConfigHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

/**
 * Серверный расчёт «открытости» игрока для метели.
 *
 * Две величины (обе 0..1, сглажены EMA):
 *   skyExposure — доля лучей вверх (конус ~35°), доходящих до неба.
 *                 «На поверхности» (≈1) или «под землёй» (≈0).
 *   openness    — доля лучей во все стороны, не упёршихся в блок.
 *                 «Сколько открытого пространства вокруг».
 *
 * Пересчёт не чаще раза в UPDATE_EVERY тиков и только при сдвиге > 1 блока.
 *
 * Дополнительно:
 *   - Детерминированный поиск источников тепла (факелы, огонь, лава)
 *     в радиусе getMaxHeatDistance() вокруг игрока. Это надёжнее и быстрее,
 *     чем рейкасты, так как не зависит от случайных направлений лучей.
 *   - Хранение найденных источников в Map для того, чтобы они не "мерцали"
 *     при каждом новом рейкасте.
 */
public final class BlizzardExposure {

    // ===== Параметры =====
    private static final int    RAYS_SPHERE    = 32;
    private static final int    RAYS_SKY       = 12;
    private static final double SKY_CONE_DEG   = 35.0;
    private static final int    UPDATE_EVERY   = 4;
    private static final double RADIUS_SPHERE = 16.0;
    private static final double RADIUS_SKY    = 32.0;


    private static final Random RNG = new Random();

    /** Состояние на одного игрока. */
    public static final class Entry {
        public BlockPos lastPos;
        public long     lastTick;
        public float    skySmoothed;
        public float    openSmoothed;

        /** Целевые значения (результат рейкастов). */
        public float    targetSky;
        public float    targetOpen;

        public boolean  initialized;

        /**
         * Активные источники тепла вокруг игрока.
         * Ключ — абсолютные координаты блока. Значение — сила тепла.
         * Мы храним их, чтобы случайные лучи не заставляли факелы "мерцать".
         */
        public final java.util.Map<BlockPos, Float> heatSources = new java.util.HashMap<>();

        /**
         * Измерение, в котором были найдены источники.
         * Нужно для очистки при телепортации.
         */
        public int dimension = Integer.MIN_VALUE;

        public float lastSentSky  = -1f;
        public float lastSentOpen = -1f;
        public long  lastSentTick = Long.MIN_VALUE;
    }

    private static final Map<UUID, Entry> CACHE = new HashMap<>();

    private BlizzardExposure() {}

    // ===== Публичный API =====

    public static float getSkyExposure(EntityPlayerMP p) {
        Entry e = CACHE.get(p.getUniqueID());
        return e == null ? 1.0f : e.skySmoothed;
    }

    public static float getOpenness(EntityPlayerMP p) {
        Entry e = CACHE.get(p.getUniqueID());
        return e == null ? 1.0f : e.openSmoothed;
    }

    /**
     * Единая величина «насколько метель достаёт до игрока».
     *
     * Используется и для визуала (снежинки, шейдер), и для геймплея
     * (урон холодом, замедление). Формула — вероятностное ИЛИ:
     * exposure = 1 - (1 - sky) * (1 - open).
     *
     * Свойства:
     *   sky=0, open=0  → 0   (глухая пещера)
     *   sky=1, open=0  → 1   (открытое небо, тесно вокруг)
     *   sky=0, open=1  → 1   (потолок над головой, но открыто вокруг)
     *   sky=1, open=1  → 1   (полностью открыто)
     *
     * @param p серверный игрок.
     * @return 0..1 — множитель силы метели.
     */
    public static float getExposure(EntityPlayerMP p) {
        float sky  = getSkyExposure(p);
        float open = getOpenness(p);
        return 1f - (1f - sky) * (1f - open);
    }

    public static Entry getEntry(EntityPlayerMP p) {
        return CACHE.computeIfAbsent(p.getUniqueID(), k -> new Entry());
    }

    /**
     * Вызывается из tickWeather каждый тик для каждого серверного игрока.
     * Внутри сам решает, пора ли пересчитывать:
     *   движущийся игрок — раз в UPDATE_EVERY тиков;
     *   стоящий — раз в 20 тиков (окружение не меняется).
     *
     * После пересчёта значения сглаживаются EMA, чтобы при ходьбе
     * не дёргались на каждом шаге.
     *
     * @param player серверный игрок;
     * @param world  мир, в котором он находится.
     */
    public static void tick(EntityPlayerMP player, World world) {
        long now = world.getTotalWorldTime();
        Entry e = getEntry(player);

        // Если игрок сменил измерение (или это первый вход), очищаем старые данные
        if (e.dimension != player.dimension) {
            e.heatSources.clear();
            e.lastPos = null;
            e.lastTick = 0;
            e.dimension = player.dimension;
        }

        boolean moved = e.lastPos == null
                || player.getPosition().distanceSq(e.lastPos) > 1;

        // 1. РЕЙКАСТЫ И ПОИСК ТЕПЛА (редко)
        long interval = moved ? UPDATE_EVERY : 20;
        if (now - e.lastTick >= interval) {
            e.lastPos = player.getPosition();
            e.lastTick = now;

            Vec3d eye = player.getPositionEyes(1.0f);

            // Рейкасты для openness и skyExposure
            float rawSky  = castCone(world, eye, RAYS_SKY, RADIUS_SKY, SKY_CONE_DEG, e);
            float rawOpen = castSphere(world, eye, RAYS_SPHERE, RADIUS_SPHERE, e);

            if (!e.initialized) {
                e.skySmoothed  = rawSky;
                e.openSmoothed = rawOpen;
                e.targetSky    = rawSky;
                e.targetOpen   = rawOpen;
                e.initialized  = true;
            } else {
                e.targetSky  = rawSky;
                e.targetOpen = rawOpen;
            }

            // ДЕТЕРМИНИРОВАННЫЙ ПОИСК ТЕПЛА
            findHeatSources(world, eye, e);

            // ОЧИСТКА СТАРЫХ ИСТОЧНИКОВ
            // Проходим по всем сохранённым источникам и удаляем те,
            // которые стали слишком далеко (игрок ушёл) или были сломаны.
            java.util.Iterator<java.util.Map.Entry<BlockPos, Float>> it = e.heatSources.entrySet().iterator();
            while (it.hasNext()) {
                java.util.Map.Entry<BlockPos, Float> entry = it.next();
                BlockPos pos = entry.getKey();

                // Если блок сломали — удаляем
                if (getHeatStrength(world, pos) <= 0f) {
                    it.remove();
                    continue;
                }

                // Если игрок ушёл слишком далеко — удаляем
                double distSq = eye.squareDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distSq > getMaxHeatDistance() * getMaxHeatDistance()) {
                    it.remove();
                }
            }
        }

        // 2. ПЛАВНОЕ ДВИЖЕНИЕ К ЦЕЛИ (каждый тик)
        if (e.initialized) {
            float step = 0.05f;

            if (e.skySmoothed < e.targetSky) {
                e.skySmoothed = Math.min(e.targetSky, e.skySmoothed + step);
            } else if (e.skySmoothed > e.targetSky) {
                e.skySmoothed = Math.max(e.targetSky, e.skySmoothed - step);
            }

            if (e.openSmoothed < e.targetOpen) {
                e.openSmoothed = Math.min(e.targetOpen, e.openSmoothed + step);
            } else if (e.openSmoothed > e.targetOpen) {
                e.openSmoothed = Math.max(e.targetOpen, e.openSmoothed - step);
            }
        }
    }

    /**
     * Детерминированно ищет источники тепла в кубе вокруг игрока.
     * Проходит по всем блокам в радиусе getMaxHeatDistance() (5 блоков).
     *
     * Вызывается раз в UPDATE_EVERY или 20 тиков (не каждый тик).
     */
    private static void findHeatSources(World world, Vec3d eye, Entry e) {
        int radius = (int) Math.ceil(getMaxHeatDistance());
        int cx = (int) Math.floor(eye.x);
        int cy = (int) Math.floor(eye.y);
        int cz = (int) Math.floor(eye.z);

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Проверяем расстояние по квадрату (быстро, без sqrt)
                    if (dx * dx + dy * dy + dz * dz > getMaxHeatDistance() * getMaxHeatDistance()) {
                        continue;
                    }

                    pos.setPos(cx + dx, cy + dy, cz + dz);

                    // Проверяем только загруженные чанки
                    if (!world.isBlockLoaded(pos)) continue;

                    // Проверяем, является ли блок источником тепла
                    float heat = getHeatStrength(world, pos);
                    if (heat > 0f) {
                        // Запоминаем источник (toImmutable, чтобы координаты не менялись)
                        e.heatSources.put(pos.toImmutable(), heat);
                    }
                }
            }
        }
    }

    /**
     * Возвращает процент защиты от холода благодаря всем найденным источникам тепла.
     *
     * Используется логарифмическая шкала (убывающая отдача).
     * Расстояние до каждого источника пересчитывается "на лету", чтобы
     * защита плавно снижалась, если игрок отходит от факела.
     *
     * @param p серверный игрок.
     * @return 0.0 .. 0.9 (максимум 90% защиты).
     */
    public static float getHeatProtection(EntityPlayerMP p) {
        Entry e = CACHE.get(p.getUniqueID());
        if (e == null || e.heatSources.isEmpty()) return 0f;

        Vec3d eye = p.getPositionEyes(1.0f);
        float totalHeatSum = 0f;
        World world = p.world;

        for (java.util.Map.Entry<BlockPos, Float> entry : e.heatSources.entrySet()) {
            BlockPos pos = entry.getKey();
            float strength = entry.getValue();

            // Проверяем, существует ли ещё блок и всё ещё даёт тепло
            if (getHeatStrength(world, pos) <= 0f) continue;

            // Расстояние от глаз до центра блока
            double dist = Math.sqrt(eye.squareDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5));
            if (dist > getMaxHeatDistance()) continue;

            // Коэффициент расстояния: 1.0 вплотную, 0.0 на максимальном расстоянии
            float distFactor = 1.0f - ((float) dist / getMaxHeatDistance());
            totalHeatSum += strength * distFactor;
        }

        if (totalHeatSum <= 0f) return 0f;

        // Логарифмическая шкала (убывающая отдача)
        float protection = 1.0f - (1.0f / (1.0f + totalHeatSum));

        return Math.min(0.9f, protection);
    }

    /**
     * Возвращает силу тепла для блока (0, если блок не греет).
     */
    private static float getHeatStrength(World world, BlockPos pos) {
        Block block = world.getBlockState(pos).getBlock();

        float torch = (float) ConfigHandler.heat.heatTorch;
        float fire = (float) ConfigHandler.heat.heatFire;
        float lava = (float) ConfigHandler.heat.heatLava;

        if (block == Blocks.TORCH) return torch;
        if (block == Blocks.FIRE) return fire;
        if (block == Blocks.LAVA || block == Blocks.FLOWING_LAVA) return lava;
        return 0f;
    }

    /**
     * Берет дальность из конфига
     */
    private static float getMaxHeatDistance() {
        return (float) ConfigHandler.heat.maxHeatDistance;
    }


    /**
     * Очищает карту источников тепла для игрока.
     * Вызывается при смерти, чтобы после респавна игрок не получал
     * фантомное тепло от факелов, найденных до смерти.
     */
    public static void clearHeatSources(EntityPlayerMP player) {
        Entry e = CACHE.get(player.getUniqueID());
        if (e != null) {
            e.heatSources.clear();
        }
    }

    public static void clear(EntityPlayerMP player) {
        CACHE.remove(player.getUniqueID());
    }

    public static void clearAll() {
        CACHE.clear();
    }

    // ===== Рейкасты =====

    /**
     * Пускает лучи по сфере и считает долю открытых.
     */
    private static float castSphere(World world, Vec3d eye, int rays, double R, Entry e) {
        int open = 0;
        for (int i = 0; i < rays; i++) {
            Vec3d dir = randomUnitVector(RNG);
            if (isRayOpen(world, eye, dir, R)) open++;
        }
        return open / (float) rays;
    }

    /**
     * Считает долю лучей вверх (в конусе), доходящих до неба.
     *
     * Направления генерируются rejection sampling: берём равномерный вектор
     * на сфере и отбрасываем те, что ниже косинуса полуугла конуса.
     * Из-за малого телесного угла (35°) доля попаданий в конус ~9%,
     * поэтому попыток нужно в разы больше, чем лучей.
     *
     * ВАЖНО: знаменатель — accepted, а не rays. Иначе при нехватке попыток
     * результат систематически занижается (на открытом поле показывал бы ~50%).
     *
     * @param world        мир;
     * @param eye          точка старта (глаза игрока);
     * @param rays         сколько лучей принять;
     * @param R            радиус рейкаста в блоках;
     * @param halfAngleDeg полуугол конуса от вертикали в градусах.
     * @return доля открытых лучей 0..1 (open / accepted).
     */
    private static float castCone(World world, Vec3d eye, int rays, double R, double halfAngleDeg, Entry e) {
        double cosMax = Math.cos(Math.toRadians(halfAngleDeg));
        int open = 0;
        int accepted = 0;
        int attempts = 0;
        int maxAttempts = rays * 50; // запас: при 35° acceptance ~9%, нужно ~11x
        while (accepted < rays && attempts < maxAttempts) {
            attempts++;
            Vec3d dir = randomUnitVector(RNG);
            if (dir.y < cosMax) continue; // вне конуса — отбрасываем
            accepted++;
            if (isRayOpen(world, eye, dir, R)) open++;
        }
        return accepted == 0 ? 0f : open / (float) accepted;
    }

    /**
     * true, если луч не упёрся в «непрозрачный для снега» блок.
     * Листва и жидкости пролетаются.
     *
     * Поиск тепла перенесён в findHeatSources() для лучшей оптимизации.
     */
    private static boolean isRayOpen(World world, Vec3d from, Vec3d dir, double R) {
        Vec3d current = from;
        double remaining = R;

        for (int depth = 0; depth < 8 && remaining > 0.1; depth++) {
            Vec3d to = current.add(dir.scale(remaining));
            BlockPos endPos = new BlockPos(to);
            if (!world.isBlockLoaded(endPos)) return false;

            RayTraceResult hit = world.rayTraceBlocks(current, to, false, true, false);
            if (hit == null) return true;
            if (hit.typeOfHit != RayTraceResult.Type.BLOCK) return true;

            BlockPos pos = hit.getBlockPos();
            if (!world.isBlockLoaded(pos)) return false;

            IBlockState st = world.getBlockState(pos);
            Block block = st.getBlock();

            if (block.isLeaves(st, world, pos) || block instanceof BlockLiquid) {
                double consumed = current.distanceTo(hit.hitVec);
                remaining -= consumed + 0.01;
                current = hit.hitVec.add(dir.scale(0.01));
                continue;
            }
            return false;
        }
        return true;
    }

    private static Vec3d randomUnitVector(Random r) {
        double z = 2.0 * r.nextDouble() - 1.0;
        double a = 2.0 * Math.PI * r.nextDouble();
        double s = Math.sqrt(1.0 - z * z);
        return new Vec3d(s * Math.cos(a), z, s * Math.sin(a));
    }
}