package com.yuelengm.pico8gtnh.proxy;

import java.io.File;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.gtnewhorizon.gtnhlib.client.model.loading.ModelRegistry;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.config.Pico8Config;
import com.yuelengm.pico8gtnh.gui.Pico8CartridgeScreen;
import com.yuelengm.pico8gtnh.gui.PicoRScreen;
import com.yuelengm.pico8gtnh.service.PicoRSession;

import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@SuppressWarnings("unused")
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        ConfigurationManager.registerConfig(Pico8Config.class);
        ModelRegistry.registerModid(Pico8GtnhMod.MODID);
    }

    @Override
    public void openPico8Screen(boolean chooseCartridge) {
        File currentCart = PicoRSession.getCurrentCartFile();
        if (!chooseCartridge && currentCart != null) {
            ClientGUI.open(PicoRScreen.resume(currentCart));
        } else {
            Pico8CartridgeScreen.open();
        }
    }
}
