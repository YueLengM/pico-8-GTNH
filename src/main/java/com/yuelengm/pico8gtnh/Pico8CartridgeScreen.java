package com.yuelengm.pico8gtnh;

import java.awt.Desktop;
import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;

/** Builds the MUI2 screen for choosing a local PICO-8 cartridge. */
public final class Pico8CartridgeScreen {

    private static final URI BROWSE_CARTS_URI = URI
        .create("https://www.lexaloffle.com/bbs/?cat=7#sub=2&mode=carts&orderby=featured");

    private final File cartsDirectory = new File(Minecraft.getMinecraft().mcDataDir, "pico8carts");
    private File[] carts = new File[0];
    private File selectedCart;
    private String statusMessageKey;

    private Pico8CartridgeScreen() {}

    public static void open() {
        Pico8CartridgeScreen cartridgeScreen = new Pico8CartridgeScreen();
        cartridgeScreen.refreshCarts();
        ModularPanel panel = cartridgeScreen.buildPanel();
        ClientGUI.open(new ModularScreen(Pico8GtnhMod.MODID, panel).pausesGame(false));
    }

    private ModularPanel buildPanel() {
        ModularPanel panel = ModularPanel.defaultPanel("pico8_carts", 360, 300)
            .padding(8)
            .child(
                Flow.column()
                    .sizeRel(1f)
                    .child(
                        new TextWidget<>(IKey.lang("gui.pico8.carts.title")).height(18)
                            .widthRel(1f))
                    .child(
                        new TextWidget<>(IKey.lang("gui.pico8.carts.subtitle")).height(14)
                            .widthRel(1f))
                    .child(
                        new TextWidget<>(
                            IKey.dynamic(
                                () -> {
                                    return this.carts.length == 0
                                        ? StatCollector.translateToLocal("gui.pico8.carts.empty")
                                        : "";
                                })).height(14)
                                    .widthRel(1f))
                    .child(
                        new TextWidget<>(
                            IKey.dynamic(
                                () -> { return this.carts.length == 0 ? this.cartsDirectory.getAbsolutePath() : ""; }))
                                    .height(14)
                                    .widthRel(1f))
                    .child(
                        new ListWidget<>().widthRel(1f)
                            .expanded()
                            .children(Arrays.asList(this.carts), this::createCartButton))
                    .child(new TextWidget<>(IKey.dynamic(() -> {
                        return this.selectedCart == null
                            ? StatCollector.translateToLocal("gui.pico8.carts.no_selection")
                            : this.selectedCart.getName();
                    })).height(14)
                        .widthRel(1f))
                    .child(
                        new TextWidget<>(
                            IKey.dynamic(
                                () -> {
                                    return this.statusMessageKey == null ? ""
                                        : StatCollector.translateToLocal(this.statusMessageKey);
                                })).height(14)
                                    .widthRel(1f))
                    .child(
                        Flow.row()
                            .widthRel(1f)
                            .height(22)
                            .child(actionButton("gui.pico8.carts.load", this::loadSelectedCart))
                            .child(actionButton("gui.pico8.carts.refresh", Pico8CartridgeScreen::open)))
                    .child(
                        Flow.row()
                            .widthRel(1f)
                            .height(22)
                            .child(actionButton("gui.pico8.carts.open_folder", this::openCartsFolder))
                            .child(actionButton("gui.pico8.carts.get_carts", this::browseCarts))));
        return panel;
    }

    private ButtonWidget<?> createCartButton(File cart) {
        return new ButtonWidget<>().widthRel(1f)
            .height(20)
            .overlay(IKey.dynamic(() -> (cart.equals(this.selectedCart) ? "> " : "") + cart.getName()))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                this.selectedCart = cart;
                this.statusMessageKey = null;
                return true;
            });
    }

    private static ButtonWidget<?> actionButton(String translationKey, Runnable action) {
        return new ButtonWidget<>().expanded()
            .height(20)
            .overlay(IKey.lang(translationKey))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                action.run();
                return true;
            });
    }

    private void loadSelectedCart() {
        if (this.selectedCart == null || !this.selectedCart.isFile()) {
            this.statusMessageKey = "gui.pico8.carts.select_first";
            return;
        }
        ClientGUI.open(new PicoRScreen(this.selectedCart));
    }

    private void refreshCarts() {
        try {
            if (!this.cartsDirectory.isDirectory() && !this.cartsDirectory.mkdirs()) {
                throw new IOException("Could not create cartridge folder: " + this.cartsDirectory);
            }
            File[] files = this.cartsDirectory.listFiles(new FilenameFilter() {

                @Override
                public boolean accept(File directory, String name) {
                    String lowercaseName = name.toLowerCase(Locale.ROOT);
                    return lowercaseName.endsWith(".p8") || lowercaseName.endsWith(".p8.png");
                }
            });
            if (files == null) {
                throw new IOException("Could not read cartridge folder: " + this.cartsDirectory);
            }
            Arrays.sort(files, new Comparator<File>() {

                @Override
                public int compare(File first, File second) {
                    return first.getName()
                        .compareToIgnoreCase(second.getName());
                }
            });
            this.carts = files;
            this.statusMessageKey = null;
        } catch (IOException exception) {
            this.carts = new File[0];
            this.statusMessageKey = "gui.pico8.carts.folder_error";
            Pico8GtnhMod.LOG.error("Could not read PICO-8 cartridge folder", exception);
        }
    }

    private void openCartsFolder() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop folder access is not supported on this system");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Opening folders is not supported on this system");
            }
            desktop.open(this.cartsDirectory);
            this.statusMessageKey = null;
        } catch (IOException | RuntimeException exception) {
            this.statusMessageKey = "gui.pico8.carts.open_folder_error";
            Pico8GtnhMod.LOG.error("Could not open PICO-8 cartridge folder", exception);
        }
    }

    private void browseCarts() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop browser access is not supported on this system");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("Opening a web browser is not supported on this system");
            }
            desktop.browse(BROWSE_CARTS_URI);
            this.statusMessageKey = null;
        } catch (IOException | RuntimeException exception) {
            this.statusMessageKey = "gui.pico8.carts.open_browser_error";
            Pico8GtnhMod.LOG.error("Could not open the PICO-8 carts page", exception);
        }
    }
}
