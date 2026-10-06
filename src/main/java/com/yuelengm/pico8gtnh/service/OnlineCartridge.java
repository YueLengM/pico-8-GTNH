package com.yuelengm.pico8gtnh.service;

/** Metadata for a cartridge listed on the Lexaloffle BBS. */
public final class OnlineCartridge {

    private final int threadId;
    private final String title;
    private final String author;
    private final String thumbnailUrl;

    public OnlineCartridge(int threadId, String title, String author, String thumbnailUrl) {
        this.threadId = threadId;
        this.title = title;
        this.author = author;
        this.thumbnailUrl = thumbnailUrl;
    }

    public int getThreadId() {
        return threadId;
    }

    public String getTitle() {
        return title;
    }

    public String getAuthor() {
        return author;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }
}
