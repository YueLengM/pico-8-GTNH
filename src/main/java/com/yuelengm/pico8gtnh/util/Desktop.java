package com.yuelengm.pico8gtnh.util;

import java.io.File;
import java.io.IOException;
import java.net.URI;

/** Helpers for opening files and web pages with the user's desktop environment. */
public final class Desktop {

    private Desktop() {}

    public static void openFolder(File folder) throws IOException {
        if (!java.awt.Desktop.isDesktopSupported()) {
            throw new IOException("Desktop folder access is not supported on this system");
        }
        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        if (!desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
            throw new IOException("Opening folders is not supported on this system");
        }
        desktop.open(folder);
    }

    public static void browse(URI uri) throws IOException {
        if (!java.awt.Desktop.isDesktopSupported()) {
            throw new IOException("Desktop browser access is not supported on this system");
        }
        java.awt.Desktop desktop = java.awt.Desktop.getDesktop();
        if (!desktop.isSupported(java.awt.Desktop.Action.BROWSE)) {
            throw new IOException("Opening web pages is not supported on this system");
        }
        desktop.browse(uri);
    }
}
