package com.yuelengm.pico8gtnh.gui;

import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.StatCollector;

import org.lwjgl.input.Mouse;

import com.yuelengm.pico8gtnh.multiplayer.RoomClient;
import com.yuelengm.pico8gtnh.multiplayer.RoomInfo;

/** Global public rooms; downloading does not count as membership. */
public final class Pico8RoomScreen extends GuiScreen {

    private int first;
    private long refreshed;

    @Override
    public void initGui() {
        buttonList.clear();
        buttonList.add(new GuiButton(0, width / 2 - 154, height - 28, 150, 20, text("gui.pico8.room.local")));
        buttonList.add(new GuiButton(1, width / 2 + 4, height - 28, 150, 20, text("gui.pico8.room.refresh")));
        RoomClient.INSTANCE.refreshRooms();
        refreshed = System.nanoTime();
    }

    private String text(String key) {
        return StatCollector.translateToLocal(key);
    }

    private int rows() {
        return Math.max(1, (height - 90) / 32);
    }

    @Override
    public void updateScreen() {
        if (System.nanoTime() - refreshed > 2_000_000_000L) {
            RoomClient.INSTANCE.refreshRooms();
            refreshed = System.nanoTime();
        }
        buttonList.get(0).enabled = !RoomClient.INSTANCE.busy();
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float ticks) {
        drawDefaultBackground();
        drawCenteredString(fontRendererObj, text("gui.pico8.room.list"), width / 2, 12, 0xFFFFFF);
        List<RoomInfo> rooms = RoomClient.INSTANCE.rooms();
        first = Math.max(0, Math.min(first, Math.max(0, rooms.size() - rows())));
        int left = Math.max(8, width / 2 - 180), right = Math.min(width - 8, width / 2 + 180);
        if (rooms.isEmpty()) drawCenteredString(fontRendererObj, text("gui.pico8.room.empty"), width / 2, 44, 0xAAAAAA);
        for (int i = first; i < Math.min(rooms.size(), first + rows()); i++) {
            RoomInfo room = rooms.get(i);
            int y = 32 + (i - first) * 32;
            boolean hover = mouseX >= left && mouseX < right && mouseY >= y && mouseY < y + 30;
            drawRect(left, y, right, y + 30, hover ? 0xD0404040 : 0xC0202020);
            drawString(
                fontRendererObj,
                fontRendererObj.trimStringToWidth(room.hostName + " — " + room.cartName, right - left - 12),
                left + 6,
                y + 4,
                0xFFFFFF);
            drawString(
                fontRendererObj,
                StatCollector.translateToLocalFormatted("gui.pico8.room.members", room.members.size()),
                left + 6,
                y + 17,
                0xAAAAAA);
        }
        String status = RoomClient.INSTANCE.status();
        if (!status.isEmpty()) drawCenteredString(fontRendererObj, text(status), width / 2, height - 44, 0xFFD34E);
        super.drawScreen(mouseX, mouseY, ticks);
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button.id == 0) Pico8CartridgeScreen.open();
        else RoomClient.INSTANCE.refreshRooms();
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        if (button != 0 || RoomClient.INSTANCE.busy()) return;
        int left = Math.max(8, width / 2 - 180), right = Math.min(width - 8, width / 2 + 180);
        if (x < left || x >= right || y < 32 || y >= 32 + rows() * 32) return;
        List<RoomInfo> rooms = RoomClient.INSTANCE.rooms();
        int index = first + (y - 32) / 32;
        if (index < rooms.size()) RoomClient.INSTANCE.join(rooms.get(index));
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) first += wheel < 0 ? 1 : -1;
    }

    @Override
    public void onGuiClosed() {
        if (!RoomClient.INSTANCE.openingGame() && RoomClient.INSTANCE.busy()) RoomClient.INSTANCE.leave(false);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }
}
