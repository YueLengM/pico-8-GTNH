package com.yuelengm.pico8gtnh.util;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URL;

import org.jsoup.Connection;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;

/** Shared web page and image fetch operations using Jsoup's default User-Agent. */
public final class Fetch {

    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };

    private Fetch() {}

    public static Document getDocument(String url, int timeoutMillis, int maxBodySize) throws IOException {
        return Jsoup.connect(url)
            .timeout(timeoutMillis)
            .maxBodySize(maxBodySize)
            .get();
    }

    public static URL downloadPng(URL url, File destination, int timeoutMillis, int maxBytes) throws IOException {
        Connection.Response response = Jsoup.connect(url.toExternalForm())
            .ignoreContentType(true)
            .timeout(timeoutMillis)
            .maxBodySize(maxBytes + 1)
            .execute();
        if (response.statusCode() != 200) {
            throw new IOException("Image download failed with HTTP " + response.statusCode());
        }

        byte[] image = response.bodyAsBytes();
        if (image.length > maxBytes) {
            throw new IOException("The image is larger than " + maxBytes + " bytes");
        }
        if (!hasPngSignature(image)) {
            throw new IOException("The downloaded file is not a PNG image");
        }

        try (FileOutputStream output = new FileOutputStream(destination)) {
            output.write(image);
        }
        return response.url();
    }

    private static boolean hasPngSignature(byte[] image) {
        if (image.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (image[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }
}
