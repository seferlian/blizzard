package com.blizzard.capabilities;

import com.blizzard.config.ConfigHandler;
import com.blizzard.damage.BlizzardDamageSource;
import com.blizzard.raycast.BlizzardExposure;
import com.blizzard.weather.BlizzardManager;
import com.blizzard.weather.BlizzardReflection;
import com.blizzard.network.client.ClientPacketHandlerCold;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Накопленный холод у игрока во время снежной метели.
 *
 * МЕХАНИКА:
 * - Растёт, пока игрок «открыт» метели (exposure > 5%) и метель активна.
 * - Скорость роста задается в количестве за тик (легко переносится в конфиг).
 * - Каждая надетая деталь брони замедляет накопление на заданный процент.
 * - Медленно убывает, если exposure < 5% или метель закончилась.
 * - cold = 0..1. При 0 шкала в HUD не рисуется.
 * - Сбрасывается после смерти (PlayerEvent.Clone с isWasDeath).
 *
 * Только для игроков. Только в Overworld. Считается на сервере,
 * клиент получает готовое значение пакетом.
 */
public class BlizzardColdCapability {

    /** Порог exposure, ниже которого холод убывает, а не растёт. */
    private static float getExposureThreshold() { return (float) ConfigHandler.cold.exposureThreshold; }


    /**
     * Базовая скорость накопления холода за 1 тик (без брони).
     * 1.0f / 1200.0f = полное заполнение шкалы за 60 секунд (1 минуту).
     */
    private static float getColdGainPerTick() { return (float) ConfigHandler.cold.coldGainPerTick; }

    /**
     * Насколько снижается скорость накопления за КАЖДУЮ надетую деталь брони.
     * 0.15f = каждая деталь замедляет накопление на 15%.
     * Если надеть все 4 детали, замедление будет 60% (скорость упадёт до 40%).
     * При 0.25f (4 детали = 100%) броня будет давать полную защиту (холод не растёт).
     */
    private static float getArmorSlotSlowdown() { return (float) ConfigHandler.cold.armorSlotSlowdown; }

    /**
     * Скорость убывания при отсутствии метели (полный сброс за ~60 секунд).
     */
    private static float getDrainPerTick() { return (float) ConfigHandler.cold.drainPerTick; }

    /** Интервал синхронизации с клиентом. */
    private static final int SYNC_INTERVAL = 20;

    // ======================== УРОН (КАНДИДАТЫ В КОНФИГ) ========================

    /** Cold, при котором начинается урон. */
    private static float getDamageStartCold() { return (float) ConfigHandler.blizzard.damageStartCold; }

    /** Cold, при котором достигается максимум. */
    private static float getDamageEndCold() { return (float) ConfigHandler.blizzard.damageEndCold; }

    /** Урон за удар при cold = DAMAGE_START_COLD. */
    private static float getDamageMin() { return (float) ConfigHandler.blizzard.damageMin; }

    /** Урон за удар при cold = DAMAGE_END_COLD. */
    private static float getDamageMax() { return (float) ConfigHandler.blizzard.damageMax; }

    /** Интервал между ударами в тиках при cold = DAMAGE_START_COLD. */
    private static int getIntervalMax() { return ConfigHandler.blizzard.intervalMax; }

    /** Интервал между ударами в тиках при cold = DAMAGE_END_COLD. */
    private static int getIntervalMin() { return ConfigHandler.blizzard.intervalMin; }

    /** Множитель урона в воде*/
    private static float getWaterMultiplier() { return (float) ConfigHandler.cold.waterMultiplier; }

    /** Интервал между ударами в тиках, когда игрок в воде. */
    private static int getIntervalWater() { return ConfigHandler.cold.intervalWater; }
    // ======================== ПОЛЯ ========================

    /** Тик, на котором последний раз наносили урон. */
    private long lastDamageTick = 0;

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
        if (this.cold <= 0f) {
            this.lastDamageTick = 0;
        }
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

        // Только выживание и приключение
        if (player.isCreative() || player.isSpectator()) {
            if (this.cold != 0f) {
                this.cold = 0f;
                // немедленно синхронизируем клиент
                BlizzardManager.NETWORK.sendTo(
                        new ClientPacketHandlerCold.Packet(0f),
                        player
                );
            }
            return;
        }

