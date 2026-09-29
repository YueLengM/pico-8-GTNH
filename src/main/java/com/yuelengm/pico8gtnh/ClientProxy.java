package com.yuelengm.pico8gtnh;

import net.minecraft.client.Minecraft;

public class ClientProxy extends CommonProxy {

    @Override
    public void openPico8Screen() {
        Minecraft.getMinecraft()
            .displayGuiScreen(new Pico8CartridgeScreen());
    }
}
