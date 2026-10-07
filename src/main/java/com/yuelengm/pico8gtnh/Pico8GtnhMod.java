package com.yuelengm.pico8gtnh;

import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yuelengm.pico8gtnh.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.network.NetworkCheckHandler;
import cpw.mods.fml.relauncher.Side;

@Mod(modid = Pico8GtnhMod.MODID, version = Tags.VERSION, name = "PICO-8", acceptedMinecraftVersions = "[1.7.10]")
public class Pico8GtnhMod {

    public static final String MODID = "pico8";
    public static final Logger LOG = LogManager.getLogger(MODID);

    private static volatile String remoteVersion;

    @SuppressWarnings("unused")
    @NetworkCheckHandler
    public static boolean checkRemoteVersions(Map<String, String> remoteModVersions, Side side) {
        if (side == Side.SERVER) {
            remoteVersion = remoteModVersions.get(MODID);
            return true;
        }
        return remoteModVersions.containsKey(MODID);
    }

    public static String getRemoteVersion() {
        return remoteVersion;
    }

    public static boolean isClientOnlyMode() {
        return getRemoteVersion() == null;
    }

    @SidedProxy(
        clientSide = "com.yuelengm.pico8gtnh.proxy.ClientProxy",
        serverSide = "com.yuelengm.pico8gtnh.proxy.CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items, etc, and register them with the
    // GameRegistry." (Remove if not needed)
    public void preInit(FMLPreInitializationEvent event) {
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes." (Remove if not needed)
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this." (Remove if not needed)
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler (Remove if not needed)
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }
}
