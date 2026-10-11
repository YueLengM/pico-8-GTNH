package com.yuelengm.pico8gtnh.multiplayer;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import cpw.mods.fml.common.network.simpleimpl.IMessage;
import io.netty.buffer.ByteBuf;

/** Bounded wire envelope. Large payloads are split into paced chunks. */
public class RoomPacket implements IMessage {

    public static final UUID NONE = new UUID(0, 0);
    public static final int CHUNK_SIZE = 16 * 1024;
    public static final int MAX_STATE = 32 * 1024 * 1024;
    public static final int PROTOCOL = 3;

    public enum Kind {
        CREATE,
        CREATED,
        LIST_REQUEST,
        LIST,
        VISIBILITY,
        JOIN,
        JOIN_WAIT,
        SNAPSHOT_REQUEST,
        SNAPSHOT_CHUNK,
        MEMBERS,
        INPUT,
        CLOCK,
        PERMISSION,
        LEAVE,
        CLOSED,
        ERROR,
        RESET,
        READY,
        KICK,
        PING,
        PONG,
        REJECT_INPUT
    }

    public Kind kind;
    public UUID room = NONE;
    public UUID player = NONE;
    public long epoch;
    public long frame;
    public long sequence;
    public long revision;
    /** Local network arrival time; never sent on the wire. */
    public long receivedAt;
    public int a;
    public int b;
    public boolean flag;
    public String text = "";
    public byte[] data = new byte[0];

    public RoomPacket() {}

    public RoomPacket(Kind kind) {
        this.kind = kind;
    }

    public RoomPacket(Kind kind, UUID room) {
        this.kind = kind;
        this.room = room;
    }

    protected RoomPacket(RoomPacket source) {
        kind = source.kind;
        room = source.room;
        player = source.player;
        epoch = source.epoch;
        frame = source.frame;
        sequence = source.sequence;
        revision = source.revision;
        a = source.a;
        b = source.b;
        flag = source.flag;
        text = source.text;
        data = source.data;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        int version = buffer.readUnsignedByte();
        int ordinal = buffer.readUnsignedByte();
        if (version != PROTOCOL || ordinal >= Kind.values().length)
            throw new IllegalArgumentException("Room protocol mismatch");
        kind = Kind.values()[ordinal];
        room = new UUID(buffer.readLong(), buffer.readLong());
        player = new UUID(buffer.readLong(), buffer.readLong());
        epoch = buffer.readLong();
        frame = buffer.readLong();
        sequence = buffer.readLong();
        revision = buffer.readLong();
        a = buffer.readInt();
        b = buffer.readInt();
        flag = buffer.readBoolean();
        int textLength = buffer.readUnsignedShort();
        if (textLength > 1024 || textLength > buffer.readableBytes())
            throw new IllegalArgumentException("Room text too large");
        byte[] encoded = new byte[textLength];
        buffer.readBytes(encoded);
        text = new String(encoded, StandardCharsets.UTF_8);
        int length = buffer.readInt();
        if (length < 0 || length > CHUNK_SIZE || length != buffer.readableBytes())
            throw new IllegalArgumentException("Invalid room payload");
        data = new byte[length];
        buffer.readBytes(data);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > 1024 || data.length > CHUNK_SIZE || 82 + encoded.length + data.length >= 32767)
            throw new IllegalArgumentException("Room packet too large");
        buffer.writeByte(PROTOCOL);
        buffer.writeByte(kind.ordinal());
        buffer.writeLong(room.getMostSignificantBits());
        buffer.writeLong(room.getLeastSignificantBits());
        buffer.writeLong(player.getMostSignificantBits());
        buffer.writeLong(player.getLeastSignificantBits());
        buffer.writeLong(epoch);
        buffer.writeLong(frame);
        buffer.writeLong(sequence);
        buffer.writeLong(revision);
        buffer.writeInt(a);
        buffer.writeInt(b);
        buffer.writeBoolean(flag);
        buffer.writeShort(encoded.length);
        buffer.writeBytes(encoded);
        buffer.writeInt(data.length);
        buffer.writeBytes(data);
    }

}
