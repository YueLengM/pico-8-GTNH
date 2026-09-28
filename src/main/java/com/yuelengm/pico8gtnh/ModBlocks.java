package com.yuelengm.pico8gtnh;

import net.minecraft.block.Block;

import cpw.mods.fml.common.registry.GameRegistry;

public final class ModBlocks {

    public static final Block PICO8 = new Pico8MachineBlock();

    private ModBlocks() {}

    public static void registerBlocks() {
        GameRegistry.registerBlock(PICO8, "pico8");
    }
}
