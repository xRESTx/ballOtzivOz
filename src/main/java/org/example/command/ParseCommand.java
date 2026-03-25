package org.example.command;

import org.example.http.SimpleHttpClient;
import org.example.model.ProductRow;
import org.example.parse.CategoryJsonParser;
import org.example.parse.OzonHtmlParser;
import org.example.parse.OzonJsonParser;
import org.example.util.FilesOut;
import org.example.util.UrlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@CommandLine.Command(
    name = "parse",
    description = "Парсит Ozon и сохраняет товары с баллами за отзывы",
    mixinStandardHelpOptions = true
)
public class ParseCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ParseCommand.class);
    private static final String DEFAULT_SOURCE_URL =
        "https://www.ozon.ru/highlight/bally-za-otzyv-1171518";

    @CommandLine.Parameters(
        arity = "0..1",
        description = "Ссылка Ozon для парсинга (по умолчанию: " + DEFAULT_SOURCE_URL + ")",
        defaultValue = DEFAULT_SOURCE_URL
    )
    private String sourceUrl;

    @CommandLine.Option(
        names = {"--max-pages"},
        description = "Максимальное количество страниц для обработки (0 = без ограничений)",
        defaultValue = "0"
    )
    private int maxPages;

    @CommandLine.Option(
        names = {"--rps"},
        description = "Максимальное количество запросов в секунду",
        defaultValue = "5"
    )
    private int rps;

    @CommandLine.Option(
        names = {"--min-percent"},
        description = "Минимальный процент (баллы/цена*100) для фильтрации товаров",
        defaultValue = "0.0"
    )
    private double minPercent;

    @CommandLine.Option(
        names = {"--timeout-ms"},
        description = "Таймаут для HTTP запросов в миллисекундах",
        defaultValue = "30000"
    )
    private int timeoutMs;

    @CommandLine.Option(
        names = {"--output"},
        description = "Путь к выходному TSV файлу (по умолчанию: products.tsv)",
        defaultValue = "products.tsv"
    )
    private String outputPath;

    @CommandLine.Option(
        names = {"--verbose", "-v"},
        description = "Включить подробное логирование (DEBUG уровень)"
    )
    private boolean verbose;

    @CommandLine.Option(
        names = {"--quiet", "-q"},
        description = "Только ошибки (ERROR уровень)"
    )
    private boolean quiet;

    @CommandLine.Option(
        names = {"--cookie-file"},
        description = "Путь к файлу с cookies (по умолчанию: cookie.txt)",
        defaultValue = "cookie.txt"
    )
    private String cookieFilePath;

    @Override
    public void run() {
        setupLogging();
        logStartup();

        if (sourceUrl == null || sourceUrl.isBlank()) {
            log.error("Не указана ссылка для парсинга");
            return;
        }

        SimpleHttpClient httpClient = new SimpleHttpClient(timeoutMs, rps, cookieFilePath);
        httpClient.initializeCookies();

        CategoryJsonParser jsonParser = new CategoryJsonParser();
        OzonHtmlParser htmlParser = new OzonHtmlParser();
        OzonJsonParser ozonJsonParser = new OzonJsonParser();
        FilesOut filesOut = new FilesOut(Paths.get(outputPath));

        Set<ProductRow> allProducts = new LinkedHashSet<>();
        Set<String> visitedPageUrls = new LinkedHashSet<>();
        long totalRequests = 0;
        int processedPages = 0;
        String currentUrl = sourceUrl;

        while (currentUrl != null && (maxPages == 0 || processedPages < maxPages)) {
            if (!visitedPageUrls.add(currentUrl)) {
                log.warn("URL уже обработан, останавливаемся чтобы избежать цикла: {}", currentUrl);
                break;
            }

            processedPages++;
            log.info("=== Обработка страницы {}: {} ===", processedPages, currentUrl);

            String responseText = httpClient.fetchString(currentUrl);
            totalRequests++;

            if (responseText == null || responseText.isEmpty()) {
                log.warn("Не удалось загрузить ответ из: {}", currentUrl);
                break;
            }

            log.info("Ответ успешно загружен ({} символов)", responseText.length());

            List<ProductRow> pageProducts = extractProducts(responseText, jsonParser, htmlParser);
            int keptProducts = collectProducts(pageProducts, allProducts);

            log.info("Found tiles={}, parsed items={}, kept={}",
                pageProducts.size(), pageProducts.size(), keptProducts);

            String nextPageUrl = extractNextPageUrl(responseText, currentUrl, jsonParser, htmlParser, ozonJsonParser);
            if (nextPageUrl != null) {
                log.info("Найден URL следующей страницы: {}", nextPageUrl);
                currentUrl = nextPageUrl;
            } else {
                log.info("Следующая страница не найдена, завершаем парсинг");
                currentUrl = null;
            }
        }

        int writtenProducts = filesOut.writeProducts(allProducts);
        int writtenLinks = filesOut.writeLinks(allProducts);

        logSummary(processedPages, allProducts.size(), writtenProducts, writtenLinks, totalRequests);
    }

    private List<ProductRow> extractProducts(String responseText,
                                             CategoryJsonParser jsonParser,
                                             OzonHtmlParser htmlParser) {
        List<ProductRow> products = jsonParser.parseProducts(responseText);
        if (!products.isEmpty()) {
            return products;
        }

        String embeddedJson = htmlParser.extractJsonFromHtml(responseText);
        if (embeddedJson != null) {
            products = jsonParser.parseProducts(embeddedJson);
            if (!products.isEmpty()) {
                return products;
            }
        }

        return htmlParser.parseProductsFromHtml(responseText);
    }

    private int collectProducts(List<ProductRow> pageProducts, Set<ProductRow> allProducts) {
        int keptProducts = 0;
        for (ProductRow product : pageProducts) {
            if (product.getPercent() >= minPercent && allProducts.add(product)) {
                keptProducts++;
            }
        }
        return keptProducts;
    }

    private String extractNextPageUrl(String responseText, String currentUrl,
                                      CategoryJsonParser jsonParser,
                                      OzonHtmlParser htmlParser,
                                      OzonJsonParser ozonJsonParser) {
        String nextPageUrl = jsonParser.extractNextPage(responseText);
        if (nextPageUrl != null && !nextPageUrl.equals(currentUrl)) {
            return normalizeNextPageUrl(nextPageUrl);
        }

        String embeddedJson = htmlParser.extractJsonFromHtml(responseText);
        if (embeddedJson != null) {
            nextPageUrl = jsonParser.extractNextPage(embeddedJson);
            if (nextPageUrl != null && !nextPageUrl.equals(currentUrl)) {
                return normalizeNextPageUrl(nextPageUrl);
            }
        }

        List<String> jsonUrls = htmlParser.extractJsonUrlsFromHtml(responseText, currentUrl);
        for (String jsonUrl : jsonUrls) {
            if (!jsonUrl.equals(currentUrl)) {
                return normalizeNextPageUrl(jsonUrl);
            }
        }

        String apiNextPageUrl = ozonJsonParser.extractNextPageUrl(responseText, currentUrl);
        if (apiNextPageUrl != null && !apiNextPageUrl.equals(currentUrl)) {
            return normalizeNextPageUrl(apiNextPageUrl);
        }

        if (!UrlUtil.isApiUrl(currentUrl)) {
            String apiUrl = UrlUtil.buildJsonApiUrl(currentUrl);
            if (apiUrl != null && !apiUrl.equals(currentUrl)) {
                return apiUrl;
            }
        }

        return null;
    }

    private String normalizeNextPageUrl(String nextPageUrl) {
        if (nextPageUrl == null || nextPageUrl.isBlank()) {
            return null;
        }
        if (UrlUtil.isApiUrl(nextPageUrl)) {
            return nextPageUrl;
        }

        String apiUrl = UrlUtil.buildJsonApiUrl(nextPageUrl);
        return apiUrl != null ? apiUrl : nextPageUrl;
    }

    private void setupLogging() {
        if (verbose) {
            ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.DEBUG);
        } else if (quiet) {
            ch.qos.logback.classic.Logger rootLogger =
                (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
            rootLogger.setLevel(ch.qos.logback.classic.Level.ERROR);
        }
    }

    private void logStartup() {
        log.info("=== Запуск парсера Ozon ===");
        log.info("Source URL: {}", sourceUrl);
        log.info("Max pages: {}", maxPages == 0 ? "без ограничений" : maxPages);
        log.info("RPS: {}", rps);
        log.info("Min percent: {}", minPercent);
        log.info("Timeout: {} ms", timeoutMs);
        log.info("Output: {}", outputPath);
        log.info("Cookie file: {}", cookieFilePath);
        log.info("Verbose: {}", verbose);
        log.info("Quiet: {}", quiet);
        log.info("==========================");
    }

    private void logSummary(int processedPages, int uniqueProducts,
                            int writtenProducts, int writtenLinks, long requestCount) {
        log.info("=== Итоговая сводка ===");
        log.info("Страниц обработано: {}", processedPages);
        log.info("Уникальных товаров: {}", uniqueProducts);
        log.info("Строк записано в файл: {}", writtenProducts);
        log.info("Уникальных ссылок: {}", writtenLinks);
        log.info("HTTP запросов выполнено: {}", requestCount);
        log.info("=======================");
    }
}
