package com.yuelengm.pico8gtnh.multiplayer;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

import net.minecraft.client.Minecraft;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.factory.ClientGUI;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.gui.PicoRScreen;
import com.yuelengm.pico8gtnh.service.PicoRRuntime;
import com.yuelengm.pico8gtnh.service.PicoRSession;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import cpw.mods.fml.common.network.FMLNetworkEvent;

/** All mutable state and emulator calls run on Minecraft's client thread. */
public final class RoomClient {

    public static final RoomClient INSTANCE = new RoomClient();
    private static final long SECOND = 1_000_000_000L;
    private final ArrayBlockingQueue<Runnable> inbound = new ArrayBlockingQueue<>(4096);
    private final Deque<ChunkTransfer.Sender> outgoing = new ArrayDeque<>();
    private final List<RoomInfo> publicRooms = new ArrayList<>();
    private final List<RoomPacket> buffered = new ArrayList<>();
    private RoomInfo room;
    private RoomInfo joining;
    private UUID roomId;
    private UUID serverPlayerId;
    private PicoRSession session;
    private RollbackTimeline timeline;
    private ChunkTransfer.Receiver stateTransfer;
    private String runtimeHash;
    private String status = "";
    private boolean ready;
    private boolean creating;
    private boolean openingGame;
    private long operationStarted;
    private long lastClockSent;
    private long hostFrame;
    private long hostClock;
    private long sequence;
    private int lastP1 = -1, lastP2 = -1;
    private long lastPing;
    private long pendingPing;
    private volatile boolean disconnected;
    private volatile boolean overflow;

    private RoomClient() {}

    public boolean available() {
        return !Pico8GtnhMod.isClientOnlyMode() && Minecraft.getMinecraft().thePlayer != null;
    }

    public RoomInfo room() {
        return room;
    }

    public List<RoomInfo> publicRooms() {
        return new ArrayList<>(publicRooms);
    }

    public String status() {
        return status;
    }

    public boolean busy() {
        return joining != null || creating;
    }

    public boolean isHost() {
        return room != null && room.host.equals(self());
    }

    public boolean isController() {
        RoomInfo.Member member = room == null ? null : room.member(self());
        return member != null && member.controller;
    }

    public boolean ready() {
        return ready;
    }

    public boolean active() {
        return session != null && roomId != null;
    }

    public boolean openingGame() {
        return openingGame;
    }

    public UUID self() {
        return serverPlayerId != null ? serverPlayerId : Minecraft.getMinecraft().thePlayer.getUniqueID();
    }

    public void enqueue(RoomPacket packet) {
        packet.receivedAt = System.nanoTime();
        if (!inbound.offer(() -> {
            try {
                receive(packet);
            } catch (IOException | RuntimeException exception) {
                Pico8GtnhMod.LOG.error("PICO-8 room synchronization failed", exception);
                fail("gui.pico8.room.transfer_error");
            }
        })) overflow = true;
    }

    public void refreshRooms() {
        if (available()) RoomNetwork.toServer(new RoomPacket(RoomPacket.Kind.LIST_REQUEST));
    }

    private String runtimeHash() throws IOException {
        if (runtimeHash == null)
            try (InputStream in = PicoRRuntime.class.getResourceAsStream("/assets/pico8/pico-r.wasm")) {
                if (in == null) throw new IOException("PICO-R missing");
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
                runtimeHash = RoomCodec.hash(bytes.toByteArray());
            }
        return runtimeHash;
    }

