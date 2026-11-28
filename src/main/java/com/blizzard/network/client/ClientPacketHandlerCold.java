package com.blizzard.network.client;

import com.blizzard.network.IBlizzardClientHandler;
import com.blizzard.weather.BlizzardManager;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Синхронизация накопленного холода с клиентом.
 *
 * Сервер шлёт пакет раз в секунду на каждого игрока.
 * Клиент передаёт значение в BlizzardClientCold для интерполяции.
 */
public class ClientPacketHandlerCold implements IMessageHandler<ClientPacketHandlerCold.Packet, IMessage> {

    @Override
    public IMessage onMessage(Packet message, MessageContext ctx) {
        IBlizzardClientHandler packetHandler = BlizzardManager.PACKET_HANDLER;
        if (packetHandler != null) {
            packetHandler.handleCold(message.cold);
        }
        return null;
    }

    /** Пакет: одно float-поле. */
    public static class Packet implements IMessage {

        float cold;

        public Packet() {}

        public Packet(float cold) {
            this.cold = cold;
        }

        @Override
        public void fromBytes(ByteBuf buf) {
            this.cold = buf.readFloat();
        }

        @Override
        public void toBytes(ByteBuf buf) {
            buf.writeFloat(this.cold);
        }
    }
}