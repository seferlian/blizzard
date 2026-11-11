package com.blizzard.capabilities;

import com.blizzard.capabilities.BlizzardColdCapability;
import com.blizzard.registry.BlizzardCapabilities;
import com.blizzard.registry.BlizzardCapabilityProvider;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/**
 * Прикрепляет capability холода ко всем игрокам.
 */
public class BlizzardCapabilityHandler {

    @SubscribeEvent
    public void onAttachCapabilities(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof EntityPlayer)) return;
        if (event.getObject().hasCapability(BlizzardCapabilities.BLIZZARD_COLD, null)) return;

        event.addCapability(
                new ResourceLocation("blizzard", "cold"),
                new BlizzardCapabilityProvider(
                        BlizzardCapabilities.BLIZZARD_COLD,
                        new BlizzardColdCapability()
                )
        );
    }
}