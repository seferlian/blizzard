package com.blizzard.client;

import net.minecraft.client.audio.ISound;
import net.minecraft.client.audio.MovingSound;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Зацикленный эмбиент метели.
 *
 * Один экземпляр живёт всё время метели; громкость плавно подтягивается
 * к targetVolume, который выставляет BlizzardSoundHandler по текущему
 * blizzardStrength.
 *
 * Почему громкость тикается вручную: в Minecraft 1.12.2 SoundManager
 * не вызывает update() у MovingSound, добавленного через
 * SoundHandler.playSound(...). Звук не попадает в мапу tickableSounds,
 * и update() не спамит. Поэтому громкость обновляется явно из
 * BlizzardSoundHandler.onClientTick через tickVolume(). Метод update()
 * оставлен пустым — он не используется.
 *
 * attenuationType = NONE — звук не затухает по расстоянию, это эмбиент
 * вокруг игрока, а не точечный источник.
 */
@SideOnly(Side.CLIENT)
public class BlizzardAmbientSound extends MovingSound {

    /** Целевая громкость, к которой плавно стремимся. 0..1. */
    private float targetVolume;

    /**
     * Скорость сглаживания (доля разницы за один тик).
     * 0.05 ≈ примерно 1 секунда до достижения цели при 20 TPS.
     */
    private static final float SMOOTHING = 0.05F;

    /**
     * Создаёт зацикленный эмбиент метели.
     *
     * @param sound зарегистрированный SoundEvent метели
     *              (например, Main.BLIZZARD_WIND).
     */
    public BlizzardAmbientSound(SoundEvent sound) {
        super(sound, SoundCategory.WEATHER);
        this.repeat = true;
        this.repeatDelay = 0;
        this.volume = 0.05F;     // стартуем с тишины, громкость вырастет в tickVolume()
        this.pitch = 1.0F;
        this.xPosF = 0.0F;
        this.yPosF = 0.0F;
        this.zPosF = 0.0F;
        // Эмбиент не должен затухать по расстоянию
        this.attenuationType = ISound.AttenuationType.NONE;
    }

    /**
     * Устанавливает целевую громкость. Фактическая громкость плавно
     * подтягивается к ней в tickVolume().
     *
     * @param v целевая громкость 0..1.
     */
    public void setTargetVolume(float v) {
        this.targetVolume = v;
    }

    /**
     * Возвращает текущую (сглаженную) громкость.
     * Используется BlizzardSoundHandler, чтобы понять, когда звук можно
     * остановить (громкость упала в ноль).
     *
     * @return текущая громкость 0..1.
     */
    public float getCurrentVolume() {
        return this.volume;
    }

    /**
     * Тикает громкость вручную. Вызывается из
     * BlizzardSoundHandler.onClientTick каждый клиентский тик, пока
     * экземпляр жив.
     *
     * Плавно подтягивает volume к targetVolume с коэффициентом SMOOTHING.
     * Когда разница становится меньше 0.001, громкость фиксируется на
     * целевой — чтобы не было бесконечного «дрожания» вокруг цели.
     */
    public void tickVolume() {
        this.volume += (targetVolume - this.volume) * SMOOTHING;
        if (Math.abs(targetVolume - this.volume) < 0.001F) {
            this.volume = targetVolume;
        }
    }


    /**
     * Устанавливает текущую громкость звука напрямую (не цель).
     * Нужен, потому что поле volume в PositionedSound — protected,
     * и снаружи класса его не изменить.
     *
     * @param v громкость 0..1.
     */
    public void setInitialVolume(float v) {
        this.volume = Math.max(0.0F, Math.min(1.0F, v));
    }

    /**
     * Не используется.
     *
     * В 1.12.2 SoundManager не тикает MovingSound, добавленный через
     * playSound, поэтому update() не вызывается. Громкость обновляется
     * через tickVolume() из BlizzardSoundHandler.
     */
    @Override
    public void update() {
        // Намеренно пусто — см. javadoc класса и tickVolume().
    }
}