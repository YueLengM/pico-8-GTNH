package com.yuelengm.pico8gtnh.proxy;

import com.gtnewhorizon.gtnhlib.client.model.loading.ModelRegistry;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.gui.Pico8CartridgeScreen;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        ModelRegistry.registerModid(Pico8GtnhMod.MODID);
    }

    @Override
    public void openPico8Screen() {
        Pico8CartridgeScreen.open();
    }
}
