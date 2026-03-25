package org.example.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProductRowTest {

    @Test
    void testToCsvLine_UsesCommaAsDecimalSeparatorAndEscapesCommas() {
        ProductRow row = new ProductRow(
            "Item, with comma",
            "https://www.ozon.ru/product/test/",
            5309,
            200,
            3.77
        );

        assertEquals(
            "\"Item, with comma\",https://www.ozon.ru/product/test/,5309,200,\"3,77\"",
            row.toCsvLine()
        );
    }
}
