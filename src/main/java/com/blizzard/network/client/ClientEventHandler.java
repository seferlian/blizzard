package com.blizzard.network.client;

import com.blizzard.config.ClientVisualConfig;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import com.blizzard.Tags;

/**
 * Клиентские события: сброс серверных настроек при отключении.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Tags.MODID, value = Side.CLIENT)
public class ClientEventHandler {

    @SubscribeEvent
    public static void onDisconnect(PlayerEvent.PlayerLoggedOutEvent event) {
        // На клиенте это событие приходит при выходе из мира/с сервера.
        ClientVisualConfig.reset();
    }
}