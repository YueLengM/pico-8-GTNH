package com.yuelengm.pico8gtnh.config;

import com.gtnewhorizon.gtnhlib.config.Config;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;

@Config(modid = Pico8GtnhMod.MODID, category = "client")
public final class Pico8Config {

    @Config.Comment("Pico-8 Screen scale factor. 0 for auto scale")
    @Config.RangeInt(min = 0)
    public static int screenScale = 0;

    private Pico8Config() {}
}
