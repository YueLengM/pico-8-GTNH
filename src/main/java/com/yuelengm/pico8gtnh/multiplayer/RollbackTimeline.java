package com.yuelengm.pico8gtnh.multiplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.yuelengm.pico8gtnh.service.AsyncSnapshot;

import it.unimi.dsi.fastutil.longs.Long2ObjectRBTreeMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;

/** S[f] is the state before update f; input f produces S[f+1]. No renderer or network dependency. */
public final class RollbackTimeline {

    public interface Engine {

        byte[] save();

        default AsyncSnapshot capture() {
            return AsyncSnapshot.completed(save());
        }

        void load(byte[] state);

        void update(int p1, int p2, boolean replay);

        default void reportDiagnostics(String summary) {}
    }

    public static final class Input {

        public final UUID player;
        public final long frame;
        public final long sequence;
        public final int p1;
        public final int p2;
        public final boolean boundary;

        public Input(UUID player, long frame, long sequence, int p1, int p2) {
            this(player, frame, sequence, p1, p2, false);
        }

        public Input(UUID player, long frame, long sequence, int p1, int p2, boolean boundary) {
            this.player = player;
            this.frame = frame;
            this.sequence = sequence;
            this.p1 = p1;
            this.p2 = p2;
            this.boundary = boundary;
        }
    }

    public static final class Snapshot {

        public long frame;
        public long target;
        public byte[] state;
        private AsyncSnapshot localState;
        public final Map<UUID, Input> held = new Object2ObjectLinkedOpenHashMap<>(4);
        public final List<Input> inputs = new ArrayList<>();
    }

    private final Engine engine;
    private final int historyFrames;
    private final Long2ObjectRBTreeMap<Snapshot> history = new Long2ObjectRBTreeMap<>();
    private final Long2ObjectRBTreeMap<Map<UUID, Input>> events = new Long2ObjectRBTreeMap<>();
    private final Long2ObjectRBTreeMap<Map<UUID, Input>> boundaries = new Long2ObjectRBTreeMap<>();
    private final Map<UUID, Input> held = new Object2ObjectLinkedOpenHashMap<>(4);
    private long frame;
    private long reportStarted = System.nanoTime();
    private long saveCount, saveNanos, maxSaveNanos, savedBytes, maxSavedBytes;
    private long updateCount, updateNanos, maxUpdateNanos, forwardFrames, replayFrames;
    private long loadCount, loadNanos, maxLoadNanos;
    private boolean initialReport = true;

    public RollbackTimeline(Engine engine, int historyFrames) {
        this.engine = engine;
        this.historyFrames = historyFrames;
        capture();
    }

    public long frame() {
        return frame;
    }

    public boolean record(Input input) {
        if (input.frame < history.firstLongKey() || input.frame > frame + historyFrames) return false;
        if (input.boundary) boundaries.computeIfAbsent(input.frame, ignored -> new Object2ObjectLinkedOpenHashMap<>(4))
            .put(input.player, input);
        Map<UUID, Input> changes = events
            .computeIfAbsent(input.frame, ignored -> new Object2ObjectLinkedOpenHashMap<>(4));
        Input previous = changes.get(input.player);
        if (previous != null && previous.sequence >= input.sequence) return true;
        changes.put(input.player, input);
        replayFrom(input.frame);
        return true;
    }

    private void replayFrom(long from) {
        if (from < frame) {
            long target = frame;
            Snapshot saved = history.get(from);
            loadMeasured(saved.localState.state());
            held.clear();
            held.putAll(saved.held);
            frame = from;
            for (Snapshot stale : history.tailMap(frame + 1)
                .values()) stale.localState.discard();
            history.tailMap(frame + 1)
                .clear();
            while (frame < target) advance(true);
        }
    }

    /** Remove a prediction explicitly rejected by the server, without disturbing a permission boundary. */
    public boolean retract(UUID player, long atFrame, long sequence) {
        if (atFrame < history.firstLongKey()) return false;
        Map<UUID, Input> changes = events.get(atFrame);
        Input input = changes == null ? null : changes.get(player);
        if (input == null || input.sequence != sequence || input.boundary) return true;
        Input boundary = boundaries.containsKey(atFrame) ? boundaries.get(atFrame)
            .get(player) : null;
        if (boundary == null) changes.remove(player);
        else changes.put(player, boundary);
        replayFrom(atFrame);
        return true;
    }

    public void advance(boolean replay) {
        Map<UUID, Input> changes = events.get(frame);
        if (changes != null) {
            for (Input input : changes.values()) {
                held.put(input.player, input);
            }
        }
        int p1 = 0, p2 = 0;
        for (Input input : held.values()) {
            p1 |= input.p1;
            p2 |= input.p2;
        }
        long updateStarted = System.nanoTime();
        engine.update(p1, p2, replay);
        long updateElapsed = System.nanoTime() - updateStarted;
        updateCount++;
        updateNanos += updateElapsed;
        maxUpdateNanos = Math.max(maxUpdateNanos, updateElapsed);
        if (replay) replayFrames++;
        else forwardFrames++;
        frame++;
        held.entrySet()
            .removeIf(
                entry -> entry.getValue().p1 == 0 && entry.getValue().p2 == 0
                    && frame - entry.getValue().frame > historyFrames);
        capture();
        while (history.firstLongKey() < frame - historyFrames)
            history.remove(history.firstLongKey()).localState.discard();
        events.headMap(history.firstLongKey())
            .clear();
        boundaries.headMap(history.firstLongKey())
            .clear();
        reportDiagnostics();
    }

