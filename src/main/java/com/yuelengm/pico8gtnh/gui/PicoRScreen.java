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

import com.gtnewhorizon.gtnhlib.config.ConfigurationManager;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.config.Pico8Config;
import com.yuelengm.pico8gtnh.service.PicoRRuntime;
import com.yuelengm.pico8gtnh.service.PicoRSession;

/**
 * Basic client screen that hosts the PICO-R frame loop and its pixel buffer.
 */
public final class PicoRScreen extends GuiScreen {

    private static final long NANOS_PER_SECOND = 1000000000L;
    private static final int MAX_CATCH_UP_FRAMES = 4;
    private static final int CONTROL_ICON_COLUMN_WIDTH = 14;
    private static final int PLAYER_COLUMN_WIDTH = 36;
    private static final int FONT_HEIGHT = 9;
    private static final int CONTROL_HEADER_HEIGHT = 16;
    private static final int CONTROL_ROW_HEIGHT = 24;
    private static final int CONTROL_TABLE_HEIGHT = CONTROL_HEADER_HEIGHT + 3 * CONTROL_ROW_HEIGHT;
    private static final int SAVE_HINT_GAP = 4;
    private static final int CONTROL_PANEL_HEIGHT = CONTROL_TABLE_HEIGHT + SAVE_HINT_GAP + 2 * FONT_HEIGHT;
    private static final int CONTROL_PANEL_WIDTH = CONTROL_ICON_COLUMN_WIDTH + 2 * PLAYER_COLUMN_WIDTH;
    private static final int HINT_GAP = 4;
    private static final int SCALE_HUD_WIDTH = 32;
    private static final int SCALE_HUD_BUTTON_HEIGHT = 16;
    private static final int SCALE_HUD_VALUE_HEIGHT = 14;
    private static final int SCALE_HUD_GAP = 2;
    private static final int SCALE_HUD_SCREEN_GAP = 8;
    private static final int HIDE_HOTSPOT_SIZE = 16;
    private static final ResourceLocation GUI_HIDE_ICON = new ResourceLocation(
        Pico8GtnhMod.MODID,
        "textures/icons/gui_hide.png");
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
    private int scaleHudLeft;
    private int scaleHudTop;
    private int hideHotspotLeft;
    private int hideHotspotTop;

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

        hideHotspotLeft = pixelToGuiX(SCALE_HUD_SCREEN_GAP);
        hideHotspotTop = height - pixelToGuiY(SCALE_HUD_SCREEN_GAP) - HIDE_HOTSPOT_SIZE;
        boolean showUi = !isMouseOverHideHotspot(mouseX, mouseY);

