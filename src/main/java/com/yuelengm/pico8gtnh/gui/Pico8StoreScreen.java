package com.yuelengm.pico8gtnh.gui;

import java.awt.Desktop;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.IImageBuffer;
import net.minecraft.client.renderer.ThreadDownloadImageData;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.ResourceLocation;

import com.cleanroommc.modularui.api.GuiAxis;
import com.cleanroommc.modularui.api.IPanelHandler;
import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.GuiTextures;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.drawable.UITexture;
import com.cleanroommc.modularui.factory.ClientGUI;
import com.cleanroommc.modularui.screen.ModularPanel;
import com.cleanroommc.modularui.screen.ModularScreen;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.StringValue;
import com.cleanroommc.modularui.widgets.ButtonWidget;
import com.cleanroommc.modularui.widgets.Dialog;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.cleanroommc.modularui.widgets.layout.Flow;
import com.cleanroommc.modularui.widgets.layout.Grid;
import com.cleanroommc.modularui.widgets.textfield.TextFieldWidget;
import com.yuelengm.pico8gtnh.Pico8GtnhMod;
import com.yuelengm.pico8gtnh.service.CartService;
import com.yuelengm.pico8gtnh.service.store.OnlineCartService;
import com.yuelengm.pico8gtnh.service.store.OnlineCartridge;
import com.yuelengm.pico8gtnh.service.store.StorePage;

/** In-game browser for cartridges published on the Lexaloffle BBS. */
public final class Pico8StoreScreen {

    private static final int PANEL_PADDING = 8;
    private static final int CARD_WIDTH = 130;

