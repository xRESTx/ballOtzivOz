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
import java.util.ArrayList;
import java.util.List;

/**
 * Парсер JSON для извлечения данных товаров из ответов Ozon API.
 */
public class CategoryJsonParser {
    private static final Logger log = LoggerFactory.getLogger(CategoryJsonParser.class);
    private final Gson gson;

    public CategoryJsonParser() {
        this.gson = new GsonBuilder().setLenient().create();
    }

    /**
     * Очищает JSON от возможных префиксов (например, while(1);).
     */
    private String cleanJson(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            return jsonText;
        }
        
        // Удаляем префиксы типа while(1); или for(;;);
        String cleaned = jsonText.trim();
        if (cleaned.startsWith("while(1);") || cleaned.startsWith("for(;;);")) {
            int start = cleaned.indexOf('{');
            if (start > 0) {
                cleaned = cleaned.substring(start);
            }
        }
        
        return cleaned;
    }

    /**
     * Парсит JSON текст в JsonElement.
     */
    private JsonElement parseJson(String jsonText) {
        try {
            String cleaned = cleanJson(jsonText);
            JsonParser parser = new JsonParser();
            return parser.parse(new StringReader(cleaned));
        } catch (Exception e) {
            log.debug("Ошибка при парсинге JSON: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Извлекает URL следующей страницы из поля nextPage.
     * 
     * @param jsonText JSON текст ответа
     * @return URL следующей страницы или null
     */
    public String extractNextPage(String jsonText) {
        if (jsonText == null || jsonText.isEmpty()) {
            return null;
        }

        try {
            JsonElement root = parseJson(jsonText);
            if (root == null || !root.isJsonObject()) {
                return null;
            }

            JsonObject rootObj = root.getAsJsonObject();
            
            // Ищем nextPage в корне или вложенных объектах
            String nextPage = findNextPage(rootObj, 0);
            if (nextPage != null) {
                log.debug("Найден nextPage: {}", nextPage);
                
                // Если это относительный URL категории, преобразуем в JSON API URL
                if (!nextPage.startsWith("http") && nextPage.contains("/category/")) {
                    // Преобразуем относительный URL в JSON API URL
                    String jsonApiUrl = UrlUtil.buildJsonApiUrl(UrlUtil.normalize(nextPage));
                    if (jsonApiUrl != null) {
                        log.debug("Преобразован nextPage в JSON API URL: {}", jsonApiUrl);
                        return jsonApiUrl;
                    }
                }
                
                // Если уже абсолютный URL, возвращаем как есть
                if (nextPage.startsWith("http")) {
                    return nextPage;
                }
                
                // Иначе нормализуем
                return UrlUtil.normalize(nextPage);
            }

            return null;
        } catch (Exception e) {
            log.debug("Ошибка при извлечении nextPage: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Рекурсивно ищет поле nextPage в JSON объекте.
     */
    private String findNextPage(JsonObject obj, int depth) {
        if (depth > 10) return null; // Защита от бесконечной рекурсии
        
        // Прямой поиск поля nextPage
        if (obj.has("nextPage")) {
            JsonElement elem = obj.get("nextPage");
            if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                String url = elem.getAsString();
                if (url != null && !url.isEmpty()) {
                    return url;
                }
            }
        }

        // Рекурсивный поиск во всех вложенных объектах
        for (String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonObject()) {
                String found = findNextPage(elem.getAsJsonObject(), depth + 1);
                if (found != null) return found;
            } else if (elem.isJsonArray()) {
                JsonArray array = elem.getAsJsonArray();
                for (JsonElement item : array) {
                    if (item.isJsonObject()) {
                        String found = findNextPage(item.getAsJsonObject(), depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Извлекает товары из JSON.
     * Ищет массив в поле tileGridDesktop-3669724-default-2 и парсит каждый элемент.
     * 
     * @param jsonText JSON текст ответа
     * @return список товаров
     */
    public List<ProductRow> parseProducts(String jsonText) {
        List<ProductRow> products = new ArrayList<>();

        if (jsonText == null || jsonText.isEmpty()) {
            return products;
        }

        try {
            JsonElement root = parseJson(jsonText);
            if (root == null || !root.isJsonObject()) {
                return products;
            }

            JsonObject rootObj = root.getAsJsonObject();
            
            // Ищем tileGridDesktop-3669724-default-2
            JsonArray itemsArray = findTileGridArray(rootObj, 0);
            
            if (itemsArray == null) {
                log.debug("Массив tileGridDesktop-3669724-default-2 не найден в JSON");
                return products;
            }

            log.debug("Найден массив товаров, размер: {}", itemsArray.size());

            // Парсим каждый элемент массива
            for (JsonElement itemElem : itemsArray) {
                if (!itemElem.isJsonObject()) {
                    continue;
                }

                JsonObject item = itemElem.getAsJsonObject();
                ProductRow product = parseProductItem(item);
                
                if (product != null) {
                    products.add(product);
                }
            }

            log.debug("Извлечено {} товаров из JSON", products.size());
            return products;

        } catch (Exception e) {
            log.error("Ошибка при парсинге товаров из JSON: {}", e.getMessage(), e);
            return products;
        }
    }

    /**
     * Рекурсивно ищет массив товаров в widgetStates["tileGridDesktop-*"].
     * Значение в widgetStates - это строка JSON, которую нужно распарсить.
     */
    private JsonArray findTileGridArray(JsonObject obj, int depth) {
        if (depth > 10) return null;
        
        // Ищем widgetStates
        if (obj.has("widgetStates")) {
            JsonElement widgetStates = obj.get("widgetStates");
            if (widgetStates.isJsonObject()) {
                JsonObject widgetStatesObj = widgetStates.getAsJsonObject();
                
                // Ищем ключ, начинающийся с "tileGridDesktop"
                for (String key : widgetStatesObj.keySet()) {
                    if (key.startsWith("tileGridDesktop")) {
                        JsonElement elem = widgetStatesObj.get(key);
                        
                        // Значение может быть строкой JSON, которую нужно распарсить
                        if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                            String jsonString = elem.getAsString();
                            try {
                                JsonElement parsed = parseJson(jsonString);
                                if (parsed != null && parsed.isJsonObject()) {
                                    JsonObject parsedObj = parsed.getAsJsonObject();
                                    if (parsedObj.has("items") && parsedObj.get("items").isJsonArray()) {
                                        log.debug("Найден массив items в widgetStates[{}]", key);
                                        return parsedObj.get("items").getAsJsonArray();
                                    }
                                }
                            } catch (Exception e) {
                                log.debug("Ошибка при парсинге JSON строки из widgetStates[{}]: {}", key, e.getMessage());
                            }
                        } else if (elem.isJsonObject()) {
                            // Может быть уже объект
                            JsonObject nested = elem.getAsJsonObject();
                            if (nested.has("items") && nested.get("items").isJsonArray()) {
                                return nested.get("items").getAsJsonArray();
                            }
                        } else if (elem.isJsonArray()) {
                            // Может быть уже массив
                            return elem.getAsJsonArray();
                        }
                    }
                }
            }
        }
        
        // Также ищем напрямую поле, которое начинается с "tileGridDesktop"
        for (String key : obj.keySet()) {
            if (key.startsWith("tileGridDesktop")) {
                JsonElement elem = obj.get(key);
                if (elem.isJsonPrimitive() && elem.getAsJsonPrimitive().isString()) {
                    // Строка JSON
                    String jsonString = elem.getAsString();
                    try {
                        JsonElement parsed = parseJson(jsonString);
                        if (parsed != null && parsed.isJsonObject()) {
                            JsonObject parsedObj = parsed.getAsJsonObject();
                            if (parsedObj.has("items") && parsedObj.get("items").isJsonArray()) {
                                return parsedObj.get("items").getAsJsonArray();
                            }
                        }
                    } catch (Exception e) {
                        log.debug("Ошибка при парсинге JSON строки из {}: {}", key, e.getMessage());
                    }
                } else if (elem.isJsonArray()) {
                    return elem.getAsJsonArray();
                } else if (elem.isJsonObject()) {
                    JsonObject nested = elem.getAsJsonObject();
                    if (nested.has("items") && nested.get("items").isJsonArray()) {
                        return nested.get("items").getAsJsonArray();
                    }
                }
            }
        }

        // Рекурсивный поиск
        for (String key : obj.keySet()) {
            JsonElement elem = obj.get(key);
            if (elem.isJsonObject()) {
                JsonArray found = findTileGridArray(elem.getAsJsonObject(), depth + 1);
                if (found != null) return found;
            } else if (elem.isJsonArray()) {
                // Проверяем элементы массива
                JsonArray array = elem.getAsJsonArray();
                for (JsonElement item : array) {
                    if (item.isJsonObject()) {
                        JsonArray found = findTileGridArray(item.getAsJsonObject(), depth + 1);
                        if (found != null) return found;
                    }
                }
            }
        }

        return null;
    }

    /**
     * Парсит один товар из JSON объекта.
     * Извлекает: название, ссылку, цену, баллы.
     */
    private ProductRow parseProductItem(JsonObject item) {
        try {
            // 1. Ссылка: item["action"]["link"]
            String link = extractLink(item);
            if (link == null || link.isEmpty()) {
                log.debug("Товар без ссылки, пропускаем");
                return null;
            }
            
            // Нормализуем ссылку
            link = UrlUtil.normalize(link);

            // 2. Название: item["mainState"][*]["textAtom"]["text"] (где type = "textAtom" и id = "name")
            String name = extractName(item);
            if (name == null || name.isEmpty()) {
                log.debug("Товар без названия, пропускаем: {}", link);
                return null;
            }

            // 3. Цена: item["mainState"][*]["priceV2"]["price"][0]["text"] (где type = "priceV2")
            int price = extractPrice(item);
            if (price <= 0) {
                log.debug("Товар без цены, пропускаем: {}", link);
                return null;
            }

            // 4. Баллы за отзыв: item["tileImage"]["leftBottomBadgeV2"]["text"]
            int points = extractPoints(item);
            if (points <= 0) {
                log.debug("Товар без баллов, пропускаем: {}", link);
                return null;
            }

            // Вычисляем процент
            double percent = Math.round((points * 100.0 / price) * 100.0) / 100.0;

            return new ProductRow(name, link, price, points, percent);

        } catch (Exception e) {
            log.debug("Ошибка при парсинге товара: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Извлекает название из item["mainState"][*]["textAtom"]["text"] (где type = "textAtom" и id = "name").
     */
    private String extractName(JsonObject item) {
        if (!item.has("mainState")) {
            return null;
        }
        JsonElement mainState = item.get("mainState");
        if (!mainState.isJsonArray()) {
            return null;
        }
        JsonArray mainStateArray = mainState.getAsJsonArray();
        for (JsonElement stateElem : mainStateArray) {
            if (!stateElem.isJsonObject()) {
                continue;
            }
            JsonObject state = stateElem.getAsJsonObject();
            // Проверяем type = "textAtom" и id = "name"
            if (state.has("type") && state.get("type").getAsString().equals("textAtom")) {
                if (state.has("id") && state.get("id").getAsString().equals("name")) {
                    if (state.has("textAtom") && state.get("textAtom").isJsonObject()) {
                        JsonObject textAtom = state.get("textAtom").getAsJsonObject();
                        if (textAtom.has("text") && textAtom.get("text").isJsonPrimitive()) {
                            return textAtom.get("text").getAsString();
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Извлекает ссылку из item["action"]["link"].
     */
    private String extractLink(JsonObject item) {
        if (item.has("action")) {
            JsonElement action = item.get("action");
            if (action.isJsonObject()) {
                JsonObject actionObj = action.getAsJsonObject();
                if (actionObj.has("link")) {
                    JsonElement link = actionObj.get("link");
                    if (link.isJsonPrimitive() && link.getAsJsonPrimitive().isString()) {
                        return link.getAsString();
                    }
                }
            }
        }
        return null;
    }

    /**
     * Извлекает цену из item["mainState"][*]["priceV2"]["price"][0]["text"].
     */
    private int extractPrice(JsonObject item) {
        if (!item.has("mainState")) {
            return 0;
        }

        JsonElement mainState = item.get("mainState");
        if (!mainState.isJsonArray()) {
            return 0;
        }

        JsonArray mainStateArray = mainState.getAsJsonArray();
        for (JsonElement stateElem : mainStateArray) {
            if (!stateElem.isJsonObject()) {
                continue;
            }

            JsonObject state = stateElem.getAsJsonObject();
            
            // Проверяем type = "priceV2"
            if (state.has("type") && state.get("type").getAsString().equals("priceV2")) {
                // Структура: state["priceV2"]["price"][0]["text"]
                if (state.has("priceV2")) {
                    JsonElement priceV2 = state.get("priceV2");
                    if (priceV2.isJsonObject()) {
                        JsonObject priceV2Obj = priceV2.getAsJsonObject();
                        if (priceV2Obj.has("price")) {
                            JsonElement price = priceV2Obj.get("price");
                            if (price.isJsonArray() && price.getAsJsonArray().size() > 0) {
                                JsonElement firstPrice = price.getAsJsonArray().get(0);
                                if (firstPrice.isJsonObject()) {
                                    JsonObject priceObj = firstPrice.getAsJsonObject();
                                    if (priceObj.has("text")) {
                                        String priceText = priceObj.get("text").getAsString();
                                        return Numbers.extractInt(priceText);
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        return 0;
    }

    /**
     * Извлекает баллы из item["tileImage"]["leftBottomBadgeV2"]["text"].
     */
    private int extractPoints(JsonObject item) {
        if (!item.has("tileImage")) {
            return 0;
        }

        JsonElement tileImage = item.get("tileImage");
        if (!tileImage.isJsonObject()) {
            return 0;
        }

        JsonObject tileImageObj = tileImage.getAsJsonObject();
        if (tileImageObj.has("leftBottomBadgeV2")) {
            JsonElement badge = tileImageObj.get("leftBottomBadgeV2");
            if (badge.isJsonObject()) {
                JsonObject badgeObj = badge.getAsJsonObject();
                if (badgeObj.has("text")) {
                    String pointsText = badgeObj.get("text").getAsString();
                    return Numbers.extractInt(pointsText);
                }
            }
        }

        return 0;
    }
}

