package com.yuelengm.pico8gtnh.gui;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
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
import com.yuelengm.pico8gtnh.util.Desktop;

/** Builds the MUI2 screen for choosing a local PICO-8 cartridge. */
public final class Pico8CartridgeScreen {

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
                        this.carts.isEmpty() ? new TextWidget(IKey.lang("gui.pico8.carts.empty")).expanded()
                            .widthRel(1f)
                            .alignment(Alignment.CENTER)
                            : new ListWidget<>().widthRel(1f)
                                .expanded()
                                .background(new Rectangle().setColor(0xFF202020))
                                .children(this.carts.size(), index -> new CartridgeRow(this.carts.get(index))))
                    .child(
                        Flow.row()
                            .childPadding(2)
                            .widthRel(1f)
                            .height(20)
                            .marginTop(2)
                            .child(
                                new TextWidget(IKey.lang("gui.pico8.carts.title")).alignment(Alignment.CENTER)
                                    .style(EnumChatFormatting.BOLD)
                                    .widthRel(0.15f))
                            .child(
                                createLoadButton().expanded()
                                    .heightRel(1f))
                            .child(
                                CommonWidgets.iconButton("icons/refresh", Pico8CartridgeScreen::open)
                                    .width(20)
                                    .heightRel(1f))
                            .child(
                                CommonWidgets.iconButton("icons/folder", this::openCartsFolder)
                                    .width(20)
                                    .heightRel(1f))
                            .child(
                                CommonWidgets.iconButton("icons/add", Pico8StoreScreen::open)
                                    .width(20)
                                    .heightRel(1f))));
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
                new TextWidget(IKey.str(cart.getFileName())).expanded()
                    .height(40)
                    .alignment(Alignment.CenterLeft)
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
        ClientGUI.open(PicoRScreen.startNew(this.selectedCart.getFile()));
    }

    private void refreshCarts() {
        this.carts = this.cartService.getCartridges();
    }

    private final class CartIcon extends UITexture {

        private final DynamicTexture texture;

        private CartIcon(ResourceLocation location, DynamicTexture texture) {
            super(location, 0, 0, 1, 1, false);
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
            Desktop.openFolder(this.cartService.cartsDirectory);
        } catch (IOException | RuntimeException exception) {
            Pico8GtnhMod.LOG.error("Could not open PICO-8 cartridge folder", exception);
        }
    }

}
