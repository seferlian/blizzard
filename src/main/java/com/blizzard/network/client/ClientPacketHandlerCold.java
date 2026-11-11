package com.blizzard.network.client;

import com.blizzard.capabilities.BlizzardColdCapability;
import com.blizzard.registry.BlizzardCapabilities;
import io.netty.buffer.ByteBuf;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Синхронизация накопленного холода с клиентом.
 *
 * Сервер шлёт пакет раз в секунду на каждого игрока.
 * Клиент пишет значение в свою capability и использует его в HUD-оверлее.
 */
public class ClientPacketHandlerCold implements IMessageHandler<ClientPacketHandlerCold.Packet, IMessage> {

    @Override
    @SideOnly(Side.CLIENT)
    public IMessage onMessage(Packet message, MessageContext ctx) {
        Minecraft.getMinecraft().addScheduledTask(() -> {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player == null) return;

            BlizzardColdCapability cap = player.getCapability(BlizzardCapabilities.BLIZZARD_COLD, null);
            if (cap != null) {
                cap.setCold(message.cold);
            }
        });
        return null;
    }

    /** Пакет: одно float-поле. */
    public static class Packet implements IMessage {

        float cold;

        /** Обязательный пустой конструктор для Forge. */
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