package com.blizzard.raycast;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Клиентская обработка exposure:
 *  1) onPacket — принимает серверное значение раз в 4 тика (или при изменении);
 *  2) tick     — раз в клиентский тик делает EMA поверх линейной интерполяции
 *                между последним и предыдущим пакетом;
 *  3) snapTo   — мгновенный сброс при телепорте/смене мира.
 *
 * Клиент НЕ делает собственных рейкастов. Единственный источник — сервер.
 */
public final class BlizzardClientExposure {

    private static final int   PACKET_INTERVAL_TICKS = 4;
    private static final float EMA = 0.2f;

    private static float prevSky,  prevOpen;
    private static float currSky,  currOpen;
    private static float smoothSky, smoothOpen;

    private static long    lastPacketTick = Long.MIN_VALUE;
    private static boolean hasData = false;
    private static boolean initialized = false;

    public BlizzardClientExposure() {}

    // ===== Пакет =====

    public static void onPacket(float sky, float open) {
        prevSky  = hasData ? interpolateSky()  : sky;
        prevOpen = hasData ? interpolateOpen() : open;
        currSky  = sky;
        currOpen = open;
        lastPacketTick = currentTick();
        hasData = true;

        if (!initialized) {
            smoothSky  = sky;
            smoothOpen = open;
            initialized = true;
        }
    }

    // ===== Сброс при телепорте/смене мира =====

    public static void snapTo(float sky, float open) {
        prevSky = currSky = smoothSky = sky;
        prevOpen = currOpen = smoothOpen = open;
        lastPacketTick = currentTick();
        hasData = true;
        initialized = true;
    }

    public static void reset() {
        hasData = false;
        initialized = false;
        prevSky = prevOpen = currSky = currOpen = smoothSky = smoothOpen = 0f;
        lastPacketTick = Long.MIN_VALUE;
    }

    // ===== Чтение =====

    public static float getSkyExposure() {
        return initialized ? smoothSky : 1f;
    }

    public static float getOpenness() {
        return initialized ? smoothOpen : 1f;
    }

    /**
     * Единая величина «насколько метель достаёт до игрока».
     * Та же формула, что на сервере: 1 - (1 - sky) * (1 - open).
     *
     * @return 0..1 — множитель силы метели.
     */
    public static float getExposure() {
        float sky  = getSkyExposure();
        float open = getOpenness();
        return 1f - (1f - sky) * (1f - open);
    }

    // ===== Тик клиента =====

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!hasData) return;
        if (Minecraft.getMinecraft().world == null) return;

        float targetSky  = interpolateSky();
        float targetOpen = interpolateOpen();

        smoothSky  = smoothSky  * (1f - EMA) + targetSky  * EMA;
        smoothOpen = smoothOpen * (1f - EMA) + targetOpen * EMA;
    }

    /**
     * При смене измерения сбрасываем сглаженное состояние.
     * Иначе после телепорта в другой мир первые секунды визуал будет
     * интерполировать от старых значений (из другого мира) — артефакт.
     *
     * Следующий пакет (из нового мира) будет принят как есть, без интерполяции.
     *
     * @param event событие смены измерения.
     */
    @SubscribeEvent
    public void onDimensionChange(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent event) {
        // Обрабатываем только клиентского игрока
        if (event.player == null || !event.player.world.isRemote) return;
        reset();
    }

    // ===== Внутреннее =====

    private static float interpolateSky()  { return lerp(prevSky,  currSky,  progress()); }
    private static float interpolateOpen() { return lerp(prevOpen, currOpen, progress()); }

    private static float progress() {
        if (!hasData) return 1f;
        long elapsed = currentTick() - lastPacketTick;
        float t = elapsed / (float) PACKET_INTERVAL_TICKS;
        return Math.min(1f, Math.max(0f, t));
    }

    private static float lerp(float a, float b, float t) {
        return a + (b - a) * t;
    }

    private static long currentTick() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world == null ? 0L : mc.world.getTotalWorldTime();
    }
}