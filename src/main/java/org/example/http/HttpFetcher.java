package org.example.http;

import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.example.util.CookieLoader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class HttpFetcher {
    private static final Logger log = LoggerFactory.getLogger(HttpFetcher.class);
    private static final int MAX_REDIRECTS = 50; // Увеличенный лимит редиректов
    
    private final OkHttpClient client;
    private final AtomicLong requestCount = new AtomicLong(0);
    private final long minRequestIntervalMs;
    private volatile long lastRequestTime = 0;

    public HttpFetcher(int timeoutMs, int rps) {
        this(timeoutMs, rps, null);
    }

    public HttpFetcher(int timeoutMs, int rps, String cookieFilePath) {
        this.minRequestIntervalMs = 1000L / rps; // миллисекунды между запросами
        
        // Загружаем cookies из файла, если указан
        final List<Cookie> initialCookies = new ArrayList<>();
        if (cookieFilePath != null && !cookieFilePath.isEmpty()) {
            initialCookies.addAll(CookieLoader.loadCookiesFromFile(cookieFilePath));
        } else {
            // Пытаемся загрузить из cookie.txt по умолчанию
            initialCookies.addAll(CookieLoader.loadCookiesFromFile(null));
        }
        
        // Улучшенный CookieJar для сохранения cookies между запросами
        CookieJar cookieJar = new CookieJar() {
            private final java.util.List<Cookie> cookies = new java.util.ArrayList<>(initialCookies);
            
            @Override
            public void saveFromResponse(HttpUrl url, java.util.List<Cookie> responseCookies) {
                synchronized (cookies) {
                    // Удаляем старые cookies с тем же именем и доменом
                    for (Cookie newCookie : responseCookies) {
                        cookies.removeIf(c -> c.name().equals(newCookie.name()) 
                            && c.domain().equals(newCookie.domain())
                            && c.path().equals(newCookie.path()));
                        cookies.add(newCookie);
                    }
                    log.debug("Сохранено {} cookies (всего: {})", responseCookies.size(), cookies.size());
                }
            }
            
            @Override
            public java.util.List<Cookie> loadForRequest(HttpUrl url) {
                synchronized (cookies) {
                    java.util.List<Cookie> matchingCookies = new java.util.ArrayList<>();
                    for (Cookie cookie : cookies) {
                        // Проверяем, не истек ли cookie
                        if (cookie.expiresAt() > 0 && cookie.expiresAt() < System.currentTimeMillis()) {
                            continue; // Cookie истек
                        }
                        if (cookie.matches(url)) {
                            matchingCookies.add(cookie);
                        }
                    }
                    log.debug("Загружено {} cookies для URL: {}", matchingCookies.size(), url);
                    return matchingCookies;
                }
            }
        };
        
        this.client = new OkHttpClient.Builder()
            .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false) // Отключаем автоматические редиректы, обрабатываем вручную
            .followSslRedirects(false)
            .cookieJar(cookieJar)
            .build();
    }

    /**
     * Выполняет HTTP GET запрос с rate limiting.
     * 
     * @param url URL для запроса
     * @return Response или null при ошибке
     */
    public Response fetch(String url) {
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
            // Генерируем динамические заголовки
            String pageViewId = UUID.randomUUID().toString();
            String parentRequestId = UUID.randomUUID().toString().replace("-", "");
            
            // Заголовки для API запросов Ozon
            requestBuilder
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:146.0) Gecko/20100101 Firefox/146.0")
                .header("Accept", "application/json")
                .header("Accept-Language", "ru-RU,ru;q=0.8,en-US;q=0.5,en;q=0.3")
                .header("Accept-Encoding", "gzip, deflate, br, zstd")
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
                .header("Accept-Encoding", "gzip, deflate, br")
                .header("Connection", "keep-alive")
                .header("Upgrade-Insecure-Requests", "1")
                .header("Sec-Fetch-Dest", "document")
                .header("Sec-Fetch-Mode", "navigate")
                .header("Sec-Fetch-Site", "none")
                .header("Sec-Fetch-User", "?1")
                .header("Cache-Control", "max-age=0")
                .header("Referer", "https://www.ozon.ru/");
        }
        
        Request request = requestBuilder.build();

        try {
            // Обрабатываем редиректы вручную с детекцией циклов
            Request currentRequest = request;
            Response response = null;
            int redirectCount = 0;
            Set<String> visitedUrls = new HashSet<>(); // Для детекции циклических редиректов
            
            while (redirectCount < MAX_REDIRECTS) {
                String currentUrlString = currentRequest.url().toString();
                
                // Проверяем на циклический редирект
                if (visitedUrls.contains(currentUrlString)) {
                    log.warn("Обнаружен циклический редирект на URL: {} (уже посещен ранее)", currentUrlString);
                    
                    // Пытаемся удалить параметр __rr и повторить запрос
                    if (currentUrlString.contains("__rr=")) {
                        HttpUrl currentUrl = currentRequest.url();
                        HttpUrl.Builder urlBuilder = currentUrl.newBuilder();
                        // Удаляем все параметры __rr
                        urlBuilder.removeAllQueryParameters("__rr");
                        HttpUrl cleanUrl = urlBuilder.build();
                        String cleanUrlString = cleanUrl.toString();
                        
                        log.info("Попытка обхода цикла: удаление параметра __rr, новый URL: {}", cleanUrlString);
                        
                        if (response != null) {
                            response.close();
                        }
                        
                        // Создаем новый запрос без __rr
                        currentRequest = currentRequest.newBuilder()
                            .url(cleanUrl)
                            .build();
                        
                        // Очищаем visitedUrls для нового запроса
                        visitedUrls.clear();
                        redirectCount = 0;
                        continue;
                    }
                    
                    // Пытаемся использовать последний response, если он есть
                    if (response != null && response.isSuccessful()) {
                        break; // Используем текущий response
                    }
                    // Если response не успешный или его нет, останавливаемся
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
                        break; // Нет Location заголовка, выходим
                    }
                    
                    HttpUrl redirectUrl = currentRequest.url().resolve(location);
                    if (redirectUrl == null) {
                        response.close();
                        throw new IOException("Invalid redirect URL: " + location);
                    }
                    
                    // Проверяем, не ведет ли редирект на уже посещенный URL
                    String redirectUrlString = redirectUrl.toString();
                    if (visitedUrls.contains(redirectUrlString)) {
                        log.warn("Редирект ведет на уже посещенный URL: {}", redirectUrlString);
                        // Используем текущий response, если он успешный
                        if (response.isSuccessful()) {
                            break;
                        }
                        response.close();
                        throw new IOException("Redirect to already visited URL: " + redirectUrlString);
                    }
                    
                    // Закрываем текущий response перед следующим запросом
                    response.close();
                    
                    // Создаем новый запрос с обновленным URL
                    currentRequest = currentRequest.newBuilder()
                        .url(redirectUrl)
                        .build();
                    
                    redirectCount++;
                    log.debug("Редирект {}: {} -> {}", redirectCount, currentUrlString, redirectUrlString);
                    continue;
                }
                
                // Не редирект, выходим из цикла
                break;
            }
            
            if (redirectCount >= MAX_REDIRECTS) {
                if (response != null) {
                    response.close();
                }
                throw new IOException("Too many redirects: " + redirectCount);
            }
            
            long elapsed = System.currentTimeMillis() - startTime;
            
            if (response != null) {
                log.info("GET {} -> {} (time={} ms, redirects={})", url, response.code(), elapsed, redirectCount);
                
                if (!response.isSuccessful()) {
                    log.warn("Неуспешный ответ: {} для URL: {}", response.code(), url);
                    response.close();
                    return null;
                }
            }
            
            return response;

        } catch (IOException e) {
            long elapsed = System.currentTimeMillis() - startTime;
            log.error("Ошибка при запросе {} (time={} ms): {}", url, elapsed, e.getMessage());
            return null;
        }
    }

    /**
     * Выполняет запрос и возвращает тело ответа как строку.
     * 
     * @param url URL для запроса
     * @return содержимое ответа или null при ошибке
     */
    public String fetchString(String url) {
        try (Response response = fetch(url)) {
            if (response == null || response.body() == null) {
                return null;
            }
            return response.body().string();
        } catch (IOException e) {
            log.error("Ошибка при чтении ответа для {}: {}", url, e.getMessage());
            return null;
        }
    }

    /**
     * Получает cookies с главной страницы Ozon для последующих запросов.
     * 
     * @return true если cookies успешно получены
     */
    public boolean initializeCookies() {
        try {
            log.info("Инициализация cookies с главной страницы Ozon...");
            String mainPage = "https://www.ozon.ru/";
            try (Response response = fetch(mainPage)) {
                if (response != null && response.isSuccessful()) {
                    log.info("Cookies успешно получены");
                    return true;
                }
            }
        } catch (Exception e) {
            log.warn("Не удалось получить cookies: {}", e.getMessage());
        }
        return false;
    }

    public long getRequestCount() {
        return requestCount.get();
    }
}

