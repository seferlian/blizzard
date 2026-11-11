package com.blizzard.registry;

import com.blizzard.capabilities.BlizzardColdCapability;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityInject;
import net.minecraftforge.common.capabilities.CapabilityManager;

/**
 * Реестр capability мода Blizzard.
 */
public final class BlizzardCapabilities {

    /** Накопленный холод у игрока во время метели. */
    @CapabilityInject(BlizzardColdCapability.class)
    public static final Capability<BlizzardColdCapability> BLIZZARD_COLD = null;

    private BlizzardCapabilities() {}

    /** Регистрация — вызывать из CommonProxy.preInit(). */
    public static void register() {
        CapabilityManager.INSTANCE.register(
                BlizzardColdCapability.class,
                new BlizzardCapabilityStorage<>(),
                BlizzardColdCapability::new
        );
    }
}