package org.example.http;

import okhttp3.*;
import org.example.util.CookieLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Простой HTTP клиент для запросов к Ozon API с поддержкой cookies из файла.
 */
public class SimpleHttpClient {
    private static final Logger log = LoggerFactory.getLogger(SimpleHttpClient.class);
    private static final int MAX_REDIRECTS = 50;
    
    private final OkHttpClient client;
    private final AtomicLong requestCount = new AtomicLong(0);
    private final long minRequestIntervalMs;
    private volatile long lastRequestTime = 0;
    private final List<Cookie> loadedCookies = new ArrayList<>();
    private final CookieJar cookieJar;

    public SimpleHttpClient(int timeoutMs, int rps) {
        this(timeoutMs, rps, null);
    }

    public SimpleHttpClient(int timeoutMs, int rps, String cookieFilePath) {
        this.minRequestIntervalMs = 1000L / rps;
        
        // Загружаем cookies из файла, если указан
        if (cookieFilePath != null && !cookieFilePath.isEmpty()) {
            List<Cookie> cookies = CookieLoader.loadCookiesFromFile(cookieFilePath);
            loadedCookies.addAll(cookies);
            log.info("Загружено {} cookies из файла {}", cookies.size(), cookieFilePath);
        } else {
            // Пытаемся загрузить из cookie.txt по умолчанию
            List<Cookie> cookies = CookieLoader.loadCookiesFromFile("cookie.txt");
            if (!cookies.isEmpty()) {
                loadedCookies.addAll(cookies);
                log.info("Загружено {} cookies из файла cookie.txt", cookies.size());
            }
        }
        
        // Создаем CookieJar для автоматического сохранения cookies
        this.cookieJar = new CookieJar() {
            private final List<Cookie> cookies = new ArrayList<>(loadedCookies);
            
            @Override
            public void saveFromResponse(HttpUrl url, List<Cookie> responseCookies) {
                synchronized (cookies) {
                    for (Cookie newCookie : responseCookies) {
                        // Удаляем старые cookies с тем же именем и доменом
                        cookies.removeIf(c -> c.name().equals(newCookie.name())
                            && c.domain().equals(newCookie.domain())
                            && c.path().equals(newCookie.path()));
                        cookies.add(newCookie);
                    }
                    // Обновляем loadedCookies
                    synchronized (loadedCookies) {
                        loadedCookies.clear();
                        loadedCookies.addAll(cookies);
                    }
                    log.debug("Сохранено {} cookies (всего: {})", responseCookies.size(), cookies.size());
                }
            }
            
            @Override
            public List<Cookie> loadForRequest(HttpUrl url) {
                synchronized (cookies) {
                    List<Cookie> matchingCookies = new ArrayList<>();
                    for (Cookie cookie : cookies) {
                        if (cookie.expiresAt() > 0 && cookie.expiresAt() < System.currentTimeMillis()) {
                            continue;
                        }
                        if (cookie.matches(url)) {
                            matchingCookies.add(cookie);
                        }
                    }
                    return matchingCookies;
                }
            }
        };
        
        this.client = new OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .cookieJar(cookieJar)
            .addInterceptor(chain -> {
                // OkHttp автоматически распаковывает ответы, но убедимся что это работает
                Response originalResponse = chain.proceed(chain.request());
                return originalResponse.newBuilder()
                    .build();
            })
            .build();
    }
    
    /**
     * Инициализирует cookies, делая запрос к главной странице Ozon.
     * Это позволяет получить дополнительные cookies, установленные сервером.
     */
    public void initializeCookies() {
        String mainPageUrl = "https://www.ozon.ru/";
        log.info("Инициализация cookies: запрос к главной странице {}", mainPageUrl);
        
        // Используем fetchString для обработки редиректов
        String responseBody = fetchString(mainPageUrl);
        
        if (responseBody != null) {
            // CookieJar автоматически сохранит cookies из ответа
            log.info("Инициализация cookies завершена, получено cookies с главной страницы");
            // Обновляем loadedCookies из cookieJar
            HttpUrl httpUrl = HttpUrl.parse(mainPageUrl);
            if (httpUrl != null) {
                List<Cookie> cookiesFromJar = cookieJar.loadForRequest(httpUrl);
                synchronized (loadedCookies) {
                    loadedCookies.clear();
                    loadedCookies.addAll(cookiesFromJar);
                }
                log.info("Всего cookies после инициализации: {}", loadedCookies.size());
            }
        } else {
            log.warn("Не удалось получить cookies с главной страницы");
        }
    }

