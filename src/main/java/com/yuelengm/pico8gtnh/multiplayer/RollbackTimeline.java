package com.yuelengm.pico8gtnh.multiplayer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** S[f] is the state before update f; input f produces S[f+1]. No renderer or network dependency. */
public final class RollbackTimeline {

    public interface Engine {

        byte[] save();

        void load(byte[] state);

        void update(int p1, int p2, boolean replay);
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
        public final Map<UUID, Input> held = new LinkedHashMap<>();
        public final List<Input> inputs = new ArrayList<>();
    }

    private final Engine engine;
    private final int historyFrames;
    private final TreeMap<Long, Snapshot> history = new TreeMap<>();
    private final TreeMap<Long, Map<UUID, Input>> events = new TreeMap<>();
    private final TreeMap<Long, Map<UUID, Input>> boundaries = new TreeMap<>();
    private final Map<UUID, Input> held = new LinkedHashMap<>();
    private long frame;

    public RollbackTimeline(Engine engine, int historyFrames) {
        this.engine = engine;
        this.historyFrames = historyFrames;
        capture();
    }

    public long frame() {
        return frame;
    }

    public boolean record(Input input) {
        if (input.frame < history.firstKey() || input.frame > frame + historyFrames) return false;
        if (input.boundary) boundaries.computeIfAbsent(input.frame, ignored -> new LinkedHashMap<>())
            .put(input.player, input);
        Map<UUID, Input> changes = events.computeIfAbsent(input.frame, ignored -> new LinkedHashMap<>());
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
            engine.load(saved.state);
            held.clear();
            held.putAll(saved.held);
            frame = from;
            history.tailMap(frame, false)
                .clear();
            while (frame < target) advance(true);
        }
    }

    /** Remove a prediction explicitly rejected by the server, without disturbing a permission boundary. */
    public boolean retract(UUID player, long atFrame, long sequence) {
        if (atFrame < history.firstKey()) return false;
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
        engine.update(p1, p2, replay);
        frame++;
        held.entrySet()
            .removeIf(
                entry -> entry.getValue().p1 == 0 && entry.getValue().p2 == 0
                    && frame - entry.getValue().frame > historyFrames);
        capture();
        while (history.firstKey() < frame - historyFrames) history.pollFirstEntry();
        events.headMap(history.firstKey(), false)
            .clear();
        boundaries.headMap(history.firstKey(), false)
            .clear();
    }

    /** Permission boundaries invalidate any predicted input at or after the authoritative boundary. */
    public boolean release(UUID player, long atFrame, long sequence) {
        if (atFrame < history.firstKey() || atFrame > frame + historyFrames) return false;
        for (Map<UUID, Input> changes : events.tailMap(atFrame, true)
            .values()) changes.remove(player);
        for (Map<UUID, Input> changes : boundaries.tailMap(atFrame, true)
            .values()) changes.remove(player);
        return record(new Input(player, atFrame, sequence, 0, 0, true));
    }

    private void capture() {
        Snapshot snapshot = new Snapshot();
        snapshot.frame = frame;
        snapshot.target = frame;
        snapshot.state = engine.save();
        if (snapshot.state == null || snapshot.state.length == 0)
            throw new IllegalStateException("PICO-R snapshot failed");
        snapshot.held.putAll(held);
        history.put(frame, snapshot);
    }

    /** Send the oldest retained state and its complete input suffix, protecting an in-flight join from late inputs. */
    public Snapshot snapshot() {
        Snapshot base = history.firstEntry()
            .getValue();
        Snapshot result = new Snapshot();
        result.frame = base.frame;
        result.target = frame;
        result.state = base.state;
        result.held.putAll(base.held);
        for (Map<UUID, Input> changes : events.tailMap(base.frame, true)
            .values()) result.inputs.addAll(changes.values());
        return result;
    }

    public void restore(Snapshot snapshot) {
        if (snapshot.target < snapshot.frame || snapshot.target - snapshot.frame > historyFrames)
            throw new IllegalArgumentException("Invalid snapshot frame range");
        engine.load(snapshot.state);
        frame = snapshot.frame;
        held.clear();
        held.putAll(snapshot.held);
        history.clear();
        events.clear();
        boundaries.clear();
        capture();
        for (Input input : snapshot.inputs) {
            if (!record(input)) throw new IllegalArgumentException("Snapshot input outside history");
        }
        while (frame < snapshot.target) advance(true);
    }
}
