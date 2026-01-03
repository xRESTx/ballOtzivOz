package org.example.util;

import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class UrlUtil {
    private static final String OZON_BASE = "https://www.ozon.ru";

    /**
     * Нормализует URL: если относительный, добавляет базовый домен Ozon.
     * 
     * @param url относительный или абсолютный URL
     * @return нормализованный абсолютный URL
     */
    public static String normalize(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        
        url = url.trim();
        
        // Если уже абсолютный URL
        if (url.startsWith("http://") || url.startsWith("https://")) {
            return url;
        }
        
        // Если начинается с /, добавляем базовый домен
        if (url.startsWith("/")) {
            return OZON_BASE + url;
        }
        
        // Иначе добавляем / перед URL
        return OZON_BASE + "/" + url;
    }

    /**
     * Добавляет или обновляет параметр page в URL.
     * 
     * @param url исходный URL
     * @param page номер страницы
     * @return URL с параметром page
     */
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
            
            // Удаляем существующий параметр page, если есть
            query = query.replaceAll("(^|&)page=\\d+(&|$)", "$1");
            query = query.replaceAll("&+", "&");
            query = query.replaceAll("^&|&$", "");
            
            if (query.isEmpty()) {
                return url.split("\\?")[0] + "?page=" + page;
            } else {
                return url.split("\\?")[0] + "?" + query + "&page=" + page;
            }
        } catch (URISyntaxException e) {
            // Если не удалось распарсить, просто добавляем параметр
            return url + (url.contains("?") ? "&" : "?") + "page=" + page;
        }
    }

    /**
     * Строит URL для JSON API Ozon на основе URL каталога.
     * Для первой страницы добавляет начальные параметры пагинации.
     * Формат: https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2?url=<encoded_category_url_with_params>
     * 
     * @param catalogUrl URL каталога (например: https://www.ozon.ru/category/elektronika-15500/?has_points_from_reviews=t)
     * @return URL JSON API
     */
    public static String buildJsonApiUrl(String catalogUrl) {
        if (catalogUrl == null || catalogUrl.isEmpty()) {
            return null;
        }
        
        try {
            URI uri = new URI(catalogUrl);
            String path = uri.getPath();
            String query = uri.getQuery();
            
            // Строим относительный URL категории
            String categoryUrl = path;
            if (query != null && !query.isEmpty()) {
                categoryUrl += "?" + query;
            }
            
            // Для первой страницы добавляем начальные параметры пагинации
            // (если их еще нет в URL)
            if (!categoryUrl.contains("layout_page_index") && !categoryUrl.contains("page=")) {
                categoryUrl += (categoryUrl.contains("?") ? "&" : "?") + "layout_page_index=1&page=1";
            }
            
            // Кодируем URL для параметра url
            String encodedUrl = URLEncoder.encode(categoryUrl, StandardCharsets.UTF_8);
            
            // Строим URL JSON API
            return OZON_BASE + "/api/entrypoint-api.bx/page/json/v2?url=" + encodedUrl;
            
        } catch (Exception e) {
            // Если не удалось распарсить, пытаемся извлечь путь из URL
            String categoryUrl = catalogUrl;
            if (catalogUrl.startsWith("https://www.ozon.ru")) {
                categoryUrl = catalogUrl.substring("https://www.ozon.ru".length());
            } else if (catalogUrl.startsWith("http://www.ozon.ru")) {
                categoryUrl = catalogUrl.substring("http://www.ozon.ru".length());
            }
            
            // Добавляем начальные параметры пагинации
            if (!categoryUrl.contains("layout_page_index") && !categoryUrl.contains("page=")) {
                categoryUrl += (categoryUrl.contains("?") ? "&" : "?") + "layout_page_index=1&page=1";
            }
            
            try {
                String encodedUrl = URLEncoder.encode(categoryUrl, StandardCharsets.UTF_8);
                return OZON_BASE + "/api/entrypoint-api.bx/page/json/v2?url=" + encodedUrl;
            } catch (Exception ex) {
                return null;
            }
        }
    }

    /**
     * Строит URL JSON API для категории с начальными параметрами пагинации.
     * Формат: https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2?url=%2Fcategory%2F<category>%2F%3Fhas_points_from_reviews%3Dt%26layout_page_index%3D2%26paginator_token%3D<token>
     * 
     * @param categoryName название категории (например: "elektronika-15500")
     * @param paginatorToken токен пагинации (например: "3618992")
     * @return URL JSON API
     */
    public static String buildCategoryApiUrl(String categoryName, String paginatorToken) {
        if (categoryName == null || categoryName.isEmpty()) {
            return null;
        }
        
        // Строим относительный URL категории
        String categoryUrl = "/category/" + categoryName + "/";
        categoryUrl += "?has_points_from_reviews=t";
        categoryUrl += "&layout_page_index=2";
        
        if (paginatorToken != null && !paginatorToken.isEmpty()) {
            categoryUrl += "&paginator_token=" + paginatorToken;
        }
        
        try {
            // Кодируем URL для параметра url
            String encodedUrl = URLEncoder.encode(categoryUrl, StandardCharsets.UTF_8);
            
            // Строим URL JSON API
            return OZON_BASE + "/api/entrypoint-api.bx/page/json/v2?url=" + encodedUrl;
        } catch (Exception e) {
            return null;
        }
    }
}

