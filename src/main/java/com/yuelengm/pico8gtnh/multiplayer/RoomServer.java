package com.yuelengm.pico8gtnh.multiplayer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Server-side membership, authorization and relay. Never instantiates a PICO-R runtime. */
public final class RoomServer {

    public static final RoomServer INSTANCE = new RoomServer();
    private static final long SECOND = 1_000_000_000L;
    private static final int MAX_MEMBERS = 16;
    private final Map<UUID, ServerRoom> rooms = new LinkedHashMap<>();
    private final Map<UUID, UUID> memberships = new HashMap<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final Map<UUID, Waiting> waiting = new HashMap<>();
    private final Map<UUID, Download> downloads = new HashMap<>();
    private final ArrayBlockingQueue<Runnable> inbound = new ArrayBlockingQueue<>(4096);
    private final Set<UUID> overflowed = ConcurrentHashMap.newKeySet();

    private static final class Progress {

        long frame, clock, sequence;
        boolean ready;
        long joined = System.nanoTime();
    }

    private static final class ServerRoom {

        final RoomInfo info;
        final Map<UUID, Progress> progress = new LinkedHashMap<>();
        final Deque<UUID> syncQueue = new ArrayDeque<>();
        Path cart;
        ChunkTransfer.Receiver upload;
        UUID syncing;
        long syncStarted;
        long syncToken;
        int syncOffset, syncTotal;
        String syncHash;

        ServerRoom(RoomInfo info) {
            this.info = info;
        }
    }

    private static final class Waiting {

        final RoomPacket packet;
        final long until;

        Waiting(RoomPacket packet, long until) {
            this.packet = packet;
            this.until = until;
        }
    }

    private static final class Download {

        final ChunkTransfer.Sender sender;
        final long started = System.nanoTime();

        Download(ChunkTransfer.Sender sender) {
            this.sender = sender;
        }
    }

    private RoomServer() {}

    public void enqueue(EntityPlayerMP player, RoomPacket packet) {
        if (!inbound.offer(() -> {
            if (online(player.getUniqueID()) != player) return;
            try {
                handle(player, packet);
            } catch (IOException | IllegalArgumentException exception) {
                Pico8GtnhMod.LOG
                    .warn("Invalid PICO-8 room operation from {}", player.getCommandSenderName(), exception);
                error(player, packet.room, "gui.pico8.room.transfer_error");
                ServerRoom room = rooms.get(packet.room);
                if (room != null && room.info.host.equals(player.getUniqueID())) room.upload = null;
                else leave(player.getUniqueID(), "gui.pico8.room.transfer_error");
                downloads.remove(player.getUniqueID());
            }
        })) overflowed.add(player.getUniqueID());
    }