    /** Permission boundaries invalidate any predicted input at or after the authoritative boundary. */
    public boolean release(UUID player, long atFrame, long sequence) {
        if (atFrame < history.firstLongKey() || atFrame > frame + historyFrames) return false;
        for (Map<UUID, Input> changes : events.tailMap(atFrame)
            .values()) changes.remove(player);
        for (Map<UUID, Input> changes : boundaries.tailMap(atFrame)
            .values()) changes.remove(player);
        return record(new Input(player, atFrame, sequence, 0, 0, true));
    }

    private void capture() {
        Snapshot snapshot = new Snapshot();
        snapshot.frame = frame;
        snapshot.target = frame;
        long saveStarted = System.nanoTime();
        snapshot.localState = engine.capture();
        long saveElapsed = System.nanoTime() - saveStarted;
        if (snapshot.localState == null || snapshot.localState.size() == 0)
            throw new IllegalStateException("PICO-R snapshot failed");
        snapshot.held.putAll(held);
        Snapshot replaced = history.put(frame, snapshot);
        if (replaced != null) replaced.localState.discard();
        saveCount++;
        saveNanos += saveElapsed;
        maxSaveNanos = Math.max(maxSaveNanos, saveElapsed);
        savedBytes += snapshot.localState.size();
        maxSavedBytes = Math.max(maxSavedBytes, snapshot.localState.size());
        if (initialReport) {
            initialReport = false;
            reportDiagnostics();
        }
    }

    private void loadMeasured(byte[] state) {
        long started = System.nanoTime();
        engine.load(state);
        long elapsed = System.nanoTime() - started;
        loadCount++;
        loadNanos += elapsed;
        maxLoadNanos = Math.max(maxLoadNanos, elapsed);
    }

    private void reportDiagnostics() {
        long now = System.nanoTime();
        long elapsed = now - reportStarted;
        if (frame != 0 && elapsed < 5_000_000_000L) return;
        long historyBytes = 0;
        long rawHistoryBytes = 0;
        for (Snapshot state : history.values()) {
            historyBytes += state.localState.size();
            if (state.localState.raw()) rawHistoryBytes += state.localState.size();
        }
        engine.reportDiagnostics(
            String.format(
                Locale.ROOT,
                "frame=%d window=%.2fs forwardFps=%.2f replayFrames=%d " + "saveCount=%d saveAvgMs=%.3f saveMaxMs=%.3f "
                    + "snapshotAvgKiB=%.1f snapshotMaxKiB=%.1f historyCount=%d historyMiB=%.2f "
                    + "updateAvgMs=%.3f updateMaxMs=%.3f loadCount=%d loadAvgMs=%.3f loadMaxMs=%.3f",
                frame,
                elapsed / 1e9,
                forwardFrames * 1e9 / Math.max(1, elapsed),
                replayFrames,
                saveCount,
                saveNanos / 1e6 / Math.max(1, saveCount),
                maxSaveNanos / 1e6,
                savedBytes / 1024.0 / Math.max(1, saveCount),
                maxSavedBytes / 1024.0,
                history.size(),
                historyBytes / 1048576.0,
                updateNanos / 1e6 / Math.max(1, updateCount),
                maxUpdateNanos / 1e6,
                loadCount,
                loadNanos / 1e6 / Math.max(1, loadCount),
                maxLoadNanos / 1e6) + " rawHistoryMiB="
                + rawHistoryBytes / 1048576.0
                + " "
                + AsyncSnapshot.diagnostics());
        reportStarted = System.nanoTime();
        saveCount = saveNanos = maxSaveNanos = savedBytes = maxSavedBytes = 0;
        updateCount = updateNanos = maxUpdateNanos = forwardFrames = replayFrames = 0;
        loadCount = loadNanos = maxLoadNanos = 0;
    }

    /** Send the oldest retained state and its complete input suffix, protecting an in-flight join from late inputs. */
    public Snapshot snapshot() {
        Snapshot base = history.get(history.firstLongKey());
        Snapshot result = new Snapshot();
        result.frame = base.frame;
        result.target = frame;
        result.state = base.localState.compressedState();
        result.held.putAll(base.held);
        for (Map<UUID, Input> changes : events.tailMap(base.frame)
            .values()) result.inputs.addAll(changes.values());
        return result;
    }

    public void restore(Snapshot snapshot) {
        if (snapshot.target < snapshot.frame || snapshot.target - snapshot.frame > historyFrames)
            throw new IllegalArgumentException("Invalid snapshot frame range");
        loadMeasured(snapshot.state);
        frame = snapshot.frame;
        held.clear();
        held.putAll(snapshot.held);
        discard();
        events.clear();
        boundaries.clear();
        capture();
        for (Input input : snapshot.inputs) {
            if (!record(input)) throw new IllegalArgumentException("Snapshot input outside history");
        }
        while (frame < snapshot.target) advance(true);
    }

    public void discard() {
        for (Snapshot snapshot : history.values()) snapshot.localState.discard();
        history.clear();
    }
}
