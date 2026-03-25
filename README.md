# Ozon Public Catalog Parser

Production-ready Java приложение для парсинга публичного каталога Ozon и извлечения товаров с баллами за отзывы.

## Описание

Парсер обрабатывает каталог Ozon по URL формата:
```
https://www.ozon.ru/highlight/bally-za-otzyv-1171518
```

Для каждой карточки товара извлекает:
- **URL** товара (полный абсолютный URL)
- **Цену** (целое число в рублях)
- **Баллы** за отзыв (целое число)
- **Процент** = баллы * 100.0 / цена (округление до 2 знаков)

Результаты сохраняются в файл `products.tsv` в формате TSV:
```
url<TAB>price<TAB>points<TAB>percent
```

## Требования

- Java 23
- Gradle 8.x+
- Файл `cookie.txt` с cookies от Ozon (см. раздел "Настройка Cookies")

## Сборка

```bash
./gradlew shadowJar
```

или на Windows:

```bash
gradlew.bat shadowJar
```

После сборки будет создан файл `build/libs/ballotzivoz.jar`.

## Настройка Cookies

Для работы парсера необходимо получить cookies из браузера:

1. Откройте браузер и зайдите на https://www.ozon.ru
2. Откройте DevTools (F12) → вкладка Application/Storage → Cookies → https://www.ozon.ru
3. Скопируйте все cookies в формате `name=value` (по одной на строку)
4. Сохраните в файл `cookie.txt` в корне проекта

Пример формата файла `cookie.txt`:
```
__Secure-ETC=e05aa79ce4f8ce8a7d7f726dc753f08b
__Secure-user-id=62533126
__Secure-access-token=10.62533126.gOtcHFoTSqqbkl6c3eqL7g.70...
xcid=38da76f5b31ab890fd6cbd15e1fc0081
is_cookies_accepted=1
```

Также поддерживается формат с атрибутами:
```
name=value; domain=.ozon.ru; path=/; secure
```

См. файл `cookie.txt.example` для примера.

## Запуск

### Базовый запуск

```bash
java -jar build/libs/ballotzivoz.jar \
  https://www.ozon.ru/highlight/bally-za-otzyv-1171518
```

### С параметрами

```bash
java -jar build/libs/ballotzivoz.jar \
  https://www.ozon.ru/highlight/bally-za-otzyv-1171518 \
  --max-pages 10 \
  --rps 50 \
  --min-percent 5.0 \
  --timeout-ms 30000 \
  --output results.tsv \
  --verbose
```

### Параметры командной строки

- `URL` (необязательный) - URL страницы Ozon для парсинга (по умолчанию: `https://www.ozon.ru/highlight/bally-za-otzyv-1171518`)
- `--max-pages N` - максимальное количество страниц (0 = без ограничений, по умолчанию: 0)
- `--rps N` - максимальное количество запросов в секунду (по умолчанию: 5)
- `--min-percent N` - минимальный процент для фильтрации товаров (по умолчанию: 0.0)
- `--timeout-ms N` - таймаут для HTTP запросов в миллисекундах (по умолчанию: 30000)
- `--output PATH` - путь к выходному файлу (по умолчанию: products.tsv)
- `--cookie-file PATH` - путь к файлу с cookies (по умолчанию: cookie.txt)
- `--verbose, -v` - включить подробное логирование (DEBUG уровень)
- `--quiet, -q` - только ошибки (ERROR уровень)
- `--help, -h` - показать справку

## Алгоритм работы

### 1. Пагинация

Парсер использует JSON API Ozon для получения данных. Алгоритм пагинации:

1. Загружается первая страница каталога (HTML)
2. Из HTML извлекается JSON (из `window.__INITIAL_STATE__` или script тегов)
3. Из JSON или HTML извлекается ссылка на следующую страницу (`nextPage`, `page` и т.д.)
4. Загружается JSON следующей страницы напрямую
5. Процесс повторяется до тех пор, пока не закончатся товары или не будет достигнут лимит страниц

### 2. Парсинг данных

**Приоритет 1: Парсинг JSON**
- Извлекаются товары из JSON структуры Ozon
- Поддерживаются различные варианты структуры JSON (widgetStates, catalog, items и т.д.)
- Из каждого товара извлекаются: url, price, points

