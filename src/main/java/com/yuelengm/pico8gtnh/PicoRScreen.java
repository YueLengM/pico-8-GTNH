package com.yuelengm.pico8gtnh;

import java.io.File;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.util.List;

import javax.sound.sampled.LineUnavailableException;

import net.minecraft.client.audio.SoundCategory;
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

    private static final long NANOS_PER_SECOND = 1000000000L;
    private static final int MAX_CATCH_UP_FRAMES = 4;
    private static final List<GarbageCollectorMXBean> GC_COLLECTORS = ManagementFactory.getGarbageCollectorMXBeans();
    private static final int BUTTON_LEFT = 1;
    private static final int BUTTON_RIGHT = 1 << 1;
    private static final int BUTTON_UP = 1 << 2;
    private static final int BUTTON_DOWN = 1 << 3;
    private static final int BUTTON_O = 1 << 4;
    private static final int BUTTON_X = 1 << 5;

    private final ResourceLocation textureLocation = new ResourceLocation(Pico8GtnhMod.MODID, "pico8_screen");
    private final File cartFile;
    private PicoRRuntime runtime;
    private DynamicTexture texture;
    private int[] texturePixels;
    private PicoRAudioOutput audioOutput;
    private int audioSampleRemainder;
    private String error;
    private long lastFrameNanos;
    private long lastSlowFrameReportNanos;

    public PicoRScreen(File cartFile) {
        this.cartFile = cartFile;
    }

    @Override
    public void initGui() {
        try {
            runtime = PicoRRuntime.loadBundled();
            if (cartFile == null || !cartFile.isFile()) {
                throw new IOException("Cartridge file does not exist");
            }
            runtime.loadCart(Files.readAllBytes(cartFile.toPath()));

            texture = new DynamicTexture(PicoRRuntime.SCREEN_WIDTH, PicoRRuntime.SCREEN_HEIGHT);
            texturePixels = texture.getTextureData();
            mc.getTextureManager()
                .loadTexture(textureLocation, texture);
            runtime.update();
            copyFrameToTexture();
            lastFrameNanos = System.nanoTime();
            try {
                audioOutput = new PicoRAudioOutput();
            } catch (LineUnavailableException | IllegalArgumentException exception) {
                Pico8GtnhMod.LOG.error("Could not open PICO-8 audio output", exception);
            }
        } catch (IOException | RuntimeException | LinkageError exception) {
            error = exception.getMessage();
            Pico8GtnhMod.LOG.error("Could not load PICO-8 cartridge " + cartFile, exception);
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawDefaultBackground();
        if (error != null) {
            drawCenteredString(fontRendererObj, "PICO-8", width / 2, height / 2 - 20, 0xFFFFFFFF);
            drawCenteredString(fontRendererObj, "PICO-R failed to start", width / 2, height / 2 - 10, 0xFFFF5555);
            drawCenteredString(fontRendererObj, error, width / 2, height / 2 + 5, 0xFFFFFFFF);
        } else if (runtime != null) {
            long now = System.nanoTime();
            long frameInterval = NANOS_PER_SECOND / runtime.getFramesPerSecond();
            long overdueFrames = (now - lastFrameNanos) / frameInterval;
            if (overdueFrames > 0) {
                runtime.setButtons(0, readButtonBits());
                int framesToRun = (int) Math.min(overdueFrames, MAX_CATCH_UP_FRAMES);
                long updateNanos = 0;
                long slowestUpdateNanos = 0;
                long audioNanos = 0;
                for (int frame = 0; frame < framesToRun; frame++) {
                    long updateStart = System.nanoTime();
                    runtime.update();
                    long frameUpdateNanos = System.nanoTime() - updateStart;
                    updateNanos += frameUpdateNanos;
                    slowestUpdateNanos = Math.max(slowestUpdateNanos, frameUpdateNanos);

                    long audioStart = System.nanoTime();
                    queueAudioFrame();
                    audioNanos += System.nanoTime() - audioStart;
                }
                long uploadStart = System.nanoTime();
                copyFrameToTexture();
                long uploadNanos = System.nanoTime() - uploadStart;
                long completedAt = System.nanoTime();
                if (slowestUpdateNanos >= frameInterval
                    && completedAt - lastSlowFrameReportNanos >= 2 * NANOS_PER_SECOND) {
                    Runtime javaRuntime = Runtime.getRuntime();
                    long usedHeapMegabytes = (javaRuntime.totalMemory() - javaRuntime.freeMemory()) / (1024 * 1024);
                    long gcCount = 0;
                    long gcTimeMillis = 0;
                    for (GarbageCollectorMXBean collector : GC_COLLECTORS) {
                        if (collector.getCollectionCount() > 0) {
                            gcCount += collector.getCollectionCount();
                        }
                        if (collector.getCollectionTime() > 0) {
                            gcTimeMillis += collector.getCollectionTime();
                        }
                    }
                    Pico8GtnhMod.LOG.warn(
                        "Slow PICO-8 frame in {}: updates={}, updateTotal={}ms, updateMax={}ms, audio={}ms, texture={}ms, heap={}MB, wasmPages={}, gcCount={}, gcTime={}ms",
                        cartFile.getName(),
                        framesToRun,
                        updateNanos / 1000000,
                        slowestUpdateNanos / 1000000,
                        audioNanos / 1000000,
                        uploadNanos / 1000000,
                        usedHeapMegabytes,
                        runtime.getWasmMemoryPages(),
                        gcCount,
                        gcTimeMillis);
                    lastSlowFrameReportNanos = completedAt;
                }
                lastFrameNanos += frameInterval * framesToRun;
                if (now - lastFrameNanos >= frameInterval) {
                    lastFrameNanos = now;
                }
            }

            int scale = Math
                .max(1, Math.min((width - 32) / PicoRRuntime.SCREEN_WIDTH, (height - 70) / PicoRRuntime.SCREEN_HEIGHT));
            int gameSize = PicoRRuntime.SCREEN_WIDTH * scale;
            int left = (width - gameSize) / 2;
            int top = (height - gameSize) / 2;
            drawCenteredString(fontRendererObj, cartFile.getName(), width / 2, top - 18, 0xFFFFFFFF);
            mc.getTextureManager()
                .bindTexture(textureLocation);
            GL11.glPushMatrix();
            GL11.glTranslatef(left, top, 0.0F);
            GL11.glScalef(scale, scale, 1.0F);
            GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
            drawScreenTexture();
            GL11.glPopMatrix();
            drawCenteredString(
                fontRendererObj,
                "Arrows: move    Z/C: O    X: X    Esc: cartridges",
                width / 2,
                top + gameSize + 10,
                0xFFAAAAAA);
        }
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    @Override
    protected void keyTyped(char typedChar, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            mc.displayGuiScreen(new Pico8CartridgeScreen());
            return;
        }
        super.keyTyped(typedChar, keyCode);
    }

    @Override
    public void onGuiClosed() {
        if (audioOutput != null) {
            audioOutput.close();
            audioOutput = null;
        }
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
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
        if (Keyboard.isKeyDown(Keyboard.KEY_Z) || Keyboard.isKeyDown(Keyboard.KEY_C)) bits |= BUTTON_O;
        if (Keyboard.isKeyDown(Keyboard.KEY_X)) bits |= BUTTON_X;
        return bits;
    }

    private void copyFrameToTexture() {
        runtime.copyPixelsTo(texturePixels);
        texture.updateDynamicTexture();
    }

    private void queueAudioFrame() {
        if (audioOutput == null) {
            return;
        }

        int framesPerSecond = runtime.getFramesPerSecond();
        int sampleCount = PicoRRuntime.AUDIO_SAMPLE_RATE / framesPerSecond;
        audioSampleRemainder += PicoRRuntime.AUDIO_SAMPLE_RATE % framesPerSecond;
        if (audioSampleRemainder >= framesPerSecond) {
            sampleCount++;
            audioSampleRemainder -= framesPerSecond;
        }

        float masterVolume = mc.gameSettings.getSoundLevel(SoundCategory.MASTER);
        float recordsVolume = mc.gameSettings.getSoundLevel(SoundCategory.RECORDS);
        audioOutput.submit(runtime.generateAudio(sampleCount, masterVolume * recordsVolume));
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
