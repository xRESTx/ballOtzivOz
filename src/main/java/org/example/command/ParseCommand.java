package org.example.command;

import org.example.http.SimpleHttpClient;
import org.example.model.ProductRow;
import org.example.parse.CategoryJsonParser;
import org.example.util.FilesOut;
import org.example.util.UrlUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

import java.nio.file.Paths;
import java.util.*;

@CommandLine.Command(
    name = "parse",
    description = "Парсит публичный каталог Ozon и сохраняет товары с баллами за отзывы",
    mixinStandardHelpOptions = true
)
public class ParseCommand implements Runnable {
    private static final Logger log = LoggerFactory.getLogger(ParseCommand.class);

    @CommandLine.Parameters(
        description = "Названия категорий для парсинга (например: elektronika-15500, kompyutery-15501)"
    )
    private List<String> categories;

    @CommandLine.Option(
        names = {"--max-pages"},
        description = "Максимальное количество страниц для обработки (0 = без ограничений)",
        defaultValue = "0"
    )
    private int maxPages;

    @CommandLine.Option(
        names = {"--rps"},
        description = "Максимальное количество запросов в секунду",
        defaultValue = "100"
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
        names = {"--paginator-token"},
        description = "Начальный paginator_token для первой страницы (по умолчанию: 3618992)",
        defaultValue = "3618992"
    )
    private String initialPaginatorToken;

    @CommandLine.Option(
        names = {"--output"},
        description = "Путь к выходному файлу (по умолчанию: links.txt)",
        defaultValue = "links.txt"
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
        // Настройка уровня логирования
        setupLogging();

        // Вывод параметров при старте
        logStartup();

        if (categories == null || categories.isEmpty()) {
            log.error("Не указаны категории для парсинга");
            return;
        }

        // Инициализация компонентов
        SimpleHttpClient httpClient = new SimpleHttpClient(timeoutMs, rps, cookieFilePath);
        
        // Инициализируем cookies, делая запрос к главной странице Ozon
        httpClient.initializeCookies();
        
        CategoryJsonParser jsonParser = new CategoryJsonParser();
        FilesOut filesOut = new FilesOut(Paths.get(outputPath));

        // Общий набор всех товаров
        Set<ProductRow> allProducts = new LinkedHashSet<>();
        long totalRequests = 0;

        // Парсим каждую категорию
        for (String category : categories) {
            log.info("=== Обработка категории: {} ===", category);
            
            int categoryPages = 0;
            int categoryProducts = 0;
            
            // Строим URL первой страницы категории
            String currentUrl = UrlUtil.buildCategoryApiUrl(category, initialPaginatorToken);
            if (currentUrl == null) {
                log.error("Не удалось построить URL для категории: {}", category);
                continue;
            }

            log.info("Начальный URL категории: {}", currentUrl);

            // Цикл пагинации по категории
            while (currentUrl != null && (maxPages == 0 || categoryPages < maxPages)) {
                categoryPages++;
                log.info("Обработка страницы {} категории {}: {}", categoryPages, category, currentUrl);

                // Загружаем JSON
                String jsonText = httpClient.fetchString(currentUrl);
                totalRequests++;

                if (jsonText == null || jsonText.isEmpty()) {
                    log.warn("Не удалось загрузить JSON из: {}", currentUrl);
                    break;
                }

                log.info("JSON успешно загружен ({} символов)", jsonText.length());

                // Извлекаем товары
                List<ProductRow> pageProducts = jsonParser.parseProducts(jsonText);
                log.info("Извлечено {} товаров из JSON", pageProducts.size());

                // Фильтруем по minPercent
                List<ProductRow> filteredProducts = new ArrayList<>();
                for (ProductRow product : pageProducts) {
                    if (product.getPercent() >= minPercent) {
                        filteredProducts.add(product);
                    }
                }

                categoryProducts += filteredProducts.size();
                allProducts.addAll(filteredProducts);

                log.info("Found tiles={}, parsed items={}, kept={}", 
                    pageProducts.size(), pageProducts.size(), filteredProducts.size());

                // Ищем следующую страницу
                String nextPageUrl = jsonParser.extractNextPage(jsonText);
                if (nextPageUrl != null) {
                    log.info("Найден URL следующей страницы: {}", nextPageUrl);
                    currentUrl = nextPageUrl;
                } else {
                    log.info("Следующая страница не найдена, завершение парсинга категории");
                    currentUrl = null;
                }
            }

            log.info("Категория {} обработана: страниц={}, товаров={}", 
                category, categoryPages, categoryProducts);
        }

        // Сохраняем результаты
        int writtenProducts = filesOut.writeProducts(allProducts);
        int writtenLinks = filesOut.writeLinks(allProducts);

        // Итоговая сводка
        logSummary(categories.size(), allProducts.size(), writtenProducts, writtenLinks, totalRequests);
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
        log.info("Категории: {}", categories != null ? String.join(", ", categories) : "не указаны");
        log.info("Max pages: {}", maxPages == 0 ? "без ограничений" : maxPages);
        log.info("RPS: {}", rps);
        log.info("Min percent: {}", minPercent);
        log.info("Timeout: {} ms", timeoutMs);
        log.info("Output: {}", outputPath);
        log.info("Paginator token: {}", initialPaginatorToken);
        log.info("Cookie file: {}", cookieFilePath);
        log.info("Verbose: {}", verbose);
        log.info("Quiet: {}", quiet);
        log.info("==========================");
    }

    private void logSummary(int categoriesCount, int uniqueProducts, 
                           int writtenProducts, int writtenLinks, long requestCount) {
        log.info("=== Итоговая сводка ===");
        log.info("Категорий обработано: {}", categoriesCount);
        log.info("Уникальных товаров: {}", uniqueProducts);
        log.info("Строк записано в файл: {}", writtenProducts);
        log.info("Уникальных ссылок: {}", writtenLinks);
        log.info("HTTP запросов выполнено: {}", requestCount);
        log.info("=======================");
    }

}

