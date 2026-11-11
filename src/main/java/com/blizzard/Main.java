package com.blizzard;

import com.blizzard.capabilities.BlizzardCapabilityHandler;
import com.blizzard.capabilities.BlizzardColdHandler;
import com.blizzard.client.BlizzardColdHud;
import com.blizzard.client.BlizzardShader;
import com.blizzard.client.BlizzardSoundHandler;
import com.blizzard.command.CommandWeatherExtended;
import com.blizzard.raycast.BlizzardClientExposure;
import com.blizzard.registry.BlizzardCapabilities;
import com.blizzard.weather.BlizzardManager;
import com.blizzard.weather.BlizzardPlayerHandler;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MODID, version = Tags.VERSION, name = Tags.MODNAME, acceptedMinecraftVersions = "[1.12.2]")
public class Main {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MODID);
    public static SoundEvent BLIZZARD_WIND;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ResourceLocation loc = new ResourceLocation("blizzard", "ambient.blizzard_wind");
        BLIZZARD_WIND = new SoundEvent(loc).setRegistryName(loc);
        ForgeRegistries.SOUND_EVENTS.register(BLIZZARD_WIND);

        // Регистрация capability (общая для клиента и сервера)
        BlizzardCapabilities.register();

        MinecraftForge.EVENT_BUS.register(this);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        BlizzardManager.initNetwork();
        MinecraftForge.EVENT_BUS.register(new BlizzardPlayerHandler());

        // Capability-хуки (общие)
        MinecraftForge.EVENT_BUS.register(new BlizzardCapabilityHandler());
        MinecraftForge.EVENT_BUS.register(new BlizzardColdHandler());

        LOGGER.info("Blizzard network initialized.");

        if (FMLCommonHandler.instance().getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new BlizzardShader());
            MinecraftForge.EVENT_BUS.register(new BlizzardSoundHandler());
            BlizzardSoundHandler.setSound(BLIZZARD_WIND);
            MinecraftForge.EVENT_BUS.register(new BlizzardClientExposure());
            MinecraftForge.EVENT_BUS.register(new BlizzardColdHud());
        }
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandWeatherExtended());
    }
}