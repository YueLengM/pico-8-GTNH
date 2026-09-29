package com.yuelengm.pico8gtnh;

import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.world.World;

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

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            Pico8GtnhMod.proxy.openPico8Screen();
        }
        return true;
    }
}
