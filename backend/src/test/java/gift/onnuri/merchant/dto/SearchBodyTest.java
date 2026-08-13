package gift.onnuri.merchant.dto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * POST 검색 본문 계약 (2026-08-11 GET→POST 전환).
 * 프론트(merchants.html apiCall)가 보내는 JSON 키를 고정한다 — 검색 필터값이
 * URL 쿼리스트링(히스토리·프록시·서버 로그)에 남지 않도록 본문으로 옮긴 것.
 * GET 엔드포인트는 운영 curl·회귀 스크립트용으로 병행 유지된다.
 */
class SearchBodyTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(c -> c.getName()).toList();
    }

    @Test
    void SearchBody_는_SearchQuery_전_필드와_페이징을_받는다() {
        List<String> c = components(SearchBody.class);
        assertTrue(c.containsAll(List.of("region", "si", "gu", "dong", "cat", "brand", "mtype",
                "digital", "q", "minLat", "maxLat", "minLng", "maxLng")), "SearchQuery 필드 누락");
        assertTrue(c.containsAll(List.of("page", "size", "sort")), "페이징 필드 누락");
        assertTrue(c.containsAll(List.of("uLat", "uLng")), "가까운 순 정렬 좌표 필드 누락(2026-08-13)");
    }

    @Test
    void toQuery_는_필터_필드를_그대로_옮긴다() {
        SearchBody b = new SearchBody("서울", null, "동작구", "노량진동", null, "GS25", null,
                Boolean.TRUE, "수산", 1.0, 2.0, 3.0, 4.0, 2, 50, "name", null, null);
        SearchQuery qy = b.toQuery();
        assertEquals("서울", qy.region());
        assertEquals("동작구", qy.gu());
        assertEquals("노량진동", qy.dong());
        assertEquals("GS25", qy.brand());
        assertEquals(Boolean.TRUE, qy.digital());
        assertEquals("수산", qy.q());
        assertEquals(1.0, qy.minLat());
        assertEquals(4.0, qy.maxLng());
    }
}
