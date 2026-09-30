package com.yuelengm.pico8gtnh.service;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.Locale;

import javax.imageio.ImageIO;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

/** Data for a local PICO-8 cartridge and its optional cover texture. */
public final class Cartridge {

    private static final int P8_PNG_ICON_X = 16;
    private static final int P8_PNG_ICON_Y = 24;
    private static final int P8_PNG_ICON_SIZE = 128;
    private static final ResourceLocation UNKNOWN_PACK_ICON = new ResourceLocation("textures/misc/unknown_pack.png");

    private final String fileName;
    private final File file;
    private final ResourceLocation coverTexture;
    private final DynamicTexture dynamicCoverTexture;

    public Cartridge(File file) {
        this.fileName = file.getName();
        this.file = file;

        ResourceLocation loadedCoverTexture = UNKNOWN_PACK_ICON;
        DynamicTexture loadedDynamicCoverTexture = null;
        if (this.fileName.toLowerCase(Locale.ROOT)
            .endsWith(".p8.png")) {
            try {
                BufferedImage image = ImageIO.read(file);
                if (image != null && image.getWidth() >= P8_PNG_ICON_X + P8_PNG_ICON_SIZE
                    && image.getHeight() >= P8_PNG_ICON_Y + P8_PNG_ICON_SIZE) {
                    BufferedImage cover = image
                        .getSubimage(P8_PNG_ICON_X, P8_PNG_ICON_Y, P8_PNG_ICON_SIZE, P8_PNG_ICON_SIZE);
                    loadedDynamicCoverTexture = new DynamicTexture(cover);
                    loadedCoverTexture = Minecraft.getMinecraft()
                        .getTextureManager()
                        .getDynamicTextureLocation(
                            "pico8_cart_" + Integer.toHexString(
                                file.getAbsolutePath()
                                    .hashCode()),
                            loadedDynamicCoverTexture);
                }
                if (image != null) {
                    image.flush();
                }
            } catch (IOException exception) {
                Pico8GtnhMod.LOG.warn("Could not read PICO-8 cartridge cover {}", file, exception);
            }
        }
        this.coverTexture = loadedCoverTexture;
        this.dynamicCoverTexture = loadedDynamicCoverTexture;
    }

    public String getFileName() {
        return fileName;
    }

    public File getFile() {
        return file;
    }

    public ResourceLocation getCoverTexture() {
        return coverTexture;
    }

    public DynamicTexture getDynamicCoverTexture() {
        return dynamicCoverTexture;
    }
}
