package org.example.util;

import org.example.model.ProductRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class FilesOut {
    private static final Logger log = LoggerFactory.getLogger(FilesOut.class);
    private static final Charset OUTPUT_CHARSET = Charset.forName("UTF-8");
    
    private final Path outputPath;

    public FilesOut(Path outputPath) {
        this.outputPath = outputPath;
    }

    /**
     * Записывает товары в файл в формате TSV: name<TAB>url<TAB>price<TAB>points<TAB>percent
     * 
     * @param products коллекция товаров для записи
     * @return количество записанных строк
     */
    public int writeProducts(Collection<ProductRow> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }

        try {
            // Создаем директорию, если не существует
            Path parent = outputPath.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }

            // Записываем данные в кодировке Windows-1251
            var lines = products.stream()
                .map(ProductRow::toCsvLine)
                .collect(Collectors.toList());

            Files.write(outputPath, lines, OUTPUT_CHARSET, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            log.debug("Записано {} товаров в {}", products.size(), outputPath);
            return products.size();

        } catch (IOException e) {
            log.error("Ошибка при записи в файл {}: {}", outputPath, e.getMessage(), e);
            return 0;
        }
    }

    /**
     * Записывает только уникальные URL товаров с названиями (по одной ссылке на строку).
     * Формат: name<TAB>url
     * 
     * @param products коллекция товаров
     * @return количество уникальных ссылок
     */
    public int writeLinks(Collection<ProductRow> products) {
        if (products == null || products.isEmpty()) {
            return 0;
        }

        try {
            Path baseDirectory = outputPath.toAbsolutePath().getParent();
            if (baseDirectory == null) {
                baseDirectory = Path.of(".").toAbsolutePath().normalize();
            }
            if (!Files.exists(baseDirectory)) {
                Files.createDirectories(baseDirectory);
            }

            Path linksPath = baseDirectory.resolve("links.csv");
            if (linksPath.equals(outputPath.toAbsolutePath().normalize())) {
                linksPath = baseDirectory.resolve("links_only.csv");
                log.warn("Файл ссылок совпал с output, сохраняем ссылки в {}", linksPath);
            }
            
            // Создаем Map для хранения названий по URL (если есть дубликаты, берем первое)
            Map<String, String> urlToName = products.stream()
                .collect(Collectors.toMap(
                    ProductRow::getUrl,
                    ProductRow::getName,
                    (first, second) -> first // При дубликатах берем первое название
                ));

            var lines = urlToName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> ProductRow.escapeCsv(entry.getValue()) + "," + ProductRow.escapeCsv(entry.getKey()))
                .collect(Collectors.toList());

            Files.write(linksPath, lines, OUTPUT_CHARSET, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            log.debug("Записано {} уникальных ссылок в {}", urlToName.size(), linksPath);
            return urlToName.size();

        } catch (IOException e) {
            log.error("Ошибка при записи ссылок: {}", e.getMessage(), e);
            return 0;
        }
    }
}

