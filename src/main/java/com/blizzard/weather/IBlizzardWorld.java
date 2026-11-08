package com.blizzard.weather;

/**
 * Маркерный интерфейс для IDE.
 *
 * ASM-трансформер (BlizzardClassTransformer.addBlizzardFieldsAndMethods) добавляет
 * этот интерфейс в классы:
 *   net.minecraft.world.World
 *   net.minecraft.world.WorldServer
 *   net.minecraft.client.multiplayer.WorldClient
 *
 * В рантайме World (и его наследники) реально реализуют этот интерфейс,
 * благодаря чему IDE видит методы и код компилируется без ошибок.
 *
 * Прямых приведений к этому интерфейсу в моде нет — доступ к полям идёт
 * через BlizzardReflection (рефлексия с кешированием Field-объектов).
 */
public interface IBlizzardWorld {

    /**
     * @return true, если в данный момент в мире идёт снежная метель.
     */
    boolean isBlizzard();

    /**
     * @return остаток времени метели в тиках.
     */
    int getBlizzardTime();

    /**
     * Устанавливает остаток времени метели.
     *
     * @param time новое значение таймера в тиках.
     */
    void setBlizzardTime(int time);

    /**
     * Устанавливает флаг активности метели и остаток времени.
     *
     * @param state true — метель активна, false — нет;
     * @param time  новое значение таймера в тиках.
     */
    void setBlizzard(boolean state, int time);

    /**
     * @return текущая визуальная сила метели (0.0 – 1.0).
     */
    float getBlizzardStrength();

    /**
     * Устанавливает текущую визуальную силу метели.
     *
     * @param strength значение силы (0.0 – 1.0).
     */
    void setBlizzardStrength(float strength);
}