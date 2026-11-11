package com.blizzard.capabilities;

import com.blizzard.raycast.BlizzardExposure;
import com.blizzard.weather.BlizzardManager;
import com.blizzard.weather.BlizzardReflection;
import com.blizzard.network.client.ClientPacketHandlerCold;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Накопленный холод у игрока во время снежной метели.
 *
 * МЕХАНИКА:
 * - Растёт, пока игрок «открыт» метели (exposure > 5%) и метель активна.
 * - Медленно убывает, если exposure < 5% или метель закончилась.
 * - cold = 0..1. При 0 шкала в HUD не рисуется.
 * - Сбрасывается после смерти (PlayerEvent.Clone с isWasDeath).
 *
 * Только для игроков. Только в Overworld. Считается на сервере,
 * клиент получает готовое значение пакетом.
 */
public class BlizzardColdCapability {

    /** Порог exposure, ниже которого холод убывает, а не растёт. */
    public static final float EXPOSURE_THRESHOLD = 0.05f;

    /** Скорость роста при exposure = 1.0 (полный холод за ~30 секунд). */
    private static final float GAIN_PER_TICK = 1.0f / (30 * 20);

    /** Скорость убывания при exposure = 0 (полный сброс за ~60 секунд). */
    private static final float DRAIN_PER_TICK = 1.0f / (60 * 20);

    /** Интервал синхронизации с клиентом. */
    private static final int SYNC_INTERVAL = 20;

    /** Накопленный холод 0..1. */
    private float cold;

    /** Случайное смещение тика для десинхронизации. */
    private final int tickOffset;

    private int tick;

    public BlizzardColdCapability() {
        this.cold = 0f;
        this.tick = 0;
        this.tickOffset = ThreadLocalRandom.current().nextInt(SYNC_INTERVAL);
    }

    // ======================== ГЕТТЕРЫ ========================

    public float getCold() {
        return this.cold;
    }

    public void setCold(float value) {
        this.cold = clamp01(value);
    }

    // ======================== ТИК ========================

    /**
     * Вызывается каждый тик для серверного игрока.
     *
     * @param player серверный игрок.
     */
    public void update(EntityPlayerMP player) {
        if (player.world == null || player.world.isRemote) return;
        if (player.isDead || player.isSpectator()) return;

        // Вне Overworld холод не накапливается, но и не сбрасывается мгновенно
        if (player.world.provider.getDimension() != 0) {
            this.cold = Math.max(0f, this.cold - DRAIN_PER_TICK);
            this.scheduleSync(player);
            this.tick++;
            return;
        }

        boolean blizzard = BlizzardReflection.isBlizzard(player.world);

        if (blizzard) {
            // Гарантируем, что exposure посчитан (tickWeather мог не успеть)
            BlizzardExposure.tick(player, player.world);

            float exposure = BlizzardExposure.getExposure(player);
            if (exposure > EXPOSURE_THRESHOLD) {
                float norm = (exposure - EXPOSURE_THRESHOLD) / (1f - EXPOSURE_THRESHOLD);
                this.cold += GAIN_PER_TICK * norm;
            } else {
                this.cold -= DRAIN_PER_TICK;
            }
        } else {
            this.cold -= DRAIN_PER_TICK;
        }

        this.cold = clamp01(this.cold);
        this.scheduleSync(player);
        this.tick++;
    }

    /** Планирует отправку пакета раз в SYNC_INTERVAL тиков (со смещением). */
    private void scheduleSync(EntityPlayerMP player) {
        if ((this.tick + this.tickOffset) % SYNC_INTERVAL != 0) return;

        BlizzardManager.NETWORK.sendTo(
                new ClientPacketHandlerCold.Packet(this.cold),
                player
        );
    }

    private static float clamp01(float v) {
        if (v < 0f) return 0f;
        if (v > 1f) return 1f;
        return v;
    }

    // ======================== NBT ========================

    public NBTTagCompound serializeNBT() {
        NBTTagCompound tag = new NBTTagCompound();
        tag.setFloat("cold", this.cold);
        return tag;
    }

    public void deserializeNBT(NBTTagCompound tag) {
        this.cold = clamp01(tag.getFloat("cold"));
    }
}