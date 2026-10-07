package com.yuelengm.pico8gtnh.client.command;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiChat;
import net.minecraft.command.ICommandSender;

import com.gtnewhorizon.gtnhlib.commands.GTNHClientCommand;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/** Client-local entry point for starting or selecting a PICO-8 cartridge. */
public class Pico8ClientCommand extends GTNHClientCommand {

    private static final List<String> SUBCOMMANDS = Arrays.asList("start", "new");

    private boolean openScreenOnNextTick;
    private boolean chooseCartridge;

    @Override
    public String getCommandName() {
        return "pico8";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/pico8 <start|new>";
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        if (args.length != 1) {
            addChatMessage("Usage: " + getCommandUsage(sender));
            return;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start":
                queueOpenPico8Screen(false);
                break;
            case "new":
                queueOpenPico8Screen(true);
                break;
            default:
                addChatMessage("Usage: " + getCommandUsage(sender));
                break;
        }
    }

    private void queueOpenPico8Screen(boolean chooseCartridge) {
        this.chooseCartridge = chooseCartridge;
        this.openScreenOnNextTick = true;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase == TickEvent.Phase.END && this.openScreenOnNextTick
            && !(Minecraft.getMinecraft().currentScreen instanceof GuiChat)) {
            this.openScreenOnNextTick = false;
            Pico8GtnhMod.proxy.openPico8Screen(this.chooseCartridge);
        }
    }

    @Override
    public List<String> addTabCompletionOptions(ICommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return SUBCOMMANDS.stream()
                .filter(subcommand -> subcommand.startsWith(prefix))
                .collect(java.util.stream.Collectors.toList());
        }
        return super.addTabCompletionOptions(sender, args);
    }
}
