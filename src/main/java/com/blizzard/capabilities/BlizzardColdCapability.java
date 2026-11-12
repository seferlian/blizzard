package com.blizzard.capabilities;

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
    public static final float EXPOSURE_THRESHOLD = 0.05f;

    // ======================== ПАРАМЕТРЫ (КАНДИДАТЫ В КОНФИГ) ========================

    /**
     * Базовая скорость накопления холода за 1 тик (без брони).
     * 1.0f / 1200.0f = полное заполнение шкалы за 60 секунд (1 минуту).
     */
    private static final float COLD_GAIN_PER_TICK = 1.0f / (60 * 20);

    /**
     * Насколько снижается скорость накопления за КАЖДУЮ надетую деталь брони.
     * 0.15f = каждая деталь замедляет накопление на 15%.
     * Если надеть все 4 детали, замедление будет 60% (скорость упадёт до 40%).
     * При 0.25f (4 детали = 100%) броня будет давать полную защиту (холод не растёт).
     */
    private static final float ARMOR_SLOT_SLOWDOWN = 0.15f;

    /**
     * Скорость убывания при отсутствии метели (полный сброс за ~60 секунд).
     */
    private static final float DRAIN_PER_TICK = 1.0f / (60 * 20);

    /** Интервал синхронизации с клиентом. */
    private static final int SYNC_INTERVAL = 20;

    // ======================== УРОН (КАНДИДАТЫ В КОНФИГ) ========================

    /** Cold, при котором начинается урон. */
    private static final float DAMAGE_START_COLD = 0.2f;

    /** Cold, при котором достигается максимум. */
    private static final float DAMAGE_END_COLD = 1.0f;

    /** Урон за удар при cold = DAMAGE_START_COLD. */
    private static final float DAMAGE_MIN = 0.5f;

    /** Урон за удар при cold = DAMAGE_END_COLD. */
    private static final float DAMAGE_MAX = 8.0f;

    /** Интервал между ударами в тиках при cold = DAMAGE_START_COLD. */
    private static final int INTERVAL_MAX = 100;

    /** Интервал между ударами в тиках при cold = DAMAGE_END_COLD. */
    private static final int INTERVAL_MIN = 10;

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
            this.cold = Math.max(0f, this.cold - DRAIN_PER_TICK);
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

            if (fullStrength && exposure > EXPOSURE_THRESHOLD) {
                float norm = (exposure - EXPOSURE_THRESHOLD) / (1f - EXPOSURE_THRESHOLD);

                // Считаем суммарный штраф от всех надетых деталей брони
                int armorPieces = countArmorPieces(player);
                float armorSlowdown = Math.min(1.0f, armorPieces * ARMOR_SLOT_SLOWDOWN);
                float multiplier = 1.0f - armorSlowdown;

                this.cold += (COLD_GAIN_PER_TICK * norm) * multiplier;
            } else {
                this.cold -= DRAIN_PER_TICK;
            }
        } else {
            this.cold -= DRAIN_PER_TICK;
        }

        this.cold = clamp01(this.cold);

        // Урон (только во время метели и только если cold дошёл до порога)
        if (blizzard && this.cold >= DAMAGE_START_COLD) {
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
        float t = (this.cold - DAMAGE_START_COLD) / (DAMAGE_END_COLD - DAMAGE_START_COLD);
        if (t < 0f) t = 0f;
        if (t > 1f) t = 1f;

        // Считаем интервал
        int interval;
        if (inWater) {
            // В воде интервал всегда минимальный (максимальная частота)
            interval = INTERVAL_MIN;
        } else {
            interval = (int) (INTERVAL_MAX + (INTERVAL_MIN - INTERVAL_MAX) * t);
        }

        float damage = DAMAGE_MIN + (DAMAGE_MAX - DAMAGE_MIN) * t;

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