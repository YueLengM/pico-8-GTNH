package com.yuelengm.pico8gtnh.service;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

import javax.sound.sampled.LineUnavailableException;

import net.minecraft.client.Minecraft;
import net.minecraft.client.audio.SoundCategory;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

/** Keeps one loaded PICO-R cartridge alive while its screen is closed. */
public final class PicoRSession {

    private static PicoRSession current;

    private final File cartFile;
    private final PicoRRuntime runtime;
    private final PicoRAudioOutput audioOutput;
    private int audioSampleRemainder;

    private PicoRSession(File cartFile, PicoRRuntime runtime, PicoRAudioOutput audioOutput) {
        this.cartFile = cartFile;
        this.runtime = runtime;
        this.audioOutput = audioOutput;
    }

    /** Resumes the matching session, or creates one if it is not currently loaded. */
    public static synchronized PicoRSession resume(File cartFile) throws IOException {
        File canonicalCart = cartFile.getCanonicalFile();
        if (current != null && current.cartFile.equals(canonicalCart)) {
            current.resume();
            return current;
        }

        closeCurrent();
        return load(canonicalCart);
    }

    /** Replaces the current session and starts the selected cartridge from its initial state. */
    public static synchronized PicoRSession startNew(File cartFile) throws IOException {
        File canonicalCart = cartFile.getCanonicalFile();
        closeCurrent();
        return load(canonicalCart);
    }

    private static PicoRSession load(File canonicalCart) throws IOException {
        PicoRRuntime runtime = PicoRRuntime.loadBundled();
        try {
            if (!canonicalCart.isFile()) {
                throw new IOException("Cartridge file does not exist");
            }
            runtime.loadCart(Files.readAllBytes(canonicalCart.toPath()));
            runtime.update();

            PicoRAudioOutput audioOutput = null;
            try {
                audioOutput = new PicoRAudioOutput();
            } catch (LineUnavailableException | IllegalArgumentException exception) {
                Pico8GtnhMod.LOG.error("Could not open PICO-8 audio output", exception);
            }
            current = new PicoRSession(canonicalCart, runtime, audioOutput);
            return current;
        } catch (IOException | RuntimeException | LinkageError exception) {
            runtime.close();
            throw exception;
        }
    }

    public static synchronized File getCurrentCartFile() {
        return current == null ? null : current.cartFile;
    }

    public PicoRRuntime getRuntime() {
        return runtime;
    }

    public void pause() {
        if (audioOutput != null) {
            audioOutput.pause();
        }
    }

    public void queueAudioFrame() {
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

        Minecraft minecraft = Minecraft.getMinecraft();
        float masterVolume = minecraft.gameSettings.getSoundLevel(SoundCategory.MASTER);
        float recordsVolume = minecraft.gameSettings.getSoundLevel(SoundCategory.RECORDS);
        audioOutput.submit(runtime.generateAudio(sampleCount, masterVolume * recordsVolume));
    }

    private void resume() {
        if (audioOutput != null) {
            audioOutput.resume();
        }
    }

    private static void closeCurrent() {
        if (current != null) {
            current.audioOutputClose();
            current.runtime.close();
            current = null;
        }
    }

    private void audioOutputClose() {
        if (audioOutput != null) {
            audioOutput.close();
        }
    }
}
