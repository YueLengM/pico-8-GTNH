package com.yuelengm.pico8gtnh.multiplayer;

import net.minecraft.entity.player.EntityPlayerMP;

import com.yuelengm.pico8gtnh.Pico8GtnhMod;

import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.network.NetworkRegistry;
import cpw.mods.fml.common.network.simpleimpl.IMessage;
import cpw.mods.fml.common.network.simpleimpl.IMessageHandler;
import cpw.mods.fml.common.network.simpleimpl.MessageContext;
import cpw.mods.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import cpw.mods.fml.relauncher.Side;

public final class RoomNetwork {

    private static SimpleNetworkWrapper channel;

    private RoomNetwork() {}

    public static void init() {
        channel = NetworkRegistry.INSTANCE.newSimpleChannel("pico8_rooms");
        channel.registerMessage(ClientHandler.class, ClientMessage.class, 0, Side.CLIENT);
        channel.registerMessage(ServerHandler.class, ServerMessage.class, 1, Side.SERVER);
        FMLCommonHandler.instance()
            .bus()
            .register(RoomServer.INSTANCE);
    }

    public static void toServer(RoomPacket packet) {
        channel.sendToServer(new ServerMessage(packet));
    }

    public static void toClient(EntityPlayerMP player, RoomPacket packet) {
        ClientMessage message = new ClientMessage(packet);
        if (packet.kind == RoomPacket.Kind.CREATED || packet.kind == RoomPacket.Kind.MEMBERS)
            message.player = player.getUniqueID();
        channel.sendTo(message, player);
    }

    public static final class ClientMessage extends RoomPacket {

        public ClientMessage() {}

        public ClientMessage(RoomPacket packet) {
            super(packet);
        }
    }

    public static final class ServerMessage extends RoomPacket {

        public ServerMessage() {}

        public ServerMessage(RoomPacket packet) {
            super(packet);
        }
    }

    public static final class ClientHandler implements IMessageHandler<ClientMessage, IMessage> {

        @Override
        public IMessage onMessage(ClientMessage packet, MessageContext context) {
            Pico8GtnhMod.proxy.receiveRoomPacket(packet);
            return null;
        }
    }

    public static final class ServerHandler implements IMessageHandler<ServerMessage, IMessage> {

        @Override
        public IMessage onMessage(ServerMessage packet, MessageContext context) {
            RoomServer.INSTANCE.enqueue(context.getServerHandler().playerEntity, packet);
            return null;
        }
    }
}
