package com.yuelengm.pico8gtnh.service;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

/** Handles local cartridge storage and enumerates available cartridge files. */
public final class CartService {

    private final Minecraft minecraft = Minecraft.getMinecraft();
    public final File cartsDirectory = new File(this.minecraft.mcDataDir, "pico8carts");

    public List<Cartridge> getCartridges() {
        try {
            createCartsDirectoryIfNeeded();
            File[] files = this.cartsDirectory.listFiles((directory, name) -> {
                String lowercaseName = name.toLowerCase(Locale.ROOT);
                return lowercaseName.endsWith(".p8") || lowercaseName.endsWith(".p8.png");
            });
            if (files == null) {
                throw new IOException("Could not read cartridge folder: " + this.cartsDirectory);
            }

            List<Cartridge> cartridges = new ArrayList<>(files.length);
            for (File file : files) {
                cartridges.add(new Cartridge(file));
            }
            return cartridges;
        } catch (IOException exception) {
            Pico8GtnhMod.LOG.error("Could not read PICO-8 cartridge folder", exception);
            return new ArrayList<>();
        }
    }

    private void createCartsDirectoryIfNeeded() throws IOException {
        boolean exists = this.cartsDirectory.exists();
        if (exists) {
            return;
        }
        if (!this.cartsDirectory.mkdirs()) {
            throw new IOException("Could not create cartridge folder: " + this.cartsDirectory);
        }
        copyBundledCartridge("/assets/pico8/Celeste.p8.png", "Celeste.p8.png");
    }

    private void copyBundledCartridge(String resourcePath, String fileName) throws IOException {
        InputStream input = CartService.class.getResourceAsStream(resourcePath);
        if (input == null) {
            throw new IOException("Bundled PICO-8 cartridge is missing: " + resourcePath);
        }

        File destination = new File(this.cartsDirectory, fileName);
        try (InputStream cartridge = input; FileOutputStream output = new FileOutputStream(destination)) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = cartridge.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
        }
    }

}
