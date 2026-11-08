package com.blizzard.weather;

/**
 * Маркерный интерфейс для IDE.
 *
 * ASM-трансформер (BlizzardClassTransformer.patchWorldInfo) добавляет этот интерфейс
 * в класс net.minecraft.world.storage.WorldInfo, а также генерирует реализацию
 * всех методов ниже. В рантайме WorldInfo реально реализует этот интерфейс,
 * благодаря чему код компилируется без ошибок и IDE видит методы.
 *
 * Прямых приведений к этому интерфейсу в моде нет — доступ к полям идёт
 * через BlizzardReflection (рефлексия с кешированием Field-объектов).
 */
public interface IWorldInfoBlizzard {

    /**
     * @return true, если в данный момент в мире идёт снежная метель.
     */
    boolean isBlizzard();

    /**
     * @return остаток времени метели в тиках.
     */
    int getBlizzardTime();

    /**
     * Устанавливает флаг активности метели.
     *
     * @param b true — метель активна, false — нет.
     */
    void setBlizzard(boolean b);

    /**
     * Устанавливает остаток времени метели.
     *
     * @param t новое значение таймера в тиках.
     */
    void setBlizzardTime(int t);
}