package gift.onnuri.merchant.dto;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 다중 필터 콤마 파싱(2026-08-12) — 프론트가 "음식점,카페"처럼 보내면 IN 조건이 된다.
 * 규칙은 MerchantSpecs·ClusterRepository·프론트 JSON 폴백이 공유한다(경계면).
 */
class FilterCsvTest {

    @Test
    void 콤마_구분_값은_리스트로_풀린다() {
        assertEquals(List.of("음식점", "카페"), FilterCsv.parse("음식점,카페"));
        assertEquals(List.of("GS25"), FilterCsv.parse("GS25"));
    }

    @Test
    void 전체_빈값_null은_필터_없음이다() {
        assertNull(FilterCsv.parse(null));
        assertNull(FilterCsv.parse(""));
        assertNull(FilterCsv.parse("  "));
        assertNull(FilterCsv.parse("전체"));
    }

    @Test
    void 공백과_빈_항목은_정리된다() {
        assertEquals(List.of("음식점", "카페"), FilterCsv.parse(" 음식점 , ,카페 "));
        assertNull(FilterCsv.parse(" , ,"));
    }
}
