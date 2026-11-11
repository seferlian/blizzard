package com.blizzard.weather;

import com.blizzard.network.client.ClientPacketHandlerCold;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

/**
 * Менеджер сетевого канала мода.
 *
 * Создаёт и хранит SimpleNetworkWrapper, через который сервер отправляет клиенту
 * PacketBlizzard (состояние и сила метели), а также регистрирует обработчик
 * этого пакета на клиентской стороне.
 *
 * Инициализация вызывается один раз в Main.init().
 */
public class BlizzardManager {

    /**
     * Сетевой канал мода.
     * Имя канала ("blizzard_net") должно быть уникальным в пределах игры,
     * иначе возможны конфликты с другими модами.
     */
    public static final SimpleNetworkWrapper NETWORK =
            NetworkRegistry.INSTANCE.newSimpleChannel("blizzard_net");

    /**
     * Регистрирует обработчики пакетов.
     *
     * Пакет PacketBlizzard:
     *   id 0 (уникален в пределах этого канала);
     *   обрабатывается на стороне CLIENT (сервер шлёт — клиент принимает).
     */
    public static void initNetwork() {
        NETWORK.registerMessage(
                PacketBlizzard.Handler.class, // обработчик
                PacketBlizzard.class,          // тип пакета
                0,                             // уникальный id в канале
                Side.CLIENT                    // сторона, где обрабатывается
        );
        /**
         *
         * Пакет PacketBlizzard:
         * id 1 (уникален в пределах этого канала);
         * обрабатывается на стороне CLIENT (сервер шлёт — клиент принимает).
         */
        NETWORK.registerMessage(
                ClientPacketHandlerCold.class,
                ClientPacketHandlerCold.Packet.class,
                1,
                Side.CLIENT
        );

    }
}