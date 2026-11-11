package com.blizzard.registry;

import com.blizzard.capabilities.BlizzardColdCapability;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.Capability;

/**
 * Универсальное NBT-хранилище для capability мода Blizzard.
 * Сейчас поддерживает только BlizzardColdCapability.
 */
public class BlizzardCapabilityStorage<T> implements Capability.IStorage<T> {

    @Override
    public NBTBase writeNBT(Capability<T> capability, T instance, EnumFacing side) {
        if (instance instanceof BlizzardColdCapability) {
            return ((BlizzardColdCapability) instance).serializeNBT();
        }
        return new NBTTagCompound();
    }

    @Override
    public void readNBT(Capability<T> capability, T instance, EnumFacing side, NBTBase nbt) {
        if (instance instanceof BlizzardColdCapability && nbt instanceof NBTTagCompound) {
            ((BlizzardColdCapability) instance).deserializeNBT((NBTTagCompound) nbt);
        }
    }
}