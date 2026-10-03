package com.yuelengm.pico8gtnh.block;

import net.minecraft.block.Block;
import net.minecraft.block.material.MapColor;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.MathHelper;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.gtnewhorizon.gtnhlib.blockstate.properties.DirectionBlockProperty;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;

public class Pico8ConsoleBlock extends Block {

    public static final DirectionBlockProperty FACING = DirectionBlockProperty.facingVanilla(7);

    public Pico8ConsoleBlock() {
        super(new Material(MapColor.airColor));
        setBlockName("pico8.pico8_console");
        setHardness(1F);
        setResistance(1F);
        setStepSound(Block.soundTypeGlass);
        setCreativeTab(CreativeTabs.tabDecorations);
    }

    @Override
    public boolean isOpaqueCube() {
        return false;
    }

    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase placer, ItemStack stack) {
        int rotation = MathHelper.floor_double(placer.rotationYaw * 4.0F / 360.0F + 0.5D) & 3;
        ForgeDirection facing = switch (rotation) {
            case 0 -> ForgeDirection.NORTH;
            case 1 -> ForgeDirection.EAST;
            case 2 -> ForgeDirection.SOUTH;
            default -> ForgeDirection.WEST;
        };

        FACING.setValue(world, x, y, z, facing);
    }

    @Override
    public boolean onBlockActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
        float hitY, float hitZ) {
        if (world.isRemote) {
            Pico8GtnhMod.proxy.openPico8Screen(player.isSneaking());
        }
        return true;
    }
}
