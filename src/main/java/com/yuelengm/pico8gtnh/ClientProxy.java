package com.yuelengm.pico8gtnh;

public class ClientProxy extends CommonProxy {

    @Override
    public void openPico8Screen() {
        Pico8CartridgeScreen.open();
    }
}
