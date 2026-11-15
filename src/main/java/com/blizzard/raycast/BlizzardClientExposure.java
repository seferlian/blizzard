package com.blizzard.raycast;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Клиентская обработка exposure.
 *
 * Сглаживание полностью отвязано от тиков сервера.
 * Цель (target) обновляется, когда приходит пакет.
 * Текущее значение (current) плавно догоняет цель каждый кадр.
 */
@SideOnly(Side.CLIENT)
public final class BlizzardClientExposure {

    // Целевые значения (из пакета)
    private static float targetSky = 1f;
    private static float targetOpen = 1f;

    // Текущие сглаженные значения
    private static float currentSky = 1f;
    private static float currentOpen = 1f;

    private static boolean initialized = false;
    private static long lastSmoothNanos = 0;

    public BlizzardClientExposure() {}

    public static void onPacket(float sky, float open) {
        targetSky = sky;
        targetOpen = open;

        if (!initialized) {
            currentSky = sky;
            currentOpen = open;
            initialized = true;
        }
    }

    public static void snapTo(float sky, float open) {
        targetSky = currentSky = sky;
        targetOpen = currentOpen = open;
        initialized = true;
        lastSmoothNanos = 0;   // следующий smooth() не сделает скачок dt
    }

    public static void reset() {
        targetSky = targetOpen = 1f;
        currentSky = currentOpen = 1f;
        initialized = false;
        lastSmoothNanos = 0;
    }

    // ===== Чтение =====

    public static float getSkyExposure() {
        smooth();
        return currentSky;
    }

    public static float getOpenness() {
        smooth();
        return currentOpen;
    }

    public static float getExposure() {
        smooth();
        return 1f - (1f - currentSky) * (1f - currentOpen);
    }

    // ===== Сглаживание =====

    /**
     * Вызывается каждый кадр (при чтении).
     * Двигает current к target на 10% в каждом кадре.
     * При 60 FPS это сгладит переход за ~0.5 секунды.
     */
    private static void smooth() {
        if (!initialized) return;

        long now = System.nanoTime();
        if (lastSmoothNanos == 0) {
            lastSmoothNanos = now;
            return;   // первый вызов — только запоминаем время
        }
        float dt = (now - lastSmoothNanos) / 1_000_000_000f; // секунды
        lastSmoothNanos = now;

        // Защита от больших dt (пауза, лаг, alt-tab)
        if (dt > 0.5f) dt = 0.5f;

        // Экспоненциальное сглаживание, независимое от FPS.
        // halfLife = 0.15 с — за 0.15 с закрывается половина разрыва.
        float halfLife = 0.15f;
        float alpha = 1f - (float) Math.pow(0.5, dt / halfLife);

        currentSky  += (targetSky  - currentSky)  * alpha;
        currentOpen += (targetOpen - currentOpen) * alpha;
    }

}