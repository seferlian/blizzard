package com.blizzard.weather;

import com.blizzard.network.client.PacketBlizzard;
import com.blizzard.raycast.BlizzardExposure;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.world.World;
import net.minecraft.world.storage.WorldInfo;
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

        if (!BlizzardHooks.isDimensionAffected(world.provider.getDimension())) return;

        // Читаем состояние из Overworld
        World overworld = player.getServer() != null
                ? player.getServer().getWorld(0)
                : null;
        if (overworld == null || !BlizzardReflection.isBlizzard(overworld)) return;

        BlizzardExposure.tick(player, world);
        BlizzardExposure.Entry e = BlizzardExposure.getEntry(player);

        int time = BlizzardReflection.getBlizzardTime(overworld);
        float strength = BlizzardReflection.getBlizzardStrength(overworld);
        float sky  = BlizzardExposure.getSkyExposure(player);
        float open = BlizzardExposure.getOpenness(player);

        BlizzardManager.NETWORK.sendTo(
                new PacketBlizzard(true, time, strength, sky, open), player);

        e.lastSentSky  = sky;
        e.lastSentOpen = open;
        e.lastSentTick = world.getTotalWorldTime();
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

    @SubscribeEvent
    public void onPlayerChangedDimension(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP mp = (EntityPlayerMP) event.player;

        // Если перешёл в измерение без эффектов — шлём active = false
        if (!BlizzardHooks.isDimensionAffected(event.toDim)) {
            BlizzardManager.NETWORK.sendTo(
                    new PacketBlizzard(false, 0, 0.0F, 1f, 1f), mp);
            BlizzardExposure.clear(mp);
            return;
        }

        // Читаем состояние из Overworld
        World overworld = mp.getServer() != null
                ? mp.getServer().getWorld(0)
                : null;
        if (overworld == null || !BlizzardReflection.isBlizzard(overworld)) return;

        BlizzardExposure.tick(mp, mp.world);
        BlizzardExposure.Entry e = BlizzardExposure.getEntry(mp);

        int time = BlizzardReflection.getBlizzardTime(overworld);
        float strength = BlizzardReflection.getBlizzardStrength(overworld);
        float sky  = BlizzardExposure.getSkyExposure(mp);
        float open = BlizzardExposure.getOpenness(mp);

        BlizzardManager.NETWORK.sendTo(
                new PacketBlizzard(true, time, strength, sky, open), mp);

        e.lastSentSky  = sky;
        e.lastSentOpen = open;
        e.lastSentTick = mp.world.getTotalWorldTime();
    }
}