package com.yuelengm.pico8gtnh;

import java.io.IOException;
import java.io.InputStream;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

/**
 * Basic client screen that hosts the PICO-R frame loop and its pixel buffer.
 */
public final class PicoRScreen extends GuiScreen {

    private static final String DEMO_CART = "/assets/pico8gtnh/demo.p8";
    private static final long NANOS_PER_SECOND = 1000000000L;
    private static final int BUTTON_LEFT = 1;
    private static final int BUTTON_RIGHT = 1 << 1;
    private static final int BUTTON_UP = 1 << 2;
    private static final int BUTTON_DOWN = 1 << 3;
    private static final int BUTTON_O = 1 << 4;
    private static final int BUTTON_X = 1 << 5;

    private final ResourceLocation textureLocation = new ResourceLocation(Pico8GtnhMod.MODID, "pico8_screen");
    private PicoRRuntime runtime;
    private DynamicTexture texture;
    private int[] texturePixels;
    private String error;
    private long lastFrameNanos;

    @Override
    public void initGui() {
        try {
            runtime = PicoRRuntime.loadBundled();
            InputStream cart = PicoRScreen.class.getResourceAsStream(DEMO_CART);
            if (cart == null) {
                throw new IOException("Bundled PICO-8 demo cart is missing");
            }
            try {
                runtime.loadCart(readAllBytes(cart));
            } finally {
                cart.close();
            }

            texture = new DynamicTexture(PicoRRuntime.SCREEN_WIDTH, PicoRRuntime.SCREEN_HEIGHT);
            texturePixels = texture.getTextureData();
            mc.getTextureManager()
                .loadTexture(textureLocation, texture);
            runtime.update();
            copyFrameToTexture();
            lastFrameNanos = System.nanoTime();
        } catch (IOException | RuntimeException exception) {
            error = exception.getMessage();
            Pico8GtnhMod.LOG.error("Could not start the bundled PICO-R demo", exception);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, "PICO-8", width / 2, height / 2 - 150, 0xFFFFFFFF);
        if (error != null) {
            drawCenteredString(fontRendererObj, "PICO-R failed to start", width / 2, height / 2 - 10, 0xFFFF5555);
            drawCenteredString(fontRendererObj, error, width / 2, height / 2 + 5, 0xFFFFFFFF);
        } else if (runtime != null) {
            long now = System.nanoTime();
            long frameInterval = NANOS_PER_SECOND / runtime.getFramesPerSecond();
            if (now - lastFrameNanos >= frameInterval) {
                runtime.setButtons(0, readButtonBits());
                runtime.update();
                copyFrameToTexture();
                lastFrameNanos = now;
            }

            int left = width / 2 - 256;
            int top = height / 2 - 256;
            mc.getTextureManager()
                .bindTexture(textureLocation);
            GL11.glPushMatrix();
            GL11.glTranslatef(left, top, 0.0F);
            GL11.glScalef(4.0F, 4.0F, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            drawScreenTexture();
            GL11.glPopMatrix();
            drawCenteredString(
                fontRendererObj,
                "Arrow keys: move    Z: O    X: X    Esc: close",
                width / 2,
                top + 530,
                0xFFAAAAAA);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
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
    public void onGuiClosed() {
        if (texture != null) {
            mc.getTextureManager()
                .deleteTexture(textureLocation);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private int readButtonBits() {
        int bits = 0;
        if (Keyboard.isKeyDown(Keyboard.KEY_LEFT)) bits |= BUTTON_LEFT;
        if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) bits |= BUTTON_RIGHT;
        if (Keyboard.isKeyDown(Keyboard.KEY_UP)) bits |= BUTTON_UP;
        if (Keyboard.isKeyDown(Keyboard.KEY_DOWN)) bits |= BUTTON_DOWN;
        if (Keyboard.isKeyDown(Keyboard.KEY_Z)) bits |= BUTTON_O;
        if (Keyboard.isKeyDown(Keyboard.KEY_X)) bits |= BUTTON_X;
        return bits;
    }

    private void copyFrameToTexture() {
        int[] pixels = runtime.getPixels();
        System.arraycopy(pixels, 0, texturePixels, 0, pixels.length);
        texture.updateDynamicTexture();
    }

    private void drawScreenTexture() {
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(0, PicoRRuntime.SCREEN_HEIGHT, zLevel, 0.0D, 1.0D);
        tessellator.addVertexWithUV(PicoRRuntime.SCREEN_WIDTH, PicoRRuntime.SCREEN_HEIGHT, zLevel, 1.0D, 1.0D);
        tessellator.addVertexWithUV(PicoRRuntime.SCREEN_WIDTH, 0, zLevel, 1.0D, 0.0D);
        tessellator.addVertexWithUV(0, 0, zLevel, 0.0D, 0.0D);
        tessellator.draw();
    }

    private static byte[] readAllBytes(InputStream input) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) {
            output.write(buffer, 0, count);
        }
        return output.toByteArray();
    }
}
