package com.yuelengm.pico8gtnh;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;

public class Pico8MachineBlock extends Block {

    public Pico8MachineBlock() {
        super(Material.rock);
        setBlockName("pico8gtnh.pico8");
        setBlockTextureName("minecraft:stone");
        setHardness(1.5F);
        setResistance(10.0F);
        setStepSound(Block.soundTypeStone);
        setCreativeTab(CreativeTabs.tabBlock);
    }
}
