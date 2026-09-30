package com.yuelengm.pico8gtnh.gui;

import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiConfirmOpenLink;
import net.minecraft.client.gui.GuiYesNoCallback;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.client.renderer.texture.ITextureObject;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import org.jetbrains.annotations.NotNull;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.api.widget.Interactable;
import com.cleanroommc.modularui.drawable.GuiDraw;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.service.CartService;
import com.yuelengm.pico8gtnh.service.Cartridge;

/** Builds the MUI2 screen for choosing a local PICO-8 cartridge. */
public final class Pico8CartridgeScreen implements GuiYesNoCallback {

    private static final URI BROWSE_CARTS_URI = URI
        .create("https://www.lexaloffle.com/bbs/?cat=7#sub=2&mode=carts&orderby=featured");

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final CartService cartService = new CartService();
    private List<Cartridge> carts = new ArrayList<>();
    private Cartridge selectedCart;

    private Pico8CartridgeScreen() {}

    public static void open() {
        Pico8CartridgeScreen cartridgeScreen = new Pico8CartridgeScreen();
        cartridgeScreen.refreshCarts();
        ModularPanel panel = cartridgeScreen.buildPanel();
        ClientGUI.open(new ModularScreen(Pico8GtnhMod.MODID, panel).pausesGame(false));
    }

    private ModularPanel buildPanel() {
        return ModularPanel.defaultPanel("pico8_carts")
            .widthRel(0.8f)
            .heightRel(0.8f)
            .padding(8)
            .child(
                Flow.column()
                    .full()
                    .child(
                        this.carts.isEmpty() ? new TextWidget<>(IKey.lang("gui.pico8.carts.empty")).expanded()
                            .fullWidth()
                            .textAlign(Alignment.CENTER)
                            : new ListWidget<>().fullWidth()
                                .expanded()
                                .background(new Rectangle().color(0xFF202020))
                                .children(this.carts, CartridgeRow::new))
                    .child(
                        Flow.row()
                            .childPadding(2)
                            .fullWidth()
                            .height(20)
                            .marginTop(2)
                            .child(
                                new TextWidget<>(IKey.lang("gui.pico8.carts.title")).textAlign(Alignment.CENTER)
                                    .style(EnumChatFormatting.BOLD)
                                    .widthRel(0.15f))
                            .child(
                                createLoadButton().expanded()
                                    .fullHeight())
                            .child(
                                actionButton("icons/refresh", Pico8CartridgeScreen::open).width(20)
                                    .fullHeight())
                            .child(
                                actionButton("icons/folder", this::openCartsFolder).width(20)
                                    .fullHeight())
                            .child(
                                actionButton("icons/world", this::onBrowseCarts).width(20)
                                    .fullHeight())));
    }

    private final class CartridgeRow extends Flow implements Interactable {

        private final Cartridge cart;

        private CartridgeRow(Cartridge cart) {
            super(GuiAxis.X);
            this.cart = cart;
            widthRel(1f);
            height(128 + 4);
            padding(2);
            crossAxisAlignment(Alignment.CrossAxis.CENTER);
            child(
                new CartIcon(cart.getCoverTexture(), cart.getDynamicCoverTexture()).asWidget()
                    .size(128)
                    .marginRight(4));
            child(
                new TextWidget<>(IKey.str(cart.getFileName())).expanded()
                    .height(40)
                    .textAlign(Alignment.CenterLeft)
                    .style(EnumChatFormatting.WHITE));
            background((context, x, y, width, height, widgetTheme) -> drawRowBackground(cart, x, y, width, height));
        }

        @Override
        public @NotNull Result onMousePressed(int mouseButton) {
            if (mouseButton != 0) {
                return Result.IGNORE;
            }
            selectedCart = this.cart;
            return Result.SUCCESS;
        }
    }

