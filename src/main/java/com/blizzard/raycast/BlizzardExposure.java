package com.blizzard.raycast;

import net.minecraft.block.Block;
import net.minecraft.block.BlockLiquid;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayerMP;
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
 */
public final class BlizzardExposure {

    // ===== Параметры =====
    private static final int    RAYS_SPHERE    = 32;
    private static final int    RAYS_SKY       = 12;
    private static final double SKY_CONE_DEG   = 35.0;
    private static final int    UPDATE_EVERY   = 4;
    private static final float  EMA            = 0.25f;
    private static final double RADIUS_SPHERE = 16.0;
    private static final double RADIUS_SKY    = 32.0;

    /** Порог изменения, при котором пакет шлётся немедленно (не ждём 4 тика). */
    public static final float SEND_THRESHOLD = 0.15f;

    private static final Random RNG = new Random();

    /** Состояние на одного игрока. */
    public static final class Entry {
        public BlockPos lastPos;
        public long     lastTick;
        public float    skySmoothed;
        public float    openSmoothed;
        public boolean  initialized;

        // последнее отправленное клиенту — для «слать при изменении»
        public float lastSentSky  = -1f;
        public float lastSentOpen = -1f;
        /** Тик, на котором последний раз отправляли пакет (для периодической синхронизации таймера). */
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

        boolean moved = e.lastPos == null
                || player.getPosition().distanceSq(e.lastPos) > 1;

        // Движущийся игрок — раз в UPDATE_EVERY тиков;
        // стоящий — раз в 20 (окружение не меняется).
        long interval = moved ? UPDATE_EVERY : 20;
        if (now - e.lastTick < interval) return;

        e.lastPos = player.getPosition();
        e.lastTick = now;

        Vec3d eye = player.getPositionEyes(1.0f);

        float rawSky  = castCone(world, eye, RAYS_SKY, RADIUS_SKY, SKY_CONE_DEG);
        float rawOpen = castSphere(world, eye, RAYS_SPHERE, RADIUS_SPHERE);

        if (!e.initialized) {
            e.skySmoothed  = rawSky;
            e.openSmoothed = rawOpen;
            e.initialized  = true;
        } else {
            e.skySmoothed  = e.skySmoothed  * (1f - EMA) + rawSky  * EMA;
            e.openSmoothed = e.openSmoothed * (1f - EMA) + rawOpen * EMA;
        }
    }

    public static void clear(EntityPlayerMP player) {
        CACHE.remove(player.getUniqueID());
    }

    public static void clearAll() {
        CACHE.clear();
    }

    // ===== Рейкасты =====

    private static float castSphere(World world, Vec3d eye, int rays, double R) {
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
    private static float castCone(World world, Vec3d eye, int rays, double R, double halfAngleDeg) {
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
     * Консервативно: если конец луча в незагруженном чанке — считаем «закрыто».
     * Иначе в пещере на границе чанков луч «уходит в пустоту» и даёт ложную
     * открытость (2-3% в закрытом коридоре).
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
            return false; // упёрлись в твёрдый блок
        }
        return true; // исчерпали глубину — считаем открытым (листва/вода не бесконечны)
    }

    private static Vec3d randomUnitVector(Random r) {
        double z = 2.0 * r.nextDouble() - 1.0;
        double a = 2.0 * Math.PI * r.nextDouble();
        double s = Math.sqrt(1.0 - z * z);
        return new Vec3d(s * Math.cos(a), z, s * Math.sin(a));
    }
}