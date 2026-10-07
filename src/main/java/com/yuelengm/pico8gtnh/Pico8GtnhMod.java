package com.yuelengm.pico8gtnh;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.yuelengm.pico8gtnh.proxy.CommonProxy;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@Mod(modid = Pico8GtnhMod.MODID, version = Tags.VERSION, name = "PICO-8", acceptedMinecraftVersions = "[1.7.10]")
public class Pico8GtnhMod {

    public static final String MODID = "pico8";
    public static final Logger LOG = LogManager.getLogger(MODID);

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
}
