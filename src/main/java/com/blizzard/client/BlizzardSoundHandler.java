package com.blizzard.client;

import com.blizzard.weather.BlizzardReflection;
import net.minecraft.client.Minecraft;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Клиентский обработчик звука метели.
 *
 * Держит один экземпляр BlizzardAmbientSound на всё время метели и каждый
 * клиентский тик:
 *   1) читает blizzardStrength из клиентского мира через
 *      BlizzardReflection.getBlizzardStrength;
 *   2) пересчитывает целевую громкость по нелинейной (квадратичной) кривой —
 *      чтобы на малых strength звук был почти неслышен, а на больших —
 *      заметно громче;
 *   3) создаёт эмбиент, если его ещё нет и громкость должна быть > 0;
 *   4) обновляет целевую громкость и вручную тикает громкость через
 *      BlizzardAmbientSound.tickVolume();
 *   5) останавливает эмбиент, когда целевая громкость 0 и фактическая
 *      громкость уже почти нулевая.
 *
 * Регистрируется только на клиенте (см. Main.init()).
 */
@SideOnly(Side.CLIENT)
public class BlizzardSoundHandler {

    /** Минимальная сила метели, при которой вообще играем звук. */
    private static final float MIN_STRENGTH = 0.05F;

    /**
     * Максимальная громкость эмбиента. Даже при strength = 1.0 не ставим 1.0,
     * чтобы файл не «орал» — 0.7 обычно достаточно для эмбиента.
     */
    private static final float MAX_VOLUME = 0.7F;

    /**
     * Зарегистрированный SoundEvent метели.
     * Заполняется из Main.init() через setSound(SoundEvent).
     */
    private static SoundEvent blizzardWindSound;

    /** Единственный экземпляр эмбиента, пока метель активна. */
    private BlizzardAmbientSound ambient;

    /**
     * Устанавливает SoundEvent, который будет проигрываться.
     * Вызывать из Main.init() на клиенте, после регистрации звука
     * в ForgeRegistries.SOUND_EVENTS.
     *
     * @param sound зарегистрированный SoundEvent метели.
     */
    public static void setSound(SoundEvent sound) {
        blizzardWindSound = sound;
    }

    /**
     * Тик клиента: управляет воспроизведением звука метели.
     *
     * Работает только в фазе TickEvent.Phase.END, чтобы состояние мира
     * на этот тик уже было обновлено (включая blizzardStrength, пришедший
     * пакетом).
     *
     * @param event событие тика клиента.
     */
    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;

        Minecraft mc = Minecraft.getMinecraft();

        // Мир или игрок ещё не загружены — гасим эмбиент и выходим
        if (mc.world == null || mc.player == null) {
            stopAmbient(mc);
            return;
        }

        // Звук ещё не зарегистрирован — нечего играть
        if (blizzardWindSound == null) return;

        // Текущая сила метели на клиенте (0..1)
        float strength = BlizzardReflection.getBlizzardStrength(mc.world);

        // Нормализуем strength в 0..1 относительно порога MIN_STRENGTH
        float normalized = (strength - MIN_STRENGTH) / (1.0F - MIN_STRENGTH);
        if (normalized < 0.0F) normalized = 0.0F;
        if (normalized > 1.0F) normalized = 1.0F;

        // Кубическая кривая: тихо на малых strength, громко на больших.
        float targetVolume = normalized * normalized * normalized * MAX_VOLUME;

        // Эмбиента ещё нет — создаём, если громкость должна быть > 0
        if (ambient == null) {
            if (targetVolume > 0.001F) {
                ambient = new BlizzardAmbientSound(blizzardWindSound);
                ambient.setTargetVolume(targetVolume);
                ambient.setInitialVolume(targetVolume);  // стартовая громкость = целевой
                // isSoundPlaying-проверка, как в VoidRift — гарантирует,
                // что SoundManager зарегистрирует звук корректно
                if (!mc.getSoundHandler().isSoundPlaying(ambient)) {
                    mc.getSoundHandler().playSound(ambient);
                }
            }
            return;
        }

        // Эмбиент уже есть — обновляем цель и вручную тикаем громкость
        ambient.setTargetVolume(targetVolume);
        ambient.tickVolume();

        // Если цель — тишина и фактическая громкость уже почти нулевая,
        // останавливаем и забываем экземпляр
        if (targetVolume <= 0.001F && ambient.getCurrentVolume() <= 0.001F) {
            stopAmbient(mc);
        }
    }

    /**
     * Останавливает текущий эмбиент (если он есть) и обнуляет ссылку.
     *
     * @param mc экземпляр клиента, у которого берём SoundHandler.
     */
    private void stopAmbient(Minecraft mc) {
        if (ambient != null) {
            mc.getSoundHandler().stopSound(ambient);
            ambient = null;
        }
    }
}