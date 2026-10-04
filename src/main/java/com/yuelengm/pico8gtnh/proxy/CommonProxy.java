package com.yuelengm.pico8gtnh.proxy;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.Tags;
import com.yuelengm.pico8gtnh.block.ModBlocks;
import com.yuelengm.pico8gtnh.recipe.RecipeLoader;

import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

public class CommonProxy {

    public void preInit(FMLPreInitializationEvent event) {
        ModBlocks.registerBlocks();
        Pico8GtnhMod.LOG.info("Pico-8 GTNH loaded at version " + Tags.VERSION);
    }

    public void init(FMLInitializationEvent event) {
        RecipeLoader.registerRecipes();
    }

    public void postInit(FMLPostInitializationEvent event) {}

    public void serverStarting(FMLServerStartingEvent event) {}

    public void openPico8Screen(boolean chooseCartridge) {}
}
