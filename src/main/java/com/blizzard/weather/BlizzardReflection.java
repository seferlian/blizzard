package com.blizzard.weather;

import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;

import java.lang.reflect.Field;

/**
 * Класс-обёртка над рефлексией для доступа к полям, которые ASM добавляет
 * в World (и его наследники) и в WorldInfo.
 *
 * Поля кешируются при первом обращении (initWorldCache / initInfoCache),
 * чтобы не искать их каждый раз — тик метели вызывает эти методы до 20 раз в секунду.
 *
 * Прямых приведений к интерфейсам IBlizzardWorld / IWorldInfoBlizzard в моде нет,
 * весь доступ идёт через этот класс.
 */
public class BlizzardReflection {

    /** Отладка: выводить в лог, какие поля удалось найти при инициализации кеша. */
    private static final boolean DEBUG = false;

    // ==================== Кеш полей World ====================
    /** Поле isBlizzard (Z) — флаг активности метели. */
    private static Field FIELD_WORLD_IS_BLIZZARD = null;
    /** Поле blizzardTime (I) — остаток времени метели в тиках. */
    private static Field FIELD_WORLD_BLIZZARD_TIME = null;
    /** Поле blizzardStrength (F) — текущая визуальная сила метели (0.0 – 1.0). */
    private static Field FIELD_WORLD_BLIZZARD_STRENGTH = null;
    /** Поле prevBlizzardStrength (F) — предыдущая сила (для интерполяции). */
    private static Field FIELD_WORLD_PREV_BLIZZARD_STRENGTH = null;
    /** Флаг: кеш полей World уже инициализирован. */
    private static boolean worldCacheInitialized = false;

    // ==================== Кеш полей WorldInfo ====================
    /** Поле blizzard (Z) — сохранённый флаг активности метели. */
    private static Field FIELD_INFO_BLIZZARD = null;
    /** Поле blizzardTime (I) — сохранённый остаток времени. */
    private static Field FIELD_INFO_BLIZZARD_TIME = null;
    /** Поле blizzardStrength (F) — сохранённая сила метели. */
    private static Field FIELD_INFO_BLIZZARD_STRENGTH = null;
    /** Флаг: кеш полей WorldInfo уже инициализирован. */
    private static boolean infoCacheInitialized = false;

    /**
     * Находит и кеширует поля класса World.
     * Вызывается один раз при первом обращении к любому методу World-секции.
     */
    private static void initWorldCache() {
        if (worldCacheInitialized) return;
        worldCacheInitialized = true;

        // Ищем поля по MCP-именам (в dev) — в prod Forge сам ремапит имена при сборке
        FIELD_WORLD_IS_BLIZZARD            = findField(World.class, "isBlizzard");
        FIELD_WORLD_BLIZZARD_TIME          = findField(World.class, "blizzardTime");
        FIELD_WORLD_BLIZZARD_STRENGTH      = findField(World.class, "blizzardStrength");
        FIELD_WORLD_PREV_BLIZZARD_STRENGTH = findField(World.class, "prevBlizzardStrength");

        // Делаем поля доступными для чтения/записи (иначе IllegalAccessException)
        if (FIELD_WORLD_IS_BLIZZARD != null) FIELD_WORLD_IS_BLIZZARD.setAccessible(true);
        if (FIELD_WORLD_BLIZZARD_TIME != null) FIELD_WORLD_BLIZZARD_TIME.setAccessible(true);
        if (FIELD_WORLD_BLIZZARD_STRENGTH != null) FIELD_WORLD_BLIZZARD_STRENGTH.setAccessible(true);
        if (FIELD_WORLD_PREV_BLIZZARD_STRENGTH != null) FIELD_WORLD_PREV_BLIZZARD_STRENGTH.setAccessible(true);

        if (DEBUG) {
            System.out.println("[BlizzardReflection] World cache: "
                    + "isBlizzard=" + (FIELD_WORLD_IS_BLIZZARD != null)
                    + " blizzardTime=" + (FIELD_WORLD_BLIZZARD_TIME != null)
                    + " blizzardStrength=" + (FIELD_WORLD_BLIZZARD_STRENGTH != null)
                    + " prevBlizzardStrength=" + (FIELD_WORLD_PREV_BLIZZARD_STRENGTH != null));
        }
    }

