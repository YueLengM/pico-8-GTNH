package com.yuelengm.pico8gtnh.proxy;

import com.yuelengm.pico8gtnh.gui.Pico8CartridgeScreen;

public class ClientProxy extends CommonProxy {

    @Override
    public void openPico8Screen() {
        Pico8CartridgeScreen.open();
    }
}
