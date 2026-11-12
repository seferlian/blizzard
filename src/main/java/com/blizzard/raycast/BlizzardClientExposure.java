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
    }

    public static void reset() {
        targetSky = targetOpen = 1f;
        currentSky = currentOpen = 1f;
        initialized = false;
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

        // Коэффициент сглаживания (0.1 = 10% за кадр)
        float alpha = 0.1f;

        currentSky += (targetSky - currentSky) * alpha;
        currentOpen += (targetOpen - currentOpen) * alpha;
    }
}