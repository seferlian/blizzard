package com.blizzard.config;

import com.blizzard.network.client.VisualSyncPacket;

/**
 * Клиентский «оверлей» визуальных настроек, присланных сервером.
 *
 * Пока serverSynced == false — геттеры возвращают локальный ConfigHandler.visual.
 * После приёма пакета — возвращают значения сервера.
 * Флаг сбрасывается при отключении от сервера
 * (см. BlizzardClientPacketHandler.onDisconnect).
 *
 */
public class ClientVisualConfig {

    private static boolean serverSynced = false;

    private static double fogCurve;
    private static double fogShelterFloor;
    private static double fogStartMin;
    private static double fogStartMax;
    private static double fogEndMin;
    private static double fogEndMax;
    private static double skyWhiten;
    private static double skyDesaturate;
    private static double skyGray;
    private static double skyInterpEnd;
    private static double skyInterpCurve;

    private static int snowRadius;
    private static int snowQuadsPerBlock;
    private static double snowSize;
    private static double snowWindMultiplier;
    private static int snowFallCycle;

    /** Вызывается из VisualSyncPacket.Handler (уже в главном потоке). */
    public static void apply(VisualSyncPacket p) {
        fogCurve = p.fogCurve;
        fogShelterFloor = p.fogShelterFloor;
        fogStartMin = p.fogStartMin;
        fogStartMax = p.fogStartMax;
        fogEndMin = p.fogEndMin;
        fogEndMax = p.fogEndMax;
        skyWhiten = p.skyWhiten;
        skyDesaturate = p.skyDesaturate;
        skyGray = p.skyGray;
        skyInterpEnd = p.skyInterpEnd;
        skyInterpCurve = p.skyInterpCurve;

        snowRadius = p.snowRadius;
        snowQuadsPerBlock = p.snowQuadsPerBlock;
        snowSize = p.snowSize;
        snowWindMultiplier = p.snowWindMultiplier;
        snowFallCycle = p.snowFallCycle;

        serverSynced = true;
    }

    /** Сброс при отключении от сервера/выходе из мира. */
    public static void reset() {
        serverSynced = false;
    }

    public static boolean isServerSynced() {
        return serverSynced;
    }

    // ===== Геттеры =====
    public static double getFogCurve() {
        return serverSynced ? fogCurve : ConfigHandler.visual.fogCurve;
    }

    public static double getFogShelterFloor() {
        return serverSynced ? fogShelterFloor : ConfigHandler.visual.fogShelterFloor;
    }

    public static double getFogStartMin() {
        return serverSynced ? fogStartMin : ConfigHandler.visual.fogStartMin;
    }

    public static double getFogStartMax() {
        return serverSynced ? fogStartMax : ConfigHandler.visual.fogStartMax;
    }

    public static double getFogEndMin() {
        return serverSynced ? fogEndMin : ConfigHandler.visual.fogEndMin;
    }

    public static double getFogEndMax() {
        return serverSynced ? fogEndMax : ConfigHandler.visual.fogEndMax;
    }

    public static double getSkyWhiten() {
        return serverSynced ? skyWhiten : ConfigHandler.visual.skyWhiten;
    }

    public static double getSkyDesaturate() {
        return serverSynced ? skyDesaturate : ConfigHandler.visual.skyDesaturate;
    }

    public static double getSkyGray() {
        return serverSynced ? skyGray : ConfigHandler.visual.skyGray;
    }

    public static double getSkyInterpEnd() {
        return serverSynced ? skyInterpEnd : ConfigHandler.visual.skyInterpEnd;
    }

    public static double getSkyInterpCurve() {
        return serverSynced ? skyInterpCurve : ConfigHandler.visual.skyInterpCurve;
    }

    public static int getSnowRadius() {
        return serverSynced ? snowRadius : ConfigHandler.visual.snowRadius;
    }

    public static int getSnowQuadsPerBlock() {
        return serverSynced ? snowQuadsPerBlock : ConfigHandler.visual.snowQuadsPerBlock;
    }

    public static double getSnowSize() {
        return serverSynced ? snowSize : ConfigHandler.visual.snowSize;
    }

    public static double getSnowWindMultiplier() {
        return serverSynced ? snowWindMultiplier : ConfigHandler.visual.snowWindMultiplier;
    }

    public static int getSnowFallCycle() {
        return serverSynced ? snowFallCycle : ConfigHandler.visual.snowFallCycle;
    }
}