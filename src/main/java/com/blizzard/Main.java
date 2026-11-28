package com.blizzard;

import com.blizzard.capabilities.BlizzardCapabilityHandler;
import com.blizzard.capabilities.BlizzardColdHandler;
import com.blizzard.client.BlizzardClientCold;
import com.blizzard.client.BlizzardColdHud;
import com.blizzard.client.BlizzardShader;
import com.blizzard.client.BlizzardSoundHandler;
import com.blizzard.command.CommandBlizzard;
import com.blizzard.command.CommandWeatherExtended;
import com.blizzard.network.IBlizzardClientHandler;
import com.blizzard.registry.BlizzardCapabilities;
import com.blizzard.registry.BlizzardItems;
import com.blizzard.weather.BlizzardManager;
import com.blizzard.weather.BlizzardPlayerHandler;
import com.blizzard.weather.SnowIcePopulator;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.SidedProxy;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.common.registry.GameRegistry;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(modid = Tags.MODID, version = Tags.VERSION, name = Tags.MODNAME, acceptedMinecraftVersions = "[1.12.2]")
public class Main {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MODID);
    public static SoundEvent BLIZZARD_WIND;

    /**
     * Обработчик сетевых пакетов на текущей стороне.
     *
     * Forge подставляет реализацию по имени класса, поэтому общий код
     * (и выделенный сервер) не ссылается на клиентские классы статически:
     * на сервере создаётся ServerPacketHandler, на клиенте —
     * BlizzardClientPacketHandler.
     */
    @SidedProxy(
            clientSide = "com.blizzard.client.BlizzardClientPacketHandler",
            serverSide = "com.blizzard.network.ServerPacketHandler"
    )
    public static IBlizzardClientHandler packetHandler;

    @EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        ConfigManager.sync(Tags.MODID, net.minecraftforge.common.config.Config.Type.INSTANCE);//регистрация конфига
        ResourceLocation loc = new ResourceLocation("blizzard", "ambient.blizzard_wind");
        BLIZZARD_WIND = new SoundEvent(loc).setRegistryName(loc);
        ForgeRegistries.SOUND_EVENTS.register(BLIZZARD_WIND);

        // Регистрация capability (общая для клиента и сервера)
        BlizzardCapabilities.register();

        // Регистрация предметов (термочайный пакет)
        BlizzardItems.register();

        MinecraftForge.EVENT_BUS.register(this);
    }

    @EventHandler
    public void init(FMLInitializationEvent event) {
        BlizzardManager.initNetwork();
        // Обработчик пакетов выбран @SidedProxy по физической стороне
        BlizzardManager.PACKET_HANDLER = packetHandler;
        MinecraftForge.EVENT_BUS.register(new BlizzardPlayerHandler());
        MinecraftForge.EVENT_BUS.register(new SnowIcePopulator());

        // Capability-хуки (общие)
        MinecraftForge.EVENT_BUS.register(new BlizzardCapabilityHandler());
        MinecraftForge.EVENT_BUS.register(new BlizzardColdHandler());

        LOGGER.info("Blizzard network initialized.");

        if (FMLCommonHandler.instance().getSide().isClient()) {
            // Сброс клиентского состояния при выходе с сервера/из мира
            MinecraftForge.EVENT_BUS.register(BlizzardManager.PACKET_HANDLER);

            MinecraftForge.EVENT_BUS.register(new BlizzardShader());
            MinecraftForge.EVENT_BUS.register(new BlizzardSoundHandler());
            BlizzardSoundHandler.setSound(BLIZZARD_WIND);
            MinecraftForge.EVENT_BUS.register(new BlizzardClientCold());
            MinecraftForge.EVENT_BUS.register(new BlizzardColdHud());
        }
    }

    @EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandWeatherExtended());
        event.registerServerCommand(new CommandBlizzard());
    }
}
