package com.blizzard.weather;

import com.blizzard.raycast.BlizzardExposure;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

/**
 * Обработчик входа игрока в игру.
 *
 * При подключении игрока:
 *  1) регистрирует соответствие WorldInfo → World (для синхронизации в writeNBT);
 *  2) если в мире уже идёт метель — отправляет игроку PacketBlizzard,
 *     чтобы клиент сразу знал о состоянии погоды (иначе визуал появится
 *     только после следующего тика tickWeather, а это до 50 мс задержки).
 */
public class BlizzardPlayerHandler {

    /**
     * Вызывается, когда игрок успешно вошёл в мир.
     *
     * @param event событие входа игрока.
     */
    /**
     * Вызывается, когда игрок успешно вошёл в мир.
     *
     * @param event событие входа игрока.
     */
    @SubscribeEvent
    public void onPlayerLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        World world = player.world;
        if (world == null || world.isRemote) return;

        BlizzardHooks.registerWorldInfo(world);

        if (world.provider.getDimension() != 0) return;

        if (BlizzardReflection.isBlizzard(world)) {
            // Считаем экспозицию сразу, чтобы клиент не ждал 4 тика
            BlizzardExposure.tick(player, world);
            BlizzardExposure.Entry e = BlizzardExposure.getEntry(player);

            int time = BlizzardReflection.getBlizzardTime(world);
            float strength = BlizzardReflection.getBlizzardStrength(world);
            float sky  = BlizzardExposure.getSkyExposure(player);
            float open = BlizzardExposure.getOpenness(player);

            BlizzardManager.NETWORK.sendTo(
                    new PacketBlizzard(true, time, strength, sky, open), player);

            e.lastSentSky  = sky;
            e.lastSentOpen = open;
            e.lastSentTick = world.getTotalWorldTime();
        }
    }

    /**
     * Вызывается, когда игрок выходит из мира.
     *
     * Чистим кэш экспозиции и отладочный таймер, чтобы при повторном входе
     * значения не унаследовались от прошлой сессии.
     *
     * @param event событие выхода игрока.
     */
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.player instanceof EntityPlayerMP) {
            EntityPlayerMP p = (EntityPlayerMP) event.player;
            BlizzardExposure.clear(p);
            BlizzardHooks.clearDebugFor(p.getUniqueID());
        }
    }
}