        // Вне Overworld холод не накапливается, но и не сбрасывается мгновенно
        if (player.world.provider.getDimension() != 0) {
            this.cold = Math.max(0f, this.cold - getDrainPerTick());
            this.scheduleSync(player);
            this.tick++;
            return;
        }

        boolean blizzard = BlizzardReflection.isBlizzard(player.world);

        if (blizzard) {
            // Гарантируем, что exposure посчитан (tickWeather мог не успеть)
            BlizzardExposure.tick(player, player.world);

            float strength = BlizzardReflection.getBlizzardStrength(player.world);
            float exposure = BlizzardExposure.getExposure(player);

            // Холод накапливается только при полной силе метели
            // и при достаточной открытости
            boolean fullStrength = strength >= 0.999f;

            if (fullStrength && exposure > getExposureThreshold()) {
                float norm = (exposure - getExposureThreshold()) / (1f - getExposureThreshold());

                // 1. Броня
                int armorPieces = countArmorPieces(player);
                float armorSlowdown = Math.min(1.0f, armorPieces * getArmorSlotSlowdown());
                float multiplier = 1.0f - armorSlowdown;

                // 2. Тепло от огня/факелов
                float heatProtection = BlizzardExposure.getHeatProtection(player);
                multiplier *= (1.0f - heatProtection);

                // 3. Вода (если применяется)
                if (player.isInWater()) {
                    multiplier *= (float) getWaterMultiplier();
                }

                this.cold += (getColdGainPerTick() * norm) * multiplier;

            } else {
                this.cold -= getDrainPerTick();
            }
        } else {
            this.cold -= getDrainPerTick();
        }

        this.cold = clamp01(this.cold);

        // Урон (только во время метели и только если cold дошёл до порога)
        if (blizzard && this.cold >= getDamageStartCold()) {
            this.applyColdDamage(player);
        }

        this.scheduleSync(player);
        this.tick++;
    }

    /**
     * Считает количество надетых деталей брони (0..4).
     * Проверяет только слоты шлема, нагрудника, штанов и ботинок.
     *
     * В 1.12.2 слоты брони в инвентаре игрока:
     * 36 — ботинки, 37 — штаны, 38 — нагрудник, 39 — шлем.
     */
    private int countArmorPieces(EntityPlayerMP player) {
        int count = 0;
        for (int i = 36; i <= 39; i++) {
            ItemStack stack = player.inventory.getStackInSlot(i);
            if (!stack.isEmpty()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Наносит урон холодом.
     *
     * Шкала линейная:
     *   cold = DAMAGE_START_COLD → interval = INTERVAL_MAX, damage = DAMAGE_MIN
     *   cold = DAMAGE_END_COLD   → interval = INTERVAL_MIN, damage = DAMAGE_MAX
     *
     * ВАЖНО: Если игрок находится в воде, частота ударов всегда максимальная
     * (interval = INTERVAL_MIN), независимо от текущего уровня холода.
     * Это имитирует быстрое переохлаждение в мокрой одежде.
     */
    private void applyColdDamage(EntityPlayerMP player) {
        // Проверяем, находится ли игрок в воде
        boolean inWater = player.isInWater();

        // t ∈ [0, 1] — насколько cold продвинулся от начала до конца шкалы
        float t = (this.cold - getDamageStartCold()) / (getDamageEndCold() - getDamageStartCold());
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;

        // Считаем интервал
        int interval;
        if (inWater) {
            // В воде интервал берется из конфига (по умолчанию 10 тиков)
            interval = getIntervalWater();
        } else {
            interval = (int) (getIntervalMax() + (getIntervalMin() - getIntervalMax()) * t);
        }

        float damage = getDamageMin() + (getDamageMax() - getDamageMin()) * t;

        long now = player.world.getTotalWorldTime();
        if (now - this.lastDamageTick >= interval) {
            this.lastDamageTick = now;
            player.attackEntityFrom(BlizzardDamageSource.COLD, damage);
        }
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