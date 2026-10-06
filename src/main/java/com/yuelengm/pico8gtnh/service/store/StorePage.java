package com.yuelengm.pico8gtnh.service.store;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Cartridge data and query state for one page of the online store. */
public final class StorePage {

    public enum State {

        LOADING,
        READY,
        ERROR
    }

    private final OnlineCartService.Order order;
    private final int pageNumber;
    private final String search;
    private final List<OnlineCartridge> cartridges;
    private final State state;

    private StorePage(OnlineCartService.Order order, int pageNumber, String search, List<OnlineCartridge> cartridges,
        State state) {
        this.order = order;
        this.pageNumber = Math.max(1, pageNumber);
        this.search = search == null ? "" : search;
        this.cartridges = Collections.unmodifiableList(new ArrayList<>(cartridges));
        this.state = state;
    }

    public static StorePage loading(OnlineCartService.Order order, int pageNumber, String search) {
        return new StorePage(order, pageNumber, search, Collections.emptyList(), State.LOADING);
    }

    public StorePage withCartridges(List<OnlineCartridge> cartridges) {
        return new StorePage(this.order, this.pageNumber, this.search, cartridges, State.READY);
    }

    public StorePage withError() {
        return new StorePage(this.order, this.pageNumber, this.search, Collections.emptyList(), State.ERROR);
    }

    public StorePage withOrder(OnlineCartService.Order order) {
        return loading(order, 1, this.search);
    }

    public StorePage withPageNumber(int pageNumber) {
        return loading(this.order, pageNumber, this.search);
    }

    public StorePage withSearch(String search) {
        return loading(this.order, 1, search);
    }

    public OnlineCartService.Order getOrder() {
        return order;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public String getSearch() {
        return search;
    }

    public List<OnlineCartridge> getCartridges() {
        return cartridges;
    }

    public State getState() {
        return state;
    }

    public String getPageLabel() {
        return this.search.isEmpty() ? "Page " + this.pageNumber : "Page " + this.pageNumber + " · " + this.search;
    }
}
