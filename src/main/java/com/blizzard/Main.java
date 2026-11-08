package com.blizzard;

import com.blizzard.command.CommandWeatherExtended;
import com.blizzard.weather.BlizzardManager;
import com.blizzard.weather.BlizzardPlayerHandler;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MODID, version = Tags.VERSION, name = Tags.MODNAME, acceptedMinecraftVersions = "[1.12.2]")
public class Main {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MODID);

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        MinecraftForge.EVENT_BUS.register(this);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        BlizzardManager.initNetwork();
        MinecraftForge.EVENT_BUS.register(new BlizzardPlayerHandler());
        LOGGER.info("Blizzard network initialized.");
        //MinecraftForge.EVENT_BUS.register(new BlizzardSaveHandler());
        if (FMLCommonHandler.instance().getSide().isClient()) {
            MinecraftForge.EVENT_BUS.register(new com.blizzard.client.BlizzardShader());
        }

    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandWeatherExtended());
    }
}