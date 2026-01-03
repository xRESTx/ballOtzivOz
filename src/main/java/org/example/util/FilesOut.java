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
    private static final Charset WINDOWS_1251 = Charset.forName("windows-1251");
    
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
                .map(ProductRow::toTsvLine)
                .collect(Collectors.toList());

            Files.write(outputPath, lines, WINDOWS_1251, StandardOpenOption.CREATE, 
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
            Path linksPath = outputPath.getParent().resolve("links.txt");
            
            // Создаем Map для хранения названий по URL (если есть дубликаты, берем первое)
            Map<String, String> urlToName = products.stream()
                .collect(Collectors.toMap(
                    ProductRow::getUrl,
                    ProductRow::getName,
                    (first, second) -> first // При дубликатах берем первое название
                ));

            var lines = urlToName.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getValue() + "\t" + entry.getKey())
                .collect(Collectors.toList());

            Files.write(linksPath, lines, WINDOWS_1251, StandardOpenOption.CREATE, 
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);

            log.debug("Записано {} уникальных ссылок в {}", urlToName.size(), linksPath);
            return urlToName.size();

        } catch (IOException e) {
            log.error("Ошибка при записи ссылок: {}", e.getMessage(), e);
            return 0;
        }
    }
}

