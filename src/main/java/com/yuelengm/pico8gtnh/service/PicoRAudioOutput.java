package com.yuelengm.pico8gtnh.service;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

/** Streams generated PICO-R PCM samples to the system audio device. */
public final class PicoRAudioOutput implements AutoCloseable {

    private static final AudioFormat FORMAT = new AudioFormat(PicoRRuntime.AUDIO_SAMPLE_RATE, 16, 1, true, false);
    private static final int QUEUE_CAPACITY = 8;

    private final SourceDataLine line;
    private final ArrayBlockingQueue<byte[]> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final Thread outputThread;
    private volatile boolean running = true;
    private volatile boolean paused;
    private volatile long playbackGeneration;

    public PicoRAudioOutput() throws LineUnavailableException {
        line = AudioSystem.getSourceDataLine(FORMAT);
        line.open(FORMAT, PicoRRuntime.AUDIO_SAMPLE_RATE);
        line.start();

        outputThread = new Thread(this::writeQueuedAudio, "PICO-8 audio output");
        outputThread.setDaemon(true);
        outputThread.start();
    }

    public void submit(byte[] pcm) {
        if (running && !paused && pcm.length > 0 && !queue.offer(pcm)) {
            queue.poll();
            queue.offer(pcm);
        }
    }

    public void pause() {
        paused = true;
        playbackGeneration++;
        queue.clear();
        line.stop();
        line.flush();
    }

    public void resume() {
        if (running && paused) {
            line.start();
            paused = false;
        }
    }

    @Override
    public void close() {
        running = false;
        queue.clear();
        line.stop();
        line.flush();
        line.close();
        outputThread.interrupt();
    }

    private void writeQueuedAudio() {
        while (running) {
            try {
                long generation = playbackGeneration;
                byte[] pcm = queue.poll(100, TimeUnit.MILLISECONDS);
                if (pcm != null) {
                    int offset = 0;
                    while (running && !paused && generation == playbackGeneration && offset < pcm.length) {
                        offset += line.write(pcm, offset, pcm.length - offset);
                    }
                }
            } catch (InterruptedException exception) {
                if (running) {
                    Thread.currentThread()
                        .interrupt();
                    return;
                }
            } catch (RuntimeException exception) {
                Pico8GtnhMod.LOG.error("PICO-8 audio output stopped unexpectedly", exception);
                return;
            }
        }
    }
}
