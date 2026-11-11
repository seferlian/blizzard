package com.blizzard.capabilities;

import com.blizzard.registry.BlizzardCapabilities;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Тик холода и сброс после смерти.
 */
public class BlizzardColdHandler {

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof EntityPlayerMP)) return;

        EntityPlayerMP player = (EntityPlayerMP) event.player;
        BlizzardColdCapability cap = player.getCapability(BlizzardCapabilities.BLIZZARD_COLD, null);
        if (cap != null) {
            cap.update(player);
        }
    }

    @SubscribeEvent
    public void onPlayerClone(PlayerEvent.Clone event) {
        // Сброс только при смерти; при возврате из End холод сохраняется
        if (!event.isWasDeath()) return;

        BlizzardColdCapability newCap = event.getEntityPlayer()
                .getCapability(BlizzardCapabilities.BLIZZARD_COLD, null);
        if (newCap != null) {
            newCap.setCold(0f);
        }
    }
}