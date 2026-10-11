package com.yuelengm.pico8gtnh.service;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import net.jpountz.lz4.LZ4Factory;

/** Owns a frame copy; the worker never accesses WASM or the simulation timeline. */
public final class AsyncSnapshot implements Runnable {

    private static final int QUEUE_CAPACITY = 4;
    private static final int MAX_PENDING_FRAMES = QUEUE_CAPACITY + 1;
    private static final long MIN_PENDING_BYTES = 64L * 1024 * 1024;
    // Include both envelope headers, even for a snapshot at the raw WASM size limit.
    private static final long MAX_PENDING_BYTES = MAX_PENDING_FRAMES * (PicoRRuntime.MAX_MEMORY_BYTES + 8L);
    private static final AtomicLong BUDGET_BYTES = new AtomicLong(MIN_PENDING_BYTES);
    private static final AtomicLong PENDING_BYTES = new AtomicLong();
    private static final AtomicLong COMPRESS_COUNT = new AtomicLong();
    private static final AtomicLong COMPRESS_NANOS = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final ThreadLocal<byte[]> SCRATCH = new ThreadLocal<>();
    private static final ThreadPoolExecutor WORKER = new ThreadPoolExecutor(
        1,
        1,
        30,
        TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(QUEUE_CAPACITY),
        task -> {
            Thread thread = new Thread(task, "PICO-8 snapshot compression");
            thread.setDaemon(true);
            return thread;
        });
    private volatile byte[] state;
    static {
        WORKER.allowCoreThreadTimeOut(true);
    }
    private volatile boolean discarded;
    private int reservedBytes;

    private AsyncSnapshot(byte[] state) {
        this.state = state;
    }

    public static AsyncSnapshot completed(byte[] state) {
        return new AsyncSnapshot(state);
    }

    public static AsyncSnapshot submit(byte[] raw) {
        AsyncSnapshot snapshot = new AsyncSnapshot(raw);
        synchronized (WORKER) {
            // Reserve enough for one active frame plus the entire bounded queue.
            long budget = Math
                .min(MAX_PENDING_BYTES, Math.max(MIN_PENDING_BYTES, MAX_PENDING_FRAMES * (long) raw.length));
            BUDGET_BYTES.set(budget);
            if (PENDING_BYTES.get() + raw.length > budget || WORKER.getQueue()
                .remainingCapacity() == 0) {
                SKIPPED.incrementAndGet();
                return snapshot;
            }
            snapshot.reservedBytes = raw.length;
            PENDING_BYTES.addAndGet(raw.length);
            WORKER.execute(snapshot);
        }
        return snapshot;
    }

    public byte[] state() {
        return state;
    }

    public int size() {
        return state.length;
    }

    public boolean raw() {
        return ByteBuffer.wrap(state)
            .getInt(4) < 0;
    }

    /** Network snapshots keep the existing compressed wire representation. */
    public byte[] compressedState() {
        byte[] current = state;
        return ByteBuffer.wrap(current)
            .getInt(4) < 0 ? compress(current) : current;
    }

    @Override
    public void run() {
        try {
            if (discarded) return;
            byte[] compressed = compress(state);
            if (!discarded) state = compressed;
        } finally {
            release();
        }
    }

    public void discard() {
        discarded = true;
        if (WORKER.remove(this)) release();
    }

    private synchronized void release() {
        if (reservedBytes != 0) {
            PENDING_BYTES.addAndGet(-reservedBytes);
            reservedBytes = 0;
        }
    }

    private static byte[] compress(byte[] raw) {
        long started = System.nanoTime();
        int length = -ByteBuffer.wrap(raw)
            .getInt(4);
        var compressor = LZ4Factory.safeInstance()
            .fastCompressor();
        int capacity = 8 + compressor.maxCompressedLength(length);
        byte[] output = SCRATCH.get();
        if (output == null || output.length < capacity) {
            output = new byte[capacity];
            SCRATCH.set(output);
        }
        ByteBuffer.wrap(output)
            .putInt(
                ByteBuffer.wrap(raw)
                    .getInt())
            .putInt(length);
        int written = compressor.compress(raw, 8, length, output, 8, output.length - 8);
        byte[] result = Arrays.copyOf(output, written + 8);
        COMPRESS_NANOS.addAndGet(System.nanoTime() - started);
        COMPRESS_COUNT.incrementAndGet();
        return result;
    }

    public static String diagnostics() {
        return "compressionPendingMiB=" + PENDING_BYTES.get() / 1048576.0
            + " compressionBudgetMiB="
            + BUDGET_BYTES.get() / 1048576.0
            + " compressionQueue="
            + WORKER.getQueue()
                .size()
            + " compressionAvgMs="
            + COMPRESS_NANOS.get() / 1e6 / Math.max(1, COMPRESS_COUNT.get())
            + " compressionSkippedTotal="
            + SKIPPED.get();
    }
}