    /**
     * Находит и кеширует поля класса WorldInfo.
     * Вызывается один раз при первом обращении к любому методу Info-секции.
     */
    private static void initInfoCache() {
        if (infoCacheInitialized) return;
        infoCacheInitialized = true;

        // Ищем поля по MCP-именам (в dev) — в prod Forge ремапит их при сборке
        FIELD_INFO_BLIZZARD          = findField(WorldInfo.class, "blizzard");
        FIELD_INFO_BLIZZARD_TIME     = findField(WorldInfo.class, "blizzardTime");
        FIELD_INFO_BLIZZARD_STRENGTH = findField(WorldInfo.class, "blizzardStrength");

        // Делаем поля доступными
        if (FIELD_INFO_BLIZZARD != null) FIELD_INFO_BLIZZARD.setAccessible(true);
        if (FIELD_INFO_BLIZZARD_TIME != null) FIELD_INFO_BLIZZARD_TIME.setAccessible(true);
        if (FIELD_INFO_BLIZZARD_STRENGTH != null) FIELD_INFO_BLIZZARD_STRENGTH.setAccessible(true);

        if (DEBUG) {
            System.out.println("[BlizzardReflection] Info cache: "
                    + "blizzard=" + (FIELD_INFO_BLIZZARD != null)
                    + " blizzardTime=" + (FIELD_INFO_BLIZZARD_TIME != null)
                    + " blizzardStrength=" + (FIELD_INFO_BLIZZARD_STRENGTH != null));
        }
    }

    // ==================== World ====================

    /**
     * @return true, если в мире активна метель.
     */
    public static boolean isBlizzard(World world) {
        initWorldCache();
        if (FIELD_WORLD_IS_BLIZZARD == null) return false;
        try { return FIELD_WORLD_IS_BLIZZARD.getBoolean(world); }
        catch (Throwable t) { t.printStackTrace(); return false; }
    }

    /**
     * Устанавливает флаг метели и остаток времени в World.
     * Синхронизация World → WorldInfo выполняется в BlizzardHooks.writeNBT
     * (перед записью в NBT при сохранении мира).
     *
     * @param world мир;
     * @param state true — метель активна, false — нет;
     * @param time  остаток времени в тиках.
     */
    public static void setBlizzard(World world, boolean state, int time) {
        initWorldCache();
        try {
            if (FIELD_WORLD_IS_BLIZZARD != null) FIELD_WORLD_IS_BLIZZARD.setBoolean(world, state);
            if (FIELD_WORLD_BLIZZARD_TIME != null) FIELD_WORLD_BLIZZARD_TIME.setInt(world, time);
        } catch (Throwable t) { t.printStackTrace(); }
    }

    /**
     * @return остаток времени метели в World в тиках (0, если поля нет).
     */
    public static int getBlizzardTime(World world) {
        initWorldCache();
        if (FIELD_WORLD_BLIZZARD_TIME == null) return 0;
        try { return FIELD_WORLD_BLIZZARD_TIME.getInt(world); }
        catch (Throwable t) { t.printStackTrace(); return 0; }
    }

