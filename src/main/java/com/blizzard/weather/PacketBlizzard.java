package com.blizzard.weather;

import com.blizzard.raycast.BlizzardClientExposure;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.*;

/**
 * Сетевой пакет для синхронизации состояния снежной метели между сервером и клиентом.
 *
 * Содержит пять полей:
 *   active      — активна ли метель в данный момент;
 *   duration    — остаток времени метели в тиках;
 *   strength    — текущая визуальная сила метели (0.0–1.0),
 *                 используется клиентом для плавного рендера шейдера и снежинок;
 *   skyExposure — доля лучей вверх (конус ~35°), доходящих до неба (0.0–1.0).
 *                 Бинарный признак «на поверхности / под землёй»:
 *                 ≈1.0 — открытое небо, ≈0.0 — игрок в укрытии;
 *   openness    — доля лучей во все стороны, не упёршихся в блок (0.0–1.0).
 *                 «Сколько открытого пространства вокруг»: 1.0 — поле,
 *                 0.0 — глухая пещера.
 *
 * Обе величины exposure считаются на сервере (BlizzardExposure) и сглаживаются
 * EMA. Клиент использует их для визуала (BlizzardClientExposure), а также они
 * зарезервированы под будущий геймплей (урон от холода, замедление и т.п.).
 *
 * Пакет отправляется сервером клиенту:
 *   каждый тик во время метели — для плавного обновления strength;
 *   немедленно при изменении exposure сильнее порога (не ждём 4 тика);
 *   один раз при старте/остановке метели;
 *   один раз при входе игрока в игру (если метель уже идёт).
 */
public class PacketBlizzard implements IMessage {

    /** Активна ли метель в данный момент. */
    public boolean active;

    /** Остаток времени метели в тиках. */
    public int duration;

    /** Текущая визуальная сила метели (0.0 – 1.0). */
    public float strength;

    /** Доля лучей вверх, доходящих до неба (0.0 – 1.0). */
    public float skyExposure;

    /** Доля лучей во все стороны, не упёршихся в блок (0.0 – 1.0). */
    public float openness;

    /**
     * Пустой конструктор, обязательный для Forge.
     * Используется при десериализации входящего пакета.
     */
    public PacketBlizzard() {}

    /**
     * Основной конструктор пакета.
     *
     * @param active      активна ли метель;
     * @param duration    остаток времени в тиках;
     * @param strength    текущая визуальная сила (0.0 – 1.0);
     * @param skyExposure доля лучей вверх, доходящих до неба (0.0 – 1.0);
     * @param openness    доля лучей во все стороны, не упёршихся в блок (0.0 – 1.0).
     */
    public PacketBlizzard(boolean active, int duration, float strength,
                          float skyExposure, float openness) {
        this.active = active;
        this.duration = duration;
        this.strength = strength;
        this.skyExposure = skyExposure;
        this.openness = openness;
    }

    /**
     * Читает данные из сетевого буфера.
     * Порядок чтения должен совпадать с порядком записи в toBytes(ByteBuf).
     *
     * @param buf входящий буфер.
     */
    @Override
    public void fromBytes(ByteBuf buf) {
        this.active      = buf.readBoolean(); // читаем флаг активности
        this.duration    = buf.readInt();     // читаем остаток времени
        this.strength    = buf.readFloat();   // читаем силу метели
        this.skyExposure = buf.readFloat();   // читаем долю лучей вверх
        this.openness    = buf.readFloat();   // читаем долю открытых лучей вокруг
    }

    /**
     * Записывает данные в сетевой буфер.
     * Порядок записи должен совпадать с порядком чтения в fromBytes(ByteBuf).
     *
     * @param buf исходящий буфер.
     */
    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeBoolean(active);       // пишем флаг активности
        buf.writeInt(duration);         // пишем остаток времени
        buf.writeFloat(strength);       // пишем силу метели
        buf.writeFloat(skyExposure);    // пишем долю лучей вверх
        buf.writeFloat(openness);       // пишем долю открытых лучей вокруг
    }

    /**
     * Обработчик пакета на стороне клиента.
     * Применяет полученное состояние метели к клиентскому миру
     * через BlizzardReflection, а exposure — в BlizzardClientExposure
     * для интерполяции и сглаживания перед рендером.
     */
    public static class Handler implements IMessageHandler<PacketBlizzard, IMessage> {

        /**
         * Вызывается при получении пакета.
         * Переключаемся на главный поток клиента (Minecraft требует всех
         * обращений к mc.world из главного потока), затем применяем
         * состояние.
         *
         * @param message входящий пакет;
         * @param ctx     контекст обработки.
         * @return null — ответ не отправляется.
         */
        @Override
        public IMessage onMessage(PacketBlizzard message, MessageContext ctx) {
            // Переключаемся на главный поток клиента — world нельзя менять из сетевого потока
            Minecraft.getMinecraft().addScheduledTask(() -> {
                Minecraft mc = Minecraft.getMinecraft();
                // Проверяем, что мир уже загружен (пакет мог прийти до входа в мир)
                if (mc.world != null) {
                    // Устанавливаем флаг активности и остаток времени
                    BlizzardReflection.setBlizzard(mc.world, message.active, message.duration);
                    // Устанавливаем визуальную силу для шейдера и снежинок
                    BlizzardReflection.setBlizzardStrength(mc.world, message.strength);
                    // Передаём exposure в клиентский калькулятор (интерполяция + EMA)
                    BlizzardClientExposure.onPacket(message.skyExposure, message.openness);
                }
            });
            return null; // ответ не нужен
        }
    }
}