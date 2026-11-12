package com.blizzard.client;

import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Клиентская интерполяция холода между пакетами.
 *
 * Сервер шлёт cold раз в секунду (плюс при резких изменениях).
 * Здесь — линейная интерполяция по реальному интервалу между пакетами,
 * чтобы HUD не дёргался ступеньками.
 *
 * При cold = 0 — мгновенный сброс, шкала исчезает сразу.
 */
@SideOnly(Side.CLIENT)
public class BlizzardClientCold {

    private static float prevCold;
    private static float currCold;
    private static float smoothCold;

    private static long prevPacketTick = Long.MIN_VALUE;
    private static long lastPacketTick = Long.MIN_VALUE;

    private static boolean hasData = false;

    public BlizzardClientCold() {}

    public static void onPacket(float cold) {
        float clamped = Math.max(0f, Math.min(1f, cold));

        // Ноль — мгновенный сброс, без интерполяции
        if (clamped <= 0.001f) {
            reset();
            return;
        }

        if (!hasData) {
            prevCold = currCold = smoothCold = clamped;
            hasData = true;
            prevPacketTick = Long.MIN_VALUE;
        } else {
            prevCold = smoothCold;
            currCold = clamped;
            prevPacketTick = lastPacketTick;
        }
        lastPacketTick = currentTick();
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!hasData) return;
        if (Minecraft.getMinecraft().world == null) return;

        float t = progress();
        smoothCold = prevCold + (currCold - prevCold) * t;
    }

    @SubscribeEvent
    public void onDimensionChange(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.player == null || !event.player.world.isRemote) return;
        reset();
    }

    public static float getCold() {
        return hasData ? smoothCold : 0f;
    }

    public static void reset() {
        hasData = false;
        prevCold = currCold = smoothCold = 0f;
        prevPacketTick = Long.MIN_VALUE;
        lastPacketTick = Long.MIN_VALUE;
    }

    private static float progress() {
        if (lastPacketTick == Long.MIN_VALUE) return 1f;
        if (prevPacketTick == Long.MIN_VALUE) return 1f;

        long now = currentTick();
        long total = lastPacketTick - prevPacketTick;
        long elapsed = now - lastPacketTick;

        if (total <= 0) return 1f;
        float t = elapsed / (float) total;
        return Math.min(1f, Math.max(0f, t));
    }

    private static long currentTick() {
        Minecraft mc = Minecraft.getMinecraft();
        return mc.world == null ? 0L : mc.world.getTotalWorldTime();
    }
}