    private RollbackTimeline newTimeline(PicoRSession active, int fps) {
        PicoRRuntime runtime = active.getRuntime();
        return new RollbackTimeline(new RollbackTimeline.Engine() {

            @Override
            public byte[] save() {
                return active.saveRollbackState();
            }

            @Override
            public com.yuelengm.pico8gtnh.service.AsyncSnapshot capture() {
                return active.captureRollbackState();
            }

            @Override
            public void load(byte[] state) {
                active.loadRollbackState(state);
            }

            @Override
            public void update(int p1, int p2, boolean replay) {
                runtime.setButtons(0, p1);
                runtime.setButtons(1, p2);
                runtime.update();
                active.advanceAudioFrame(!replay);
            }

            @Override
            public void reportDiagnostics(String summary) {
                long gcCount = 0, gcMillis = 0;
                for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                    gcCount += Math.max(0, gc.getCollectionCount());
                    gcMillis += Math.max(0, gc.getCollectionTime());
                }
                Runtime jvm = Runtime.getRuntime();
                Pico8GtnhMod.LOG.info(
                    "PICO-8 snapshot profile: {} wasmBytes={} targetFps={} heapUsedBytes={} heapCommittedBytes={} "
                        + "gcCountTotal={} gcMsTotal={}",
                    summary,
                    (long) runtime.getWasmMemoryPages() * 65536,
                    fps,
                    jvm.totalMemory() - jvm.freeMemory(),
                    jvm.totalMemory(),
                    gcCount,
                    gcMillis);
            }
        }, fps);
    }

    public void attach(PicoRSession active, File cart, boolean asMember) throws IOException {
        if (!available()) return;
        if (asMember) {
            if (joining == null) throw new IOException("Room join cancelled");
            session = active;
            timeline = newTimeline(active, joining.fps);
            ready = false;
            return;
        }
        if (session == active && roomId != null) return;
        leave(false);
        session = active;
        roomId = UUID.randomUUID();
        ready = false;
        creating = true;
        operationStarted = System.nanoTime();
        status = "gui.pico8.room.creating";
        RoomInfo info = new RoomInfo();
        info.id = roomId;
        info.host = self();
        info.hostName = Minecraft.getMinecraft().thePlayer.getCommandSenderName();
        info.cartName = cart.getName();
        info.runtimeHash = runtimeHash();
        info.fps = active.getRuntime()
            .getFramesPerSecond();
        timeline = newTimeline(active, info.fps);
        RoomPacket create = new RoomPacket(RoomPacket.Kind.CREATE, roomId);
        create.data = RoomCodec.room(info);
        RoomNetwork.toServer(create);
    }

    public void publish() {
        if (!isHost() || session == null) return;
        RoomPacket packet = new RoomPacket(RoomPacket.Kind.VISIBILITY, roomId);
        packet.epoch = room.epoch;
        packet.flag = !room.published;
        RoomNetwork.toServer(packet);
    }

    public void toggleController(UUID player) {
        if (!isHost() || player.equals(self())) return;
        RoomInfo.Member member = room.member(player);
        if (member == null) return;
        RoomPacket packet = new RoomPacket(RoomPacket.Kind.PERMISSION, roomId);
        packet.player = player;
        packet.flag = !member.controller;
        packet.frame = timeline.frame();
        packet.epoch = room.epoch;
        RoomNetwork.toServer(packet);
    }

    public void join(RoomInfo selected) {
        if (!available() || busy() || roomId != null) return;
        try {
            if (!runtimeHash().equals(selected.runtimeHash)) {
                notifyKey("gui.pico8.room.incompatible");
                return;
            }
            joining = selected;
            requestJoin();
        } catch (IOException exception) {
            fail("gui.pico8.room.transfer_error");
        }
    }

    private void requestJoin() {
        if (joining == null) return;
        roomId = joining.id;
        status = "gui.pico8.room.syncing";
        operationStarted = System.nanoTime();
        RoomPacket join = new RoomPacket(RoomPacket.Kind.JOIN, roomId);
        join.text = joining.runtimeHash;
        RoomNetwork.toServer(join);
    }

    private void receive(RoomPacket packet) throws IOException {
        if (!available()) return;
        if (packet.kind == RoomPacket.Kind.LIST) {
            if (packet.flag) publicRooms.clear();
            else if (packet.data.length > 0) publicRooms.add(RoomCodec.room(packet.data));
            return;
        }
        if (packet.kind == RoomPacket.Kind.ERROR) {
            if (packet.room.equals(roomId) || joining != null && packet.room.equals(joining.id)) {
                if (isHost() && !creating) {
                    status = "";
                    notifyKey(packet.text);
                } else fail(packet.text);
            }
            return;
        }
        if (roomId == null || !roomId.equals(packet.room)) return;
        switch (packet.kind) {
            case CREATED:
                room = RoomCodec.room(packet.data);
                acceptServerIdentity(packet);
                creating = false;
                ready = true;
                status = "";
                hostClock = System.nanoTime();
                hostFrame = timeline.frame();
                break;
            case JOIN_WAIT:
                status = StatCollector
                    .translateToLocalFormatted("gui.pico8.room.cooldown", Math.max(1, (packet.frame + 999) / 1000));
                break;
            case MEMBERS:
                room = RoomCodec.room(packet.data);
                acceptServerIdentity(packet);
                if (joining != null && session == null) {
                    openingGame = true;
                    try {
                        ClientGUI.open(PicoRScreen.joinRoom());
                    } finally {
                        openingGame = false;
                    }
                }
                break;
            case SNAPSHOT_REQUEST:
                if (!isHost() || packet.epoch != room.epoch) return;
                RollbackTimeline.Snapshot outgoingSnapshot = timeline.snapshot();
                byte[] encodedSnapshot = RoomCodec.snapshot(outgoingSnapshot);
                Pico8GtnhMod.LOG.info(
                    "PICO-8 sending room snapshot: room={}, player={}, base={}, target={}, bytes={}",
                    roomId,
                    packet.player,
                    outgoingSnapshot.frame,
                    outgoingSnapshot.target,
                    encodedSnapshot.length);
                outgoing.removeIf(sender -> sender.kind == RoomPacket.Kind.SNAPSHOT_CHUNK);
                outgoing.add(
                    new ChunkTransfer.Sender(
                        RoomPacket.Kind.SNAPSHOT_CHUNK,
                        roomId,
                        packet.player,
                        room.epoch,
                        System.nanoTime(),
                        encodedSnapshot));
                break;
            case SNAPSHOT_CHUNK:
                if (isHost() || room == null || packet.epoch != room.epoch || ready) return;
                if (stateTransfer == null) stateTransfer = new ChunkTransfer.Receiver();
                byte[] state = stateTransfer.accept(packet, RoomPacket.MAX_STATE);
                if (state != null) finishSync(RoomCodec.snapshot(state));
                break;
            case INPUT:
            case PERMISSION:
                if (room == null || packet.epoch != room.epoch) return;
                if (!ready) {
                    if (buffered.size() >= 8192) throw new IOException("Join input buffer full");
                    buffered.add(packet);
                    return;
                }
                applyInput(packet);
                break;
            case REJECT_INPUT:
                if (room != null && ready && packet.epoch == room.epoch && packet.player.equals(self())) {
                    if (!timeline.retract(self(), packet.frame, packet.sequence) && RoomNetwork.LATENCY_KICKS_ENABLED)
                        fail("gui.pico8.room.latency");
                    lastP1 = -1;
                    lastP2 = -1;
                }
                break;
            case CLOCK:
                if (room == null || packet.epoch != room.epoch || !packet.player.equals(room.host)) return;
                hostFrame = packet.frame;
                hostClock = packet.receivedAt;
                break;
            case RESET:
                if (room == null || packet.epoch < room.epoch) return;
                room.epoch = packet.epoch;
                for (RoomInfo.Member member : room.members) member.controlFrame = 0;
                if (!isHost()) {
                    ready = false;
                    buffered.clear();
                    stateTransfer = null;
                    sequence = 0;
                    status = "gui.pico8.room.syncing";
                    operationStarted = System.nanoTime();
                    hostClock = 0;
                }
                break;
            case CLOSED:
                fail(packet.text);
                break;
            case PONG:
                if (pendingPing == packet.sequence) {
                    if (RoomNetwork.LATENCY_KICKS_ENABLED && ready
                        && !isHost()
                        && packet.receivedAt - pendingPing > SECOND) fail("gui.pico8.room.latency");
                    pendingPing = 0;
                }
                break;
            default:
                break;
        }
    }

    private void acceptServerIdentity(RoomPacket packet) throws IOException {
        if (packet.player.equals(RoomPacket.NONE) || room.member(packet.player) == null)
            throw new IOException("Missing server-assigned room player identity");
        if (serverPlayerId != null && !serverPlayerId.equals(packet.player))
            throw new IOException("Room player identity changed");
        serverPlayerId = packet.player;
    }

    private void applyInput(RoomPacket packet) {
        boolean accepted;
        if (packet.kind == RoomPacket.Kind.PERMISSION) {
            RoomInfo.Member member = room.member(packet.player);
            if (member != null) {
                member.controller = packet.flag;
                member.revision = packet.revision;
                member.controlFrame = packet.frame;
            }
            accepted = timeline.release(packet.player, packet.frame, packet.sequence);
            if (packet.player.equals(self())) {
                sequence = Math.max(sequence, packet.sequence);
                lastP1 = -1;
                lastP2 = -1;
            }
        } else {
            accepted = timeline
                .record(new RollbackTimeline.Input(packet.player, packet.frame, packet.sequence, packet.a, packet.b));
        }
        if (!accepted && RoomNetwork.LATENCY_KICKS_ENABLED) {
            if (isHost()) {
                RoomPacket kick = new RoomPacket(RoomPacket.Kind.KICK, roomId);
                kick.player = packet.player;
                kick.epoch = room.epoch;
                RoomNetwork.toServer(kick);
            } else fail("gui.pico8.room.latency");
        }
    }

    private void finishSync(RollbackTimeline.Snapshot snapshot) {
        if (timeline == null) throw new IllegalStateException("No room runtime");
        Pico8GtnhMod.LOG.info(
            "PICO-8 restoring room snapshot: room={}, base={}, target={}, stateBytes={}, bufferedInputs={}",
            roomId,
            snapshot.frame,
            snapshot.target,
            snapshot.state.length,
            buffered.size());
        if (snapshot.target < snapshot.frame || snapshot.target - snapshot.frame > room.fps)
            throw new IllegalArgumentException("Invalid snapshot frame range");
        long target = Math.max(snapshot.target, estimatedHost());
        if (target - snapshot.frame > 61L * room.fps)
            throw new IllegalArgumentException("Initial synchronization expired");
        List<RollbackTimeline.Input> inputs = new ArrayList<>(snapshot.inputs);
        // Fold permission boundaries in relay order before replaying chronological inputs.
        for (RoomPacket packet : buffered) {
            if (packet.frame < snapshot.frame) continue;
            if (packet.kind == RoomPacket.Kind.PERMISSION)
                inputs.removeIf(input -> input.player.equals(packet.player) && input.frame >= packet.frame);
            inputs.add(
                new RollbackTimeline.Input(
                    packet.player,
                    packet.frame,
                    packet.sequence,
                    packet.a,
                    packet.b,
                    packet.kind == RoomPacket.Kind.PERMISSION));
        }
        snapshot.inputs.clear();
        snapshot.target = snapshot.frame;
        timeline.restore(snapshot);
        inputs.sort(Comparator.comparingLong(input -> input.frame));
        for (RollbackTimeline.Input input : inputs) {
            while (timeline.frame() < Math.min(input.frame, target)) timeline.advance(true);
            if (!timeline.record(input)) throw new IllegalArgumentException("Join input outside retained history");
        }
        while (timeline.frame() < target) timeline.advance(true);
        long caughtUp = Math.max(target, estimatedHost());
        if (caughtUp - snapshot.frame > 61L * room.fps)
            throw new IllegalArgumentException("Initial synchronization expired");
        while (timeline.frame() < caughtUp) timeline.advance(true);
        Pico8GtnhMod.LOG.info("PICO-8 room snapshot restored: room={}, frame={}", roomId, timeline.frame());
        buffered.clear();
        stateTransfer = null;
        ready = true;
        joining = null;
        status = "";
        lastP1 = -1;
        lastP2 = -1;
        lastClockSent = 0;
        if (hostClock == 0) {
            hostFrame = timeline.frame();
            hostClock = System.nanoTime();
        }
        RoomPacket completed = new RoomPacket(RoomPacket.Kind.READY, roomId);
        completed.epoch = room.epoch;
        completed.frame = timeline.frame();
        RoomNetwork.toServer(completed);
    }

    private long estimatedHost() {
        return hostClock == 0 ? 0 : hostFrame + Math.max(0, System.nanoTime() - hostClock) * room.fps / SECOND;
    }

    public void advance(int p1, int p2) {
        if (!ready || timeline == null) return;
        RoomInfo.Member controller = room.member(self());
        if (controller != null && controller.controller) {
            if (p1 != lastP1 || p2 != lastP2) {
                RoomPacket input = new RoomPacket(RoomPacket.Kind.INPUT, roomId);
                input.player = self();
                input.epoch = room.epoch;
                input.frame = Math.max(timeline.frame(), controller.controlFrame);
                input.sequence = ++sequence;
                input.revision = controller.revision;
                input.a = p1;
                input.b = p2;
                if (!timeline.record(new RollbackTimeline.Input(self(), input.frame, input.sequence, p1, p2))
                    && RoomNetwork.LATENCY_KICKS_ENABLED) {
                    fail("gui.pico8.room.latency");
                    return;
                }
                RoomNetwork.toServer(input);
                lastP1 = p1;
                lastP2 = p2;
            }
        }
        timeline.advance(false);
    }

    public int framesToRun(int elapsedFrames) {
        if (!ready) return 0;
        if (isHost()) return Math.min(elapsedFrames, 8);
        long difference = estimatedHost() - timeline.frame();
        if (RoomNetwork.LATENCY_KICKS_ENABLED && Math.abs(difference) > room.fps) {
            fail("gui.pico8.room.latency");
            return 0;
        }
        if (difference <= 0) return 0;
        return (int) Math.min(8, Math.max(elapsedFrames, difference > 2 ? difference : 1));
    }

    public void resetAfterLoad() {
        if (!isHost()) return;
        if (timeline != null) timeline.discard();
        timeline = newTimeline(session, room.fps);
        sequence = 0;
        lastP1 = -1;
        lastP2 = -1;
        outgoing.clear();
        for (RoomInfo.Member member : room.members) member.controlFrame = 0;
        RoomPacket reset = new RoomPacket(RoomPacket.Kind.RESET, roomId);
        reset.epoch = ++room.epoch;
        RoomNetwork.toServer(reset);
    }

    private void notifyKey(String key) {
        if (Minecraft.getMinecraft().thePlayer != null)
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentTranslation(key));
    }

    private void fail(String key) {
        boolean playing = session != null;
        notifyKey(key);
        leave(false);
        status = key;
        if (playing && Minecraft.getMinecraft().currentScreen instanceof PicoRScreen) Minecraft.getMinecraft()
            .displayGuiScreen(null);
    }

    public void leave(boolean notify) {
        if (roomId != null && available()) RoomNetwork.toServer(new RoomPacket(RoomPacket.Kind.LEAVE, roomId));
        else if (joining != null && available())
            RoomNetwork.toServer(new RoomPacket(RoomPacket.Kind.LEAVE, joining.id));
        room = null;
        joining = null;
        roomId = null;
        serverPlayerId = null;
        session = null;
        if (timeline != null) timeline.discard();
        timeline = null;
        ready = false;
        creating = false;
        outgoing.clear();
        buffered.clear();
        stateTransfer = null;
        hostClock = 0;
        sequence = 0;
        lastP1 = -1;
        lastP2 = -1;
        pendingPing = 0;
        status = "";
        if (notify) notifyKey("gui.pico8.room.left");
    }

    @SubscribeEvent
    public void disconnected(FMLNetworkEvent.ClientDisconnectionFromServerEvent event) {
        disconnected = true;
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (disconnected) {
            disconnected = false;
            inbound.clear();
            room = null;
            joining = null;
            roomId = null;
            leave(false);
            publicRooms.clear();
            return;
        }
        if (overflow) {
            overflow = false;
            inbound.clear();
            fail("gui.pico8.room.transfer_error");
            return;
        }
        Runnable task;
        for (int i = 0; i < 512 && (task = inbound.poll()) != null; i++) task.run();
        if (!available()) return;
        for (int i = 0; i < 4 && !outgoing.isEmpty(); i++) {
            ChunkTransfer.Sender sender = outgoing.peek();
            RoomNetwork.toServer(sender.next());
            if (sender.done()) outgoing.poll();
        }
        long now = System.nanoTime();
        if ((busy() || roomId != null && !ready) && now - operationStarted > 60 * SECOND) {
            fail("gui.pico8.room.join_timeout");
            return;
        }
        if (ready && room != null) {
            if (RoomNetwork.LATENCY_KICKS_ENABLED && !isHost() && hostClock != 0 && now - hostClock > SECOND) {
                fail("gui.pico8.room.latency");
                return;
            }
            if (now - lastClockSent >= SECOND / 10) {
                RoomPacket clock = new RoomPacket(RoomPacket.Kind.CLOCK, roomId);
                clock.epoch = room.epoch;
                clock.frame = timeline.frame();
                RoomNetwork.toServer(clock);
                lastClockSent = now;
            }
            if (RoomNetwork.LATENCY_KICKS_ENABLED && !isHost() && pendingPing != 0 && now - pendingPing > SECOND) {
                fail("gui.pico8.room.latency");
                return;
            }
            if (!isHost() && pendingPing == 0 && now - lastPing > SECOND) {
                RoomPacket ping = new RoomPacket(RoomPacket.Kind.PING, roomId);
                ping.sequence = now;
                pendingPing = now;
                lastPing = now;
                RoomNetwork.toServer(ping);
            }
        }
    }
}
