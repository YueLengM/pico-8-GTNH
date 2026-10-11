package com.yuelengm.pico8gtnh.multiplayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.UUID;

/** Strict contiguous transfer; paced by the sender's tick rather than flooding the Minecraft connection. */
public final class ChunkTransfer {

    private ChunkTransfer() {}

    public static final class Sender {

        public final UUID room;
        public final UUID player;
        public final RoomPacket.Kind kind;
        private final byte[] bytes;
        private final String hash;
        private final long epoch;
        private final long token;
        private int offset;

        public Sender(RoomPacket.Kind kind, UUID room, UUID player, long epoch, long token, byte[] bytes) {
            this.kind = kind;
            this.room = room;
            this.player = player;
            this.epoch = epoch;
            this.token = token;
            this.bytes = bytes;
            this.hash = RoomCodec.hash(bytes);
        }

        public boolean done() {
            return offset == bytes.length;
        }

        public RoomPacket next() {
            RoomPacket packet = new RoomPacket(kind, room);
            packet.player = player;
            packet.epoch = epoch;
            packet.sequence = token;
            packet.a = offset;
            packet.b = bytes.length;
            packet.text = hash;
            int packetSize = RoomPacket.CHUNK_SIZE;
            int end = Math.min(bytes.length, offset + packetSize);
            packet.data = Arrays.copyOfRange(bytes, offset, end);
            offset = end;
            return packet;
        }
    }

    public static final class Receiver {

        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private long token;
        private long epoch;
        private int total;
        private String hash;
        public final long started = System.nanoTime();

        public byte[] accept(RoomPacket packet, int maximum) throws IOException {
            if (packet.b <= 0 || packet.b > maximum
                || packet.a != bytes.size()
                || packet.data.length == 0
                || (long) packet.a + packet.data.length > packet.b) throw new IOException("Invalid transfer chunk");
            if (bytes.size() == 0) {
                token = packet.sequence;
                epoch = packet.epoch;
                total = packet.b;
                hash = packet.text;
            } else if (token != packet.sequence || epoch != packet.epoch
                || total != packet.b
                || !hash.equals(packet.text)) {
                    throw new IOException("Transfer changed midway");
                }
            bytes.write(packet.data);
            if (bytes.size() != total) return null;
            byte[] complete = bytes.toByteArray();
            if (!RoomCodec.hash(complete)
                .equals(hash)) throw new IOException("Transfer hash mismatch");
            return complete;
        }
    }
}
