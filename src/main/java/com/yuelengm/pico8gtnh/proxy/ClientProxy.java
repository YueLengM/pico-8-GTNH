package com.yuelengm.pico8gtnh.proxy;

import java.io.File;

import net.minecraftforge.client.ClientCommandHandler;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.gtnewhorizon.gtnhlib.config.ConfigException;
import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.Tags;
import com.yuelengm.pico8gtnh.client.command.Pico8ClientCommand;
import com.yuelengm.pico8gtnh.config.Pico8Config;
import com.yuelengm.pico8gtnh.gui.Pico8CartridgeScreen;
import com.yuelengm.pico8gtnh.gui.PicoRScreen;
import com.yuelengm.pico8gtnh.service.PicoRSession;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;

@SuppressWarnings("unused")
public class ClientProxy extends CommonProxy {

    @Override
    public void preInit(FMLPreInitializationEvent event) {
        super.preInit(event);
        try {
            ConfigurationManager.registerConfig(Pico8Config.class);
        } catch (ConfigException e) {
            throw new IllegalStateException("Could not register PICO-8 configuration", e);
        }
        Pico8GtnhMod.LOG.info("Pico-8 GTNH loaded at version " + Tags.VERSION);
    }

    @Override
    public void init(FMLInitializationEvent event) {
        super.init(event);
        Pico8ClientCommand command = new Pico8ClientCommand();
        ClientCommandHandler.instance.registerCommand(command);
        FMLCommonHandler.instance()
            .bus()
            .register(command);
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
