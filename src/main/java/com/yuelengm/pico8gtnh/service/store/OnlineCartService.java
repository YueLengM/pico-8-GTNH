package com.yuelengm.pico8gtnh.service.store;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/** Fetches and downloads PICO-8 cartridges from the Lexaloffle BBS. */
public final class OnlineCartService {

    private static final String BBS_ROOT = "https://www.lexaloffle.com/bbs/";
    private static final String HOST = "www.lexaloffle.com";
    private static final int TIMEOUT_MILLIS = 15000;
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A };
    private static final Pattern CART_DATA_ENTRY = Pattern.compile(
        "\\['[^']*'\\s*,\\s*(\\d+)\\s*,\\s*`([^`]+)`\\s*,\\s*\"([^\"]+)\"\\s*,\\s*[^,]+\\s*,\\s*[^,]+\\s*,\\s*\"[^\"]*\"\\s*,\\s*[^,]+\\s*,\\s*\"([^\"]*)\"");
    private static final Pattern DOWNLOAD_PATH = Pattern
        .compile("p8_run_cart\\([^,]+,\\s*'[^']*',\\s*'([^']+\\.p8\\.png)'", Pattern.CASE_INSENSITIVE);

    public enum Order {

        NEWEST("ts"),
        FEATURED("featured"),
        LUCKY("lucky");

        private final String parameter;

        Order(String parameter) {
            this.parameter = parameter;
        }

        public String getParameter() {
            return parameter;
        }
    }

    public List<OnlineCartridge> getCartridges(Order order, int page, String search) throws IOException {
        Document document = Jsoup.connect(buildPageUrl(order, page, search))
            .userAgent("PICO-8-GTNH/1.0 (in-game cartridge browser)")
            .timeout(TIMEOUT_MILLIS)
            .maxBodySize(2 * 1024 * 1024)
            .get();
        Element dataScript = document.selectFirst("script#cart_data_script");
        if (dataScript == null) {
            throw new IOException("The BBS response did not contain cartridge data");
        }

        List<OnlineCartridge> cartridges = new ArrayList<>();
        Matcher matcher = CART_DATA_ENTRY.matcher(dataScript.data());
        while (matcher.find()) {
            int threadId;
            try {
                threadId = Integer.parseInt(matcher.group(1));
            } catch (NumberFormatException exception) {
                continue;
            }
            String title = matcher.group(2)
                .trim();
            String thumbnailPath = matcher.group(3);
            String author = matcher.group(4)
                .trim();
            if (title.isEmpty()) {
                continue;
            }
            String thumbnailUrl = thumbnailPath.startsWith("/bbs/thumbs/") || thumbnailPath.startsWith("/media/")
                ? "https://" + HOST + thumbnailPath
                : null;
            cartridges.add(new OnlineCartridge(threadId, title, author, thumbnailUrl));
        }
        return cartridges;
    }

    public URI getPageUri(StorePage page) throws IOException {
        return URI.create(buildBrowserPageUrl(page.getOrder(), page.getPageNumber(), page.getSearch()));
    }

    private static String buildBrowserPageUrl(Order order, int page, String search) throws IOException {
        StringBuilder url = new StringBuilder(BBS_ROOT).append("?cat=7#sub=2&page=")
            .append(Math.max(1, page))
            .append("&mode=carts&orderby=")
            .append(encode(order.getParameter()));
        if (search != null && !search.trim()
            .isEmpty()) {
            url.append("&search=")
                .append(encode(search.trim()));
        }
        return url.toString();
    }

    private static String buildPageUrl(Order order, int page, String search) throws IOException {
        StringBuilder query = new StringBuilder("use_hurl=1&cat=7&sub=2&page=").append(Math.max(1, page))
            .append("&mode=carts&orderby=")
            .append(encode(order.getParameter()));
        if (search != null && !search.trim()
            .isEmpty()) {
            query.append("&search=")
                .append(encode(search.trim()));
        }
        return BBS_ROOT + "lister.php?" + query;
    }

    public StorePage getPage(StorePage page) throws IOException {
        return page.withCartridges(getCartridges(page.getOrder(), page.getPageNumber(), page.getSearch()));
    }

    /** Downloads the selected cart to the local cart folder and returns the saved file. */
    public File download(OnlineCartridge cartridge, File cartsDirectory) throws IOException {
        String detailUrl = BBS_ROOT + "?tid=" + cartridge.getThreadId();
        Document detail = Jsoup.connect(detailUrl)
            .userAgent("PICO-8-GTNH/1.0 (in-game cartridge browser)")
            .timeout(TIMEOUT_MILLIS)
            .maxBodySize(2 * 1024 * 1024)
            .get();

        String downloadPath = findDownloadPath(detail);
        URI downloadUri = URI.create(BBS_ROOT)
            .resolve(downloadPath);
        if (!HOST.equalsIgnoreCase(downloadUri.getHost()) || !downloadUri.getPath()
            .startsWith("/bbs/cposts/")
            || !downloadUri.getPath()
                .endsWith(".p8.png")) {
            throw new IOException("The cartridge page contained an unexpected download URL");
        }

        if (!cartsDirectory.exists() && !cartsDirectory.mkdirs()) {
            throw new IOException("Could not create cartridge folder: " + cartsDirectory);
        }

        String fileName = downloadUri.getPath()
            .substring(
                downloadUri.getPath()
                    .lastIndexOf('/') + 1);
        File destination = new File(cartsDirectory, fileName);
        if (destination.isFile()) {
            return destination;
        }

        File temporaryFile = File.createTempFile("pico8-bbs-", ".download", cartsDirectory);
        boolean saved = false;
        try {
            downloadPng(downloadUri.toURL(), temporaryFile);
            Files.move(temporaryFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING);
            saved = true;
            return destination;
        } finally {
            if (!saved && temporaryFile.exists() && !temporaryFile.delete()) {
                temporaryFile.deleteOnExit();
            }
        }
    }

    private static String findDownloadPath(Document detail) throws IOException {
        for (Element element : detail.select("[onclick*=p8_run_cart]")) {
            Matcher matcher = DOWNLOAD_PATH.matcher(element.attr("onclick"));
            if (matcher.find()) {
                return matcher.group(1);
            }
        }
        throw new IOException("No downloadable PICO-8 cartridge was found on the detail page");
    }

    private static void downloadPng(java.net.URL url, File destination) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setConnectTimeout(TIMEOUT_MILLIS);
        connection.setReadTimeout(TIMEOUT_MILLIS);
        connection.setRequestProperty("User-Agent", "PICO-8-GTNH/1.0 (in-game cartridge browser)");
        connection.setRequestProperty("Accept", "image/png,application/octet-stream;q=0.9,*/*;q=0.5");
        connection.connect();
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                throw new IOException("Cartridge download failed with HTTP " + connection.getResponseCode());
            }
            if (!HOST.equalsIgnoreCase(
                connection.getURL()
                    .getHost())) {
                throw new IOException("The cartridge download redirected to an unexpected host");
            }
            int contentLength = connection.getContentLength();
            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw new IOException("The cartridge file is larger than 8 MB");
            }

            try (InputStream input = new BufferedInputStream(connection.getInputStream());
                BufferedOutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
                byte[] buffer = new byte[8192];
                byte[] signature = new byte[PNG_SIGNATURE.length];
                int signatureBytes = readFully(input, signature);
                if (signatureBytes != PNG_SIGNATURE.length || !matchesPngSignature(signature)) {
                    throw new IOException("The downloaded file is not a PNG cartridge");
                }
                output.write(signature);

                int totalBytes = signatureBytes;
                int bytesRead;
                while ((bytesRead = input.read(buffer)) != -1) {
                    totalBytes += bytesRead;
                    if (totalBytes > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("The cartridge file is larger than 8 MB");
                    }
                    output.write(buffer, 0, bytesRead);
                }
            }
        } finally {
            connection.disconnect();
        }
    }

    private static int readFully(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int bytesRead = input.read(buffer, offset, buffer.length - offset);
            if (bytesRead < 0) {
                break;
            }
            offset += bytesRead;
        }
        return offset;
    }

    private static boolean matchesPngSignature(byte[] signature) {
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (signature[index] != PNG_SIGNATURE[index]) {
                return false;
            }
        }
        return true;
    }

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
