package com.yuelengm.pico8gtnh.recipe;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

import com.yuelengm.pico8gtnh.block.ModBlocks;

import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.registry.GameRegistry;
import gregtech.api.enums.ItemList;
import gregtech.api.enums.Materials;
import gregtech.api.enums.OrePrefixes;

/** Registers the PICO-8 host recipe selected for the current mod setup. */
public final class RecipeLoader {

    private RecipeLoader() {}

    public static void registerRecipes() {
        if (Loader.isModLoaded("gregtech")) {
            GameRegistry.addRecipe(
                new ShapedOreRecipe(
                    ModBlocks.PICO8,
                    "PE",
                    "MC",
                    'P',
                    ItemList.Cover_Screen.get(1),
                    'E',
                    Items.ender_pearl,
                    'M',
                    ItemList.Casing_LV.get(1),
                    'C',
                    OrePrefixes.circuit.get(Materials.LV)
                        .toString()));
        } else {
            GameRegistry.addRecipe(
                new ItemStack(ModBlocks.PICO8),
                "PE",
                "SR",
                'P',
                Blocks.glass_pane,
                'E',
                Items.ender_pearl,
                'S',
                new ItemStack(Blocks.stone_slab, 1, 0),
                'R',
                Items.redstone);
        }
    }
}
