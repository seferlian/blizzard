package com.blizzard.registry;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.ICapabilitySerializable;

/**
 * Универсальный провайдер capability для мода Blizzard.
 */
public class BlizzardCapabilityProvider implements ICapabilitySerializable<NBTTagCompound> {

    private final Capability<?> capability;
    private final Object instance;

    public BlizzardCapabilityProvider(Capability<?> capability, Object instance) {
        this.capability = capability;
        this.instance = instance;
    }

    @Override
    public boolean hasCapability(Capability<?> requested, EnumFacing facing) {
        return requested == this.capability;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getCapability(Capability<T> requested, EnumFacing facing) {
        if (requested == this.capability) {
            return (T) this.instance;
        }
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public NBTTagCompound serializeNBT() {
        Capability rawCap = this.capability;
        return (NBTTagCompound) rawCap.getStorage().writeNBT(rawCap, this.instance, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public void deserializeNBT(NBTTagCompound nbt) {
        Capability rawCap = this.capability;
        rawCap.getStorage().readNBT(rawCap, this.instance, null, nbt);
    }
}