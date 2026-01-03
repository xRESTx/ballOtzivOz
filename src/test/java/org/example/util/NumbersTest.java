package org.example.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NumbersTest {

    @Test
    void testExtractInt_SimpleNumber() {
        assertEquals(123, Numbers.extractInt("123"));
        assertEquals(456, Numbers.extractInt("456"));
    }

    @Test
    void testExtractInt_NumberWithText() {
        assertEquals(123, Numbers.extractInt("Цена: 123 руб."));
        assertEquals(999, Numbers.extractInt("999 баллов"));
    }

    @Test
    void testExtractInt_FirstNumber() {
        // Новое поведение: извлекаются все цифры из строки
        assertEquals(100200, Numbers.extractInt("100-200 руб."));
        assertEquals(50, Numbers.extractInt("50% скидка"));
    }

    @Test
    void testExtractInt_WithSpaces() {
        assertEquals(1234, Numbers.extractInt("1 234 руб."));
        assertEquals(5678, Numbers.extractInt("5 678"));
    }

    @Test
    void testExtractInt_EmptyString() {
        assertEquals(0, Numbers.extractInt(""));
        assertEquals(0, Numbers.extractInt("   "));
    }

    @Test
    void testExtractInt_Null() {
        assertEquals(0, Numbers.extractInt(null));
    }

    @Test
    void testExtractInt_NoNumbers() {
        assertEquals(0, Numbers.extractInt("abc"));
        assertEquals(0, Numbers.extractInt("---"));
    }
}

