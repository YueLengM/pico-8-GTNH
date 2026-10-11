package com.yuelengm.pico8gtnh.service;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
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
    private byte[] savedState;
    private boolean closed;

    private PicoRSession(File cartFile, PicoRRuntime runtime, PicoRAudioOutput audioOutput) {
        this.cartFile = cartFile;
        this.runtime = runtime;
        this.audioOutput = audioOutput;
    }

    /** Resumes the matching session, or creates one if it is not currently loaded. */
    public static synchronized PicoRSession resume(File cartFile) throws IOException {
        File canonicalCart = cartFile.getCanonicalFile();
        if (current != null && canonicalCart.equals(current.cartFile)) {
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

    /** A room guest receives the loaded cartridge as part of the host's memory snapshot. */
    public static synchronized PicoRSession startRoomGuest() throws IOException {
        closeCurrent();
        PicoRRuntime runtime = PicoRRuntime.loadBundled();
        PicoRAudioOutput audio = null;
        try {
            audio = new PicoRAudioOutput();
        } catch (LineUnavailableException | IllegalArgumentException exception) {
            Pico8GtnhMod.LOG.error("Could not open PICO-8 audio output", exception);
        }
        current = new PicoRSession(null, runtime, audio);
        return current;
    }

    public PicoRRuntime getRuntime() {
        return runtime;
    }

    public boolean isRoomGuest() {
        return cartFile == null;
    }

    /** Only discard the expected session, never a subsequently loaded cartridge. */
    public static synchronized void discard(PicoRSession expected) {
        if (current == expected) closeCurrent();
    }

    /** Saves a snapshot for the current cartridge session. */
    public boolean saveState() {
        byte[] state = saveRollbackState();
        if (state == null || state.length == 0) {
            return false;
        }
        savedState = state;
        return true;
    }

    /** Restores the most recent snapshot saved in this session, if any. */
    public boolean loadSavedState() {
        if (savedState == null) {
            return false;
        }

        if (audioOutput != null) {
            audioOutput.pause();
        }
        try {
            loadRollbackState(savedState);
            return true;
        } finally {
            if (audioOutput != null) {
                audioOutput.resume();
            }
        }
    }

    public void pause() {
        if (closed) return;
        if (audioOutput != null) {
            audioOutput.pause();
        }
    }

    public void queueAudioFrame() {
        advanceAudioFrame(true);
    }

    /** Advances audio state even when playback is unavailable or a frame is being replayed. */
    public void advanceAudioFrame(boolean audible) {

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
        byte[] pcm = runtime.generateAudio(sampleCount, masterVolume * recordsVolume);
        if (audible && audioOutput != null) audioOutput.submit(pcm);
    }

    public AsyncSnapshot captureRollbackState() {
        return AsyncSnapshot.submit(runtime.copyMemoryState(audioSampleRemainder));
    }

    /** LZ4 linear memory plus the host-side fractional audio sample counter. */
    public byte[] saveRollbackState() {
        byte[] state = runtime.saveMemoryState();
        if (state == null) throw new IllegalStateException("Could not serialize PICO-R state");
        return ByteBuffer.allocate(Integer.BYTES + state.length)
            .putInt(audioSampleRemainder)
            .put(state)
            .array();
    }

    public void loadRollbackState(byte[] bytes) {
        if (bytes.length <= Integer.BYTES) throw new IllegalArgumentException("Invalid rollback snapshot");
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        int remainder = buffer.getInt();
        runtime.loadMemoryState(bytes, Integer.BYTES, bytes.length - Integer.BYTES);
        if (remainder < 0 || remainder >= runtime.getFramesPerSecond())
            throw new IllegalArgumentException("Invalid audio counter");
        audioSampleRemainder = remainder;
        if (audioOutput != null) {
            audioOutput.pause();
            audioOutput.resume();
        }
    }

    private void resume() {
        if (audioOutput != null) {
            audioOutput.resume();
        }
    }

    public static synchronized void closeCurrent() {
        if (current != null) {
            PicoRSession previous = current;
            current = null;
            previous.closed = true;
            previous.savedState = null;
            try {
                previous.audioOutputClose();
            } finally {
                previous.runtime.close();
            }
        }
    }

    private void audioOutputClose() {
        if (audioOutput != null) {
            audioOutput.close();
        }
    }
}