    private static final ExecutorService NETWORK_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "PICO-8 BBS browser");
        thread.setDaemon(true);
        return thread;
    });
    private static final ResourceLocation UNKNOWN_PACK_ICON = new ResourceLocation("textures/misc/unknown_pack.png");
    private static final UITexture LOADING_ICON = UITexture.fullImage(Pico8GtnhMod.MODID, "icons/loading");
    private static final UITexture CHECKMARK_ICON = UITexture.fullImage(Pico8GtnhMod.MODID, "icons/checkmark");
    private static final ConcurrentHashMap<Integer, RemoteThumbnailTexture> THUMBNAILS = new ConcurrentHashMap<>();

    private final Minecraft minecraft = Minecraft.getMinecraft();
    private final OnlineCartService onlineCartService = new OnlineCartService();
    private final CartService cartService = new CartService();
    private final Set<Integer> downloadedCartridges = new HashSet<>();
    private final Map<Integer, ButtonWidget<?>> downloadButtons = new HashMap<>();
    private StorePage storePage = StorePage.loading(OnlineCartService.Order.FEATURED, 1, "");
    private OnlineCartridge downloadingCart;
    private String downloadingCartTitle = "";
    private int requestVersion;
    private ModularScreen screen;
    private IPanelHandler downloadDialogHandler;
    private TextFieldWidget searchField;
    private Grid cartridgeGrid;
    private int cartridgeGridColumns;
    private boolean gridRebuildQueued;

    private Pico8StoreScreen() {}

    public static void open() {
        Pico8StoreScreen browser = new Pico8StoreScreen();
        browser.loadPage(browser.storePage);
    }

    private void loadPage(StorePage requestedPage) {
        final int version = ++this.requestVersion;
        this.storePage = StorePage
            .loading(requestedPage.getOrder(), requestedPage.getPageNumber(), requestedPage.getSearch());
        this.showPage();
        NETWORK_EXECUTOR.submit(() -> {
            try {
                StorePage result = this.onlineCartService.getPage(requestedPage);
                this.minecraft.func_152344_a(() -> {
                    if (version != this.requestVersion || ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.storePage = result;
                    this.showPage();
                });
            } catch (IOException | RuntimeException exception) {
                Pico8GtnhMod.LOG.warn("Could not load the Lexaloffle BBS cartridge list", exception);
                this.minecraft.func_152344_a(() -> {
                    if (version != this.requestVersion || ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.storePage = requestedPage.withError();
                    this.showPage();
                });
            }
        });
    }

    private void showPage() {
        this.closeDownloadDialog();
        this.downloadDialogHandler = null;
        this.downloadButtons.clear();
        this.cartridgeGrid = null;
        this.screen = new ModularScreen(Pico8GtnhMod.MODID, this.buildPanel()).pausesGame(false);
        ClientGUI.open(this.screen);
        if (this.cartridgeGrid != null) {
            this.screen.registerFrameUpdateListener(this.cartridgeGrid, this::refreshGridColumns);
        }
        if (this.downloadingCart != null) {
            this.openDownloadDialog(this.downloadingCart);
        }
    }

    private ModularPanel buildPanel() {
        Flow column = Flow.column()
            .full()
            .childPadding(4)
            .child(this.buildSearchRow());

        if (this.storePage.getState() == StorePage.State.LOADING) {
            column.child(
                new TextWidget<>(IKey.lang("gui.pico8.online.loading")).expanded()
                    .fullWidth()
                    .textAlign(Alignment.CENTER)
                    .style(EnumChatFormatting.WHITE));
        } else if (this.storePage.getState() == StorePage.State.ERROR) {
            column.child(
                new TextWidget<>(IKey.lang("gui.pico8.online.error")).expanded()
                    .fullWidth()
                    .textAlign(Alignment.CENTER)
                    .style(EnumChatFormatting.RED));
        } else if (this.storePage.getCartridges()
            .isEmpty()) {
                column.child(
                    new TextWidget<>(IKey.lang("gui.pico8.online.empty")).expanded()
                        .fullWidth()
                        .textAlign(Alignment.CENTER));
            } else {
                this.cartridgeGridColumns = this.cardsPerRow();
                this.cartridgeGrid = new Grid().fullWidth()
                    .alignment(Alignment.CENTER)
                    .expanded()
                    .scrollable()
                    .gridOfWidthElements(
                        this.cartridgeGridColumns,
                        this.storePage.getCartridges(),
                        (x, y, index, cartridge) -> new CartridgeCard(cartridge));
                column.child(this.cartridgeGrid);
            }

        column.child(this.buildPageRow());

        return ModularPanel.defaultPanel("pico8_online_carts")
            .full()
            .padding(PANEL_PADDING)
            .background(new Rectangle().color(0x80202020))
            .disableThemeBackground(true)
            .disableHoverThemeBackground(true)
            .child(column);
    }

    private int cardsPerRow() {
        ScaledResolution resolution = new ScaledResolution(
            this.minecraft,
            this.minecraft.displayWidth,
            this.minecraft.displayHeight);
        int gridWidth = resolution.getScaledWidth() - PANEL_PADDING * 2;
        int columns = (gridWidth) / (CARD_WIDTH);
        return Math.max(1, columns);
    }

    private void refreshGridColumns() {
        if (this.cartridgeGrid == null) {
            return;
        }
        int columns = this.cardsPerRow();
        if (columns != this.cartridgeGridColumns && !this.gridRebuildQueued) {
            this.gridRebuildQueued = true;
            ModularScreen screenToRefresh = this.screen;
            this.minecraft.func_152344_a(() -> {
                this.gridRebuildQueued = false;
                if (ModularScreen.getCurrent() == screenToRefresh) {
                    this.showPage();
                }
            });
        }
    }

    private Flow buildSearchRow() {
        this.searchField = new TextFieldWidget().value(new StringValue(this.storePage.getSearch()))
            .setMaxLength(80)
            .expanded()
            .fullHeight();

        return Flow.row()
            .childPadding(2)
            .fullWidth()
            .height(20)
            .child(
                Flow.row()
                    .widthRel(0.5f)
                    .fullHeight()
                    .childPadding(1)
                    .child(
                        iconButton("icons/world", this::openSearchPage).size(20)
                            .padding(2))
                    .child(
                        sortButton("gui.pico8.online.sort_newest", OnlineCartService.Order.NEWEST).expanded()
                            .fullHeight())
                    .child(
                        sortButton("gui.pico8.online.sort_featured", OnlineCartService.Order.FEATURED).expanded()
                            .fullHeight())
                    .child(
                        sortButton("gui.pico8.online.sort_lucky", OnlineCartService.Order.LUCKY).expanded()
                            .fullHeight()))
            .child(
                Flow.row()
                    .widthRel(0.5f)
                    .fullHeight()
                    .child(this.searchField)
                    .child(
                        iconButton(
                            "icons/searsh",
                            () -> this.loadPage(
                                this.storePage.withSearch(
                                    this.searchField.getText()
                                        .trim()))).size(20)
                                            .padding(2)));
    }

    private Flow buildPageRow() {
        return Flow.row()
            .childPadding(2)
            .fullWidth()
            .height(20)
            .child(textButton("gui.pico8.online.previous", () -> {
                if (this.storePage.getPageNumber() > 1) {
                    this.loadPage(this.storePage.withPageNumber(this.storePage.getPageNumber() - 1));
                }
            }).width(80)
                .fullHeight())
            .child(
                new TextWidget<>(IKey.lang("gui.pico8.online.page", this.storePage.getPageNumber())).expanded()
                    .fullHeight()
                    .textAlign(Alignment.CENTER)
                    .style(EnumChatFormatting.WHITE))
            .child(
                textButton(
                    "gui.pico8.online.next",
                    () -> this.loadPage(this.storePage.withPageNumber(this.storePage.getPageNumber() + 1))).width(80)
                        .fullHeight());
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

    private ButtonWidget<?> sortButton(String labelKey, OnlineCartService.Order order) {
        boolean selected = this.storePage.getOrder() == order;
        return new ButtonWidget<>().background((context, x, y, width, height, widgetTheme) -> {
            UITexture buttonTexture = selected ? GuiTextures.MC_BUTTON_HOVERED : GuiTextures.MC_BUTTON;
            buttonTexture.draw(context, x, y, width, height, widgetTheme);
        })
            .hoverBackground(GuiTextures.MC_BUTTON_HOVERED::draw)
            .overlay(IKey.lang(labelKey))
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                this.loadPage(this.storePage.withOrder(order));
                return true;
            });
    }

    private static ButtonWidget<?> iconButton(String iconPath, Runnable action) {
        UITexture icon = UITexture.fullImage(Pico8GtnhMod.MODID, iconPath);
        return new ButtonWidget<>().overlay(icon)
            .onMousePressed(mouseButton -> {
                if (mouseButton != 0) {
                    return false;
                }
                action.run();
                return true;
            });
    }

    private ButtonWidget<?> createDownloadButton(OnlineCartridge cartridge) {
        return iconButton("icons/download", () -> {
            if (isDownloadEnabled(cartridge)) {
                this.downloadCart(cartridge);
            }
        });
    }

    private boolean isDownloadEnabled(OnlineCartridge cartridge) {
        return this.downloadingCart == null && !this.isCartridgeDownloaded(cartridge);
    }

    private boolean isCartridgeDownloaded(OnlineCartridge cartridge) {
        return this.downloadedCartridges.contains(cartridge.getThreadId());
    }

    private void openCartridgePage(OnlineCartridge cartridge) {
        try {
            openInBrowser(URI.create("https://www.lexaloffle.com/bbs/?tid=" + cartridge.getThreadId()));
        } catch (IOException | RuntimeException exception) {
            Pico8GtnhMod.LOG.warn("Could not open the Lexaloffle BBS page for {}", cartridge.getThreadId(), exception);
        }
    }

    private void openSearchPage() {
        String search = this.searchField.getText()
            .trim();
        StorePage page = search.equals(this.storePage.getSearch()) ? this.storePage : this.storePage.withSearch(search);
        try {
            openInBrowser(this.onlineCartService.getPageUri(page));
        } catch (IOException | RuntimeException exception) {
            Pico8GtnhMod.LOG.warn("Could not open the Lexaloffle BBS cartridge list", exception);
        }
    }

    private static void openInBrowser(URI uri) throws IOException {
        if (!Desktop.isDesktopSupported()) {
            throw new IOException("Desktop browser access is not supported on this system");
        }
        Desktop desktop = Desktop.getDesktop();
        if (!desktop.isSupported(Desktop.Action.BROWSE)) {
            throw new IOException("Opening web pages is not supported on this system");
        }
        desktop.browse(uri);
    }

    private void downloadCart(OnlineCartridge selected) {
        if (this.downloadingCart != null) {
            return;
        }
        this.downloadingCart = selected;
        this.openDownloadDialog(selected);
        NETWORK_EXECUTOR.submit(() -> {
            try {
                this.onlineCartService.download(selected, this.cartService.cartsDirectory);
                this.minecraft.func_152344_a(() -> {
                    if (ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.downloadedCartridges.add(selected.getThreadId());
                    this.downloadingCart = null;
                    this.closeDownloadDialog();
                    this.markDownloadComplete(selected);
                });
            } catch (IOException | RuntimeException exception) {
                Pico8GtnhMod.LOG.warn("Could not download PICO-8 cartridge {}", selected.getTitle(), exception);
                this.minecraft.func_152344_a(() -> {
                    if (ModularScreen.getCurrent() != this.screen) {
                        return;
                    }
                    this.downloadingCart = null;
                    this.closeDownloadDialog();
                });
            }
        });
    }

    private void markDownloadComplete(OnlineCartridge cartridge) {
        ButtonWidget<?> button = this.downloadButtons.get(cartridge.getThreadId());
        if (button == null) {
            return;
        }
        button.overlay(CHECKMARK_ICON);
    }

    private void openDownloadDialog(OnlineCartridge cartridge) {
        this.downloadingCartTitle = cartridge.getTitle();
        if (this.downloadDialogHandler == null) {
            this.downloadDialogHandler = IPanelHandler.simple(this.screen.getMainPanel(), (parentPanel, player) -> {
                Dialog<Void> dialog = new Dialog<>("pico8_download_status");
                dialog.width(220)
                    .height(64)
                    .padding(8)
                    .background(new Rectangle().color(0xF0202020))
                    .child(
                        Flow.column()
                            .full()
                            .child(
                                new TextWidget<>(IKey.lang("gui.pico8.online.downloading_status")).fullWidth()
                                    .height(20)
                                    .textAlign(Alignment.CENTER)
                                    .style(EnumChatFormatting.WHITE))
                            .child(
                                new TextWidget<>(IKey.lang(() -> this.downloadingCartTitle)).fullWidth()
                                    .height(20)
                                    .textAlign(Alignment.CENTER)
                                    .style(EnumChatFormatting.GRAY)));
                return dialog;
            }, true);
        }
        this.downloadDialogHandler.openPanel();
    }

    private void closeDownloadDialog() {
        if (this.downloadDialogHandler != null) {
            this.downloadDialogHandler.closePanel();
        }
    }

    private final class CartridgeCard extends Flow {

        private CartridgeCard(OnlineCartridge cartridge) {
            super(GuiAxis.Y);
            String authorLabel = cartridge.getAuthor()
                .isEmpty() ? "" : ("by " + cartridge.getAuthor());
            width(CARD_WIDTH);
            coverChildrenHeight();
            paddingBottom(4);
            child(
                new RemoteCartIcon(cartridge).asWidget()
                    .size(128));
            child(
                new TextWidget<>(IKey.str(cartridge.getTitle())).fullWidth()
                    .height(34)
                    .textAlign(Alignment.CENTER)
                    .style(EnumChatFormatting.WHITE));
            child(
                new TextWidget<>(IKey.str(authorLabel)).fullWidth()
                    .height(16)
                    .textAlign(Alignment.CENTER)
                    .style(EnumChatFormatting.GRAY));
            Flow actionRow = Flow.row()
                .childPadding(2)
                .mainAxisAlignment(Alignment.MainAxis.CENTER)
                .fullWidth()
                .height(20)
                .child(
                    iconButton("icons/world_page", () -> openCartridgePage(cartridge)).size(20)
                        .padding(2));
            if (isCartridgeDownloaded(cartridge)) {
                actionRow.child(
                    CHECKMARK_ICON.asWidget()
                        .size(2)
                        .margin(2));
            } else {
                ButtonWidget<?> downloadButton = createDownloadButton(cartridge);
                Pico8StoreScreen.this.downloadButtons.put(cartridge.getThreadId(), downloadButton);
                actionRow.child(
                    downloadButton.size(20)
                        .padding(2));
            }
            child(actionRow);
        }
    }

    private final class RemoteCartIcon extends UITexture {

        private final int threadId;

        private RemoteCartIcon(OnlineCartridge cartridge) {
            super(
                cartridge.getThumbnailUrl() == null ? UNKNOWN_PACK_ICON : getTextureLocation(cartridge),
                0,
                0,
                1,
                1,
                null);
            this.threadId = cartridge.getThreadId();
            if (cartridge.getThumbnailUrl() != null) {
                THUMBNAILS.computeIfAbsent(cartridge.getThreadId(), threadId -> {
                    RemoteThumbnailTexture texture = new RemoteThumbnailTexture(cartridge.getThumbnailUrl());
                    minecraft.getTextureManager()
                        .loadTexture(getTextureLocation(cartridge), texture);
                    return texture;
                });
            }
        }

        @Override
        public void draw(float x, float y, float width, float height) {
            RemoteThumbnailTexture thumbnail = THUMBNAILS.get(this.threadId);
            if (thumbnail != null && !thumbnail.isImageReady()) {
                LOADING_ICON.draw(x + 32, y + 32, 64, 64);
            } else {
                super.draw(x, y, width, height);
            }
        }

    }

    private static final class RemoteThumbnailTexture extends ThreadDownloadImageData {

        private final AtomicBoolean imageReady;

        private RemoteThumbnailTexture(String imageUrl) {
            this(imageUrl, new AtomicBoolean());
        }

        private RemoteThumbnailTexture(String imageUrl, AtomicBoolean imageReady) {
            super(null, imageUrl, UNKNOWN_PACK_ICON, new IImageBuffer() {

                @Override
                public BufferedImage parseUserSkin(BufferedImage image) {
                    return image;
                }

                @Override
                public void func_152634_a() {
                    imageReady.set(true);
                }
            });
            this.imageReady = imageReady;
        }

        private boolean isImageReady() {
            return this.imageReady.get();
        }
    }

    private static ResourceLocation getTextureLocation(OnlineCartridge cartridge) {
        return new ResourceLocation(Pico8GtnhMod.MODID, "textures/online_cart_" + cartridge.getThreadId() + ".png");
    }
}
