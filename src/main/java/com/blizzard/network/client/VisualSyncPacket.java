package com.blizzard.network.client;

import com.blizzard.config.ConfigHandler;
import com.blizzard.network.IBlizzardClientHandler;
import com.blizzard.weather.BlizzardManager;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;

/**
 * Пакет синхронизации клиентских визуальных настроек с сервера.
 *
 * Сервер шлёт его игроку при входе в мир. Клиент сохраняет значения
 * в ClientVisualConfig и выставляет флаг serverSynced = true.
 * Локальный blizzard.cfg клиента при этом НЕ меняется.
 */
public class VisualSyncPacket implements IMessage {

    // ===== Поля (должны совпадать по порядку с toBytes/fromBytes) =====
    public double fogCurve;
    public double fogShelterFloor;
    public double fogStartMin;
    public double fogStartMax;
    public double fogEndMin;
    public double fogEndMax;
    public double skyWhiten;
    public double skyDesaturate;
    public double skyGray;
    public double skyInterpEnd;
    public double skyInterpCurve;

    public int snowRadius;
    public int snowQuadsPerBlock;
    public double snowSize;
    public double snowWindMultiplier;
    public int snowFallCycle;

    public VisualSyncPacket() {}

    /** Собрать пакет из текущего серверного конфига. */
    public VisualSyncPacket(ConfigHandler.Visual v) {
        this.fogCurve = v.fogCurve;
        this.fogShelterFloor = v.fogShelterFloor;
        this.fogStartMin = v.fogStartMin;
        this.fogStartMax = v.fogStartMax;
        this.fogEndMin = v.fogEndMin;
        this.fogEndMax = v.fogEndMax;
        this.skyWhiten = v.skyWhiten;
        this.skyDesaturate = v.skyDesaturate;
        this.skyGray = v.skyGray;
        this.skyInterpEnd = v.skyInterpEnd;
        this.skyInterpCurve = v.skyInterpCurve;

        this.snowRadius = v.snowRadius;
        this.snowQuadsPerBlock = v.snowQuadsPerBlock;
        this.snowSize = v.snowSize;
        this.snowWindMultiplier = v.snowWindMultiplier;
        this.snowFallCycle = v.snowFallCycle;
    }

    @Override
    public void fromBytes(ByteBuf buf) {
        this.fogCurve = buf.readDouble();
        this.fogShelterFloor = buf.readDouble();
        this.fogStartMin = buf.readDouble();
        this.fogStartMax = buf.readDouble();
        this.fogEndMin = buf.readDouble();
        this.fogEndMax = buf.readDouble();
        this.skyWhiten = buf.readDouble();
        this.skyDesaturate = buf.readDouble();
        this.skyGray = buf.readDouble();
        this.skyInterpEnd = buf.readDouble();
        this.skyInterpCurve = buf.readDouble();

        this.snowRadius = buf.readInt();
        this.snowQuadsPerBlock = buf.readInt();
        this.snowSize = buf.readDouble();
        this.snowWindMultiplier = buf.readDouble();
        this.snowFallCycle = buf.readInt();
    }

    @Override
    public void toBytes(ByteBuf buf) {
        buf.writeDouble(this.fogCurve);
        buf.writeDouble(this.fogShelterFloor);
        buf.writeDouble(this.fogStartMin);
        buf.writeDouble(this.fogStartMax);
        buf.writeDouble(this.fogEndMin);
        buf.writeDouble(this.fogEndMax);
        buf.writeDouble(this.skyWhiten);
        buf.writeDouble(this.skyDesaturate);
        buf.writeDouble(this.skyGray);
        buf.writeDouble(this.skyInterpEnd);
        buf.writeDouble(this.skyInterpCurve);

        buf.writeInt(this.snowRadius);
        buf.writeInt(this.snowQuadsPerBlock);
        buf.writeDouble(this.snowSize);
        buf.writeDouble(this.snowWindMultiplier);
        buf.writeInt(this.snowFallCycle);
    }

    // ===== Обработчик =====
    public static class Handler implements IMessageHandler<VisualSyncPacket, IMessage> {

        @Override
        public IMessage onMessage(VisualSyncPacket message, MessageContext ctx) {
            IBlizzardClientHandler packetHandler = BlizzardManager.PACKET_HANDLER;
            if (packetHandler != null) {
                packetHandler.handleVisual(message);
            }
            return null;
        }
    }
}