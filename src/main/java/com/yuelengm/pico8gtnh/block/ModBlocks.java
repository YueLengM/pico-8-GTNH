package com.yuelengm.pico8gtnh.block;

import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.blockstate.registry.BlockPropertyRegistry;

import cpw.mods.fml.common.registry.GameRegistry;

public final class ModBlocks {

    public static final Pico8ConsoleBlock PICO8 = new Pico8ConsoleBlock();

    private ModBlocks() {}

    public static void registerBlocks() {
        GameRegistry.registerBlock(PICO8, "pico8");
        BlockPropertyRegistry.registerBlockItemProperty(PICO8, Pico8ConsoleBlock.FACING, ForgeDirection.SOUTH);
    }
}
