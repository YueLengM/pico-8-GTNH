package com.yuelengm.pico8gtnh.gui;

import java.io.File;
import java.io.IOException;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.GL11;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.service.PicoRRuntime;
import com.yuelengm.pico8gtnh.service.PicoRSession;

/**
 * Basic client screen that hosts the PICO-R frame loop and its pixel buffer.
 */
public final class PicoRScreen extends GuiScreen {

    private static final long NANOS_PER_SECOND = 1000000000L;
    private static final int MAX_CATCH_UP_FRAMES = 4;
    private static final int TITLE_HEIGHT = 18;
    private static final int CONTROL_PANEL_GAP = 8;
    private static final int CONTROL_ICON_COLUMN_WIDTH = 14;
    private static final int PLAYER_COLUMN_WIDTH = 36;
    private static final int CONTROL_HEADER_HEIGHT = 16;
    private static final int CONTROL_ROW_HEIGHT = 24;
    private static final int CONTROL_PANEL_HEIGHT = CONTROL_HEADER_HEIGHT + 3 * CONTROL_ROW_HEIGHT;
    private static final int CONTROL_PANEL_WIDTH = CONTROL_ICON_COLUMN_WIDTH + 2 * PLAYER_COLUMN_WIDTH;
    private static final int HORIZONTAL_MARGIN = 2;
    private static final int HINT_GAP = 4;
    private static final int FONT_HEIGHT = 9;
    private static final ResourceLocation DPAD_ICON = new ResourceLocation(
        Pico8GtnhMod.MODID,
        "textures/icons/controller_dpad.png");
    private static final ResourceLocation O_ICON = new ResourceLocation(
        Pico8GtnhMod.MODID,
        "textures/icons/controller_o.png");
    private static final ResourceLocation X_ICON = new ResourceLocation(
        Pico8GtnhMod.MODID,
        "textures/icons/controller_x.png");
    private static final int BUTTON_LEFT = 1;
    private static final int BUTTON_RIGHT = 1 << 1;
    private static final int BUTTON_UP = 1 << 2;
    private static final int BUTTON_DOWN = 1 << 3;
    private static final int BUTTON_O = 1 << 4;
    private static final int BUTTON_X = 1 << 5;

    private final ResourceLocation textureLocation = new ResourceLocation(Pico8GtnhMod.MODID, "pico8_screen");
    private final File cartFile;
    private boolean startFresh;
    private PicoRSession session;
    private PicoRRuntime runtime;
    private DynamicTexture texture;
    private int[] texturePixels;
    private String error;
    private long lastFrameNanos;

    public static PicoRScreen resume(File cartFile) {
        return new PicoRScreen(cartFile, false);
    }

    public static PicoRScreen startNew(File cartFile) {
        return new PicoRScreen(cartFile, true);
    }

    private PicoRScreen(File cartFile, boolean startFresh) {
        this.cartFile = cartFile;
        this.startFresh = startFresh;
    }

