package com.yuelengm.pico8gtnh.multiplayer;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public final class RoomCodec {

    private RoomCodec() {}

    public static String hash(byte[] bytes) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(bytes);
            StringBuilder result = new StringBuilder();
            for (byte b : digest) result.append(String.format("%02x", b & 255));
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public static void uuid(DataOutputStream out, UUID value) throws IOException {
        out.writeLong(value.getMostSignificantBits());
        out.writeLong(value.getLeastSignificantBits());
    }

    public static UUID uuid(DataInputStream in) throws IOException {
        return new UUID(in.readLong(), in.readLong());
    }

    public static byte[] room(RoomInfo room) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            uuid(out, room.id);
            uuid(out, room.host);
            out.writeUTF(room.hostName);
            out.writeUTF(room.cartName);
            out.writeUTF(room.runtimeHash);
            out.writeInt(room.fps);
            out.writeLong(room.epoch);
            out.writeBoolean(room.published);
            out.writeInt(room.members.size());
            for (RoomInfo.Member member : room.members) {
                uuid(out, member.id);
                out.writeUTF(member.name);
                out.writeBoolean(member.controller);
                out.writeLong(member.revision);
                out.writeLong(member.controlFrame);
            }
        }
        return bytes.toByteArray();
    }

    public static RoomInfo room(byte[] bytes) throws IOException {
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes))) {
            RoomInfo room = new RoomInfo();
            room.id = uuid(in);
            room.host = uuid(in);
            room.hostName = in.readUTF();
            room.cartName = in.readUTF();
            room.runtimeHash = in.readUTF();
            room.fps = in.readInt();
            room.epoch = in.readLong();
            room.published = in.readBoolean();
            int count = in.readInt();
            if (count < 0 || count > 16 || (room.fps != 30 && room.fps != 60))
                throw new IOException("Invalid room metadata");
            for (int i = 0; i < count; i++) {
                RoomInfo.Member member = new RoomInfo.Member(uuid(in), in.readUTF(), in.readBoolean());
                member.revision = in.readLong();
                member.controlFrame = in.readLong();
                room.members.add(member);
            }
            if (in.available() != 0) throw new IOException("Trailing room metadata");
            return room;
        }
    }

    private static void input(DataOutputStream out, RollbackTimeline.Input input) throws IOException {
        uuid(out, input.player);
        out.writeLong(input.frame);
        out.writeLong(input.sequence);
        out.writeByte(input.p1);
        out.writeByte(input.p2);
        out.writeBoolean(input.boundary);
    }

    private static RollbackTimeline.Input input(DataInputStream in) throws IOException {
        UUID player = uuid(in);
        long frame = in.readLong(), sequence = in.readLong();
        int p1 = in.readUnsignedByte(), p2 = in.readUnsignedByte();
        if (frame < 0 || sequence < 0 || p1 > 63 || p2 > 63) throw new IOException("Invalid snapshot input");
        return new RollbackTimeline.Input(player, frame, sequence, p1, p2, in.readBoolean());
    }

    public static byte[] snapshot(RollbackTimeline.Snapshot snapshot) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(raw)) {
            out.writeLong(snapshot.frame);
            out.writeLong(snapshot.target);
            out.writeInt(snapshot.state.length);
            out.write(snapshot.state);
            out.writeInt(snapshot.held.size());
            for (RollbackTimeline.Input input : snapshot.held.values()) input(out, input);
            out.writeInt(snapshot.inputs.size());
            for (RollbackTimeline.Input input : snapshot.inputs) input(out, input);
        }
        if (raw.size() > RoomPacket.MAX_STATE) throw new IOException("Snapshot too large");
        ByteArrayOutputStream compressed = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(compressed)) {
            raw.writeTo(gzip);
        }
        if (compressed.size() > RoomPacket.MAX_STATE) throw new IOException("Compressed snapshot too large");
        return compressed.toByteArray();
    }

    public static RollbackTimeline.Snapshot snapshot(byte[] compressed) throws IOException {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = gzip.read(buffer)) != -1) {
                if (raw.size() + count > RoomPacket.MAX_STATE) throw new IOException("Inflated snapshot too large");
                raw.write(buffer, 0, count);
            }
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(raw.toByteArray()))) {
            RollbackTimeline.Snapshot snapshot = new RollbackTimeline.Snapshot();
            snapshot.frame = in.readLong();
            snapshot.target = in.readLong();
            int length = in.readInt();
            if (snapshot.frame < 0 || length <= 0 || length > in.available())
                throw new IOException("Invalid snapshot state");
            snapshot.state = new byte[length];
            in.readFully(snapshot.state);
            int held = in.readInt();
            if (held < 0 || held > 64) throw new IOException("Invalid held input count");
            for (int i = 0; i < held; i++) {
                RollbackTimeline.Input input = input(in);
                snapshot.held.put(input.player, input);
            }
            int changes = in.readInt();
            if (changes < 0 || changes > 8192) throw new IOException("Invalid snapshot input count");
            for (int i = 0; i < changes; i++) snapshot.inputs.add(input(in));
            if (in.available() != 0) throw new IOException("Trailing snapshot bytes");
            return snapshot;
        }
    }
}
