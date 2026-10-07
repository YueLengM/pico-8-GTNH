package com.yuelengm.pico8gtnh.gui;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;

/** Shared button builders for PICO-8 GUI screens. */
public final class CommonWidgets {

    private CommonWidgets() {}

    public static ButtonWidget<?> iconButton(String iconPath, Runnable action) {
        UITexture icon = UITexture.fullImage(Pico8GtnhMod.MODID, iconPath);
        return new ButtonWidget<>().child(
            icon.asWidget()
                .size(16)
                .center())
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                action.run();
                return true;
            });
    }

    public static ButtonWidget<?> textButton(String labelKey, Runnable action) {
        return new ButtonWidget<>().overlay(IKey.lang(labelKey))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                action.run();
                return true;
            });
    }
}
