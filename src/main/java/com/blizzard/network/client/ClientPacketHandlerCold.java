package com.blizzard.network.client;

import com.blizzard.client.BlizzardClientCold;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Синхронизация накопленного холода с клиентом.
 *
 * Сервер шлёт пакет раз в секунду на каждого игрока.
 * Клиент передаёт значение в BlizzardClientCold для интерполяции.
 */
public class ClientPacketHandlerCold implements IMessageHandler<ClientPacketHandlerCold.Packet, IMessage> {

    @Override
    @SideOnly(Side.CLIENT)
    public IMessage onMessage(Packet message, MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            BlizzardClientCold.onPacket(message.cold);
        });
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