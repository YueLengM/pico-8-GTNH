package com.yuelengm.pico8gtnh.gui;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ThreadDownloadImageData;
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
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.ListWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.service.CartService;
import com.yuelengm.pico8gtnh.service.OnlineCartService;
import com.yuelengm.pico8gtnh.service.OnlineCartridge;

/** In-game browser for cartridges published on the Lexaloffle BBS. */
public final class Pico8OnlineCartridgeScreen {

    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PICO-8 BBS browser");
        thread.setDaemon(true);
        return thread;
    });
    private static final ResourceLocation UNKNOWN_PACK_ICON = new ResourceLocation("textures/misc/unknown_pack.png");
    private static final Set<Integer> REQUESTED_THUMBNAILS = Collections
        .newSetFromMap(new ConcurrentHashMap<Integer, Boolean>());

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final OnlineCartService onlineCartService = new OnlineCartService();
    private final CartService cartService = new CartService();
    private List<OnlineCartridge> cartridges = Collections.emptyList();
    private OnlineCartridge selectedCart;
    private OnlineCartService.Order order = OnlineCartService.Order.FEATURED;
    private int page = 1;
    private String search = "";
    private String status;
    private boolean loading;
    private boolean downloading;
    private int requestVersion;
    private ModularScreen screen;
    private TextFieldWidget searchField;

    private Pico8OnlineCartridgeScreen() {}

    public static void open() {
        Pico8OnlineCartridgeScreen browser = new Pico8OnlineCartridgeScreen();
        browser.loadPage();
    }

    private void loadPage() {
        final int version = ++this.requestVersion;
        this.loading = true;
        this.downloading = false;
        this.status = null;
        this.selectedCart = null;
        this.show();
        NETWORK_EXECUTOR.submit(() -> {
            try {
                List<OnlineCartridge> result = this.onlineCartService.getCartridges(this.order, this.page, this.search);
                this.minecraft.func_152344_a(() -> {
                    if (version != this.requestVersion || ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.cartridges = result;
                    this.loading = false;
                    this.status = null;
                    this.show();
                });
            } catch (IOException | RuntimeException exception) {
                Pico8GtnhMod.LOG.warn("Could not load the Lexaloffle BBS cartridge list", exception);
                this.minecraft.func_152344_a(() -> {
                    if (version != this.requestVersion || ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.loading = false;
                    this.status = "gui.pico8.online.error";
                    this.show();
                });
            }
        });
    }

    private void show() {
        this.screen = new ModularScreen(Pico8GtnhMod.MODID, this.buildPanel()).pausesGame(false);
        ClientGUI.open(this.screen);
    }

    private ModularPanel buildPanel() {
        Flow column = Flow.column()
            .full()
            .child(this.buildSearchRow())
            .child(this.buildSortRow());

        if (this.loading) {
            column.child(
                new TextWidget<>(IKey.lang("gui.pico8.online.loading")).expanded()
                    .fullWidth()
                    .textAlign(Alignment.CENTER));
        } else if (this.status != null) {
            column.child(
                new TextWidget<>(IKey.lang(this.status)).expanded()
                    .fullWidth()
                    .textAlign(Alignment.CENTER));
        } else if (this.cartridges.isEmpty()) {
            column.child(
                new TextWidget<>(IKey.lang("gui.pico8.online.empty")).expanded()
                    .fullWidth()
                    .textAlign(Alignment.CENTER));
        } else {
            column.child(
                new ListWidget<>().fullWidth()
                    .expanded()
                    .background(new Rectangle().color(0xFF202020))
                    .children(this.cartridges, OnlineCartridgeRow::new));
        }

        return ModularPanel.defaultPanel("pico8_online_carts")
            .widthRel(0.82f)
            .heightRel(0.86f)
            .padding(8)
            .child(
                column.child(this.buildPageRow())
                    .child(this.buildActionRow()));
    }

    private Flow buildSearchRow() {
        this.searchField = new TextFieldWidget().value(new StringValue(this.search))
            .hintText("search=a")
            .setMaxLength(80)
            .expanded()
            .height(20)
            .background(new Rectangle().color(0xFF202020));

        return Flow.row()
            .childPadding(2)
            .fullWidth()
            .height(22)
            .child(this.searchField)
            .child(textButton("gui.pico8.online.search", () -> {
                this.search = this.searchField.getText()
                    .trim();
                this.page = 1;
                this.loadPage();
            }).width(70)
                .fullHeight());
    }

    private Flow buildSortRow() {
        return Flow.row()
            .childPadding(2)
            .fullWidth()
            .height(20)
            .child(
                textButton("gui.pico8.online.sort_newest", () -> this.changeOrder(OnlineCartService.Order.NEWEST))
                    .expanded()
                    .fullHeight())
            .child(
                textButton("gui.pico8.online.sort_featured", () -> this.changeOrder(OnlineCartService.Order.FEATURED))
                    .expanded()
                    .fullHeight())
            .child(
                textButton("gui.pico8.online.sort_lucky", () -> this.changeOrder(OnlineCartService.Order.LUCKY))
                    .expanded()
                    .fullHeight());
    }

    private Flow buildPageRow() {
        return Flow.row()
            .childPadding(2)
            .fullWidth()
            .height(20)
            .child(textButton("gui.pico8.online.previous", () -> {
                if (this.page > 1) {
                    this.page--;
                    this.loadPage();
                }
            }).width(80)
                .fullHeight())
            .child(
                new TextWidget<>(IKey.str(this.pageLabel())).expanded()
                    .fullHeight()
                    .textAlign(Alignment.CENTER))
            .child(textButton("gui.pico8.online.next", () -> {
                this.page++;
                this.loadPage();
            }).width(80)
                .fullHeight());
    }

    private Flow buildActionRow() {
        return Flow.row()
            .childPadding(2)
            .fullWidth()
            .height(22)
            .child(
                textButton("gui.pico8.online.local", Pico8CartridgeScreen::open).width(110)
                    .fullHeight())
            .child(
                new TextWidget<>(this.status == null ? IKey.str("") : IKey.lang(this.status)).expanded()
                    .fullHeight()
                    .textAlign(Alignment.CENTER))
            .child(
                this.createDownloadButton()
                    .width(110)
                    .fullHeight());
    }

    private String pageLabel() {
        return this.search.isEmpty() ? "Page " + this.page : "Page " + this.page + " · " + this.search;
    }

    private void changeOrder(OnlineCartService.Order nextOrder) {
        this.order = nextOrder;
        this.page = 1;
        this.loadPage();
    }

    private static ButtonWidget<?> textButton(String labelKey, Runnable action) {
        return new ButtonWidget<>().overlay(IKey.lang(labelKey))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                action.run();
                return true;
            });
    }

    private ButtonWidget<?> createDownloadButton() {
        return new ButtonWidget<>().background((context, x, y, width, height, widgetTheme) -> {
            UITexture buttonTexture = isDownloadEnabled() ? GuiTextures.MC_BUTTON : GuiTextures.MC_BUTTON_DISABLED;
            buttonTexture.draw(context, x, y, width, height, widgetTheme);
        })
            .hoverBackground((context, x, y, width, height, widgetTheme) -> {
                UITexture buttonTexture = isDownloadEnabled() ? GuiTextures.MC_BUTTON_HOVERED
                    : GuiTextures.MC_BUTTON_DISABLED;
                buttonTexture.draw(context, x, y, width, height, widgetTheme);
            })
            .overlay(IKey.lang(this.downloading ? "gui.pico8.online.downloading" : "gui.pico8.online.download"))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0 || !isDownloadEnabled()) {
                    return false;
                }
                this.downloadSelectedCart();
                return true;
            });
    }

    private boolean isDownloadEnabled() {
        return this.selectedCart != null && !this.downloading;
    }

    private void downloadSelectedCart() {
        if (this.selectedCart == null || this.downloading) {
            return;
        }
        OnlineCartridge selected = this.selectedCart;
        this.downloading = true;
        this.status = "gui.pico8.online.downloading_status";
        this.show();
        NETWORK_EXECUTOR.submit(() -> {
            try {
                File downloaded = this.onlineCartService.download(selected, this.cartService.cartsDirectory);
                this.minecraft.func_152344_a(() -> {
                    if (ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    ClientGUI.open(PicoRScreen.startNew(downloaded));
                });
            } catch (IOException | RuntimeException exception) {
                Pico8GtnhMod.LOG.warn("Could not download PICO-8 cartridge {}", selected.getTitle(), exception);
                this.minecraft.func_152344_a(() -> {
                    if (ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.downloading = false;
                    this.status = "gui.pico8.online.download_error";
                    this.show();
                });
            }
        });
    }

    private final class OnlineCartridgeRow extends Flow implements Interactable {

        private final OnlineCartridge cartridge;

        private OnlineCartridgeRow(OnlineCartridge cartridge) {
            super(GuiAxis.X);
            this.cartridge = cartridge;
            widthRel(1f);
            height(132);
            padding(2);
            crossAxisAlignment(Alignment.CrossAxis.CENTER);
            child(
                new RemoteCartIcon(cartridge).asWidget()
                    .size(128)
                    .marginRight(4));
            child(
                new TextWidget<>(IKey.str(cartridge.getTitle())).expanded()
                    .height(40)
                    .textAlign(Alignment.CenterLeft)
                    .style(EnumChatFormatting.WHITE));
            background(
                (context, x, y, width, height, widgetTheme) -> drawRowBackground(cartridge, x, y, width, height));
        }

        @Override
        public @NotNull Result onMousePressed(int mouseButton) {
            if (mouseButton != 0) {
                return Result.IGNORE;
            }
            selectedCart = this.cartridge;
            return Result.SUCCESS;
        }
    }

    private void drawRowBackground(OnlineCartridge cartridge, float x, float y, float width, float height) {
        boolean selected = cartridge.equals(this.selectedCart);
        int background = selected ? 0xFF363636 : 0xFF292929;
        int topLeftEdge = selected ? 0xFFFFD34E : 0xFF505050;
        int bottomRightEdge = selected ? 0xFFFFD34E : 0xFF171717;
        GuiDraw.drawRect(x, y, width, height, background);
        GuiDraw.drawRect(x, y, width, 1, topLeftEdge);
        GuiDraw.drawRect(x, y, 1, height, topLeftEdge);
        GuiDraw.drawRect(x, y + height - 1, width, 1, bottomRightEdge);
        GuiDraw.drawRect(x + width - 1, y, 1, height, bottomRightEdge);
    }

    private final class RemoteCartIcon extends UITexture {

        private RemoteCartIcon(OnlineCartridge cartridge) {
            super(
                cartridge.getThumbnailUrl() == null ? UNKNOWN_PACK_ICON : getTextureLocation(cartridge),
                0,
                0,
                1,
                1,
                null);
            if (cartridge.getThumbnailUrl() != null && REQUESTED_THUMBNAILS.add(cartridge.getThreadId())) {
                ResourceLocation location = getTextureLocation(cartridge);
                minecraft.getTextureManager()
                    .loadTexture(
                        location,
                        new ThreadDownloadImageData(null, cartridge.getThumbnailUrl(), UNKNOWN_PACK_ICON, null));
            }
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            super.draw(x, y, width, height);
        }
    }

    private static ResourceLocation getTextureLocation(OnlineCartridge cartridge) {
        return new ResourceLocation(Pico8GtnhMod.MODID, "online_cart_" + cartridge.getThreadId());
    }
}
