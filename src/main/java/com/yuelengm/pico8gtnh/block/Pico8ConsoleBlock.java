package com.yuelengm.pico8gtnh.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

public class Pico8ConsoleBlock extends Block {

    public Pico8ConsoleBlock() {
        super(new Material(MapColor.airColor));
        setBlockName("pico8gtnh.pico8");
        setHardness(1F);
        setResistance(1F);
        setStepSound(Block.soundTypeGlass);
        setCreativeTab(CreativeTabs.tabDecorations);

        // TODO: custom model
        setBlockTextureName("minecraft:redstone_lamp_on");
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            Pico8GtnhMod.proxy.openPico8Screen();
        }
        return true;
    }
}
