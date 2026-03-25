package org.example.parse;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.example.model.ProductRow;
import org.example.util.Numbers;
import org.example.util.UrlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.StringReader;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class OzonJsonParser {
    private static final Logger log = LoggerFactory.getLogger(OzonJsonParser.class);
    private static final Gson gson = new GsonBuilder()
        .setLenient()
        .create();

    /**
     * Парсит JSON ответ от Ozon и извлекает товары.
     * 
     * @param jsonText JSON текст
     * @return список товаров
     */
    public List<ProductRow> parseProducts(String jsonText) {
        List<ProductRow> products = new ArrayList<>();
        
        if (jsonText == null || jsonText.isEmpty()) {
            return products;
        }

        try {
            // Очищаем JSON от возможных лишних символов
            String cleanedJson = cleanJson(jsonText);
            
            JsonElement root = parseJson(cleanedJson);
            if (root == null) {
                return products;
            }
            
            if (!root.isJsonObject()) {
                log.warn("Корневой элемент JSON не является объектом");
                return products;
            }

            JsonObject rootObj = root.getAsJsonObject();
            
            // Ищем товары в различных возможных структурах JSON Ozon
            extractProductsFromObject(rootObj, products);
            
            log.debug("Извлечено {} товаров из JSON", products.size());
            
        } catch (Exception e) {
            log.error("Ошибка при парсинге JSON: {}", e.getMessage(), e);
        }

        return products;
    }

    /**
     * Извлекает URL для первой страницы с данными из начального JSON ответа.
     * Строит URL с layout_page_index=2 и paginator_token (без search_page_state и start_page_id).
     * 
     * @param jsonText JSON текст из начального запроса
     * @param baseCatalogUrl базовый URL каталога
     * @return URL JSON API для первой страницы с данными или null
     */
    public String extractFirstPageUrlWithToken(String jsonText, String baseCatalogUrl) {
        if (jsonText == null || jsonText.isEmpty()) {
            return null;
        }

        try {
            String cleanedJson = cleanJson(jsonText);
            JsonElement root = parseJson(cleanedJson);
            if (root == null || !root.isJsonObject()) {
                return null;
            }

            JsonObject rootObj = root.getAsJsonObject();
            
            // Извлекаем только paginator_token для первой страницы
            String paginatorToken = extractPaginatorToken(rootObj);
            
            log.info("Извлеченный paginator_token для первой страницы: {}", 
                paginatorToken != null ? paginatorToken.substring(0, Math.min(20, paginatorToken.length())) + "..." : "null");
            
            // Проверяем наличие paginator_token
            if (paginatorToken == null || paginatorToken.isEmpty()) {
                log.warn("paginator_token не найден в начальном JSON, первая страница недоступна");
                return null;
            }
            
            // Строим URL с layout_page_index=2 и paginator_token
            String firstPageUrl = buildFirstPageApiUrl(baseCatalogUrl, paginatorToken);
            if (firstPageUrl != null) {
                log.info("Построен URL для первой страницы: {}", firstPageUrl);
                return firstPageUrl;
            }
            
            return null;
        } catch (Exception e) {
            log.debug("Ошибка при извлечении URL первой страницы: {}", e.getMessage());
            return null;
        }
    }
    
    /**
     * Строит URL для первой страницы с layout_page_index=2 и paginator_token.
     */
    private String buildFirstPageApiUrl(String baseCatalogUrl, String paginatorToken) {
        try {
            // Извлекаем базовый URL категории
            String baseCategoryUrl = baseCatalogUrl;
            try {
                java.net.URI uri = new java.net.URI(baseCatalogUrl);
                String path = uri.getPath();
                String query = uri.getQuery();
                baseCategoryUrl = path;
                if (query != null && !query.isEmpty()) {
                    // Удаляем параметры пагинации из query, оставляем только фильтры
                    String[] params = query.split("&");
                    StringBuilder cleanQuery = new StringBuilder();
                    for (String param : params) {
                        if (!param.startsWith("layout_page_index=") && 
                            !param.startsWith("page=") && 
                            !param.startsWith("paginator_token=") &&
                            !param.startsWith("search_page_state=") &&
                            !param.startsWith("start_page_id=")) {
                            if (cleanQuery.length() > 0) {
                                cleanQuery.append("&");
                            }
                            cleanQuery.append(param);
                        }
                    }
                    if (cleanQuery.length() > 0) {
                        baseCategoryUrl += "?" + cleanQuery.toString();
                    }
                }
            } catch (Exception e) {
                // Игнорируем ошибки парсинга
            }
            
            // Строим URL категории с layout_page_index=2 и paginator_token
            StringBuilder categoryUrlWithParams = new StringBuilder(baseCategoryUrl);
            boolean hasParams = baseCategoryUrl.contains("?");
            
            categoryUrlWithParams.append(hasParams ? "&" : "?").append("layout_page_index=2");
            categoryUrlWithParams.append("&paginator_token=").append(paginatorToken);
            
            String categoryUrl = categoryUrlWithParams.toString();
            log.debug("Построен URL категории с параметрами: {}", categoryUrl);
            
            // Кодируем полный URL категории для параметра url
            String encodedUrl = URLEncoder.encode(categoryUrl, StandardCharsets.UTF_8.toString());
            
            // Строим финальный URL JSON API
            String baseUrl = "https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2";
            String finalUrl = baseUrl + "?url=" + encodedUrl;
            log.debug("Финальный JSON API URL: {}", finalUrl);
            
            return finalUrl;
            
        } catch (Exception e) {
            log.debug("Ошибка при построении URL первой страницы: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Извлекает текущий номер страницы (не следующий).
     */
    private Integer extractPageIndex(JsonObject obj) {
        String[] pageFields = {"layout_page_index", "layoutPageIndex", "page", "pageIndex", "page_index", "currentPage"};
        
        for (String field : pageFields) {
            if (obj.has(field)) {
                JsonElement elem = obj.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    return elem.getAsInt();
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    try {
                        return Integer.parseInt(elem.getAsString());
                    } catch (NumberFormatException e) {
                        // Игнорируем
                    }
                }
            }
        }
        
        // Рекурсивный поиск
        String[] searchPaths = {"pagination", "data", "widgetStates", "catalog", "state", "pageState"};
        for (String path : searchPaths) {
            if (obj.has(path)) {
                JsonElement elem = obj.get(path);
                if (elem.isJsonObject()) {
                    Integer found = extractPageIndex(elem.getAsJsonObject());
                    if (found != null) return found;
                }
            }
        }
        
        return null;
    }

    /**
     * Извлекает ссылку на следующую страницу из JSON.
     * Ищет nextPage, paginator_token, layout_page_index и другие параметры пагинации.
     * 
     * @param jsonText JSON текст
     * @param currentUrl текущий URL для построения следующей страницы
     * @return URL следующей страницы или null
     */
    public String extractNextPageUrl(String jsonText, String currentUrl) {
        if (jsonText == null || jsonText.isEmpty()) {
            return null;
        }

        try {
            // Очищаем JSON от возможных лишних символов
            String cleanedJson = cleanJson(jsonText);
            
            JsonElement root = parseJson(cleanedJson);
            if (root == null || !root.isJsonObject()) {
                return null;
            }

            JsonObject rootObj = root.getAsJsonObject();
            
            // Вариант 1: Ищем готовый URL следующей страницы в поле nextPage
            String nextUrl = findNextPageInObject(rootObj);
            if (nextUrl != null) {
                log.info("Найдена ссылка на следующую страницу в nextPage: {}", nextUrl);
                // Если nextPage - относительный URL, нормализуем его
                if (!nextUrl.startsWith("http")) {
                    nextUrl = UrlUtil.normalize(nextUrl);
                }
                // Если nextPage не содержит /api/, строим JSON API URL
                if (!nextUrl.contains("/api/entrypoint-api.bx")) {
                    // Пытаемся извлечь параметры из nextUrl и построить JSON API URL
                    String jsonApiUrl = buildJsonApiUrlFromCatalogUrl(nextUrl);
                    if (jsonApiUrl != null) {
                        return jsonApiUrl;
                    }
                }
                return nextUrl;
            }
            
            // Вариант 2: Ищем параметры пагинации и строим URL для entrypoint-api.bx
            String paginatorToken = extractPaginatorToken(rootObj);
            Integer nextPageIndex = extractNextPageIndex(rootObj);
            String searchPageState = extractSearchPageState(rootObj);
            String startPageId = extractStartPageId(rootObj);
            
            log.info("Извлеченные параметры пагинации: paginator_token={}, page_index={}, search_page_state={}, start_page_id={}", 
                paginatorToken != null ? paginatorToken.substring(0, Math.min(20, paginatorToken.length())) + "..." : "null",
                nextPageIndex, 
                searchPageState != null ? searchPageState.substring(0, Math.min(20, searchPageState.length())) + "..." : "null",
                startPageId != null ? startPageId.substring(0, Math.min(20, startPageId.length())) + "..." : "null");
            
            // Проверяем наличие ОБЯЗАТЕЛЬНЫХ параметров
            if (paginatorToken == null || paginatorToken.isEmpty()) {
                log.warn("paginator_token не найден, следующая страница недоступна");
                return null;
            }
            if (searchPageState == null || searchPageState.isEmpty()) {
                log.warn("search_page_state не найден, следующая страница недоступна");
                return null;
            }
            if (startPageId == null || startPageId.isEmpty()) {
                log.warn("start_page_id не найден, следующая страница недоступна");
                return null;
            }
            
            // Если все обязательные параметры есть, строим URL
            if (nextPageIndex == null) {
                log.warn("page_index не найден, используем значение по умолчанию: 2");
                nextPageIndex = 2;
            }
            
            String nextApiUrl = buildNextApiUrl(currentUrl, paginatorToken, nextPageIndex, searchPageState, startPageId);
            if (nextApiUrl != null) {
                log.info("Построен URL следующей страницы API: {}", nextApiUrl);
                return nextApiUrl;
            } else {
                log.warn("Не удалось построить URL следующей страницы");
                return null;
            }
            
        } catch (Exception e) {
            log.debug("Не удалось найти следующую страницу в JSON: {}", e.getMessage());
            return null;
        }
    }
    
    private String extractStartPageId(JsonObject obj) {
        return extractStartPageId(obj, 0);
    }
    
    private String extractStartPageId(JsonObject obj, int depth) {
        if (depth > 10) return null; // Защита от бесконечной рекурсии
        
        String[] idFields = {"start_page_id", "startPageId", "pageId", "id", "requestId", "request_id"};
        
        for (String field : idFields) {
            if (obj.has(field)) {
                JsonElement elem = obj.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    String value = elem.getAsString();
                    if (!value.isEmpty()) {
                        log.debug("Найден start_page_id в поле '{}': {}", field, value);
                        return value;
                    }
                }
            }
        }
        
        // Рекурсивный поиск во всех вложенных объектах
        for (String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonObject()) {
                String found = extractStartPageId(elem.getAsJsonObject(), depth + 1);
                if (found != null) return found;
            } else if (elem.isJsonArray()) {
                JsonArray array = elem.getAsJsonArray();
                for (JsonElement item : array) {
                    if (item.isJsonObject()) {
                        String found = extractStartPageId(item.getAsJsonObject(), depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Строит URL следующей страницы для entrypoint-api.bx на основе параметров.
     */
    private String buildNextApiUrl(String currentUrl, String paginatorToken, Integer nextPageIndex, String searchPageState, String startPageId) {
        try {
            // Если текущий URL уже является API URL, извлекаем параметр url и обновляем его
            if (currentUrl != null && currentUrl.contains("/api/entrypoint-api.bx/page/json/v2")) {
                // Парсим текущий URL и извлекаем параметр url
                java.net.URI uri = new java.net.URI(currentUrl);
                String query = uri.getQuery();
                
                String categoryUrl = null;
                if (query != null && !query.isEmpty()) {
                    for (String param : query.split("&")) {
                        if (param.startsWith("url=")) {
                            try {
                                categoryUrl = URLDecoder.decode(param.substring(4), StandardCharsets.UTF_8.toString());
                            } catch (Exception e) {
                                categoryUrl = param.substring(4);
                            }
                            break;
                        }
                    }
                }
                
                if (categoryUrl == null) {
                    categoryUrl = "/";
                }
                
                // Обновляем параметры в URL категории
                String baseCategoryUrl = categoryUrl;
                // Удаляем параметры пагинации из categoryUrl
                if (baseCategoryUrl.contains("?")) {
                    String[] parts = baseCategoryUrl.split("\\?", 2);
                    baseCategoryUrl = parts[0];
                    String oldQuery = parts[1];
                    String[] params = oldQuery.split("&");
                    StringBuilder cleanQuery = new StringBuilder();
                    for (String param : params) {
                        if (!param.startsWith("layout_page_index=") && 
                            !param.startsWith("page=") && 
                            !param.startsWith("paginator_token=") &&
                            !param.startsWith("search_page_state=") &&
                            !param.startsWith("start_page_id=")) {
                            if (cleanQuery.length() > 0) {
                                cleanQuery.append("&");
                            }
                            cleanQuery.append(param);
                        }
                    }
                    if (cleanQuery.length() > 0) {
                        baseCategoryUrl += "?" + cleanQuery.toString();
                    }
                }
                
                // Добавляем новые параметры пагинации
                StringBuilder categoryUrlWithParams = new StringBuilder(baseCategoryUrl);
                boolean hasParams = baseCategoryUrl.contains("?");
                
                if (nextPageIndex != null) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("layout_page_index=").append(nextPageIndex);
                    categoryUrlWithParams.append("&page=").append(nextPageIndex);
                    hasParams = true;
                }
                if (paginatorToken != null && !paginatorToken.isEmpty()) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("paginator_token=").append(paginatorToken);
                    hasParams = true;
                }
                if (searchPageState != null && !searchPageState.isEmpty()) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("search_page_state=").append(searchPageState);
                    hasParams = true;
                }
                if (startPageId != null && !startPageId.isEmpty()) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("start_page_id=").append(startPageId);
                }
                
                // Кодируем обновленный URL категории
                String encodedUrl = URLEncoder.encode(categoryUrlWithParams.toString(), StandardCharsets.UTF_8.toString());
                
                // Строим новый URL JSON API
                return uri.getScheme() + "://" + uri.getHost() + uri.getPath() + "?url=" + encodedUrl;
            } else {
                // Если текущий URL не API, строим новый API URL на основе текущего
                // Сначала извлекаем базовый URL категории из currentUrl
                String baseCategoryUrl = currentUrl;
                try {
                    java.net.URI uri = new java.net.URI(currentUrl);
                    String path = uri.getPath();
                    String query = uri.getQuery();
                    baseCategoryUrl = path;
                    if (query != null && !query.isEmpty()) {
                        // Удаляем параметры пагинации из query, оставляем только фильтры
                        String[] params = query.split("&");
                        StringBuilder cleanQuery = new StringBuilder();
                        for (String param : params) {
                            if (!param.startsWith("layout_page_index=") && 
                                !param.startsWith("page=") && 
                                !param.startsWith("paginator_token=") &&
                                !param.startsWith("search_page_state=") &&
                                !param.startsWith("start_page_id=")) {
                                if (cleanQuery.length() > 0) {
                                    cleanQuery.append("&");
                                }
                                cleanQuery.append(param);
                            }
                        }
                        if (cleanQuery.length() > 0) {
                            baseCategoryUrl += "?" + cleanQuery.toString();
                        }
                    }
                } catch (Exception e) {
                    // Игнорируем ошибки парсинга
                }
                
                // Строим URL категории с параметрами пагинации
                StringBuilder categoryUrlWithParams = new StringBuilder(baseCategoryUrl);
                boolean hasParams = baseCategoryUrl.contains("?");
                
                if (nextPageIndex != null) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("layout_page_index=").append(nextPageIndex);
                    categoryUrlWithParams.append("&page=").append(nextPageIndex);
                    hasParams = true;
                }
                if (paginatorToken != null && !paginatorToken.isEmpty()) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("paginator_token=").append(paginatorToken);
                    hasParams = true;
                }
                if (searchPageState != null && !searchPageState.isEmpty()) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("search_page_state=").append(searchPageState);
                    hasParams = true;
                }
                if (startPageId != null && !startPageId.isEmpty()) {
                    categoryUrlWithParams.append(hasParams ? "&" : "?").append("start_page_id=").append(startPageId);
                }
                
                // Кодируем полный URL категории для параметра url
                String encodedUrl = java.net.URLEncoder.encode(categoryUrlWithParams.toString(), "UTF-8");
                
                // Строим финальный URL JSON API
                String baseUrl = "https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2";
                return baseUrl + "?url=" + encodedUrl;
            }
        } catch (Exception e) {
            log.debug("Ошибка при построении URL следующей страницы: {}", e.getMessage());
            return null;
        }
    }
    
    private String extractSearchPageState(JsonObject obj) {
        return extractSearchPageState(obj, 0);
    }
    
    private String extractSearchPageState(JsonObject obj, int depth) {
        if (depth > 10) return null; // Защита от бесконечной рекурсии
        
        String[] stateFields = {"search_page_state", "searchPageState", "pageState", "state", "searchState"};
        
        for (String field : stateFields) {
            if (obj.has(field)) {
                JsonElement elem = obj.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    String value = elem.getAsString();
                    if (!value.isEmpty()) {
                        log.debug("Найден search_page_state в поле '{}': {}", field, value.substring(0, Math.min(50, value.length())));
                        return value;
                    }
                }
            }
        }
        
        // Рекурсивный поиск во всех вложенных объектах
        for (String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonObject()) {
                String found = extractSearchPageState(elem.getAsJsonObject(), depth + 1);
                if (found != null) return found;
            } else if (elem.isJsonArray()) {
                JsonArray array = elem.getAsJsonArray();
                for (JsonElement item : array) {
                    if (item.isJsonObject()) {
                        String found = extractSearchPageState(item.getAsJsonObject(), depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }
        
        return null;
    }

    /**
     * Очищает JSON текст от возможных лишних символов и оберток.
     */
    private String cleanJson(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            return jsonText;
        }
        
        String cleaned = jsonText.trim();
        
        // Удаляем возможные префиксы типа "while(1);" или "for(;;);"
        if (cleaned.startsWith("while(1);") || cleaned.startsWith("for(;;);")) {
            int startIdx = cleaned.indexOf('{');
            if (startIdx > 0) {
                cleaned = cleaned.substring(startIdx);
            }
        }
        
        // Удаляем возможные суффиксы
        int lastBrace = cleaned.lastIndexOf('}');
        if (lastBrace > 0 && lastBrace < cleaned.length() - 1) {
            cleaned = cleaned.substring(0, lastBrace + 1);
        }
        
        return cleaned;
    }

    /**
     * Парсит JSON с использованием lenient режима.
     */
    private JsonElement parseJson(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            return null;
        }
        
        try {
            // Пробуем стандартный парсер
            return JsonParser.parseString(jsonText);
        } catch (Exception e) {
            log.debug("Стандартный парсинг не удался, пробуем lenient режим: {}", e.getMessage());
            try {
                // Используем JsonReader с lenient режимом
                com.google.gson.stream.JsonReader reader = new com.google.gson.stream.JsonReader(new StringReader(jsonText));
                reader.setLenient(true);
                return gson.fromJson(reader, JsonElement.class);
            } catch (Exception e2) {
                log.error("Не удалось распарсить JSON даже в lenient режиме: {}", e2.getMessage());
                return null;
            }
        }
    }

    private String extractPaginatorToken(JsonObject obj) {
        return extractPaginatorToken(obj, 0);
    }
    
    private String extractPaginatorToken(JsonObject obj, int depth) {
        if (depth > 10) return null; // Защита от бесконечной рекурсии
        
        String[] tokenFields = {"paginator_token", "paginatorToken", "token", "nextToken", "next_token", "pageToken"};
        
        for (String field : tokenFields) {
            if (obj.has(field)) {
                JsonElement elem = obj.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    String value = elem.getAsString();
                    if (!value.isEmpty()) {
                        log.debug("Найден paginator_token в поле '{}': {}", field, value.substring(0, Math.min(50, value.length())));
                        return value;
                    }
                }
            }
        }
        
        // Рекурсивный поиск во всех вложенных объектах
        for (String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonObject()) {
                String found = extractPaginatorToken(elem.getAsJsonObject(), depth + 1);
                if (found != null) return found;
            } else if (elem.isJsonArray()) {
                JsonArray array = elem.getAsJsonArray();
                for (JsonElement item : array) {
                    if (item.isJsonObject()) {
                        String found = extractPaginatorToken(item.getAsJsonObject(), depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }
        
        return null;
    }

    private Integer extractNextPageIndex(JsonObject obj) {
        String[] pageFields = {"layout_page_index", "layoutPageIndex", "page", "pageIndex", "page_index", "currentPage"};
        
        for (String field : pageFields) {
            if (obj.has(field)) {
                JsonElement elem = obj.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    int currentPage = elem.getAsInt();
                    int nextPage = currentPage + 1;
                    log.debug("Найден номер страницы в поле '{}': текущая={}, следующая={}", field, currentPage, nextPage);
                    return nextPage; // Следующая страница
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    try {
                        int currentPage = Integer.parseInt(elem.getAsString());
                        int nextPage = currentPage + 1;
                        log.debug("Найден номер страницы в поле '{}' (строка): текущая={}, следующая={}", field, currentPage, nextPage);
                        return nextPage;
                    } catch (NumberFormatException e) {
                        // Игнорируем
                    }
                }
            }
        }
        
        // Рекурсивный поиск в различных структурах
        String[] searchPaths = {"pagination", "data", "widgetStates", "catalog", "state", "pageState"};
        for (String path : searchPaths) {
            if (obj.has(path)) {
                JsonElement elem = obj.get(path);
                if (elem.isJsonObject()) {
                    Integer found = extractNextPageIndex(elem.getAsJsonObject());
                    if (found != null) return found;
                }
            }
        }
        
        // Если не нашли текущую страницу, возвращаем 2 (вторая страница)
        log.debug("Номер страницы не найден, используем значение по умолчанию: 2");
        return 2;
    }

    private void extractProductsFromObject(JsonObject obj, List<ProductRow> products) {
        // Вариант 1: widgetStates -> catalog -> items
        if (obj.has("widgetStates")) {
            JsonObject widgetStates = obj.getAsJsonObject("widgetStates");
            if (widgetStates.has("catalog")) {
                JsonObject catalog = widgetStates.getAsJsonObject("catalog");
                if (catalog.has("items")) {
                    extractFromItemsArray(catalog.getAsJsonArray("items"), products);
                }
            }
        }

        // Вариант 2: catalog -> items
        if (obj.has("catalog")) {
            JsonObject catalog = obj.getAsJsonObject("catalog");
            if (catalog.has("items")) {
                extractFromItemsArray(catalog.getAsJsonArray("items"), products);
            }
        }

        // Вариант 3: items напрямую
        if (obj.has("items")) {
            extractFromItemsArray(obj.getAsJsonArray("items"), products);
        }

        // Вариант 4: data -> catalog -> items
        if (obj.has("data")) {
            JsonElement data = obj.get("data");
            if (data.isJsonObject()) {
                extractProductsFromObject(data.getAsJsonObject(), products);
            }
        }

        // Вариант 5: results -> items
        if (obj.has("results")) {
            JsonElement results = obj.get("results");
            if (results.isJsonArray()) {
                extractFromItemsArray(results.getAsJsonArray(), products);
            } else if (results.isJsonObject()) {
                JsonObject resultsObj = results.getAsJsonObject();
                if (resultsObj.has("items")) {
                    extractFromItemsArray(resultsObj.getAsJsonArray("items"), products);
                }
            }
        }
    }

    private void extractFromItemsArray(JsonArray items, List<ProductRow> products) {
        if (items == null) {
            return;
        }

        for (JsonElement item : items) {
            if (!item.isJsonObject()) {
                continue;
            }

            JsonObject itemObj = item.getAsJsonObject();
            Optional<ProductRow> product = parseProductItem(itemObj);
            product.ifPresent(products::add);
        }
    }

    private Optional<ProductRow> parseProductItem(JsonObject item) {
        try {
            // Извлекаем URL
            String url = extractUrl(item);
            if (url == null || url.isEmpty()) {
                return Optional.empty();
            }
            url = UrlUtil.normalizeProductUrl(url);

            // Извлекаем цену
            int price = extractPrice(item);
            if (price <= 0) {
                log.debug("Пропущен товар без цены: {}", url);
                return Optional.empty();
            }

            // Извлекаем баллы
            int points = extractPoints(item);
            if (points <= 0) {
                log.debug("Пропущен товар без баллов: {}", url);
                return Optional.empty();
            }

            // Вычисляем процент
            double percent = Math.round((points * 100.0 / price) * 100.0) / 100.0;

            return Optional.of(new ProductRow("", url, price, points, percent));

        } catch (Exception e) {
            log.debug("Ошибка при парсинге товара: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String extractUrl(JsonObject item) {
        // Варианты полей для URL
        String[] urlFields = {"url", "link", "href", "webUrl", "web_url", "productUrl", "product_url"};
        
        for (String field : urlFields) {
            if (item.has(field)) {
                JsonElement elem = item.get(field);
                if (elem.isJsonPrimitive()) {
                    return elem.getAsString();
                }
            }
        }

        // Если есть id, можно построить URL
        if (item.has("id")) {
            JsonElement idElem = item.get("id");
            if (idElem.isJsonPrimitive()) {
                String id = idElem.getAsString();
                return "/product/" + id + "/";
            }
        }

        return null;
    }

    private int extractPrice(JsonObject item) {
        // Варианты полей для цены
        String[] priceFields = {"price", "finalPrice", "final_price", "priceWithDiscount", 
            "price_with_discount", "currentPrice", "current_price", "priceInfo", "price_info"};

        for (String field : priceFields) {
            if (item.has(field)) {
                JsonElement elem = item.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    return elem.getAsInt();
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    return Numbers.extractInt(elem.getAsString());
                } else if (elem.isJsonObject()) {
                    // Если price это объект, ищем в нем
                    JsonObject priceObj = elem.getAsJsonObject();
                    if (priceObj.has("value")) {
                        JsonElement value = priceObj.get("value");
                        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                            return value.getAsInt();
                        }
                    }
                }
            }
        }

        return 0;
    }

    private int extractPoints(JsonObject item) {
        // Варианты полей для баллов
        String[] pointsFields = {"points", "reviewPoints", "review_points", "bonusPoints", 
            "bonus_points", "cashback", "cashbackPoints", "cashback_points"};

        for (String field : pointsFields) {
            if (item.has(field)) {
                JsonElement elem = item.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isNumber()) {
                    return elem.getAsInt();
                } else if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    return Numbers.extractInt(elem.getAsString());
                }
            }
        }

        // Ищем в nested объектах
        if (item.has("bonusInfo")) {
            JsonElement bonusInfo = item.get("bonusInfo");
            if (bonusInfo.isJsonObject()) {
                return extractPoints(bonusInfo.getAsJsonObject());
            }
        }

        return 0;
    }

    private String findNextPageInObject(JsonObject obj) {
        return findNextPageInObject(obj, 0);
    }
    
    private String findNextPageInObject(JsonObject obj, int depth) {
        if (depth > 10) return null; // Защита от бесконечной рекурсии
        
        // Приоритет: ищем nextPage (именно это поле содержит готовый URL)
        if (obj.has("nextPage")) {
            JsonElement elem = obj.get("nextPage");
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                String url = elem.getAsString();
                if (url != null && !url.isEmpty()) {
                    log.debug("Найден nextPage: {}", url);
                    return url;
                }
            }
        }
        
        // Ищем другие варианты полей
        String[] nextPageFields = {"next_page", "nextPageUrl", "next_page_url", "next"};
        for (String field : nextPageFields) {
            if (obj.has(field)) {
                JsonElement elem = obj.get(field);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    String url = elem.getAsString();
                    if (url != null && !url.isEmpty()) {
                        log.debug("Найден {}: {}", field, url);
                        return url;
                    }
                } else if (elem.isJsonObject()) {
                    JsonObject pagObj = elem.getAsJsonObject();
                    if (pagObj.has("url") || pagObj.has("nextUrl") || pagObj.has("next_url")) {
                        JsonElement urlElem = pagObj.has("url") ? pagObj.get("url") 
                            : (pagObj.has("nextUrl") ? pagObj.get("nextUrl") : pagObj.get("next_url"));
                        if (urlElem.isJsonPrimitive()) {
                            String url = urlElem.getAsString();
                            log.debug("Найден URL в объекте {}: {}", field, url);
                            return url;
                        }
                    }
                }
            }
        }

        // Рекурсивный поиск во всех вложенных объектах
        for (String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonObject()) {
                String found = findNextPageInObject(elem.getAsJsonObject(), depth + 1);
                if (found != null) return found;
            } else if (elem.isJsonArray()) {
                JsonArray array = elem.getAsJsonArray();
                for (JsonElement item : array) {
                    if (item.isJsonObject()) {
                        String found = findNextPageInObject(item.getAsJsonObject(), depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }

        return null;
    }
    
    /**
     * Строит JSON API URL на основе URL каталога (если nextPage содержит URL каталога).
     */
    private String buildJsonApiUrlFromCatalogUrl(String catalogUrl) {
        try {
            // Если это уже JSON API URL, возвращаем как есть
            if (catalogUrl.contains("/api/entrypoint-api.bx")) {
                return catalogUrl;
            }
            
            // Иначе строим JSON API URL
            return org.example.util.UrlUtil.buildJsonApiUrl(catalogUrl);
        } catch (Exception e) {
            log.debug("Ошибка при построении JSON API URL из каталога: {}", e.getMessage());
            return null;
        }
    }
}

