package org.example.parse;

import org.example.model.ProductRow;
import org.example.util.Numbers;
import org.example.util.UrlUtil;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class OzonHtmlParser {
    private static final Logger log = LoggerFactory.getLogger(OzonHtmlParser.class);
    
    private static final Pattern JSON_SCRIPT_PATTERN = Pattern.compile(
        "<script[^>]*>\\s*window\\.__INITIAL_STATE__\\s*=\\s*(\\{.*?\\});", 
        Pattern.DOTALL
    );

    /**
     * Извлекает JSON из HTML страницы Ozon.
     * Ищет window.__INITIAL_STATE__, ссылки на JSON файлы или другие JSON структуры.
     * 
     * @param html HTML содержимое страницы
     * @return JSON строка или null
     */
    public String extractJsonFromHtml(String html) {
        if (html == null || html.isEmpty()) {
            return null;
        }

        // Вариант 1: window.__INITIAL_STATE__
        Matcher matcher = JSON_SCRIPT_PATTERN.matcher(html);
        if (matcher.find()) {
            String json = matcher.group(1);
            log.debug("Найден JSON в window.__INITIAL_STATE__");
            return json;
        }

        // Вариант 2: Ищем JSON в script тегах
        try {
            Document doc = Jsoup.parse(html);
            Elements scripts = doc.select("script");
            
            for (Element script : scripts) {
                String scriptText = script.html();
                if (scriptText.contains("widgetStates") || scriptText.contains("catalog") 
                    || scriptText.contains("layout_page_index") || scriptText.contains("paginator_token")) {
                    // Пытаемся извлечь JSON объект
                    int startIdx = scriptText.indexOf('{');
                    int endIdx = scriptText.lastIndexOf('}');
                    if (startIdx >= 0 && endIdx > startIdx) {
                        String json = scriptText.substring(startIdx, endIdx + 1);
                        log.debug("Найден JSON в script теге");
                        return json;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Ошибка при парсинге HTML для извлечения JSON: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Извлекает ссылки на JSON файлы из HTML, особенно /api/entrypoint-api.bx/page/json/v2.
     * 
     * @param html HTML содержимое страницы
     * @param baseUrl базовый URL для построения абсолютных ссылок
     * @return список URL JSON файлов (приоритет у entrypoint-api.bx)
     */
    public List<String> extractJsonUrlsFromHtml(String html, String baseUrl) {
        List<String> jsonUrls = new ArrayList<>();
        List<String> priorityUrls = new ArrayList<>(); // Приоритетные URL (entrypoint-api.bx)
        
        if (html == null || html.isEmpty()) {
            return jsonUrls;
        }

        try {
            // 1. Приоритетный поиск: /api/entrypoint-api.bx/page/json/v2
            Pattern entrypointPattern = Pattern.compile(
                "(https?://[^\"'\\s]*ozon\\.ru/api/entrypoint-api\\.bx/page/json/v2[^\"'\\s]*)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher entrypointMatcher = entrypointPattern.matcher(html);
            while (entrypointMatcher.find()) {
                String foundUrl = entrypointMatcher.group(1);
                String normalized = UrlUtil.normalize(foundUrl);
                if (!priorityUrls.contains(normalized)) {
                    priorityUrls.add(normalized);
                }
            }
            
            // 2. Ищем ссылки на API Ozon (/api/entrypoint-api.bx/page/json/)
            Pattern apiUrlPattern = Pattern.compile(
                "(https?://[^\"'\\s]*ozon\\.ru/api/[^\"'\\s]*(?:entrypoint-api|page/json)[^\"'\\s]*)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher apiMatcher = apiUrlPattern.matcher(html);
            while (apiMatcher.find()) {
                String foundUrl = apiMatcher.group(1);
                String normalized = UrlUtil.normalize(foundUrl);
                if (!priorityUrls.contains(normalized) && !jsonUrls.contains(normalized)) {
                    jsonUrls.add(normalized);
                }
            }
            
            // 3. Ищем в JavaScript переменных и строках
            Pattern jsUrlPattern = Pattern.compile(
                "['\"](https?://[^'\"]*ozon\\.ru/api/entrypoint-api[^'\"]*)['\"]",
                Pattern.CASE_INSENSITIVE
            );
            Matcher jsMatcher = jsUrlPattern.matcher(html);
            while (jsMatcher.find()) {
                String foundUrl = jsMatcher.group(1);
                String normalized = UrlUtil.normalize(foundUrl);
                if (!priorityUrls.contains(normalized) && !jsonUrls.contains(normalized)) {
                    priorityUrls.add(normalized);
                }
            }
            
            // 4. Ищем в data-атрибутах
            Document doc = Jsoup.parse(html);
            Elements elementsWithData = doc.select("[data-json-url], [data-api-url], [data-url]");
            for (Element elem : elementsWithData) {
                String jsonUrl = elem.attr("data-json-url");
                if (jsonUrl.isEmpty()) {
                    jsonUrl = elem.attr("data-api-url");
                }
                if (jsonUrl.isEmpty()) {
                    jsonUrl = elem.attr("data-url");
                }
                if (!jsonUrl.isEmpty() && jsonUrl.contains("/api/")) {
                    String normalized = UrlUtil.normalize(jsonUrl);
                    if (normalized.contains("entrypoint-api")) {
                        if (!priorityUrls.contains(normalized)) {
                            priorityUrls.add(normalized);
                        }
                    } else if (!jsonUrls.contains(normalized)) {
                        jsonUrls.add(normalized);
                    }
                }
            }
            
            // 5. Общий поиск URL с параметрами url= и paginator_token
            Pattern generalUrlPattern = Pattern.compile(
                "(https?://[^\"'\\s]*ozon\\.ru/api/[^\"'\\s]*(?:url=|paginator_token|layout_page_index)[^\"'\\s]*)",
                Pattern.CASE_INSENSITIVE
            );
            Matcher generalMatcher = generalUrlPattern.matcher(html);
            while (generalMatcher.find()) {
                String foundUrl = generalMatcher.group(1);
                String normalized = UrlUtil.normalize(foundUrl);
                if (!priorityUrls.contains(normalized) && !jsonUrls.contains(normalized)) {
                    jsonUrls.add(normalized);
                }
            }
            
            // Объединяем: сначала приоритетные, потом остальные
            List<String> result = new ArrayList<>(priorityUrls);
            result.addAll(jsonUrls);
            
            log.debug("Найдено {} ссылок на JSON файлы в HTML ({} приоритетных)", 
                result.size(), priorityUrls.size());
            
        } catch (Exception e) {
            log.debug("Ошибка при поиске JSON URL в HTML: {}", e.getMessage());
        }

        return jsonUrls.isEmpty() && priorityUrls.isEmpty() ? new ArrayList<>() : 
            (priorityUrls.isEmpty() ? jsonUrls : priorityUrls);
    }

    /**
     * Парсит HTML страницу и извлекает товары (fallback метод).
     * 
     * @param html HTML содержимое
     * @return список товаров
     */
    public List<ProductRow> parseProductsFromHtml(String html) {
        List<ProductRow> products = new ArrayList<>();
        
        if (html == null || html.isEmpty()) {
            return products;
        }

        try {
            Document doc = Jsoup.parse(html);
            
            // Ищем карточки товаров по различным селекторам
            Elements tiles = doc.select("a[href*='/product/'], div[data-widget*='searchResultsV2'] a, "
                + "div[data-widget*='searchResults'] a, .tile-root a");
            
            log.debug("Найдено {} потенциальных карточек товаров в HTML", tiles.size());
            
            for (Element tile : tiles) {
                Optional<ProductRow> product = parseProductTile(tile);
                product.ifPresent(products::add);
            }
            
        } catch (Exception e) {
            log.error("Ошибка при парсинге HTML: {}", e.getMessage(), e);
        }

        return products;
    }

    private Optional<ProductRow> parseProductTile(Element tile) {
        try {
            // Извлекаем URL
            String href = tile.attr("href");
            if (href == null || href.isEmpty()) {
                return Optional.empty();
            }
            String url = UrlUtil.normalize(href);

            // Ищем цену в родительских элементах
            Element container = tile.parent();
            while (container != null && !container.hasClass("tile-root") && container.parent() != null) {
                container = container.parent();
            }
            
            if (container == null) {
                container = tile;
            }

            // Извлекаем цену
            int price = 0;
            Elements priceElements = container.select("[class*='price'], [class*='Price'], "
                + "[data-test-id*='price'], .tile-price, .price");
            for (Element priceElem : priceElements) {
                String priceText = priceElem.text();
                price = Numbers.extractInt(priceText);
                if (price > 0) break;
            }

            if (price <= 0) {
                return Optional.empty();
            }

            // Извлекаем баллы
            int points = 0;
            Elements pointsElements = container.select("[class*='point'], [class*='Point'], "
                + "[class*='bonus'], [class*='Bonus'], [class*='cashback']");
            for (Element pointsElem : pointsElements) {
                String pointsText = pointsElem.text();
                points = Numbers.extractInt(pointsText);
                if (points > 0) break;
            }

            if (points <= 0) {
                return Optional.empty();
            }

            // Вычисляем процент
            double percent = Math.round((points * 100.0 / price) * 100.0) / 100.0;

            return Optional.of(new ProductRow("", url, price, points, percent));

        } catch (Exception e) {
            log.debug("Ошибка при парсинге карточки товара: {}", e.getMessage());
            return Optional.empty();
        }
    }
}