**Приоритет 2: Парсинг HTML (fallback)**
- Если JSON не найден, используется парсинг HTML через Jsoup
- Ищутся карточки товаров по селекторам
- Извлекаются цена и баллы из текста элементов

### 3. Фильтрация

- Пропускаются товары без цены (`price <= 0`)
- Пропускаются товары без баллов (`points <= 0`)
- Применяется фильтр по минимальному проценту (`percent >= minPercent`)

### 4. Сохранение результатов

- **links.txt** - уникальные ссылки на товары (по одной на строку)
- **output файл** (по умолчанию products.tsv) - полные данные в формате TSV: `url<TAB>price<TAB>points<TAB>percent`

## Логирование

### Уровни логирования

- **INFO** (по умолчанию): основные события (запросы, страницы, итоги)
- **DEBUG** (`--verbose`): подробная информация о парсинге
- **ERROR** (`--quiet`): только ошибки

### Формат логов

При старте выводятся все параметры:
```
=== Запуск парсера Ozon ===
URL: https://www.ozon.ru/category/...
Max pages: без ограничений
RPS: 5
Min percent: 0.0
...
```

На каждый HTTP запрос:
```
GET https://www.ozon.ru/... -> 200 (time=123 ms)
```

На каждую страницу:
```
Found tiles=24, parsed items=24, kept=20
```

В конце - итоговая сводка:
```
=== Итоговая сводка ===
Страниц обработано: 5
Товаров найдено (tiles): 120
Товаров распарсено: 120
Товаров сохранено (после фильтрации): 115
Уникальных товаров: 115
Строк записано в файл: 115
Уникальных ссылок: 115
HTTP запросов выполнено: 6
=======================
```

Логи также сохраняются в файл `ozon-parser.log`.

## HTTP клиент

- **User-Agent**: нормальный браузерный User-Agent (Mozilla/5.0...)
- **Заголовки**: Accept, Accept-Language, Accept-Encoding и т.д.
- **Redirects**: автоматическое следование редиректам
- **Таймауты**: настраиваемые connect/read таймауты
- **Rate limiting**: ограничение количества запросов в секунду (RPS)

## Архитектура проекта

```
src/main/java/org/example/
├── App.java                    # Точка входа (Picocli)
├── command/
│   └── ParseCommand.java       # Основная логика (orchestrator)
├── http/
│   └── HttpFetcher.java        # HTTP клиент с rate limiting
├── parse/
│   ├── OzonJsonParser.java     # Парсер JSON
│   └── OzonHtmlParser.java     # Парсер HTML (fallback)
├── model/
│   └── ProductRow.java         # Модель товара
└── util/
    ├── Numbers.java            # Утилиты для извлечения чисел
    ├── UrlUtil.java            # Утилиты для работы с URL
    └── FilesOut.java           # Запись результатов в файлы

src/test/java/org/example/util/
├── NumbersTest.java
└── UrlUtilTest.java
```

## Зависимости

- **OkHttp 4.12.0** - HTTP клиент
- **Jsoup 1.17.2** - HTML парсинг
- **Gson 2.10.1** - JSON парсинг
- **Picocli 4.7.5** - CLI фреймворк
- **SLF4J + Logback** - Логирование
- **JUnit Jupiter** - Тестирование

## Тестирование

```bash
./gradlew test
```

## Примеры использования

### Парсинг с фильтром по проценту

```bash
java -jar build/libs/ballotzivoz.jar \
  https://www.ozon.ru/highlight/bally-za-otzyv-1171518 \
  --min-percent 10.0 \
  --output high-percent.tsv
```

### Ограничение количества страниц

```bash
java -jar build/libs/ballotzivoz.jar \
  https://www.ozon.ru/highlight/bally-za-otzyv-1171518 \
  --max-pages 5 \
  --verbose
```

### Медленный парсинг (низкий RPS)

```bash
java -jar build/libs/ballotzivoz.jar \
  https://www.ozon.ru/highlight/bally-za-otzyv-1171518 \
  --rps 10 \
  --timeout-ms 60000
```

## Примечания

- Парсер пропускает товары без цены или без баллов
- URL товаров нормализуются (относительные преобразуются в абсолютные)
- Процент вычисляется как `points * 100.0 / price` с округлением до 2 знаков
- Результаты сохраняются в формате TSV (табуляция как разделитель)
- Дубликаты товаров автоматически удаляются (по URL)

## Лицензия

Проект создан для образовательных целей.

