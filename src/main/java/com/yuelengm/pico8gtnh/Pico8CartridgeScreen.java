package com.yuelengm.pico8gtnh;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;

import org.lwjgl.input.Keyboard;

/** Lets the player choose a local .p8 or .p8.png cartridge. */
public final class Pico8CartridgeScreen extends GuiScreen {

    private static final int BUTTON_PREVIOUS = 1;
    private static final int BUTTON_LOAD = 2;
    private static final int BUTTON_REFRESH = 3;
    private static final int BUTTON_NEXT = 4;
    private static final int FIRST_CART_BUTTON = 100;
    private static final int CART_ROW_HEIGHT = 22;
    private static final String DEMO_RESOURCE = "/assets/pico8gtnh/demo.p8";

    private final File cartsDirectory = new File(Minecraft.getMinecraft().mcDataDir, "pico8gtnh/carts");
    private File[] carts = new File[0];
    private File selectedCart;
    private String statusMessage;
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
        drawCenteredString(fontRendererObj, "PICO-8 Cartridges", width / 2, 18, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, "Choose a cart, then press Load", width / 2, 32, 0xFFAAAAAA);

        int listStartY = 48;
        if (carts.length == 0) {
            drawCenteredString(
                fontRendererObj,
                "No cartridges found. Add .p8 or .p8.png files to:",
                width / 2,
                listStartY + 10,
                0xFFFFCC66);
            drawCenteredString(
                fontRendererObj,
                cartsDirectory.getAbsolutePath(),
                width / 2,
                listStartY + 24,
                0xFFFFFFFF);
        }

        String pageText = "Page " + (page + 1) + " / " + Math.max(1, (carts.length + pageSize - 1) / pageSize);
        drawCenteredString(fontRendererObj, pageText, width / 2, height - 72, 0xFFAAAAAA);
        if (statusMessage != null) {
            drawCenteredString(fontRendererObj, statusMessage, width / 2, height - 88, 0xFFFF7777);
        }

        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id >= FIRST_CART_BUTTON) {
            int cartIndex = page * pageSize + button.id - FIRST_CART_BUTTON;
            if (cartIndex >= 0 && cartIndex < carts.length) {
                selectedCart = carts[cartIndex];
                statusMessage = null;
                rebuildButtons();
            }
        } else if (button.id == BUTTON_LOAD) {
            if (selectedCart == null || !selectedCart.isFile()) {
                statusMessage = "Select a cartridge first";
            } else {
                mc.displayGuiScreen(new PicoRScreen(selectedCart));
            }
        } else if (button.id == BUTTON_REFRESH) {
            selectedCart = null;
            page = 0;
            refreshCarts();
            rebuildButtons();
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
            installDemoCartIfMissing();
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
            statusMessage = null;
        } catch (IOException exception) {
            carts = new File[0];
            statusMessage = exception.getMessage();
            Pico8GtnhMod.LOG.error("Could not read PICO-8 cartridge folder", exception);
        }
    }

    private void installDemoCartIfMissing() throws IOException {
        File demoFile = new File(cartsDirectory, "demo.p8");
        if (demoFile.exists()) {
            return;
        }
        InputStream demoResource = Pico8CartridgeScreen.class.getResourceAsStream(DEMO_RESOURCE);
        if (demoResource == null) {
            throw new IOException("Bundled demo cartridge is missing");
        }
        try {
            Files.copy(demoResource, demoFile.toPath());
        } finally {
            demoResource.close();
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
        buttonList.add(new GuiButton(BUTTON_PREVIOUS, width / 2 - 150, buttonY, 55, 20, "<"));
        buttonList.add(new GuiButton(BUTTON_LOAD, width / 2 - 90, buttonY, 95, 20, "Load"));
        buttonList.add(new GuiButton(BUTTON_REFRESH, width / 2 + 10, buttonY, 80, 20, "Refresh"));
        buttonList.add(new GuiButton(BUTTON_NEXT, width / 2 + 95, buttonY, 55, 20, ">"));
    }
}
