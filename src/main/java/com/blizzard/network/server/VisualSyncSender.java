package com.blizzard.network.server;

import com.blizzard.Tags;
import com.blizzard.config.ConfigHandler;
import com.blizzard.network.client.VisualSyncPacket;
import com.blizzard.weather.BlizzardManager;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Серверная синхронизация визуальных настроек.
 *
 * 1) При входе игрока пакет отправляется сразу и повторно через RETRY_TICKS тиков.
 * 2) При изменении конфига на сервере (команда /blizzard reload) — рассылка всем.
 *
 * Пакет идемпотентен: клиент просто перезаписывает ClientVisualConfig,
 * поэтому повторная отправка безопасна.
 */
@Mod.EventBusSubscriber(modid = Tags.MODID)
public class VisualSyncSender {

    /** Через сколько тиков после входа повторить отправку (страховка от потери). */
    private static final int RETRY_TICKS = 60; // ~3 секунды

    /** UUID игрока -> оставшиеся тики до повторной отправки. */
    private static final Map<UUID, Integer> pending = new HashMap<>();

    // ===== Вход игрока: сразу + повтор =====
    @SubscribeEvent
    public static void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;

        send(player);                       // сразу
        pending.put(player.getUniqueID(), RETRY_TICKS); // и ещё раз позже
    }

    // ===== Отсчёт до повторной отправки =====
    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || pending.isEmpty()) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        Iterator<Map.Entry<UUID, Integer>> it = pending.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, Integer> e = it.next();
            int left = e.getValue() - 1;
            if (left <= 0) {
                EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(e.getKey());
                if (p != null) send(p);
                it.remove();
            } else {
                e.setValue(left);
            }
        }
    }

    // ===== Выход игрока: чистим очередь =====
    @SubscribeEvent
    public static void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        pending.remove(event.player.getUniqueID());
    }

    // ===== Публичные точки отправки =====

    /** Отправить текущие визуальные настройки одному игроку. */
    public static void send(EntityPlayerMP player) {
        BlizzardManager.NETWORK.sendTo(
                new VisualSyncPacket(ConfigHandler.visual),
                player
        );
    }

    /** Разослать текущие визуальные настройки всем игрокам. */
    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        VisualSyncPacket packet = new VisualSyncPacket(ConfigHandler.visual);
        for (EntityPlayerMP player : server.getPlayerList().getPlayers()) {
            BlizzardManager.NETWORK.sendTo(packet, player);
        }
    }
}