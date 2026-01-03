package org.example.util;

public class Numbers {
    /**
     * Извлекает целое число из строки.
     * Удаляет все нецифровые символы (включая пробелы, которые используются как разделители тысяч)
     * и возвращает число.
     * 
     * Примеры:
     * - "4 000 ₽" -> 4000
     * - "27 900 ₽" -> 27900
     * - "800 баллов за отзыв" -> 800
     * 
     * @param text текст для парсинга
     * @return извлеченное число или 0, если число не найдено
     */
    public static int extractInt(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        
        // Удаляем все нецифровые символы (включая пробелы, запятые, точки и т.д.)
        // Это позволяет правильно обрабатывать числа с пробелами как разделителями тысяч
        String digitsOnly = text.replaceAll("[^0-9]", "");
        
        if (digitsOnly.isEmpty()) {
            return 0;
        }
        
        try {
            return Integer.parseInt(digitsOnly);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

