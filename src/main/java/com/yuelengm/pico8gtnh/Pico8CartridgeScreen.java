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
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;

/** Lets the player choose a local .p8 or .p8.png cartridge. */
public final class Pico8CartridgeScreen extends GuiScreen {

    private static final int BUTTON_PREVIOUS = 1;
    private static final int BUTTON_LOAD = 2;
    private static final int BUTTON_REFRESH = 3;
    private static final int BUTTON_NEXT = 4;
    private static final int BUTTON_OPEN_FOLDER = 5;
    private static final int BUTTON_BROWSE_CARTS = 6;
    private static final int FIRST_CART_BUTTON = 100;
    private static final int CART_ROW_HEIGHT = 22;
    private static final URI BROWSE_CARTS_URI = URI
        .create("https://www.lexaloffle.com/bbs/?cat=7#sub=2&mode=carts&orderby=featured");

    private final File cartsDirectory = new File(Minecraft.getMinecraft().mcDataDir, "pico8carts");
    private File[] carts = new File[0];
    private File selectedCart;
    private String statusMessageKey;
    private int page;
    private int pageSize;

    @Override
    public void initGui() {
        pageSize = Math.max(1, (height - 140) / CART_ROW_HEIGHT);
        refreshCarts();
        rebuildButtons();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.pico8.carts.title"),
            width / 2,
            18,
            0xFFFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.pico8.carts.subtitle"),
            width / 2,
            32,
            0xFFAAAAAA);

        int listStartY = 48;
        if (carts.length == 0) {
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal("gui.pico8.carts.empty"),
                width / 2,
                listStartY + 10,
                0xFFFFCC66);
        }

        String pageText = StatCollector.translateToLocalFormatted(
            "gui.pico8.carts.page",
            page + 1,
            Math.max(1, (carts.length + pageSize - 1) / pageSize));
        drawCenteredString(fontRendererObj, pageText, width / 2, height - 72, 0xFFAAAAAA);
        if (statusMessageKey != null) {
            drawCenteredString(
                fontRendererObj,
                StatCollector.translateToLocal(statusMessageKey),
                width / 2,
                height - 88,
                0xFFFF7777);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= FIRST_CART_BUTTON) {
            int cartIndex = page * pageSize + button.id - FIRST_CART_BUTTON;
            if (cartIndex >= 0 && cartIndex < carts.length) {
                selectedCart = carts[cartIndex];
                statusMessageKey = null;
                rebuildButtons();
            }
        } else if (button.id == BUTTON_LOAD) {
            if (selectedCart == null || !selectedCart.isFile()) {
                statusMessageKey = "gui.pico8.carts.select_first";
            } else {
                mc.displayGuiScreen(new PicoRScreen(selectedCart));
            }
        } else if (button.id == BUTTON_REFRESH) {
            selectedCart = null;
            page = 0;
            refreshCarts();
            rebuildButtons();
        } else if (button.id == BUTTON_OPEN_FOLDER) {
            openCartsFolder();
        } else if (button.id == BUTTON_BROWSE_CARTS) {
            browseCarts();
        } else if (button.id == BUTTON_PREVIOUS && page > 0) {
            page--;
            rebuildButtons();
        } else if (button.id == BUTTON_NEXT && (page + 1) * pageSize < carts.length) {
            page++;
            rebuildButtons();
        }
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private void refreshCarts() {
        try {
            if (!cartsDirectory.isDirectory() && !cartsDirectory.mkdirs()) {
                throw new IOException("Could not create cartridge folder: " + cartsDirectory);
            }
            File[] files = cartsDirectory.listFiles(new FilenameFilter() {

                @Override
                public boolean accept(File directory, String name) {
                    String lowercaseName = name.toLowerCase(Locale.ROOT);
                    return lowercaseName.endsWith(".p8") || lowercaseName.endsWith(".p8.png");
                }
            });
            if (files == null) {
                throw new IOException("Could not read cartridge folder: " + cartsDirectory);
            }
            Arrays.sort(files, new Comparator<File>() {

                @Override
                public int compare(File first, File second) {
                    return first.getName()
                        .compareToIgnoreCase(second.getName());
                }
            });
            carts = files;
            if (selectedCart != null && !contains(selectedCart)) {
                selectedCart = null;
            }
            statusMessageKey = null;
        } catch (IOException exception) {
            carts = new File[0];
            statusMessageKey = "gui.pico8.carts.folder_error";
            Pico8GtnhMod.LOG.error("Could not read PICO-8 cartridge folder", exception);
        }
    }

    private boolean contains(File cart) {
        for (File availableCart : carts) {
            if (availableCart.equals(cart)) {
                return true;
            }
        }
        return false;
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
            desktop.open(cartsDirectory);
            statusMessageKey = null;
        } catch (IOException | RuntimeException exception) {
            statusMessageKey = "gui.pico8.carts.open_folder_error";
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
            statusMessageKey = null;
        } catch (IOException | RuntimeException exception) {
            statusMessageKey = "gui.pico8.carts.open_browser_error";
            Pico8GtnhMod.LOG.error("Could not open the PICO-8 carts page", exception);
        }
    }

    private void rebuildButtons() {
        buttonList.clear();
        int visibleCartCount = Math.min(pageSize, carts.length - page * pageSize);
        int buttonWidth = Math.min(360, width - 40);
        int buttonLeft = (width - buttonWidth) / 2;
        for (int row = 0; row < visibleCartCount; row++) {
            File cart = carts[page * pageSize + row];
            String prefix = cart.equals(selectedCart) ? "> " : "";
            buttonList.add(
                new GuiButton(
                    FIRST_CART_BUTTON + row,
                    buttonLeft,
                    48 + row * CART_ROW_HEIGHT,
                    buttonWidth,
                    20,
                    prefix + cart.getName()));
        }

        int buttonY = height - 52;
        buttonList.add(
            new GuiButton(
                BUTTON_PREVIOUS,
                width / 2 - 150,
                buttonY,
                65,
                20,
                StatCollector.translateToLocal("gui.pico8.carts.previous")));
        buttonList.add(
            new GuiButton(
                BUTTON_LOAD,
                width / 2 - 80,
                buttonY,
                75,
                20,
                StatCollector.translateToLocal("gui.pico8.carts.load")));
        buttonList.add(
            new GuiButton(
                BUTTON_REFRESH,
                width / 2,
                buttonY,
                75,
                20,
                StatCollector.translateToLocal("gui.pico8.carts.refresh")));
        buttonList.add(
            new GuiButton(
                BUTTON_NEXT,
                width / 2 + 80,
                buttonY,
                70,
                20,
                StatCollector.translateToLocal("gui.pico8.carts.next")));

        int externalButtonY = height - 28;
        int externalButtonWidth = Math.min(160, (width - 30) / 2);
        int externalButtonsWidth = externalButtonWidth * 2 + 10;
        int externalButtonsLeft = (width - externalButtonsWidth) / 2;
        buttonList.add(
            new GuiButton(
                BUTTON_OPEN_FOLDER,
                externalButtonsLeft,
                externalButtonY,
                externalButtonWidth,
                20,
                StatCollector.translateToLocal("gui.pico8.carts.open_folder")));
        buttonList.add(
            new GuiButton(
                BUTTON_BROWSE_CARTS,
                externalButtonsLeft + externalButtonWidth + 10,
                externalButtonY,
                externalButtonWidth,
                20,
                StatCollector.translateToLocal("gui.pico8.carts.get_carts")));
    }
}