    /**
     * Выполняет GET запрос и возвращает тело ответа как строку.
     * 
     * @param url URL для запроса
     * @return тело ответа или null в случае ошибки
     */
    public String fetchString(String url) {
        // Rate limiting
        long now = System.currentTimeMillis();
        long timeSinceLastRequest = now - lastRequestTime;
        if (timeSinceLastRequest < minRequestIntervalMs) {
            try {
                Thread.sleep(minRequestIntervalMs - timeSinceLastRequest);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        lastRequestTime = System.currentTimeMillis();

        long startTime = System.currentTimeMillis();
        requestCount.incrementAndGet();

        // Определяем тип запроса по URL
        boolean isApiRequest = url.contains("/api/") || url.contains("/page/json/");
        
        Request.Builder requestBuilder = new Request.Builder()
            .url(url);
        
        if (isApiRequest) {
            // Генерируем динамические заголовки для API запросов
            String pageViewId = UUID.randomUUID().toString();
            String parentRequestId = UUID.randomUUID().toString().replace("-", "");
            
            requestBuilder
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
                .header("Accept", "application/json")
                .header("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3")
                // Не указываем Accept-Encoding для API запросов, чтобы получить несжатые ответы
                // (OkHttp не поддерживает автоматическую распаковку br и zstd)
                .header("Referer", "https://www.ozon.ru/")
                .header("x-o3-app-name", "dweb_client")
                .header("x-o3-app-version", "release_24-11-2025_810214bf")
                .header("x-o3-manifest-version", "frontend-ozon-ru:810214bf758f49057080e0d8aa6c82511b4ec186,checkout-render-api:1e0f52903de2eda47a258b6f142f07269cdae765,fav-render-api:3774a6d81bb05ac6e51f04817f0995097e7b1832,sf-render-api:e5c894ee84ac2cd9fc977be7a4bcae05c744ecbd")
                .header("x-page-view-id", pageViewId)
                .header("x-o3-parent-requestid", parentRequestId)
                .header("Content-Type", "application/json")
                .header("Sec-GPC", "1")
                .header("Sec-Fetch-Dest", "empty")
                .header("Sec-Fetch-Mode", "cors")
                .header("Sec-Fetch-Site", "same-origin")
                .header("Connection", "keep-alive");
        } else {
            // Заголовки для обычных HTML запросов
            requestBuilder
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7")
                .header("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7")
                .header("Accept-Encoding", "gzip, deflate")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .header("Referer", "https://www.ozon.ru/");
        }

        // Добавляем cookies в заголовок Cookie
        if (!loadedCookies.isEmpty()) {
            HttpUrl httpUrl = HttpUrl.parse(url);
            if (httpUrl != null) {
                StringBuilder cookieHeader = new StringBuilder();
                for (Cookie cookie : loadedCookies) {
                    if (cookie.matches(httpUrl)) {
                        if (cookieHeader.length() > 0) {
                            cookieHeader.append("; ");
                        }
                        cookieHeader.append(cookie.name()).append("=").append(cookie.value());
                    }
                }
                if (cookieHeader.length() > 0) {
                    requestBuilder.header("Cookie", cookieHeader.toString());
                    log.debug("Добавлено {} cookies в запрос", cookieHeader.toString().split(";").length);
                }
            }
        }

        Request request = requestBuilder.build();

        try {
            // Обрабатываем редиректы вручную с детекцией циклов
            Request currentRequest = request;
            Response response = null;
            int redirectCount = 0;
            Set<String> visitedUrls = new HashSet<>();
            String originalUrl = url; // Сохраняем исходный URL
            
            while (redirectCount < MAX_REDIRECTS) {
                String currentUrlString = currentRequest.url().toString();
                
                // Проверяем на циклический редирект
                // Но если это исходный URL, не считаем это циклом (может быть нормальный редирект)
                if (visitedUrls.contains(currentUrlString) && !currentUrlString.equals(originalUrl)) {
                    log.warn("Обнаружен циклический редирект на URL: {} (уже посещен ранее)", currentUrlString);
                    
                    // Пытаемся удалить параметр __rr и повторить запрос
                    if (currentUrlString.contains("__rr=")) {
                        HttpUrl currentUrl = currentRequest.url();
                        HttpUrl.Builder urlBuilder = currentUrl.newBuilder();
                        urlBuilder.removeAllQueryParameters("__rr");
                        HttpUrl cleanUrl = urlBuilder.build();
                        String cleanUrlString = cleanUrl.toString();
                        
                        log.info("Попытка обхода цикла: удаление параметра __rr, новый URL: {}", cleanUrlString);
                        
                        if (response != null) {
                            response.close();
                        }
                        
                        // Если чистый URL - это исходный URL, просто используем его
                        if (cleanUrlString.equals(originalUrl)) {
                            currentRequest = currentRequest.newBuilder()
                                .url(cleanUrl)
                                .build();
                            visitedUrls.remove(originalUrl);
                            visitedUrls.remove(cleanUrlString);
                            continue;
                        }
                        
                        // Создаем новый запрос без __rr
                        currentRequest = currentRequest.newBuilder()
                            .url(cleanUrl)
                            .build();
                        
                        visitedUrls.clear();
                        redirectCount = 0;
                        continue;
                    }
                    
                    // Пытаемся использовать последний response, если он есть
                    if (response != null && response.isSuccessful()) {
                        break;
                    }
                    if (response != null) {
                        response.close();
                    }
                    throw new IOException("Circular redirect detected at: " + currentUrlString);
                }
                
                visitedUrls.add(currentUrlString);
                response = client.newCall(currentRequest).execute();
                
                // Проверяем, является ли ответ редиректом
                int code = response.code();
                if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                    String location = response.header("Location");
                    if (location == null) {
                        break;
                    }
                    
                    HttpUrl redirectUrl = currentRequest.url().resolve(location);
                    if (redirectUrl == null) {
                        response.close();
                        throw new IOException("Invalid redirect URL: " + location);
                    }
                    
                    String redirectUrlString = redirectUrl.toString();
                    
                    // Проверяем, добавляет ли редирект параметр __rr
                    if (redirectUrlString.contains("__rr=") && !currentUrlString.contains("__rr=")) {
                        // Редирект добавляет __rr - удаляем его из редиректа
                        HttpUrl cleanUrl = redirectUrl.newBuilder()
                            .removeAllQueryParameters("__rr")
                            .build();
                        String cleanUrlString = cleanUrl.toString();
                        
                        log.info("Редирект добавляет __rr, удаляем параметр: {} -> {}", redirectUrlString, cleanUrlString);
                        
                        response.close();
                        
                        // Если URL без __rr совпадает с исходным URL, это нормально - просто игнорируем редирект
                        // и продолжаем с исходным URL (не считаем это циклом)
                        if (cleanUrlString.equals(originalUrl)) {
                            log.info("Редирект с __rr ведет на исходный URL, игнорируем редирект и используем исходный URL");
                            // Используем исходный URL, но не добавляем его в visitedUrls снова
                            // Удаляем исходный URL из visitedUrls, если он там есть, чтобы не считать это циклом
                            visitedUrls.remove(originalUrl);
                            visitedUrls.remove(cleanUrlString);
                            
                            currentRequest = currentRequest.newBuilder()
                                .url(cleanUrl)
                                .build();
                            // Не увеличиваем redirectCount, так как это не настоящий редирект
                            continue;
                        }
                        
                        // Если URL без __rr уже был посещен (но не исходный), это может быть цикл
                        if (visitedUrls.contains(cleanUrlString)) {
                            log.warn("URL без __rr уже был посещен (не исходный), возможен цикл");
                            // Пытаемся использовать исходный URL
                            HttpUrl originalHttpUrl = HttpUrl.parse(originalUrl);
                            if (originalHttpUrl != null) {
                                log.info("Используем исходный URL: {}", originalUrl);
                                currentRequest = currentRequest.newBuilder()
                                    .url(originalHttpUrl)
                                    .build();
                                // Очищаем историю и начинаем заново
                                visitedUrls.clear();
                                visitedUrls.add(originalUrl);
                                redirectCount = 0;
                                continue;
                            }
                        }
                        
                        // Используем URL без __rr
                        currentRequest = currentRequest.newBuilder()
                            .url(cleanUrl)
                            .build();
                        
                        redirectCount++;
                        continue;
                    }
                    
                    // Если редирект содержит __rr и текущий URL тоже содержит __rr, проверяем на цикл
                    if (redirectUrlString.contains("__rr=") && currentUrlString.contains("__rr=")) {
                        // Оба URL содержат __rr - проверяем, не создает ли это цикл
                        HttpUrl cleanRedirectUrl = redirectUrl.newBuilder()
                            .removeAllQueryParameters("__rr")
                            .build();
                        HttpUrl cleanCurrentUrl = currentRequest.url().newBuilder()
                            .removeAllQueryParameters("__rr")
                            .build();
                        
                        String cleanRedirectUrlString = cleanRedirectUrl.toString();
                        String cleanCurrentUrlString = cleanCurrentUrl.toString();
                        
                        // Если чистые URL одинаковы, это цикл
                        if (cleanRedirectUrlString.equals(cleanCurrentUrlString)) {
                            log.warn("Обнаружен цикл с __rr, используем исходный URL без __rr");
                            response.close();
                            
                            // Используем исходный URL без __rr
                            currentRequest = currentRequest.newBuilder()
                                .url(cleanCurrentUrl)
                                .build();
                            
                            // Если этот URL уже был посещен, выходим
                            if (visitedUrls.contains(cleanCurrentUrlString)) {
                                break;
                            }
                            
                            redirectCount++;
                            continue;
                        }
                    }
                    
                    // Проверяем, не ведет ли редирект на уже посещенный URL
                    // Проверяем как точное совпадение, так и версию без __rr
                    boolean alreadyVisited = visitedUrls.contains(redirectUrlString);
                    if (!alreadyVisited && redirectUrlString.contains("__rr=")) {
                        // Проверяем версию без __rr
                        HttpUrl cleanUrl = redirectUrl.newBuilder()
                            .removeAllQueryParameters("__rr")
                            .build();
                        String cleanUrlString = cleanUrl.toString();
                        alreadyVisited = visitedUrls.contains(cleanUrlString) || cleanUrlString.equals(originalUrl);
                        
                        if (alreadyVisited) {
                            log.warn("Редирект ведет на уже посещенный URL (без __rr): {}", cleanUrlString);
                            response.close();
                            
                            // Пытаемся использовать исходный URL
                            HttpUrl originalHttpUrl = HttpUrl.parse(originalUrl);
                            if (originalHttpUrl != null && !visitedUrls.contains(originalUrl)) {
                                log.info("Используем исходный URL: {}", originalUrl);
                                currentRequest = request.newBuilder()
                                    .url(originalHttpUrl)
                                    .build();
                                visitedUrls.clear();
                                redirectCount = 0;
                                continue;
                            } else {
                                log.error("Исходный URL уже был посещен, цикл редиректов");
                                break;
                            }
                        }
                    }
                    
                    if (alreadyVisited) {
                        log.warn("Редирект ведет на уже посещенный URL: {}", redirectUrlString);
                        
                        // Если response успешный, используем его
                        if (response.isSuccessful()) {
                            break;
                        }
                        response.close();
                        throw new IOException("Redirect to already visited URL: " + redirectUrlString);
                    }
                    
                    response.close();
                    
                    // Создаем новый запрос с обновленным URL
                    currentRequest = currentRequest.newBuilder()
                        .url(redirectUrl)
                        .build();
                    
                    redirectCount++;
                    log.debug("Редирект {}: {} -> {}", redirectCount, currentUrlString, redirectUrlString);
                    continue;
                }
                
                break;
            }
            
            if (redirectCount >= MAX_REDIRECTS) {
                if (response != null) {
                    response.close();
                }
                throw new IOException("Too many redirects: " + redirectCount);
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            if (response == null) {
                log.error("Ошибка: response is null");
                return null;
            }
            
            if (!response.isSuccessful()) {
                log.warn("GET {} -> {} (time={} ms, redirects={})", url, response.code(), elapsed, redirectCount);
                response.close();
                return null;
            }

            if (response.body() == null) {
                response.close();
                return null;
            }
            
            // Проверяем Content-Encoding заголовок
            String contentEncoding = response.header("Content-Encoding");
            if (contentEncoding != null) {
                log.debug("Content-Encoding: {}", contentEncoding);
                // OkHttp автоматически распаковывает только gzip и deflate
                // Если ответ в br или zstd, OkHttp не распакует его
                if (contentEncoding.contains("br") || contentEncoding.contains("zstd")) {
                    log.warn("Ответ сжат в br или zstd, OkHttp не распакует автоматически. Удаляем заголовок Accept-Encoding для следующего запроса.");
                }
            }
            
            // Читаем тело ответа как байты сначала для проверки
            byte[] bodyBytes = response.body().bytes();
            
            // Проверяем первые байты на наличие магических чисел сжатия
            boolean isCompressed = false;
            if (bodyBytes.length >= 2) {
                int firstByte = bodyBytes[0] & 0xFF;
                int secondByte = bodyBytes[1] & 0xFF;
                
                // Gzip: 0x1F 0x8B
                // Deflate: может начинаться с разных значений
                // Brotli: начинается с 0x81, 0xCE и т.д.
                if (firstByte == 0x1F && secondByte == 0x8B) {
                    log.warn("Обнаружен gzip в теле ответа, но OkHttp должен был распаковать. Возможно проблема с распаковкой.");
                    isCompressed = true;
                } else if (firstByte == 0x78 && (secondByte == 0x01 || secondByte == 0x9C || secondByte == 0xDA)) {
                    log.warn("Обнаружен deflate/zlib в теле ответа, но OkHttp должен был распаковать.");
                    isCompressed = true;
                } else if (firstByte == 0x81 || firstByte == 0xCE) {
                    log.error("Обнаружен Brotli (br) в теле ответа! OkHttp не распаковывает br автоматически. Ответ будет бинарным.");
                    isCompressed = true;
                }
            }
            
            // Пытаемся прочитать как строку
            String body = new String(bodyBytes, java.nio.charset.StandardCharsets.UTF_8);
            
            // Проверяем, что это действительно текст, а не бинарные данные
            if (body != null && body.length() > 0) {
                // Проверяем первые символы - если это нечитаемые символы, возможно ответ сжат
                char firstChar = body.charAt(0);
                if (firstChar < 32 && firstChar != '\n' && firstChar != '\r' && firstChar != '\t') {
                    log.error("Ответ содержит бинарные данные! Content-Encoding: {}, isCompressed: {}", 
                        contentEncoding, isCompressed);
                    // Пытаемся найти начало JSON (символ { или [)
                    int jsonStart = -1;
                    for (int i = 0; i < Math.min(100, body.length()); i++) {
                        char c = body.charAt(i);
                        if (c == '{' || c == '[') {
                            jsonStart = i;
                            break;
                        }
                    }
                    if (jsonStart > 0) {
                        log.warn("Найден JSON начиная с позиции {}, обрезаем бинарные данные", jsonStart);
                        body = body.substring(jsonStart);
                    } else {
                        log.error("JSON не найден в ответе, возможно ответ полностью бинарный");
                        response.close();
                        return null;
                    }
                }
            }
            
            log.info("GET {} -> {} (time={} ms, redirects={}, body size={}, encoding={})", 
                url, response.code(), elapsed, redirectCount, body != null ? body.length() : 0, contentEncoding);
            response.close();
            return body;

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Ошибка при запросе {} (time={} ms): {}", url, elapsed, e.getMessage());
            return null;
        }
    }

    public long getRequestCount() {
        return requestCount.get();
    }
}