    /**
     * Устанавливает остаток времени метели в World.
     *
     * @param world мир;
     * @param time  новое значение в тиках.
     */
    public static void setBlizzardTime(World world, int time) {
        initWorldCache();
        if (FIELD_WORLD_BLIZZARD_TIME == null) return;
        try { FIELD_WORLD_BLIZZARD_TIME.setInt(world, time); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    /**
     * @return текущая визуальная сила метели (0.0 – 1.0).
     */
    public static float getBlizzardStrength(World world) {
        initWorldCache();
        if (FIELD_WORLD_BLIZZARD_STRENGTH == null) return 0.0F;
        try { return FIELD_WORLD_BLIZZARD_STRENGTH.getFloat(world); }
        catch (Throwable t) { t.printStackTrace(); return 0.0F; }
    }

    /**
     * Устанавливает визуальную силу метели в World.
     *
     * @param world    мир;
     * @param strength сила (0.0 – 1.0).
     */
    public static void setBlizzardStrength(World world, float strength) {
        initWorldCache();
        if (FIELD_WORLD_BLIZZARD_STRENGTH == null) return;
        try { FIELD_WORLD_BLIZZARD_STRENGTH.setFloat(world, strength); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    /**
     * @return предыдущая визуальная сила метели (для интерполяции на клиенте).
     */
    public static float getPrevBlizzardStrength(World world) {
        initWorldCache();
        if (FIELD_WORLD_PREV_BLIZZARD_STRENGTH == null) return 0.0F;
        try { return FIELD_WORLD_PREV_BLIZZARD_STRENGTH.getFloat(world); }
        catch (Throwable t) { t.printStackTrace(); return 0.0F; }
    }

    /**
     * Устанавливает предыдущую визуальную силу метели.
     *
     * @param world    мир;
     * @param strength предыдущее значение силы (0.0 – 1.0).
     */
    public static void setPrevBlizzardStrength(World world, float strength) {
        initWorldCache();
        if (FIELD_WORLD_PREV_BLIZZARD_STRENGTH == null) return;
        try { FIELD_WORLD_PREV_BLIZZARD_STRENGTH.setFloat(world, strength); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    // ==================== WorldInfo ====================

    /**
     * @return true, если в WorldInfo сохранён флаг активной метели.
     */
    public static boolean isBlizzardInfo(WorldInfo info) {
        initInfoCache();
        if (FIELD_INFO_BLIZZARD == null) return false;
        try { return FIELD_INFO_BLIZZARD.getBoolean(info); }
        catch (Throwable t) { t.printStackTrace(); return false; }
    }

    /**
     * Устанавливает флаг активности метели в WorldInfo.
     *
     * @param info  объект WorldInfo;
     * @param state true — метель активна, false — нет.
     */
    public static void setBlizzardInfo(WorldInfo info, boolean state) {
        initInfoCache();
        if (FIELD_INFO_BLIZZARD == null) return;
        try { FIELD_INFO_BLIZZARD.setBoolean(info, state); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    /**
     * @return сохранённый остаток времени метели в тиках (0, если поля нет).
     */
    public static int getBlizzardTimeInfo(WorldInfo info) {
        initInfoCache();
        if (FIELD_INFO_BLIZZARD_TIME == null) return 0;
        try { return FIELD_INFO_BLIZZARD_TIME.getInt(info); }
        catch (Throwable t) { t.printStackTrace(); return 0; }
    }

    /**
     * Устанавливает сохранённый остаток времени метели.
     *
     * @param info объект WorldInfo;
     * @param time новое значение в тиках.
     */
    public static void setBlizzardTimeInfo(WorldInfo info, int time) {
        initInfoCache();
        if (FIELD_INFO_BLIZZARD_TIME == null) return;
        try { FIELD_INFO_BLIZZARD_TIME.setInt(info, time); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    /**
     * @return сохранённая визуальная сила метели (0.0 – 1.0).
     */
    public static float getBlizzardStrengthInfo(WorldInfo info) {
        initInfoCache();
        if (FIELD_INFO_BLIZZARD_STRENGTH == null) return 0.0F;
        try { return FIELD_INFO_BLIZZARD_STRENGTH.getFloat(info); }
        catch (Throwable t) { t.printStackTrace(); return 0.0F; }
    }

    /**
     * Устанавливает сохранённую визуальную силу метели.
     *
     * @param info     объект WorldInfo;
     * @param strength значение силы (0.0 – 1.0).
     */
    public static void setBlizzardStrengthInfo(WorldInfo info, float strength) {
        initInfoCache();
        if (FIELD_INFO_BLIZZARD_STRENGTH == null) return;
        try { FIELD_INFO_BLIZZARD_STRENGTH.setFloat(info, strength); }
        catch (Throwable t) { t.printStackTrace(); }
    }

    // ==================== Хелперы ====================

    /**
     * Ищет поле в классе и его суперклассах.
     *
     * @param cls  класс, с которого начинать поиск;
     * @param name имя поля.
     * @return Field или null, если поле не найдено.
     */
    private static Field findField(Class<?> cls, String name) {
        Class<?> current = cls;
        while (current != null) {
            try { return current.getDeclaredField(name); }
            catch (NoSuchFieldException e) { current = current.getSuperclass(); }
        }
        return null;
    }

    /**
     * Сбрасывает кеш полей. Нужен только для тестов/отладки.
     */
    public static void resetCache() {
        worldCacheInitialized = false;
        infoCacheInitialized = false;
        FIELD_WORLD_IS_BLIZZARD = null;
        FIELD_WORLD_BLIZZARD_TIME = null;
        FIELD_WORLD_BLIZZARD_STRENGTH = null;
        FIELD_WORLD_PREV_BLIZZARD_STRENGTH = null;
        FIELD_INFO_BLIZZARD = null;
        FIELD_INFO_BLIZZARD_TIME = null;
        FIELD_INFO_BLIZZARD_STRENGTH = null;
    }
}