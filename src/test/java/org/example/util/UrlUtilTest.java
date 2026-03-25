package org.example.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class UrlUtilTest {

    @Test
    void testNormalize_AbsoluteUrl() {
        assertEquals("https://www.ozon.ru/product/123/", 
            UrlUtil.normalize("https://www.ozon.ru/product/123/"));
        assertEquals("http://example.com", 
            UrlUtil.normalize("http://example.com"));
    }

    @Test
    void testNormalize_RelativeUrl() {
        assertEquals("https://www.ozon.ru/product/123/", 
            UrlUtil.normalize("/product/123/"));
        assertEquals("https://www.ozon.ru/category/elektronika", 
            UrlUtil.normalize("/category/elektronika"));
    }

    @Test
    void testNormalize_RelativeUrlWithoutSlash() {
        assertEquals("https://www.ozon.ru/product/123", 
            UrlUtil.normalize("product/123"));
    }

    @Test
    void testNormalize_Null() {
        assertNull(UrlUtil.normalize(null));
    }

    @Test
    void testNormalize_Empty() {
        assertNull(UrlUtil.normalize(""));
        assertNull(UrlUtil.normalize("   "));
    }

    @Test
    void testAddPageParam_NoQuery() {
        String url = "https://www.ozon.ru/category/elektronika";
        String result = UrlUtil.addPageParam(url, 2);
        assertTrue(result.contains("page=2"));
        assertTrue(result.startsWith(url));
    }

    @Test
    void testAddPageParam_WithQuery() {
        String url = "https://www.ozon.ru/category/elektronika?filter=test";
        String result = UrlUtil.addPageParam(url, 3);
        assertTrue(result.contains("page=3"));
        assertTrue(result.contains("filter=test"));
    }

    @Test
    void testAddPageParam_ReplaceExisting() {
        String url = "https://www.ozon.ru/category/elektronika?page=1";
        String result = UrlUtil.addPageParam(url, 5);
        assertTrue(result.contains("page=5"));
        assertFalse(result.contains("page=1"));
    }

    @Test
    void testBuildJsonApiUrl_HighlightUrl() {
        String url = "https://www.ozon.ru/highlight/bally-za-otzyv-1171518/?page=2";
        String result = UrlUtil.buildJsonApiUrl(url);

        assertTrue(result.startsWith("https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2?url="));
        assertTrue(result.contains("%2Fhighlight%2Fbally-za-otzyv-1171518%2F%3Fpage%3D2"));
        assertFalse(result.contains("layout_page_index"));
    }

    @Test
    void testBuildJsonApiUrl_ReturnsApiUrlAsIs() {
        String apiUrl =
            "https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2?url=%2Fhighlight%2Fbally-za-otzyv-1171518%2F%3Fpage%3D2";

        assertEquals(apiUrl, UrlUtil.buildJsonApiUrl(apiUrl));
        assertTrue(UrlUtil.isApiUrl(apiUrl));
    }

    @Test
    void testBuildJsonApiUrl_HighlightRootAddsDefaultQuery() {
        String result = UrlUtil.buildJsonApiUrl("https://www.ozon.ru/highlight/bally-za-otzyv-1171518");

        assertEquals(
            "https://www.ozon.ru/api/entrypoint-api.bx/page/json/v2?url=/highlight/bally-za-otzyv-1171518&paginator_token=3618992",
            result
        );
    }

    @Test
    void testNormalizeProductUrl_StripsQueryTail() {
        String productUrl =
            "https://www.ozon.ru/product/svetilnik-potolochnyy-svetodiodnyy-nakladnoy-2688222903/?_bctx=CAYQvsBH&at=83tBMvpr";

        assertEquals(
            "https://www.ozon.ru/product/svetilnik-potolochnyy-svetodiodnyy-nakladnoy-2688222903/",
            UrlUtil.normalizeProductUrl(productUrl)
        );
    }
}

