package gift.onnuri.merchant.dto;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * API↔프론트 경계면 계약 테스트 (TDD 시드).
 *
 * 이 시스템의 최악 결함은 500이 아니라 "에러 없이 다른 숫자"다 — 응답 필드명이
 * 바뀌면 프론트 normItem()이 조용히 undefined를 소비한다. 여기서 record 컴포넌트
 * 이름을 고정해, 계약을 깨는 리팩토링이 컴파일·테스트 단계에서 잡히게 한다.
 * 이름을 바꿔야 한다면 이 테스트와 프론트 소비 코드(merchants.html)를 한 변경
 * 단위로 함께 바꾼다 (.claude/skills/dev-testing/SKILL.md 경계면 표 참조).
 */
class ApiContractTest {

    private List<String> components(Class<?> record) {
        return Arrays.stream(record.getRecordComponents())
                .map(c -> c.getName()).toList();
    }

    @Test
    void MerchantView_는_프론트가_소비하는_필드를_모두_노출한다() {
        List<String> c = components(MerchantView.class);
        // normItem(): marketType → market_type 변환의 원천 필드
        assertTrue(c.contains("marketType"), "marketType 누락 — 프론트 시장유형 태그가 조용히 사라진다");
        assertTrue(c.containsAll(List.of("id", "name", "cat", "brand", "region", "si", "gu", "dong",
                "addr", "market", "paper", "card", "qr", "lat", "lng")));
    }

    @Test
    void MapPin_은_지도_정보창_필드를_모두_노출한다() {
        List<String> c = components(MapResult.MapPin.class);
        assertTrue(c.contains("market"), "market 누락 — 지도 정보창 시장명이 사라진다");
        assertTrue(c.containsAll(List.of("id", "name", "cat", "brand", "addr", "card", "qr", "lat", "lng")));
    }

    @Test
    void SearchQuery_는_프론트가_보내는_파라미터를_모두_받는다() {
        List<String> c = components(SearchQuery.class);
        assertTrue(c.containsAll(List.of("region", "si", "gu", "dong", "cat", "brand", "mtype",
                "digital", "q", "minLat", "maxLat", "minLng", "maxLng")));
    }

    @Test
    void ClusterResult_는_지도_클러스터_필드를_노출한다() {
        assertEquals(List.of("items", "total", "grid"), components(ClusterResult.class));
        assertEquals(List.of("lat", "lng", "count"), components(ClusterResult.Cell.class));
    }

    @Test
    void PageResult_와_CountItem_shape() {
        assertEquals(List.of("items", "total", "page", "size"), components(PageResult.class));
        assertEquals(List.of("key", "count"), components(CountItem.class));
    }
}
