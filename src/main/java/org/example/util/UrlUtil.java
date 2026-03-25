package org.example.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class UrlUtil {
    private static final String OZON_BASE = "https://www.ozon.ru";
    private static final String OZON_API_PREFIX = OZON_BASE + "/api/entrypoint-api.bx/page/json/v2";
    private static final String REVIEW_POINTS_HIGHLIGHT_PATH = "/highlight/bally-za-otzyv-1171518";
    private static final String INITIAL_HIGHLIGHT_PAGINATOR_TOKEN = "3618992";

    public static String normalize(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }

        String trimmedUrl = url.trim();
        if (trimmedUrl.startsWith("http://") || trimmedUrl.startsWith("https://")) {
            return trimmedUrl;
        }
        if (trimmedUrl.startsWith("/")) {
            return OZON_BASE + trimmedUrl;
        }
        return OZON_BASE + "/" + trimmedUrl;
    }

    public static boolean isApiUrl(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        return url.contains("/api/entrypoint-api.bx/page/json/")
            || url.startsWith(OZON_API_PREFIX);
    }

    public static String normalizeProductUrl(String url) {
        String normalizedUrl = normalize(url);
        if (normalizedUrl == null) {
            return null;
        }

        try {
            URI uri = new URI(normalizedUrl);
            String path = uri.getRawPath();
            if (path != null && path.contains("/product/")) {
                return new URI(
                    uri.getScheme() != null ? uri.getScheme() : "https",
                    null,
                    uri.getHost() != null ? uri.getHost() : "www.ozon.ru",
                    uri.getPort(),
                    path,
                    null,
                    null
                ).toString();
            }
        } catch (URISyntaxException ignored) {
            // Fallback below keeps the product path and drops the query suffix if the URI is malformed.
        }

        int productQueryIndex = normalizedUrl.indexOf("/?");
        if (normalizedUrl.contains("/product/") && productQueryIndex >= 0) {
            return normalizedUrl.substring(0, productQueryIndex);
        }
        if (normalizedUrl.contains("/product/")) {
            int queryIndex = normalizedUrl.indexOf('?');
            if (queryIndex >= 0) {
                return normalizedUrl.substring(0, queryIndex);
            }
        }
        return normalizedUrl;
    }

    public static String addPageParam(String url, int page) {
        if (url == null || url.isEmpty()) {
            return url;
        }

        try {
            URI uri = new URI(url);
            String query = uri.getQuery();

            if (query == null || query.isEmpty()) {
                return url + (url.contains("?") ? "&" : "?") + "page=" + page;
            }

            query = query.replaceAll("(^|&)page=\\d+(&|$)", "$1");
            query = query.replaceAll("&+", "&");
            query = query.replaceAll("^&|&$", "");

            if (query.isEmpty()) {
                return url.split("\\?")[0] + "?page=" + page;
            }
            return url.split("\\?")[0] + "?" + query + "&page=" + page;
        } catch (URISyntaxException e) {
            return url + (url.contains("?") ? "&" : "?") + "page=" + page;
        }
    }

    public static String buildJsonApiUrl(String catalogUrl) {
        if (catalogUrl == null || catalogUrl.isBlank()) {
            return null;
        }

        String normalizedUrl = normalize(catalogUrl);
        if (isApiUrl(normalizedUrl)) {
            return normalizedUrl;
        }

        try {
            URI uri = new URI(normalizedUrl);
            String path = uri.getRawPath();
            String query = uri.getRawQuery();

            if (isInitialReviewPointsHighlight(path, query)) {
                return buildInitialHighlightApiUrl();
            }

            String pageUrl = path;
            if (query != null && !query.isEmpty()) {
                pageUrl += "?" + query;
            }

            String encodedUrl = URLEncoder.encode(pageUrl, StandardCharsets.UTF_8);
            return OZON_API_PREFIX + "?url=" + encodedUrl;
        } catch (Exception e) {
            String pageUrl = normalizedUrl;
            if (pageUrl.startsWith("https://www.ozon.ru")) {
                pageUrl = pageUrl.substring("https://www.ozon.ru".length());
            } else if (pageUrl.startsWith("http://www.ozon.ru")) {
                pageUrl = pageUrl.substring("http://www.ozon.ru".length());
            }

            try {
                String encodedUrl = URLEncoder.encode(pageUrl, StandardCharsets.UTF_8);
                return OZON_API_PREFIX + "?url=" + encodedUrl;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    public static String buildInitialHighlightApiUrl() {
        return OZON_API_PREFIX
            + "?url=" + REVIEW_POINTS_HIGHLIGHT_PATH
            + "&paginator_token=" + INITIAL_HIGHLIGHT_PAGINATOR_TOKEN;
    }

    private static boolean isInitialReviewPointsHighlight(String path, String query) {
        if (path == null || (query != null && !query.isEmpty())) {
            return false;
        }

        String canonicalPath = path.endsWith("/") && path.length() > 1
            ? path.substring(0, path.length() - 1)
            : path;
        return REVIEW_POINTS_HIGHLIGHT_PATH.equals(canonicalPath);
    }

    public static String buildCategoryApiUrl(String categoryName, String paginatorToken) {
        if (categoryName == null || categoryName.isEmpty()) {
            return null;
        }

        String categoryUrl = "/category/" + categoryName + "/";
        categoryUrl += "?has_points_from_reviews=t";
        categoryUrl += "&layout_page_index=2";

        if (paginatorToken != null && !paginatorToken.isEmpty()) {
            categoryUrl += "&paginator_token=" + paginatorToken;
        }

        try {
            String encodedUrl = URLEncoder.encode(categoryUrl, StandardCharsets.UTF_8);
            return OZON_API_PREFIX + "?url=" + encodedUrl;
        } catch (Exception e) {
            return null;
        }
    }
}