    private void drawRowBackground(Cartridge cart, float x, float y, float width, float height) {
        boolean selected = cart.equals(this.selectedCart);
        int background = selected ? 0xFF363636 : 0xFF292929;
        int topLeftEdge = selected ? 0xFFFFD34E : 0xFF505050;
        int bottomRightEdge = selected ? 0xFFFFD34E : 0xFF171717;

        GuiDraw.drawRect(x, y, width, height, background);
        GuiDraw.drawRect(x, y, width, 1, topLeftEdge);
        GuiDraw.drawRect(x, y, 1, height, topLeftEdge);
        GuiDraw.drawRect(x, y + height - 1, width, 1, bottomRightEdge);
        GuiDraw.drawRect(x + width - 1, y, 1, height, bottomRightEdge);
    }

    private static ButtonWidget<?> actionButton(String iconPath, Runnable action) {
        UITexture icon = UITexture.fullImage(Pico8GtnhMod.MODID, iconPath);

        return new ButtonWidget<>().padding(2)
            .overlay(icon)
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                action.run();
                return true;
            });
    }

    private ButtonWidget<?> createLoadButton() {
        return new ButtonWidget<>().background((context, x, y, width, height, widgetTheme) -> {
            UITexture buttonTexture = this.selectedCart == null ? GuiTextures.MC_BUTTON_DISABLED
                : GuiTextures.MC_BUTTON;
            buttonTexture.draw(context, x, y, width, height, widgetTheme);
        })
            .hoverBackground((context, x, y, width, height, widgetTheme) -> {
                UITexture buttonTexture = this.selectedCart == null ? GuiTextures.MC_BUTTON_DISABLED
                    : GuiTextures.MC_BUTTON_HOVERED;
                buttonTexture.draw(context, x, y, width, height, widgetTheme);
            })
            .overlay(IKey.lang("gui.pico8.carts.load"))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0 || this.selectedCart == null) {
                    return false;
                }
                this.loadSelectedCart();
                return true;
            });
    }

    private void loadSelectedCart() {
        if (this.selectedCart == null || !this.selectedCart.getFile()
            .isFile()) {
            return;
        }
        ClientGUI.open(new PicoRScreen(this.selectedCart.getFile()));
    }

    private void refreshCarts() {
        this.carts = this.cartService.getCartridges();
    }

    private final class CartIcon extends UITexture {

        private final DynamicTexture texture;

        private CartIcon(ResourceLocation location, DynamicTexture texture) {
            super(location, 0, 0, 1, 1, null);
            this.texture = texture;
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            if (this.texture != null) {
                ITextureObject registered = minecraft.getTextureManager()
                    .getTexture(this.location);
                if (registered != this.texture) {
                    minecraft.getTextureManager()
                        .loadTexture(this.location, this.texture);
                }
            }
            super.draw(x, y, width, height);
        }
    }

    private void openCartsFolder() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop folder access is not supported on this system");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Opening folders is not supported on this system");
            }
            desktop.open(this.cartService.cartsDirectory);
        } catch (IOException | RuntimeException exception) {
            Pico8GtnhMod.LOG.error("Could not open PICO-8 cartridge folder", exception);
        }
    }

    private void onBrowseCarts() {
        if (minecraft.gameSettings.chatLinksPrompt) {
            minecraft.displayGuiScreen(new GuiConfirmOpenLink(this, BROWSE_CARTS_URI.toASCIIString(), 0, false));
        } else {
            browseCarts();
        }
    }

    public void confirmClicked(boolean result, int id) {
        if (id == 0) {
            if (result) {
                browseCarts();
            }

            open();
        }
    }

    public void browseCarts() {
        try {
            if (!Desktop.isDesktopSupported()) {
                throw new IOException("Desktop browser access is not supported on this system");
            }
            Desktop desktop = Desktop.getDesktop();
            if (!desktop.isSupported(Desktop.Action.BROWSE)) {
                throw new IOException("Opening a web browser is not supported on this system");
            }
            desktop.browse(BROWSE_CARTS_URI);
        } catch (IOException | RuntimeException exception) {
            Pico8GtnhMod.LOG.error("Could not open the PICO-8 carts page", exception);
        }
    }
}
