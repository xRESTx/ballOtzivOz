package org.example.util;

import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class CookieLoader {
    private static final Logger log = LoggerFactory.getLogger(CookieLoader.class);
    private static final String DEFAULT_COOKIE_FILE = "cookie.txt";
    private static final HttpUrl OZON_URL = HttpUrl.parse("https://www.ozon.ru");

    /**
     * Загружает cookies из файла cookie.txt.
     * Формат файла: каждая строка содержит cookie в формате "name=value" или "name=value; domain=.ozon.ru; path=/"
     * Также поддерживается формат Netscape: строки начинающиеся с # игнорируются, формат:
     * domain	flag	path	secure	expiration	name	value
     * 
     * @param cookieFilePath путь к файлу с cookies (null = cookie.txt в текущей директории)
     * @return список загруженных cookies
     */
    public static List<Cookie> loadCookiesFromFile(String cookieFilePath) {
        List<Cookie> cookies = new ArrayList<>();
        
        if (cookieFilePath == null || cookieFilePath.isEmpty()) {
            cookieFilePath = DEFAULT_COOKIE_FILE;
        }
        
        Path path = Paths.get(cookieFilePath);
        if (!Files.exists(path)) {
            log.warn("Файл cookies не найден: {}. Cookies не будут загружены.", cookieFilePath);
            return cookies;
        }
        
        try {
            List<String> lines = Files.readAllLines(path);
            log.info("Загрузка cookies из файла: {} ({} строк)", cookieFilePath, lines.size());
            
            for (String line : lines) {
                line = line.trim();
                
                // Пропускаем пустые строки и комментарии
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                
                // Пытаемся распарсить cookie
                Cookie cookie = parseCookieLine(line);
                if (cookie != null) {
                    cookies.add(cookie);
                    log.debug("Загружен cookie: {}={}", cookie.name(), cookie.value());
                }
            }
            
            log.info("Успешно загружено {} cookies из файла", cookies.size());
            
        } catch (IOException e) {
            log.error("Ошибка при чтении файла cookies {}: {}", cookieFilePath, e.getMessage());
        }
        
        return cookies;
    }

    /**
     * Парсит строку с cookie.
     * Поддерживает форматы:
     * 1. Простой: "name=value"
     * 2. С атрибутами: "name=value; domain=.ozon.ru; path=/; secure"
     * 3. Netscape: "domain	flag	path	secure	expiration	name	value"
     */
    private static Cookie parseCookieLine(String line) {
        try {
            // Формат Netscape (табуляция)
            if (line.contains("\t")) {
                return parseNetscapeCookie(line);
            }
            
            // Простой формат или с атрибутами
            String[] parts = line.split(";");
            String nameValue = parts[0].trim();
            
            if (!nameValue.contains("=")) {
                return null;
            }
            
            String[] nv = nameValue.split("=", 2);
            if (nv.length != 2) {
                return null;
            }
            
            String name = nv[0].trim();
            String value = nv[1].trim();
            
            // Парсим дополнительные атрибуты
            String domain = "www.ozon.ru"; // OkHttp требует домен без точки в начале
            String path = "/";
            boolean secure = false;
            boolean httpOnly = false;
            long expiresAt = 0;
            
            for (int i = 1; i < parts.length; i++) {
                String attr = parts[i].trim().toLowerCase();
                if (attr.startsWith("domain=")) {
                    domain = normalizeDomain(attr.substring(7).trim());
                } else if (attr.startsWith("path=")) {
                    path = attr.substring(5).trim();
                } else if (attr.equals("secure")) {
                    secure = true;
                } else if (attr.equals("httponly")) {
                    httpOnly = true;
                } else if (attr.startsWith("expires=")) {
                    // Простая обработка expires (можно улучшить)
                    expiresAt = System.currentTimeMillis() + (365L * 24 * 60 * 60 * 1000); // +1 год
                }
            }
            
            // Нормализуем домен (убираем точку в начале, если есть)
            domain = normalizeDomain(domain);
            
            // Создаем cookie
            Cookie.Builder builder = new Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path);
            
            if (secure) {
                builder = builder.secure();
            }
            if (httpOnly) {
                builder = builder.httpOnly();
            }
            if (expiresAt > 0) {
                builder = builder.expiresAt(expiresAt);
            }
            
            try {
                Cookie cookie = builder.build();
                // Проверяем, что cookie валиден для Ozon
                if (cookie.matches(OZON_URL)) {
                    return cookie;
                }
            } catch (Exception e) {
                log.debug("Ошибка при создании cookie с доменом '{}': {}", domain, e.getMessage());
            }
            
            // Если не подходит, создаем с нормализованным доменом
            try {
                return new Cookie.Builder()
                    .name(name)
                    .value(value)
                    .domain("www.ozon.ru") // Используем www.ozon.ru вместо .ozon.ru
                    .path("/")
                    .build();
            } catch (Exception e) {
                log.debug("Ошибка при создании cookie с доменом www.ozon.ru: {}", e.getMessage());
                return null;
            }
            
        } catch (Exception e) {
            log.debug("Ошибка при парсинге cookie строки '{}': {}", line, e.getMessage());
            return null;
        }
    }

    /**
     * Нормализует домен для OkHttp (убирает точку в начале, если есть).
     * OkHttp не принимает домены с точкой в начале типа ".ozon.ru"
     */
    private static String normalizeDomain(String domain) {
        if (domain == null || domain.isEmpty()) {
            return "www.ozon.ru";
        }
        domain = domain.trim();
        // Убираем точку в начале
        if (domain.startsWith(".")) {
            domain = domain.substring(1);
        }
        // Если домен пустой или не содержит ozon.ru, используем www.ozon.ru
        if (domain.isEmpty() || !domain.contains("ozon.ru")) {
            return "www.ozon.ru";
        }
        // Если домен начинается с ozon.ru, добавляем www
        if (domain.startsWith("ozon.ru")) {
            return "www." + domain;
        }
        return domain;
    }

    /**
     * Парсит cookie в формате Netscape.
     * Формат: domain	flag	path	secure	expiration	name	value
     */
    private static Cookie parseNetscapeCookie(String line) {
        try {
            String[] fields = line.split("\t");
            if (fields.length < 7) {
                return null;
            }
            
            String domain = normalizeDomain(fields[0].trim());
            String path = fields[2].trim();
            boolean secure = "TRUE".equalsIgnoreCase(fields[3].trim());
            long expiration = Long.parseLong(fields[4].trim());
            String name = fields[5].trim();
            String value = fields[6].trim();
            
            // Если expiration в прошлом, пропускаем
            if (expiration > 0 && expiration < System.currentTimeMillis() / 1000) {
                return null;
            }
            
            Cookie.Builder builder = new Cookie.Builder()
                .name(name)
                .value(value)
                .domain(domain)
                .path(path);
            
            if (secure) {
                builder = builder.secure();
            }
            if (expiration > 0) {
                builder = builder.expiresAt(expiration * 1000); // Конвертируем в миллисекунды
            }
            
            return builder.build();
            
        } catch (Exception e) {
            log.debug("Ошибка при парсинге Netscape cookie: {}", e.getMessage());
            return null;
        }
    }
}