    private EntityPlayerMP online(UUID id) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null) return null;
        for (EntityPlayerMP player : server.getConfigurationManager().playerEntityList) {
            if (player.getUniqueID()
                .equals(id)) return player;
        }
        return null;
    }

    private void send(UUID id, RoomPacket packet) {
        EntityPlayerMP player = online(id);
        if (player != null) RoomNetwork.toClient(player, packet);
    }

    private void error(EntityPlayerMP player, UUID room, String key) {
        RoomPacket packet = new RoomPacket(RoomPacket.Kind.ERROR, room);
        packet.text = key;
        RoomNetwork.toClient(player, packet);
    }

    private void broadcast(ServerRoom room, RoomPacket packet) {
        for (UUID id : new ArrayList<>(room.progress.keySet())) send(id, packet);
    }

    private void members(ServerRoom room) throws IOException {
        RoomPacket packet = new RoomPacket(RoomPacket.Kind.MEMBERS, room.info.id);
        packet.data = RoomCodec.room(room.info);
        broadcast(room, packet);
    }

    private void handle(EntityPlayerMP player, RoomPacket packet) throws IOException {
        UUID id = player.getUniqueID();
        long now = System.nanoTime();
        if (packet.kind == RoomPacket.Kind.PING) {
            RoomPacket pong = new RoomPacket(RoomPacket.Kind.PONG, packet.room);
            pong.sequence = packet.sequence;
            send(id, pong);
            return;
        }
        if (packet.kind == RoomPacket.Kind.LIST_REQUEST) {
            RoomPacket start = new RoomPacket(RoomPacket.Kind.LIST);
            start.flag = true;
            send(id, start);
            for (ServerRoom room : rooms.values()) if (room.info.published) {
                RoomPacket entry = new RoomPacket(RoomPacket.Kind.LIST, room.info.id);
                entry.data = RoomCodec.room(room.info);
                send(id, entry);
            }
            return;
        }
        if (packet.kind == RoomPacket.Kind.LEAVE) {
            waiting.remove(id);
            downloads.remove(id);
            if (packet.room.equals(memberships.get(id))) leave(id, "gui.pico8.room.left");
            return;
        }
        if (packet.kind == RoomPacket.Kind.CREATE) {
            if (memberships.containsKey(id) || rooms.size() >= 64) {
                error(player, packet.room, "gui.pico8.room.busy");
                return;
            }
            RoomInfo info = RoomCodec.room(packet.data);
            if (info.cartName.length() > 128 || !info.cartHash.matches("[0-9a-f]{64}")
                || !info.runtimeHash.matches("[0-9a-f]{64}")) throw new IOException("Invalid cartridge metadata");
            info.id = packet.room;
            if (info.id.equals(RoomPacket.NONE) || rooms.containsKey(info.id)) throw new IOException("Duplicate room");
            info.host = id;
            info.hostName = player.getCommandSenderName();
            info.epoch = 0;
            info.published = false;
            info.members.clear();
            info.members.add(new RoomInfo.Member(id, info.hostName, true));
            ServerRoom room = new ServerRoom(info);
            Progress progress = new Progress();
            progress.ready = true;
            progress.clock = now;
            room.progress.put(id, progress);
            rooms.put(info.id, room);
            memberships.put(id, info.id);
            waiting.remove(id);
            downloads.remove(id);
            RoomPacket created = new RoomPacket(RoomPacket.Kind.CREATED, info.id);
            created.data = RoomCodec.room(info);
            send(id, created);
            return;
        }
        ServerRoom room = rooms.get(packet.room);
        if (room == null) {
            error(player, packet.room, "gui.pico8.room.unavailable");
            return;
        }
        boolean host = room.info.host.equals(id);
        if (packet.kind == RoomPacket.Kind.CART_REQUEST) {
            if (!room.info.published || room.cart == null || downloads.containsKey(id)) {
                error(player, packet.room, "gui.pico8.room.unavailable");
                return;
            }
            byte[] bytes = Files.readAllBytes(room.cart);
            downloads.put(
                id,
                new Download(new ChunkTransfer.Sender(RoomPacket.Kind.CART_CHUNK, room.info.id, id, 0, now, bytes)));
            return;
        }
        if (packet.kind == RoomPacket.Kind.JOIN) {
            join(player, packet);
            return;
        }
        Progress progress = room.progress.get(id);
        if (progress == null || packet.epoch != room.info.epoch && packet.kind != RoomPacket.Kind.RESET) return;
        switch (packet.kind) {
            case VISIBILITY:
                if (!host) return;
                if (!packet.flag) {
                    room.info.published = false;
                    room.upload = null;
                    members(room);
                } else if (room.cart != null) {
                    room.info.published = true;
                    members(room);
                }
                break;
            case UPLOAD:
                if (!host || room.info.published) return;
                if (packet.a == 0) room.upload = new ChunkTransfer.Receiver();
                if (room.upload == null) throw new IOException("Upload has no start");
                byte[] complete = room.upload.accept(packet, RoomPacket.MAX_CART);
                if (complete != null) {
                    if (!RoomCodec.hash(complete)
                        .equals(room.info.cartHash)) throw new IOException("Cartridge changed");
                    if (room.cart == null) room.cart = Files.createTempFile("pico8-room-", ".cart");
                    Files.write(room.cart, complete);
                    room.upload = null;
                    room.info.published = true;
                    members(room);
                }
                break;
            case INPUT:
                RoomInfo.Member member = room.info.member(id);
                if (!progress.ready || member == null
                    || !member.controller
                    || packet.revision != member.revision
                    || packet.sequence <= progress.sequence
                    || packet.frame < member.controlFrame
                    || packet.a < 0
                    || packet.a > 63
                    || packet.b < 0
                    || packet.b > 63) {
                    RoomPacket rejected = new RoomPacket(RoomPacket.Kind.REJECT_INPUT, room.info.id);
                    rejected.player = id;
                    rejected.epoch = room.info.epoch;
                    rejected.frame = packet.frame;
                    rejected.sequence = packet.sequence;
                    send(id, rejected);
                    return;
                }
                if (!withinHistory(room, packet.frame, now)) {
                    if (!host) leave(id, "gui.pico8.room.latency");
                    return;
                }
                progress.sequence = packet.sequence;
                packet.player = id;
                broadcast(room, packet);
                break;
            case CLOCK:
                if (!progress.ready || packet.frame < 0) return;
                if (!host && !withinHistory(room, packet.frame, now)) {
                    leave(id, "gui.pico8.room.latency");
                    return;
                }
                progress.frame = packet.frame;
                progress.clock = now;
                packet.player = id;
                if (host) broadcast(room, packet);
                break;
            case PERMISSION:
                if (!host || packet.player.equals(id) || !withinHistory(room, packet.frame, now)) return;
                RoomInfo.Member target = room.info.member(packet.player);
                Progress controlled = room.progress.get(packet.player);
                if (target == null || controlled == null || !controlled.ready) return;
                target.controller = packet.flag;
                target.revision++;
                target.controlFrame = packet.frame;
                packet.sequence = ++controlled.sequence;
                packet.revision = target.revision;
                packet.a = 0;
                packet.b = 0;
                broadcast(room, packet);
                members(room);
                break;
            case SNAPSHOT_CHUNK:
                if (!host || !packet.player.equals(room.syncing)) return;
                if (packet.a != room.syncOffset || packet.b <= 0
                    || packet.b > RoomPacket.MAX_STATE
                    || packet.data.length == 0
                    || (long) packet.a + packet.data.length > packet.b) throw new IOException("Invalid snapshot relay");
                if (packet.a == 0) {
                    room.syncToken = packet.sequence;
                    room.syncTotal = packet.b;
                    room.syncHash = packet.text;
                } else if (packet.sequence != room.syncToken || packet.b != room.syncTotal
                    || !packet.text.equals(room.syncHash)) throw new IOException("Snapshot transfer changed");
                room.syncOffset += packet.data.length;
                send(packet.player, packet);
                if (room.syncOffset == room.syncTotal) {
                    room.syncing = null;
                    requestNext(room);
                }
                break;
            case READY:
                if (progress.ready) return;
                if (!withinHistory(room, packet.frame, now)) {
                    leave(id, "gui.pico8.room.latency");
                    return;
                }
                progress.ready = true;
                progress.frame = packet.frame;
                progress.clock = now;
                members(room);
                break;
            case RESET:
                if (!host || packet.epoch != room.info.epoch + 1) return;
                room.info.epoch = packet.epoch;
                room.syncQueue.clear();
                room.syncing = null;
                room.upload = null;
                for (Map.Entry<UUID, Progress> entry : room.progress.entrySet()) {
                    room.info.member(entry.getKey()).controlFrame = 0;
                    Progress state = entry.getValue();
                    state.sequence = 0;
                    state.frame = 0;
                    state.clock = now;
                    state.ready = entry.getKey()
                        .equals(id);
                    state.joined = now;
                    if (!state.ready) room.syncQueue.add(entry.getKey());
                }
                broadcast(room, packet);
                requestNext(room);
                break;
            case KICK:
                if (host && !packet.player.equals(id) && room.progress.containsKey(packet.player))
                    leave(packet.player, "gui.pico8.room.latency");
                break;
            default:
                break;
        }
    }

    private long estimatedHost(ServerRoom room, long now) {
        Progress host = room.progress.get(room.info.host);
        return host.frame + Math.min(SECOND, Math.max(0, now - host.clock)) * room.info.fps / SECOND;
    }

    private boolean withinHistory(ServerRoom room, long frame, long now) {
        return frame >= 0 && Math.abs(estimatedHost(room, now) - frame) <= room.info.fps;
    }

    private void join(EntityPlayerMP player, RoomPacket packet) throws IOException {
        UUID id = player.getUniqueID();
        ServerRoom room = rooms.get(packet.room);
        long now = System.nanoTime();
        if (room == null || !room.info.published
            || room.cart == null
            || memberships.containsKey(id)
            || room.info.members.size() >= MAX_MEMBERS
            || !packet.text.equals(room.info.cartHash + ":" + room.info.runtimeHash)) {
            error(player, packet.room, "gui.pico8.room.unavailable");
            waiting.remove(id);
            return;
        }
        long until = cooldowns.getOrDefault(id, 0L);
        if (until > now) {
            waiting.put(id, new Waiting(packet, until));
            RoomPacket wait = new RoomPacket(RoomPacket.Kind.JOIN_WAIT, room.info.id);
            wait.frame = (until - now) / 1_000_000;
            send(id, wait);
            return;
        }
        waiting.remove(id);
        downloads.remove(id);
        room.info.members.add(new RoomInfo.Member(id, player.getCommandSenderName(), false));
        room.progress.put(id, new Progress());
        memberships.put(id, room.info.id);
        members(room);
        room.syncQueue.add(id);
        requestNext(room);
    }

    private void requestNext(ServerRoom room) {
        if (room.syncing != null) return;
        while (!room.syncQueue.isEmpty()) {
            UUID next = room.syncQueue.poll();
            if (!room.progress.containsKey(next)) continue;
            room.syncing = next;
            room.syncStarted = System.nanoTime();
            room.syncOffset = 0;
            RoomPacket request = new RoomPacket(RoomPacket.Kind.SNAPSHOT_REQUEST, room.info.id);
            request.player = next;
            request.epoch = room.info.epoch;
            send(room.info.host, request);
            return;
        }
    }

    private void leave(UUID id, String reason) {
        UUID roomId = memberships.remove(id);
        waiting.remove(id);
        downloads.remove(id);
        if (roomId == null) return;
        cooldowns.put(id, System.nanoTime() + 5 * SECOND);
        ServerRoom room = rooms.get(roomId);
        if (room == null) return;
        if (room.info.host.equals(id)) {
            RoomPacket closed = new RoomPacket(RoomPacket.Kind.CLOSED, roomId);
            closed.text = "gui.pico8.room.closed";
            broadcast(room, closed);
            for (UUID member : room.progress.keySet()) {
                memberships.remove(member);
                cooldowns.put(member, System.nanoTime() + 5 * SECOND);
            }
            rooms.remove(roomId);
            downloads.entrySet()
                .removeIf(entry -> entry.getValue().sender.room.equals(roomId));
            deleteCart(room);
            return;
        }
        Progress departed = room.progress.remove(id);
        room.info.members.removeIf(member -> member.id.equals(id));
        room.syncQueue.remove(id);
        RoomPacket neutral = new RoomPacket(RoomPacket.Kind.PERMISSION, roomId);
        neutral.player = id;
        neutral.epoch = room.info.epoch;
        neutral.frame = room.progress.get(room.info.host).frame;
        neutral.sequence = departed.sequence + 1;
        neutral.flag = false;
        broadcast(room, neutral);
        RoomPacket closed = new RoomPacket(RoomPacket.Kind.CLOSED, roomId);
        closed.text = reason;
        send(id, closed);
        try {
            members(room);
        } catch (IOException exception) {
            Pico8GtnhMod.LOG.error("Could not encode room", exception);
        }
        if (id.equals(room.syncing)) {
            room.syncing = null;
            requestNext(room);
        }
    }

    private void deleteCart(ServerRoom room) {
        if (room.cart != null) try {
            Files.deleteIfExists(room.cart);
        } catch (IOException exception) {
            Pico8GtnhMod.LOG.warn("Could not remove temporary room cartridge {}", room.cart, exception);
        }
    }

    public void clear() {
        for (ServerRoom room : rooms.values()) deleteCart(room);
        rooms.clear();
        memberships.clear();
        waiting.clear();
        downloads.clear();
        cooldowns.clear();
        inbound.clear();
        overflowed.clear();
    }

    @SubscribeEvent
    public void logout(PlayerEvent.PlayerLoggedOutEvent event) {
        leave(event.player.getUniqueID(), "gui.pico8.room.left");
    }

    @SubscribeEvent
    public void tick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (UUID id : new ArrayList<>(overflowed)) {
            overflowed.remove(id);
            leave(id, "gui.pico8.room.latency");
        }
        Runnable task;
        for (int i = 0; i < 512 && (task = inbound.poll()) != null; i++) task.run();
        long now = System.nanoTime();
        for (Map.Entry<UUID, Waiting> entry : new ArrayList<>(waiting.entrySet())) if (now >= entry.getValue().until) {
            EntityPlayerMP player = online(entry.getKey());
            waiting.remove(entry.getKey());
            if (player != null) try {
                join(player, entry.getValue().packet);
            } catch (IOException exception) {
                error(player, entry.getValue().packet.room, "gui.pico8.room.transfer_error");
            }
        }
        for (Map.Entry<UUID, Download> entry : new ArrayList<>(downloads.entrySet())) {
            Download download = entry.getValue();
            ServerRoom room = rooms.get(download.sender.room);
            if (online(entry.getKey()) == null || room == null
                || !room.info.published
                || now - download.started > 30 * SECOND) {
                downloads.remove(entry.getKey());
                continue;
            }
            for (int i = 0; i < 4 && !download.sender.done(); i++) send(entry.getKey(), download.sender.next());
            if (download.sender.done()) downloads.remove(entry.getKey());
        }
        for (ServerRoom room : new ArrayList<>(rooms.values())) {
            if (room.upload != null && now - room.upload.started > 30 * SECOND) room.upload = null;
            if (room.syncing != null && now - room.syncStarted > 30 * SECOND)
                leave(room.syncing, "gui.pico8.room.join_timeout");
            for (Map.Entry<UUID, Progress> entry : new ArrayList<>(room.progress.entrySet())) {
                if (entry.getKey()
                    .equals(room.info.host)) continue;
                Progress progress = entry.getValue();
                if (!progress.ready && now - progress.joined > 60 * SECOND)
                    leave(entry.getKey(), "gui.pico8.room.join_timeout");
                else if (progress.ready && now - progress.clock > 5 * SECOND)
                    leave(entry.getKey(), "gui.pico8.room.latency");
            }
        }
    }
}
