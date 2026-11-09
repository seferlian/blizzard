package com.blizzard.client;

import com.blizzard.weather.BlizzardReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Клиентский обработчик звука метели.
 *
 * Каждые SOUND_INTERVAL тиков (пока метель активна) проигрывает зацикленный
 * звук ветра с громкостью, пропорциональной blizzardStrength.
 *
 * Логика:
 *   - strength <= MIN_STRENGTH  → звук не играет, таймер сбрасывается;
 *   - иначе раз в SOUND_INTERVAL тиков играем звук с volume = f(strength).
 *
 * Так как в 1.12.2 нет API для изменения громкости уже играющего звука,
 * используется периодический перезапуск. Файл .ogg должен быть достаточно
 * длинным (5–10 с) и с плавными краями, чтобы перекрывать интервал без щелчков.
 */
@SideOnly(Side.CLIENT)
public class BlizzardSoundHandler {

    /** Минимальная сила метели, при которой вообще играем звук. */
    private static final float MIN_STRENGTH = 0.05F;

    /** Интервал между перезапусками звука в тиках (40 тиков ≈ 2 с). */
    private static final int SOUND_INTERVAL = 40;

    /** Таймер до следующего воспроизведения. */
    private int soundTimer = 0;

    /** Ссылка на зарегистрированный SoundEvent (заполняется из главного класса мода). */
    private static SoundEvent blizzardWindSound;

    /**
     * Устанавливает SoundEvent, который будет проигрываться.
     * Вызывать из preInit/init главного класса мода на клиенте.
     *
     * @param sound зарегистрированный SoundEvent метели.
     */
    public static void setSound(SoundEvent sound) {
        blizzardWindSound = sound;
    }

    /**
     * Тик клиента: управляет воспроизведением звука метели.
     *
     * @param event событие тика клиента.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();
        if (mc.world == null || mc.player == null) {
            soundTimer = 0;
            return;
        }

        // Нет зарегистрированного звука — выходим
        if (blizzardWindSound == null) return;

        float strength = BlizzardReflection.getBlizzardStrength(mc.world);

        // Метель слабая/неактивна — не играем
        if (strength <= MIN_STRENGTH) {
            soundTimer = 0;
            return;
        }

        soundTimer++;
        if (soundTimer < SOUND_INTERVAL) return;
        soundTimer = 0;

        // Громкость: от 0.1 при пороге до 1.0 при strength=1
        float volume = 0.1F + (strength - MIN_STRENGTH) / (1.0F - MIN_STRENGTH) * 0.9F;
        if (volume > 1.0F) volume = 1.0F;

        // Небольшая вариация тона, чтобы перезапуски не звучали одинаково
        float pitch = 0.9F + mc.world.rand.nextFloat() * 0.2F;

        mc.player.playSound(blizzardWindSound, SoundCategory.WEATHER, volume, pitch);
    }
}