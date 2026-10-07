package com.yuelengm.pico8gtnh.service.store;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import com.yuelengm.pico8gtnh.util.Fetch;

/** Fetches and downloads PICO-8 cartridges from the Lexaloffle BBS. */
public final class OnlineCartService {

    private static final String BBS_ROOT = "https://www.lexaloffle.com/bbs/";
    private static final String HOST = "www.lexaloffle.com";
    private static final int TIMEOUT_MILLIS = 15000;
    private static final int MAX_DOWNLOAD_BYTES = 8 * 1024 * 1024;
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
        Document document = Fetch.getDocument(buildPageUrl(order, page, search), TIMEOUT_MILLIS, 2 * 1024 * 1024);
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
        Document detail = Fetch.getDocument(detailUrl, TIMEOUT_MILLIS, 2 * 1024 * 1024);

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
            URL finalUrl = Fetch.downloadPng(downloadUri.toURL(), temporaryFile, TIMEOUT_MILLIS, MAX_DOWNLOAD_BYTES);
            if (!HOST.equalsIgnoreCase(finalUrl.getHost())) {
                throw new IOException("The cartridge download redirected to an unexpected host");
            }
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

    private static String encode(String value) throws UnsupportedEncodingException {
        return URLEncoder.encode(value, "UTF-8");
    }
}