    @Override
    public void initGui() {
        error = null;
        if (texture != null) {
            mc.getTextureManager()
                .deleteTexture(textureLocation);
            texture = null;
            texturePixels = null;
        }
        try {
            if (cartFile == null) {
                throw new IOException("Cartridge file does not exist");
            }
            if (startFresh) {
                session = PicoRSession.startNew(cartFile);
                startFresh = false;
            } else {
                session = PicoRSession.resume(cartFile);
            }
            runtime = session.getRuntime();

            texture = new DynamicTexture(PicoRRuntime.SCREEN_WIDTH, PicoRRuntime.SCREEN_HEIGHT);
            texturePixels = texture.getTextureData();
            mc.getTextureManager()
                .loadTexture(textureLocation, texture);
            copyFrameToTexture();
            lastFrameNanos = System.nanoTime();
        } catch (IOException | RuntimeException | LinkageError exception) {
            if (session != null) {
                session.pause();
            }
            session = null;
            runtime = null;
            error = "gui.pico8.runtime.start_error";
            Pico8GtnhMod.LOG.error("Could not load PICO-8 cartridge {}", cartFile, exception);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (error != null) {
            drawErrorScreen();
        } else if (runtime != null) {
            updateGame();
            drawGame();
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private void drawErrorScreen() {
        drawCenteredString(fontRendererObj, "PICO-8", width / 2, height / 2 - 20, 0xFFFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.pico8.runtime.error_title"),
            width / 2,
            height / 2 - 10,
            0xFFFF5555);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal(error),
            width / 2,
            height / 2 + 5,
            0xFFFFFFFF);
    }

    private void updateGame() {
        long now = System.nanoTime();
        long frameInterval = NANOS_PER_SECOND / runtime.getFramesPerSecond();
        long overdueFrames = (now - lastFrameNanos) / frameInterval;
        if (overdueFrames <= 0) {
            return;
        }

        runtime.setButtons(0, readButtonBits(0));
        runtime.setButtons(1, readButtonBits(1));
        int framesToRun = (int) Math.min(overdueFrames, MAX_CATCH_UP_FRAMES);
        for (int frame = 0; frame < framesToRun; frame++) {
            runtime.update();
            queueAudioFrame();
        }
        copyFrameToTexture();

        lastFrameNanos += frameInterval * framesToRun;
        if (now - lastFrameNanos >= frameInterval) {
            lastFrameNanos = now;
        }
    }

    private void drawGame() {
        int reservedHeight = TITLE_HEIGHT + HINT_GAP + FONT_HEIGHT;
        int availableGameWidth = width - 2 * (HORIZONTAL_MARGIN + CONTROL_PANEL_GAP + CONTROL_PANEL_WIDTH);
        int scale = Math.max(
            1,
            Math.min(
                availableGameWidth / PicoRRuntime.SCREEN_WIDTH,
                (height - reservedHeight) / PicoRRuntime.SCREEN_HEIGHT));
        int gameSize = PicoRRuntime.SCREEN_WIDTH * scale;
        int left = (width - gameSize) / 2;
        int contentHeight = reservedHeight + gameSize;
        int top = (height - contentHeight) / 2 + TITLE_HEIGHT;
        drawGameImage(left, top, scale);
        drawScreenText(left, top, gameSize);
        drawControlPanel(left + gameSize + CONTROL_PANEL_GAP, top + (gameSize - CONTROL_PANEL_HEIGHT) / 2);
    }

    private void drawGameImage(int left, int top, int scale) {
        mc.getTextureManager()
            .bindTexture(textureLocation);
        GL11.glPushMatrix();
        GL11.glTranslatef(left, top, 0.0F);
        GL11.glScalef(scale, scale, 1.0F);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        drawScreenTexture();
        GL11.glPopMatrix();
    }

    private void drawScreenText(int gameLeft, int top, int gameSize) {
        drawCenteredString(
            fontRendererObj,
            cartFile.getName(),
            gameLeft + gameSize / 2,
            top - TITLE_HEIGHT,
            0xFFFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.pico8.runtime.reselect"),
            width / 2,
            top + gameSize + HINT_GAP,
            0xFFAAAAAA);
    }

    private void drawControlPanel(int left, int top) {
        int playerOneCenter = left + CONTROL_ICON_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH / 2;
        int playerTwoCenter = left + CONTROL_ICON_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH / 2;
        int headerY = top + (CONTROL_HEADER_HEIGHT - FONT_HEIGHT) / 2;
        drawCenteredString(fontRendererObj, "P1", playerOneCenter, headerY, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, "P2", playerTwoCenter, headerY, 0xFFFFFFFF);

        drawControlRow(DPAD_ICON, "←→↑↓", "SFED", left, top + CONTROL_HEADER_HEIGHT);
        drawControlRow(O_ICON, "Z/C/N", "Tab/W", left, top + CONTROL_HEADER_HEIGHT + CONTROL_ROW_HEIGHT);
        drawControlRow(X_ICON, "X/V/M", "Q", left, top + CONTROL_HEADER_HEIGHT + 2 * CONTROL_ROW_HEIGHT);
    }

    private void drawControlRow(ResourceLocation icon, String playerOneKeys, String playerTwoKeys, int left, int top) {
        int iconSize = 10;
        int x = left + (CONTROL_ICON_COLUMN_WIDTH - iconSize) / 2;
        int y = top + (CONTROL_ROW_HEIGHT - iconSize) / 2;
        mc.getTextureManager()
            .bindTexture(icon);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(x, y + iconSize, zLevel, 0.0D, 1.0D);
        tessellator.addVertexWithUV(x + iconSize, y + iconSize, zLevel, 1.0D, 1.0D);
        tessellator.addVertexWithUV(x + iconSize, y, zLevel, 1.0D, 0.0D);
        tessellator.addVertexWithUV(x, y, zLevel, 0.0D, 0.0D);
        tessellator.draw();
        drawCenteredString(
            fontRendererObj,
            playerOneKeys,
            left + CONTROL_ICON_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH / 2,
            top + (CONTROL_ROW_HEIGHT - FONT_HEIGHT) / 2,
            0xFFFFFFFF);
        drawCenteredString(
            fontRendererObj,
            playerTwoKeys,
            left + CONTROL_ICON_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH / 2,
            top + (CONTROL_ROW_HEIGHT - FONT_HEIGHT) / 2,
            0xFFFFFFFF);
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
        if (session != null) {
            session.pause();
        }
        if (texture != null) {
            mc.getTextureManager()
                .deleteTexture(textureLocation);
            texture = null;
            texturePixels = null;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    private int readButtonBits(int player) {
        int bits = 0;
        if (player == 0) {
            if (Keyboard.isKeyDown(Keyboard.KEY_LEFT)) bits |= BUTTON_LEFT;
            if (Keyboard.isKeyDown(Keyboard.KEY_RIGHT)) bits |= BUTTON_RIGHT;
            if (Keyboard.isKeyDown(Keyboard.KEY_UP)) bits |= BUTTON_UP;
            if (Keyboard.isKeyDown(Keyboard.KEY_DOWN)) bits |= BUTTON_DOWN;
            if (Keyboard.isKeyDown(Keyboard.KEY_Z) || Keyboard.isKeyDown(Keyboard.KEY_C)
                || Keyboard.isKeyDown(Keyboard.KEY_N)) bits |= BUTTON_O;
            if (Keyboard.isKeyDown(Keyboard.KEY_X) || Keyboard.isKeyDown(Keyboard.KEY_V)
                || Keyboard.isKeyDown(Keyboard.KEY_M)) bits |= BUTTON_X;
        } else {
            if (Keyboard.isKeyDown(Keyboard.KEY_S)) bits |= BUTTON_LEFT;
            if (Keyboard.isKeyDown(Keyboard.KEY_F)) bits |= BUTTON_RIGHT;
            if (Keyboard.isKeyDown(Keyboard.KEY_E)) bits |= BUTTON_UP;
            if (Keyboard.isKeyDown(Keyboard.KEY_D)) bits |= BUTTON_DOWN;
            if (Keyboard.isKeyDown(Keyboard.KEY_TAB) || Keyboard.isKeyDown(Keyboard.KEY_W)) bits |= BUTTON_O;
            if (Keyboard.isKeyDown(Keyboard.KEY_Q)) bits |= BUTTON_X;
        }
        return bits;
    }

    private void copyFrameToTexture() {
        runtime.copyPixelsTo(texturePixels);
        texture.updateDynamicTexture();
    }

    private void queueAudioFrame() {
        session.queueAudioFrame();
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

}