        if (error != null) {
            drawErrorScreen();
        } else if (runtime != null) {
            updateGame();
            drawGame();
            if (showUi) {
                drawInterface();
            }
        }
    }

    private boolean isMouseOverHideHotspot(int mouseX, int mouseY) {
        return mouseX >= hideHotspotLeft && mouseX < hideHotspotLeft + HIDE_HOTSPOT_SIZE
            && mouseY >= hideHotspotTop
            && mouseY < hideHotspotTop + HIDE_HOTSPOT_SIZE;
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

    private void beginPixelProjection() {
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, mc.displayWidth, mc.displayHeight, 0.0D, 1000.0D, 3000.0D);

        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPushMatrix();
        GL11.glLoadIdentity();
        GL11.glTranslatef(0.0F, 0.0F, -2000.0F);
    }

    private void endPixelProjection() {
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glPopMatrix();
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
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
        int maxFitScale = getMaxFitScale();
        int scale = Pico8Config.screenScale == 0 ? maxFitScale : Math.min(Pico8Config.screenScale, maxFitScale);
        int gameWidth = PicoRRuntime.SCREEN_WIDTH * scale;
        int gameHeight = PicoRRuntime.SCREEN_HEIGHT * scale;
        int pixelLeft = (mc.displayWidth - gameWidth) / 2;
        int pixelTop = (mc.displayHeight - gameHeight) / 2;

        beginPixelProjection();
        try {
            drawGameImage(pixelLeft, pixelTop, scale);
        } finally {
            endPixelProjection();
        }
    }

    private void drawInterface() {
        scaleHudLeft = pixelToGuiX(SCALE_HUD_SCREEN_GAP);
        scaleHudTop = (height - scaleHudHeight()) / 2;
        drawScreenText();
        drawControlPanel();
        drawScaleHud();
        drawHideHotspot();
    }

    private int getMaxFitScale() {
        return Math.min(mc.displayWidth / PicoRRuntime.SCREEN_WIDTH, mc.displayHeight / PicoRRuntime.SCREEN_HEIGHT);
    }

    private int pixelToGuiX(int pixelX) {
        return pixelX * width / mc.displayWidth;
    }

    private int pixelToGuiY(int pixelY) {
        return pixelY * height / mc.displayHeight;
    }

    private int scaleHudHeight() {
        return SCALE_HUD_BUTTON_HEIGHT * 2 + SCALE_HUD_VALUE_HEIGHT + SCALE_HUD_GAP * 2;
    }

    private void drawScaleHud() {
        int buttonY = scaleHudTop;
        drawScaleHudButton(scaleHudLeft, buttonY, "+");

        int valueY = buttonY + SCALE_HUD_BUTTON_HEIGHT + SCALE_HUD_GAP;
        drawRect(scaleHudLeft, valueY, scaleHudLeft + SCALE_HUD_WIDTH, valueY + SCALE_HUD_VALUE_HEIGHT, 0xC0202020);
        drawCenteredString(
            fontRendererObj,
            Pico8Config.screenScale == 0 ? StatCollector.translateToLocal("gui.pico8.runtime.scale_auto")
                : getDisplayedManualScale() + "x",
            scaleHudLeft + SCALE_HUD_WIDTH / 2,
            valueY + (SCALE_HUD_VALUE_HEIGHT - FONT_HEIGHT) / 2,
            0xFFFFFFFF);

        int minusY = valueY + SCALE_HUD_VALUE_HEIGHT + SCALE_HUD_GAP;
        drawScaleHudButton(scaleHudLeft, minusY, "-");
    }

    private void drawHideHotspot() {
        mc.getTextureManager()
            .bindTexture(GUI_HIDE_ICON);
        GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
        Tessellator tessellator = Tessellator.instance;
        tessellator.startDrawingQuads();
        tessellator.addVertexWithUV(hideHotspotLeft, hideHotspotTop + HIDE_HOTSPOT_SIZE, zLevel, 0.0D, 1.0D);
        tessellator.addVertexWithUV(
            hideHotspotLeft + HIDE_HOTSPOT_SIZE,
            hideHotspotTop + HIDE_HOTSPOT_SIZE,
            zLevel,
            1.0D,
            1.0D);
        tessellator.addVertexWithUV(hideHotspotLeft + HIDE_HOTSPOT_SIZE, hideHotspotTop, zLevel, 1.0D, 0.0D);
        tessellator.addVertexWithUV(hideHotspotLeft, hideHotspotTop, zLevel, 0.0D, 0.0D);
        tessellator.draw();
    }

    private int getDisplayedManualScale() {
        return Math.min(Pico8Config.screenScale, getMaxFitScale());
    }

    private void drawScaleHudButton(int left, int top, String label) {
        drawRect(left, top, left + SCALE_HUD_WIDTH, top + SCALE_HUD_BUTTON_HEIGHT, 0xC0303030);
        drawRect(left, top, left + SCALE_HUD_WIDTH, top + 1, 0xFF777777);
        drawRect(left, top, left + 1, top + SCALE_HUD_BUTTON_HEIGHT, 0xFF777777);
        drawRect(
            left,
            top + SCALE_HUD_BUTTON_HEIGHT - 1,
            left + SCALE_HUD_WIDTH,
            top + SCALE_HUD_BUTTON_HEIGHT,
            0xFF111111);
        drawRect(left + SCALE_HUD_WIDTH - 1, top, left + SCALE_HUD_WIDTH, top + SCALE_HUD_BUTTON_HEIGHT, 0xFF111111);
        drawCenteredString(
            fontRendererObj,
            label,
            left + SCALE_HUD_WIDTH / 2,
            top + (SCALE_HUD_BUTTON_HEIGHT - FONT_HEIGHT) / 2,
            0xFFFFFFFF);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int mouseButton) {
        if (mouseButton == 0 && isInsideScaleHudButton(mouseX, mouseY, scaleHudTop)) {
            int maxScale = Integer.MAX_VALUE / PicoRRuntime.SCREEN_WIDTH;
            int currentScale = Math.max(0, Math.min(Pico8Config.screenScale, maxScale));
            Pico8Config.screenScale = currentScale == 0 ? 1 : (currentScale < maxScale ? currentScale + 1 : maxScale);
            ConfigurationManager.save(Pico8Config.class);
            return;
        }

        int minusTop = scaleHudTop + SCALE_HUD_BUTTON_HEIGHT + SCALE_HUD_GAP + SCALE_HUD_VALUE_HEIGHT + SCALE_HUD_GAP;
        if (mouseButton == 0 && isInsideScaleHudButton(mouseX, mouseY, minusTop)) {
            int maxScale = getMaxFitScale();
            Pico8Config.screenScale = Pico8Config.screenScale == 0 ? 0
                : Math.max(0, Math.min(Pico8Config.screenScale, maxScale) - 1);
            ConfigurationManager.save(Pico8Config.class);
            return;
        }
        super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    private boolean isInsideScaleHudButton(int mouseX, int mouseY, int buttonTop) {
        return mouseX >= scaleHudLeft && mouseX < scaleHudLeft + SCALE_HUD_WIDTH
            && mouseY >= buttonTop
            && mouseY < buttonTop + SCALE_HUD_BUTTON_HEIGHT;
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

    private void drawScreenText() {
        drawCenteredString(fontRendererObj, cartFile.getName(), width / 2, pixelToGuiY(HINT_GAP), 0xFFFFFFFF);
        drawCenteredString(
            fontRendererObj,
            StatCollector.translateToLocal("gui.pico8.runtime.reselect"),
            width / 2,
            height - pixelToGuiY(HINT_GAP) - FONT_HEIGHT,
            0xFFAAAAAA);
    }

    private void drawControlPanel() {
        int left = width - CONTROL_PANEL_WIDTH - pixelToGuiX(SCALE_HUD_SCREEN_GAP);
        int top = (height - CONTROL_PANEL_HEIGHT) / 2;
        int playerOneCenter = left + CONTROL_ICON_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH / 2;
        int playerTwoCenter = left + CONTROL_ICON_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH + PLAYER_COLUMN_WIDTH / 2;
        int headerY = top + (CONTROL_HEADER_HEIGHT - FONT_HEIGHT) / 2;
        drawCenteredString(fontRendererObj, "P1", playerOneCenter, headerY, 0xFFFFFFFF);
        drawCenteredString(fontRendererObj, "P2", playerTwoCenter, headerY, 0xFFFFFFFF);

        drawControlRow(DPAD_ICON, "←→↑↓", "SFED", left, top + CONTROL_HEADER_HEIGHT);
        drawControlRow(O_ICON, "Z/C/N", "Tab/W", left, top + CONTROL_HEADER_HEIGHT + CONTROL_ROW_HEIGHT);
        drawControlRow(X_ICON, "X/V/M", "Q", left, top + CONTROL_HEADER_HEIGHT + 2 * CONTROL_ROW_HEIGHT);

        int hintLeft = left + CONTROL_PANEL_WIDTH / 2;
        int hintTop = top + CONTROL_TABLE_HEIGHT + SAVE_HINT_GAP;
        drawCenteredString(
            fontRendererObj,
            "P: " + StatCollector.translateToLocal("gui.pico8.runtime.save"),
            hintLeft,
            hintTop,
            0xFFAAAAAA);
        drawCenteredString(
            fontRendererObj,
            "L: " + StatCollector.translateToLocal("gui.pico8.runtime.load"),
            hintLeft,
            hintTop + FONT_HEIGHT + SAVE_HINT_GAP,
            0xFFAAAAAA);
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
        if (keyCode == Keyboard.KEY_P) {
            saveGameState();
            return;
        }
        if (keyCode == Keyboard.KEY_L) {
            loadGameState();
            return;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(null);
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    private void saveGameState() {
        if (session == null) {
            return;
        }
        try {
            session.saveState();
        } catch (RuntimeException exception) {
            Pico8GtnhMod.LOG.error("Could not save PICO-8 cartridge state {}", cartFile, exception);
        }
    }

    private void loadGameState() {
        if (session == null) {
            return;
        }
        try {
            if (session.loadSavedState()) {
                copyFrameToTexture();
                lastFrameNanos = System.nanoTime();
            }
        } catch (RuntimeException exception) {
            Pico8GtnhMod.LOG.error("Could not load PICO-8 cartridge state {}", cartFile, exception);
        }
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
