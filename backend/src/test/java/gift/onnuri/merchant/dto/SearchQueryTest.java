package gift.onnuri.merchant.dto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SearchQuery 순수 단위 테스트 (TDD 시드 — 스프링·DB 불필요).
 * bounds 판정과 프론트 계약 센티넬을 고정한다.
 */
class SearchQueryTest {

    private SearchQuery query(Double minLat, Double maxLat, Double minLng, Double maxLng) {
        return new SearchQuery(null, null, null, null, null, null, null, null, null,
                minLat, maxLat, minLng, maxLng);
    }

    @Test
    void hasBounds_는_네_좌표가_모두_있을_때만_참() {
        assertTrue(query(37.49, 37.51, 127.02, 127.07).hasBounds());
    }

    @Test
    void hasBounds_는_하나라도_빠지면_거짓() {
        assertFalse(query(null, 37.51, 127.02, 127.07).hasBounds());
        assertFalse(query(37.49, null, 127.02, 127.07).hasBounds());
        assertFalse(query(37.49, 37.51, null, 127.07).hasBounds());
        assertFalse(query(37.49, 37.51, 127.02, null).hasBounds());
        assertFalse(query(null, null, null, null).hasBounds());
    }

    @Test
    void 동미상_센티넬은_프론트_표기와_일치한다() {
        // merchants.html의 UNKNOWN_DONG("동 미상")과 반드시 동일 — 어긋나면 동 필터가 무결과가 된다.
        assertEquals("동 미상", SearchQuery.UNKNOWN_DONG);
    }
}
